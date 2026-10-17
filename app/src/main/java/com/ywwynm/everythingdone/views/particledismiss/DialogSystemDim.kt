package com.ywwynm.everythingdone.views.particledismiss

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger

/** 仅显式启用的卓易通音频弹窗使用。始终保留同一个系统暗层，不与应用暗层接力。 */
internal class DialogSystemDim(private val window: Window, private val activity: Activity, private val owner: String) {
    private val decor = window.decorView
    private var animator: ValueAnimator? = null
    private var shown = false
    private var closing = false
    private var detached = false
    private var removed = false
    private var closeGate: DialogDimDismissGate? = null
    private val startedAt = SystemClock.uptimeMillis()

    init {
        // 初次创建就声明透明窗口，不能等带 Surface 的窗口已显示后才从背景推断格式。
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.attributes = window.attributes.apply {
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            dimAmount = 0f
            // 用内容和暗层自己的过渡，避免系统窗口退出再次改变亮度。
            windowAnimations = 0
        }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { record("attached") }
            override fun onViewDetachedFromWindow(v: View) {
                detached = true
                closeGate?.detached()
                cancelAnimator()
                record("detached")
                v.removeOnAttachStateChangeListener(this)
            }
        })
        record("configured")
    }

    fun show(appearanceAnimated: Boolean) {
        if (closing || detached || removed) return
        // N 直接图层沿用原窗口轮廓和 C 系统暗层，不再附加 K 清角覆盖。
        if (shown) return
        shown = true
        record(if (appearanceAnimated) "appearance_waiting" else "appearance_fade")
        if (!appearanceAnimated) animate(DialogDimLayer.DIM_AMOUNT, DialogDimLayer.FADE_IN_MS,
            onEnd = { record("appearance_ready") })
    }

    fun appearanceProgress(progress: Float) {
        if (closing || detached || removed) return
        val p = progress.coerceIn(0f, 1f)
        setAmount(DialogDimLayer.DIM_AMOUNT * p * p * (3f - 2f * p))
        if (p >= 1f && !appearanceRecorded) {
            appearanceRecorded = true
            record("appearance_ready")
        }
    }

    private var appearanceRecorded = false

    fun hostIsClosing(): Boolean =
        activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations

    /** Activity 销毁不能等待动画，否则框架清理窗口时会报告泄漏。 */
    fun removeImmediately() {
        removed = true
        closing = true
        closeGate?.detached()
        cancelAnimator()
        record("host_teardown")
    }

    /** 关闭请求一到就停掉淡入，后续的出现回调不能把暗层重新拉回 0.6。 */
    fun prepareDismiss() {
        if (closing) return
        closing = true
        cancelAnimator()
        record("dismiss_requested")
    }

    /** 粒子与快照已就位后，真实内容退出；原窗口继续承载逐渐淡出的系统暗层。 */
    fun dismiss(particlesReady: Boolean, onContentClosing: () -> Unit, removeWindow: () -> Unit) {
        if (closeGate != null || removed) return
        prepareDismiss()
        val gate = DialogDimDismissGate(removeWindow).also { closeGate = it }
        gate.begin()
        if (detached || !decor.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() ||
            hostIsClosing()) {
            gate.finishImmediately()
            return
        }
        val surfaceAlphas = mutableListOf<Pair<View, Float>>()
        fun collect(view: View) {
            if (ParticleSurfaceCopy.isSource(view)) surfaceAlphas += view to view.alpha
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(decor)
        val originalAlpha = decor.alpha
        val originalWindowAlpha = window.attributes.alpha
        if (particlesReady) {
            // 与出现阶段一致，同时隐藏整个窗口及内容；只改 decor 会漏掉独立 Surface。
            window.attributes = window.attributes.apply { alpha = 0f }
            decor.alpha = 0f
            decor.elevation = 0f
            decor.translationZ = 0f
            surfaceAlphas.forEach { it.first.visibility = View.INVISIBLE }
        }
        // 保留焦点与窗口顺序；交互门闩已经阻止重复操作，交接期不重新排序窗口。
        onContentClosing()
        record(if (particlesReady) "particle_handoff" else "plain_dismiss")
        val duration = if (particlesReady) ParticleDismissController.dismissAnimatorDurationMs()
            else DialogDimLayer.FADE_OUT_MS
        animate(0f, duration,
            interpolator = if (particlesReady) TimeInterpolator { fraction ->
                val p = ((fraction - .18f) / .55f).coerceIn(0f, 1f)
                p * p
            } else null,
            onFraction = if (particlesReady) null else ({ progress ->
                decor.alpha = originalAlpha * (1f - progress)
                window.attributes = window.attributes.apply { alpha = originalWindowAlpha * (1f - progress) }
                surfaceAlphas.forEach { (surface, alpha) -> surface.alpha = alpha * (1f - progress) }
            }),
            onEnd = {
                if (gate.dimCleared()) {
                    record("dim_zero")
                    ParticleDismissController.runAfterShown(decor, 100L) {
                        record("remove_after_frame")
                        gate.frameCommitted()
                    }
                    decor.invalidate()
                }
            })
    }

    private fun animate(target: Float, duration: Long, interpolator: TimeInterpolator? = null,
                        onFraction: ((Float) -> Unit)? = null, onEnd: (() -> Unit)? = null) {
        cancelAnimator()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setAmount(target); onFraction?.invoke(1f); onEnd?.invoke(); return
        }
        val from = window.attributes.dimAmount
        val value = ValueAnimator.ofFloat(0f, 1f)
        animator = value
        value.duration = duration
        value.interpolator = interpolator ?: TimeInterpolator { it }
        value.addUpdateListener {
            if (detached) return@addUpdateListener
            val p = it.animatedValue as Float
            setAmount(from + (target - from) * p)
            onFraction?.invoke(p)
        }
        value.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== value || detached) return
                animator = null
                setAmount(target)
                onEnd?.invoke()
            }
        })
        value.start()
    }

    private fun cancelAnimator() {
        val previous = animator
        animator = null
        previous?.cancel()
    }

    private fun setAmount(amount: Float) {
        if (detached || window.attributes.dimAmount == amount) return
        window.attributes = window.attributes.apply { dimAmount = amount.coerceIn(0f, 1f) }
    }

    fun record(event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        DebugFileLogger.log("fablesol_dialog_dim.log",
            "owner=$owner window=${System.identityHashCode(window)} event=$event " +
                "elapsedMs=${SystemClock.uptimeMillis() - startedAt} dim=${window.attributes.dimAmount} " +
                "flags=${window.attributes.flags} windowAlpha=${window.attributes.alpha} " +
                "decorAlpha=${decor.alpha} attached=${decor.isAttachedToWindow} " +
                "appDimCoverage=${DialogDimLayer.coverage(activity.window.decorView)} $detail",
            "[DEBUG-fablesol-system-dim]")
    }
}
