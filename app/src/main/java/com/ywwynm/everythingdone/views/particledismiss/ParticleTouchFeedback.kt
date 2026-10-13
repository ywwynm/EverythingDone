package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.utils.AnimatorDurationScale
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.util.concurrent.locks.LockSupport

/** 保留源控件的真实反馈并捕获干净快照；源窗口消散等待反馈，独立出现可并行播放。 */
internal class ParticleTouchFeedback private constructor(
    private val activity: Activity,
    private val root: View,
    private val ripples: List<GradientRippleDrawable>
) {
    @Volatile private var settled = false
    private var waitingForFinalFrame = false
    private val callbacks = mutableListOf<() -> Unit>()
    private val started = SystemClock.uptimeMillis()

    /**
     * 兜底时限按涟漪的**真实寿命**算，不再用「500 + 1000 × 时长缩放」这种与涟漪无关的常数：
     * 标称寿命（淡入 60 + 淡出 300）乘应用时长缩放，再留一点余量给首帧对齐与主线程抖动。
     * 旧口径在 OPD2515 上恰好比涟漪实际寿命短，于是每次都走满超时（2026-09-14 第一批 1.9）。
     */
    private val timeoutMs = settleTimeoutMs(
        AnimatorDurationScale.scale(activity))

    private fun watch() {
        if (settled || waitingForFinalFrame) return
        // 三条「已经不可能再看到反馈」的判定：宿主没了、承载控件已 detach、
        // alpha 停在非零值上却没有任何动画器会把它降下来。
        val stalled = ripples.none { it.feedbackAnimating } || ripples.none { it.hostAttached }
        if (!root.isAttachedToWindow || activity.isDestroyed ||
            SystemClock.uptimeMillis() - started > timeoutMs || stalled) {
            finish()
        } else if (ripples.none { it.hasVisibleFeedback }) {
            waitingForFinalFrame = true
            // alpha 到零不代表这一帧已提交；Surface 窗口此时直接 PixelCopy 会抓到尾帧残影。
            val ready = Runnable { finish() }
            if (Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
                root.viewTreeObserver.registerFrameCommitCallback { handler.post(ready) }
                root.invalidate()
            } else root.postOnAnimation { handler.post(ready) }
            handler.postDelayed(ready, 120)
        } else root.postOnAnimation { watch() }
    }

    private fun finish() {
        if (settled) return
        settled = true
        ParticleGpuWork.endPlayback()
        pending.remove(this)
        feedbackActive = pending.isNotEmpty()
        callbacks.toList().forEach { it() }
        callbacks.clear()
    }

    /** 非阻塞查询：常驻 GL 线程的状态机按拍轮询，不再阻塞等待。 */
    val isSettled: Boolean get() = settled

    fun afterSettled(action: () -> Unit) {
        if (settled) action() else callbacks += action
    }

    fun awaitPlayback(cancelled: () -> Boolean): Boolean {
        while (!settled && !cancelled()) LockSupport.parkNanos(2_000_000L)
        return !cancelled()
    }

    companion object {
        // 懒初始化：settleTimeoutMs 是纯算术，JVM 单测会直接调它，不能在类初始化时碰主线程 Looper。
        private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
        private val pending = mutableSetOf<ParticleTouchFeedback>()

        /**
         * 当前有没有自绘涟漪在播（第四批 ③）。
         *
         * [pending] 只在主线程增删，这里额外维护一个 `@Volatile` 布尔给 GL 线程读：预计算据此
         * 决定要不要把驱动队列压短，给窗口让路。
         */
        @Volatile var feedbackActive = false
            private set

        fun current(activity: Activity): ParticleTouchFeedback? = pending.lastOrNull { it.activity === activity }

        /** 只在实际记录材料的同步区间使用；异步准备阶段不能隐藏真实反馈。 */
        fun omitFromSnapshot(root: View): () -> Unit {
            val saved = findRipples(root).map { it to it.omitFromSnapshot }
            saved.forEach { (ripple, _) -> ripple.omitFromSnapshot = true }
            return { saved.forEach { (ripple, previous) -> ripple.omitFromSnapshot = previous } }
        }

        /** 整页离屏渲染收到原生续帧后，更新按钮本身的显示列表，包含波纹结束清理。 */
        fun invalidateNativeRipples(root: View) {
            fun containsRipple(value: Drawable?): Boolean = when (value) {
                is RippleDrawable -> true
                is LayerDrawable -> (0 until value.numberOfLayers).any { containsRipple(value.getDrawable(it)) }
                is android.graphics.drawable.DrawableWrapper -> containsRipple(value.drawable)
                else -> value?.current?.let { it !== value && containsRipple(it) } == true
            }
            fun visit(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (containsRipple(view.background) || containsRipple(view.foreground)) view.invalidate()
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
        }

        private fun findRipples(root: View): List<GradientRippleDrawable> {
            val found = mutableSetOf<GradientRippleDrawable>()
            fun drawable(value: Drawable?) {
                when (value) {
                    is GradientRippleDrawable -> if (value.hasVisibleFeedback) found += value
                    is LayerDrawable -> for (i in 0 until value.numberOfLayers) drawable(value.getDrawable(i))
                    else -> if (value?.current != null && value.current !== value) drawable(value.current)
                }
            }
            fun visit(view: View) {
                if (view.visibility != View.VISIBLE) return
                drawable(view.background); drawable(view.foreground)
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
            return found.toList()
        }

        fun begin(activity: Activity, root: View): ParticleTouchFeedback? {
            val found = findRipples(root)
            if (found.isEmpty()) return null
            return ParticleTouchFeedback(activity, root, found.toList()).also {
                pending += it
                feedbackActive = true
                ParticleGpuWork.beginPlayback()
                handler.post { it.watch() }
                // root.postOnAnimation 在 root detach 之后落进 RunQueue、永不执行：
                // 轮询链一断，超时判定也一起失效。硬兜底必须挂全局 Handler。
                handler.postDelayed({ it.finish() }, it.timeoutMs + FALLBACK_SLACK_MS)
            }
        }

        /** 兜底余量：首帧对齐、主线程抖动与最后一帧提交都在这段里。 */
        internal const val FALLBACK_SLACK_MS = 240L

        /**
         * 结算超时按涟漪的**真实寿命**算：标称寿命（淡入 60 + 淡出 300）乘唯一的时间基准，
         * 再留一点余量。第二批统一时间基准之后，这条与框架给 ValueAnimator 施加的缩放同源。
         */
        internal fun settleTimeoutMs(scale: Float): Long =
            (GradientRippleDrawable.NOMINAL_FEEDBACK_LIFETIME_MS * scale).toLong() + FALLBACK_SLACK_MS
    }
}
