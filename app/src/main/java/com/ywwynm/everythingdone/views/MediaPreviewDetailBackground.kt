package com.ywwynm.everythingdone.views

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.roundToInt

/** 只复制记事底色，在原根容器坐标绘制；渐变不能按附件空区重新拉伸。 */
internal class MediaPreviewDetailBackground private constructor(
    private val drawable: Drawable,
    private val bounds: RectF
) {
    private val originalAlpha = drawable.alpha

    fun draw(canvas: Canvas, area: RectF, alpha: Float = 1f) {
        val save = canvas.save()
        canvas.clipRect(area)
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width() / drawable.bounds.width(), bounds.height() / drawable.bounds.height())
        drawable.alpha = (originalAlpha * alpha.coerceIn(0f, 1f)).roundToInt()
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    companion object {
        fun capture(view: View?, root: View): MediaPreviewDetailBackground? {
            if (view == null || view.width <= 0 || view.height <= 0) return null
            val source = view.background ?: return null
            val copy = source.constantState?.newDrawable(view.resources)?.mutate() ?: return null
            copy.state = source.state
            copy.level = source.level
            copy.alpha = source.alpha
            copy.setBounds(0, 0, view.width, view.height)
            return MediaPreviewDetailBackground(copy, ThingDetailTransition.bounds(view, root))
        }
    }
}
