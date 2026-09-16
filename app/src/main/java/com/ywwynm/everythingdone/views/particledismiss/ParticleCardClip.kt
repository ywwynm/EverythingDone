package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * 沿父链把卡片被裁掉的部分算出来。几何在 [ParticleVisibleBounds] 里（纯函数、可单测），
 * 这里只负责从 View 树上读出每一层的裁剪范围。
 */
internal object ParticleCardClip {

    /**
     * [view] 在各级容器裁剪之后露出来的那一块，坐标以 [view] 自身左上角为原点。
     *
     * 整张卡片都露着时返回 null，调用方据此走原来那条不裁的路，不增加任何开销；
     * 返回的矩形有可能是空的（[Rect.isEmpty]），意思是一点都没露出来。
     */
    fun visibleRect(view: View): Rect? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        var bounds = ParticleVisibleBounds.whole(width, height)
        // 卡片左上角在当前这一层容器坐标系里的位置。用 left/top 而不是 getLocationOnScreen：
        // 滑动中的 translationX 与按压反馈的缩放都不算进来（见 ParticleVisibleBounds 的说明）。
        var offsetX = 0
        var offsetY = 0
        var child: View = view
        var parent = child.parent
        while (parent is ViewGroup) {
            offsetX += child.left - parent.scrollX
            offsetY += child.top - parent.scrollY
            val clipsToPadding = parent.clipToPadding
            if (parent.clipChildren || clipsToPadding) {
                bounds = ParticleVisibleBounds.clipBy(bounds, offsetX, offsetY,
                    if (clipsToPadding) parent.paddingLeft else 0,
                    if (clipsToPadding) parent.paddingTop else 0,
                    parent.width - if (clipsToPadding) parent.paddingRight else 0,
                    parent.height - if (clipsToPadding) parent.paddingBottom else 0)
                if (bounds.isEmpty) return Rect()
            }
            child = parent
            parent = child.parent
        }
        if (bounds.covers(width, height)) return null
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
}
