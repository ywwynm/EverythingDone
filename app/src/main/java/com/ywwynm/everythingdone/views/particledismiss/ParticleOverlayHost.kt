package com.ywwynm.everythingdone.views.particledismiss

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * 动画层（粒子叠加层、暗层）挂到宿主 DecorView 的方式。
 *
 * 默认走 [ViewGroup.getOverlay]：overlay 的内容画在宿主全部子 View 之上、不参与测量布局、
 * 不收输入，因此 `addView` 那一次整窗 `requestLayout` 就省掉了——ThingsActivity 的整棵树
 * （含 RecyclerView）不必为了挂一层全屏透明层重新测量一遍。代价是 overlay 不会替我们布局，
 * 必须自己 measure + layout 成全屏，并在宿主尺寸变化时自己跟上（见 [FullScreenSync]）；
 * 叠放顺序仍是「后加的在上」，与 addView 一致。
 *
 * 关掉 [ParticlePerfTuning.overlayInDecorOverlay] 时退回普通 `addView`，行为与改动前相同。
 */
internal object ParticleOverlayHost {

    fun attach(decor: ViewGroup, view: View) {
        if (!ParticlePerfTuning.overlayInDecorOverlay) {
            decor.addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        decor.overlay.add(view)
        layoutFullScreen(decor, view)
        decor.addOnLayoutChangeListener(FullScreenSync(decor, view))
    }

    fun detach(decor: ViewGroup?, view: View) {
        val parent = view.parent as? ViewGroup ?: return
        // 普通 addView 路径：parent 就是 decor 本身；overlay 路径：parent 是 OverlayViewGroup。
        if (parent === decor) decor.removeView(view)
        else if (decor != null) decor.overlay.remove(view)
        else parent.removeView(view)
    }

    /** overlay 加入的 View 不会自动布局（OverlayViewGroup.onLayout 是空的），必须自己来。 */
    fun layoutFullScreen(decor: ViewGroup, view: View) {
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    /**
     * 让 overlay 里的层跟住宿主 DecorView 的尺寸。
     *
     * 两种情况下 [attach] 当场的 [layoutFullScreen] 什么也做不了，层会以 0×0 停在
     * overlay 里、一个像素都画不出来：
     *
     * 1. **宿主首帧之前就挂上来**。透明宿主（开始做事、更突出的通知、延迟提醒、认证、
     *    列表小组件配置）在 `onCreate` / `onPostCreate` 里就提交弹窗，`doOnAttach` 因此
     *    落在 `performTraversals` 的 `dispatchAttachedToWindow`——早于第一次 `performLayout`，
     *    此刻 `decor.width` 仍是 0。
     * 2. **宿主之后改变尺寸**（旋转、分屏、insets 变化）。
     *
     * 普通 `addView` 路径由 MATCH_PARENT 天然覆盖这两种情况，改挂 overlay 之后必须自己补。
     * 监听器只弱引用层本身，层撤下（或被回收）后自己退场，不依赖 [detach] 的调用时机。
     */
    private class FullScreenSync(
        private val decor: ViewGroup, view: View
    ) : View.OnLayoutChangeListener {

        private val target = WeakReference(view)

        override fun onLayoutChange(
            v: View?, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            val view = target.get()
            if (view == null || view.parent == null) {
                decor.removeOnLayoutChangeListener(this)
                return
            }
            layoutFullScreen(decor, view)
        }
    }
}
