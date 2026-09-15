package com.ywwynm.everythingdone.views.particledismiss

import android.view.View
import android.view.ViewGroup

/**
 * 动画层（粒子叠加层、暗层）挂到宿主 DecorView 的方式。
 *
 * 默认走 [ViewGroup.getOverlay]：overlay 的内容画在宿主全部子 View 之上、不参与测量布局、
 * 不收输入，因此 `addView` 那一次整窗 `requestLayout` 就省掉了——ThingsActivity 的整棵树
 * （含 RecyclerView）不必为了挂一层全屏透明层重新测量一遍。代价是 overlay 不会替我们布局，
 * 必须自己 measure + layout 成全屏；叠放顺序仍是「后加的在上」，与 addView 一致。
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
}
