package com.ywwynm.everythingdone.views.particledismiss

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import com.ywwynm.everythingdone.R
import java.util.WeakHashMap

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
    private data class BelowFab(val parent: ViewGroup, val sync: View.OnLayoutChangeListener)
    private val belowFabParents = WeakHashMap<View, BelowFab>()

    fun attach(decor: ViewGroup, view: View) {
        if (view is ParticleDismissOverlay && view.belowNativeFab) {
            val fab = decor.findViewById<View>(R.id.fab_create)
            val parent = fab?.parent as? ViewGroup
            if (parent != null) {
                // TextureView 是窗口内容，直接插在 FAB 之前；仍使用 DecorView 坐标绘制卡片。
                parent.addView(view, parent.indexOfChild(fab), ViewGroup.LayoutParams(decor.width, decor.height))
                val sync = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                                oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                        if (view.parent !== parent) { parent.removeOnLayoutChangeListener(this); return }
                        val origin = IntArray(2).also { decor.getLocationInWindow(it) }
                        val at = IntArray(2).also { parent.getLocationInWindow(it) }
                        view.translationX = (origin[0] - at[0]).toFloat()
                        view.translationY = (origin[1] - at[1]).toFloat()
                        layoutFullScreen(decor, view)
                    }
                }
                belowFabParents[view] = BelowFab(parent, sync)
                parent.addOnLayoutChangeListener(sync)
                sync.onLayoutChange(parent, 0, 0, 0, 0, 0, 0, 0, 0)
                return
            }
        }
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
        belowFabParents.remove(view)?.let {
            it.parent.removeOnLayoutChangeListener(it.sync)
            it.parent.removeView(view)
            return
        }
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
