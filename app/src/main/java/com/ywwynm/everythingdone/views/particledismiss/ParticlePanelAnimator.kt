package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.ywwynm.everythingdone.fragments.BaseDialogFragment

/** 页面内面板复用弹窗的快照、时序和共同粒子模型，不另建运动规则。 */
internal class ParticlePanelAnimator(private val activity: Activity) {
    private var appearance: ParticleDismissOverlay? = null
    private var lastTouch: PointF? = null
    private var lastTouchAt = 0L
    private var fromBack = false

    fun recordTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouch = PointF(event.rawX, event.rawY)
            lastTouchAt = SystemClock.uptimeMillis()
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) lastTouch = null
    }

    fun dismissFromBack() { fromBack = true }

    fun appear(panel: View): Boolean {
        appearance?.release()
        ParticleDismissController.cancelAppearance(panel)
        fromBack = false
        if (BaseDialogFragment.particleAnimationMode(activity) and BaseDialogFragment.PARTICLE_ANIMATION_SHOW_BIT == 0) return false
        panel.translationY = 0f
        return ParticleDismissController.appearContent(activity, activity.window, panel) { overlay ->
            appearance = overlay
            overlay.afterRelease { if (appearance === overlay) appearance = null }
        }
    }

    fun dismiss(panel: View, onHidden: () -> Unit): Boolean {
        appearance?.release()
        appearance = null
        ParticleDismissController.cancelAppearance(panel)
        val back = fromBack
        fromBack = false
        if (BaseDialogFragment.particleAnimationMode(activity) and BaseDialogFragment.PARTICLE_ANIMATION_DISMISS_BIT == 0) return false
        val origin = IntArray(2); panel.getLocationOnScreen(origin)
        val anchor = if (back) {
            val reach = maxOf(panel.width, panel.height).toFloat()
            PointF(panel.width / 2f - reach, panel.height / 2f - reach)
        } else lastTouch?.takeIf { SystemClock.uptimeMillis() - lastTouchAt in 0..300 }?.let {
            PointF(it.x - origin[0], it.y - origin[1])
        }
        val gate = ParticleDismissStartGate { onHidden() }
        return ParticleDismissController.startContent(activity, activity.window, panel, anchor,
            onAnimationStarted = Runnable { gate.markAnimationStarted() },
            onOverlayShown = Runnable { gate.markOverlayShown() },
            useDefaultTouchDistance = back, captureOffscreen = true)
    }
}
