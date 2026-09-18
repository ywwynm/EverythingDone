package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.R

/** 首页卡片与顶栏、标题、阴影、FAB 的原生叠放关系。 */
internal object ParticleHomeForeground {
    fun listFor(source: View): View? {
        var view: View? = source
        while (view != null) {
            if (view.id == R.id.rv_things) return view
            view = view.parent as? View
        }
        return null
    }

    fun views(source: View): List<View> {
        val root = source.rootView
        val ids = if (listFor(source) != null) listOf(
            R.id.view_status_bar_scrim, R.id.actionbar, R.id.rl_header,
            R.id.actionbar_shadow, R.id.view_status_bar, R.id.fab_create, R.id.rl_contextual_toolbar
        ) else listOf(R.id.fab_create)
        // 隐藏的已布局顶栏仍需跟踪：移除主体后会从选择模式切回普通顶栏。
        return ids.mapNotNull { root.findViewById<View>(it) }
            .filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
    }

    /** 仅裁不透明主体；阴影是前景，下面的材料必须保留。返回屏幕坐标。 */
    fun opaqueBottom(source: View): Int? {
        if (listFor(source) == null) return null
        val root = source.rootView
        return listOf(R.id.ll_contextual_header, R.id.actionbar, R.id.view_status_bar)
            .mapNotNull { root.findViewById<View>(it) }.mapNotNull { view ->
                var ancestor: View? = view
                while (ancestor != null) {
                    if (ancestor.alpha < 1f || ancestor.animation?.hasEnded() == false) return@mapNotNull null
                    ancestor = ancestor.parent as? View
                }
                val bounds = Rect()
                if (view.isShown && view.getGlobalVisibleRect(bounds)) bounds.bottom else null
            }.maxOrNull()
    }

    /** Texture 在真实列表之后绘制，后面的顶栏、阴影与 FAB 自然盖住它。 */
    fun insertionPoint(source: View?): Pair<ViewGroup, View>? {
        if (source == null) return null
        val list = listFor(source) ?: return null
        return (list.parent as? ViewGroup)?.let { it to list }
    }
}
