package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.util.concurrent.locks.LockSupport

/** 真实反馈结束之前只准备粒子，不把尚在扩散的波纹冻结进快照。 */
internal class ParticleTouchFeedback private constructor(
    private val activity: Activity,
    private val root: View,
    private val ripples: List<GradientRippleDrawable>
) {
    @Volatile private var settled = false
    private var waitingForFinalFrame = false
    private val callbacks = mutableListOf<() -> Unit>()
    private val started = SystemClock.uptimeMillis()
    private val timeoutMs = 500L + (1000L * android.provider.Settings.Global.getFloat(
        activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)).toLong()

    private fun watch() {
        if (settled || waitingForFinalFrame) return
        if (!root.isAttachedToWindow || activity.isDestroyed || SystemClock.uptimeMillis() - started > timeoutMs) {
            finish()
        } else if (ripples.none { it.hasVisibleFeedback }) {
            waitingForFinalFrame = true
            // alpha 到零不代表这一帧已提交；Surface 窗口此时直接 PixelCopy 会抓到尾帧残影。
            val ready = Runnable { finish() }
            if (android.os.Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
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
        callbacks.toList().forEach { it() }
        callbacks.clear()
    }

    fun afterSettled(action: () -> Unit) {
        if (settled) action() else callbacks += action
    }

    fun awaitPlayback(cancelled: () -> Boolean): Boolean {
        while (!settled && !cancelled()) LockSupport.parkNanos(2_000_000L)
        return !cancelled()
    }

    fun <T> withoutRipple(action: () -> T): T {
        ripples.forEach { it.omitFromSnapshot = true }
        return try { action() } finally { ripples.forEach { it.omitFromSnapshot = false } }
    }

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private val pending = mutableSetOf<ParticleTouchFeedback>()

        fun current(activity: Activity): ParticleTouchFeedback? = pending.lastOrNull { it.activity === activity }

        fun begin(activity: Activity, root: View): ParticleTouchFeedback? {
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
            if (found.isEmpty()) return null
            return ParticleTouchFeedback(activity, root, found.toList()).also {
                pending += it
                ParticleGpuWork.beginPlayback()
                handler.post { it.watch() }
            }
        }

    }
}
