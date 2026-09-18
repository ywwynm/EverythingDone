package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

    /** 切走这块面板：正在播的出现层立刻撤掉，准备中的出现也一并取消。 */
    fun cancel(panel: View?) {
        appearance?.release()
        appearance = null
        panel?.let { ParticleDismissController.cancelAppearance(it) }
    }

    /**
     * [direction] 是粒子的方向（度，0 = 右、90 = 上、180 = 左、270 = 下）；出现是倒放，
     * 粒子从该方向汇入。传 null 走原来的随机方向。
     *
     * [shadowElevationPx] 非空时把面板的 elevation 投影一起拍进快照，阴影随粒子一起聚拢；
     * 此时**必须**关掉圆角遮罩，否则外扩出来的四角阴影会被削掉。
     *
     * 系统支持时（API 34+）这一路还会打开 [ParticleCardSurfaceHandoff] 的**原子交接**
     * （`cardShadow`，与 2ff2430b 给卡片做的是同一条）：末帧与真实面板的交接分两步各自
     * 与一帧绑定——「粒子最后一张缓冲撤下」与「静态快照图层显示」同属一次 Surface 提交，
     * 「快照图层撤下」与「真实面板恢复可见」同属一次窗口绘制。不这样做的话，真实面板恢复
     * 的那几帧里粒子层的末帧还压在上面，两层**半透明**的投影叠在一起，收尾会明显闪一下
     * （第 9 批第 2 条）。
     *
     * [onSettled] 在这一层真的释放之后回调一次（含各种失败路径）。
     */
    fun appear(panel: View, direction: Float? = null,
               shadowElevationPx: Float? = null,
               onSettled: (() -> Unit)? = null): Boolean {
        appearance?.release()
        ParticleDismissController.cancelAppearance(panel)
        fromBack = false
        if (BaseDialogFragment.particleAnimationMode(activity) and BaseDialogFragment.PARTICLE_ANIMATION_SHOW_BIT == 0) return false
        panel.translationY = 0f
        var finished = false
        val settle = { if (!finished) { finished = true; onSettled?.invoke(); Unit } }
        val started = ParticleDismissController.appearContent(activity, activity.window, panel,
            appearanceDirection = direction,
            roundSnapshot = shadowElevationPx == null,
            cardShadow = atomicHandoff(shadowElevationPx),
            onAppearanceFinished = settle,
            shadowElevationPx = shadowElevationPx) { overlay ->
            appearance = overlay
            overlay.afterRelease { if (appearance === overlay) appearance = null }
        }
        if (!started) settle()
        return started
    }

    /**
     * [direction] 同 [appear]：指定之后忽略真实触点，粒子一律朝该方向散开。
     * 只取射线方向，所以触点放在中心外一个对角线之外即可，距离与强度走默认值。
     *
     * [onSettled] 在动画层释放之后回调一次；[onHidden] 仍是「动画层已上屏，可以撤真实内容了」，
     * 两者不是一回事。
     */
    fun dismiss(panel: View, direction: Float? = null,
                shadowElevationPx: Float? = null,
                onSettled: (() -> Unit)? = null, onHidden: () -> Unit): Boolean {
        appearance?.release()
        appearance = null
        ParticleDismissController.cancelAppearance(panel)
        val back = fromBack
        fromBack = false
        if (BaseDialogFragment.particleAnimationMode(activity) and BaseDialogFragment.PARTICLE_ANIMATION_DISMISS_BIT == 0) return false
        val origin = IntArray(2); panel.getLocationOnScreen(origin)
        val aimed = direction?.let {
            val radians = Math.toRadians(-it.toDouble())
            val reach = hypot(panel.width.toFloat(), panel.height.toFloat())
            PointF(panel.width / 2f + (cos(radians) * reach).toFloat(),
                panel.height / 2f + (sin(radians) * reach).toFloat())
        }
        val anchor = aimed ?: if (back) {
            val reach = maxOf(panel.width, panel.height).toFloat()
            PointF(panel.width / 2f - reach, panel.height / 2f - reach)
        } else lastTouch?.takeIf { SystemClock.uptimeMillis() - lastTouchAt in 0..300 }?.let {
            PointF(it.x - origin[0], it.y - origin[1])
        }
        val gate = ParticleDismissStartGate { onHidden() }
        var finished = false
        val settle = Runnable { if (!finished) { finished = true; onSettled?.invoke() } }
        val started = ParticleDismissController.startContent(activity, activity.window, panel, anchor,
            onAnimationStarted = Runnable { gate.markAnimationStarted() },
            onOverlayShown = Runnable { gate.markOverlayShown() },
            useDefaultTouchDistance = back || aimed != null,
            onDone = settle,
            shadowElevationPx = shadowElevationPx)
        if (!started) settle.run()
        return started
    }

    private companion object {
        /**
         * 含投影的面板能不能走原子交接。低于 API 34 时 [ParticleCardSurfaceHandoff] 不可用，
         * 退回第 8 批那条「快照里带阴影 + 交接期把真实 elevation 置 0」的做法。
         */
        fun atomicHandoff(shadowElevationPx: Float?) =
            shadowElevationPx != null && ParticlePerfTuning.panelShadowAtomicHandoff &&
                ParticleCardSurfaceHandoff.supported()
    }
}
