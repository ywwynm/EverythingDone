package com.ywwynm.everythingdone.views

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 单行提示内的确定进度：边框常驻，扇形面积随真实帧处理进度变化。 */
internal class CoverGenerationIndicator : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    var failed = false
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        val stroke = (side / 12f).coerceAtLeast(1f)
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = side / 2 - stroke
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        if (failed) {
            canvas.drawRect(cx - stroke / 2, cy - radius * .6f,
                cx + stroke / 2, cy + radius * .1f, paint)
            canvas.drawCircle(cx, cy + radius * .5f, stroke * .6f, paint)
        } else {
            val inset = stroke * 1.5f
            oval.set(cx - radius + inset, cy - radius + inset,
                cx + radius - inset, cy + radius - inset)
            canvas.drawArc(oval, -90f, level.coerceIn(0, 10000) * .036f, true, paint)
        }
    }

    override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    override fun setTint(tintColor: Int) { paint.color = tintColor; invalidateSelf() }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
