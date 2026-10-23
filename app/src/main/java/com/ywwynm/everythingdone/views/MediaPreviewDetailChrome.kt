package com.ywwynm.everythingdone.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** 只保存详情的图标和遮罩；媒体移动时，图标仍在详情坐标内渐显，不随图片移动。 */
internal class MediaPreviewDetailChrome private constructor(private val tiles: List<Tile>) {
    private class Tile(val pixels: Bitmap, val bounds: RectF, val clip: RectF, val alpha: Float)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, covered: RectF, omittedMedia: RectF? = null) {
        val area = Path().apply {
            addRect(covered, Path.Direction.CW)
            omittedMedia?.let { addRect(it, Path.Direction.CW) }
        }
        for (tile in tiles) {
            if (!RectF.intersects(tile.clip, covered) &&
                omittedMedia?.let { RectF.intersects(tile.clip, it) } != true) continue
            val save = canvas.save()
            canvas.clipPath(area)
            canvas.clipRect(tile.clip)
            paint.alpha = (tile.alpha * 255).roundToInt()
            canvas.drawBitmap(tile.pixels, null, tile.bounds, paint)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        fun capture(root: View, views: List<View>): MediaPreviewDetailChrome = MediaPreviewDetailChrome(views.mapNotNull { view ->
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@mapNotNull null
            var alpha = 1f
            var node: View? = view
            while (node != null && node !== root) {
                if (node.visibility != View.VISIBLE) return@mapNotNull null
                alpha *= node.alpha
                node = node.parent as? View
            }
            if (node !== root || alpha <= 0f) return@mapNotNull null
            val clip = MediaPreviewTransition.laidOutDetailRect(view, root) ?: return@mapNotNull null
            val pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(pixels))
            Tile(pixels, ThingDetailTransition.bounds(view, root), clip, alpha)
        })

        /** 来源窗口已不可见，结束打开附件时的反馈，避免将其冻结进背景或在返回后续播。 */
        fun clearTouchFeedback(root: View) {
            fun clear(drawable: Drawable?) {
                when (drawable) {
                    is GradientRippleDrawable -> drawable.stopAnimations()
                    is RippleDrawable -> drawable.jumpToCurrentState()
                    is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) clear(drawable.getDrawable(i))
                    is DrawableWrapper -> clear(drawable.drawable)
                    else -> drawable?.current?.takeIf { it !== drawable }?.let(::clear)
                }
            }
            fun visit(view: View) {
                view.cancelPendingInputEvents()
                view.isPressed = false
                clear(view.background); clear(view.foreground)
                view.invalidate()
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
        }
    }
}
