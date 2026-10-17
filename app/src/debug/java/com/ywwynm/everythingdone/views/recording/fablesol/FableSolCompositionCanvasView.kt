package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.pow

/** L 对照：只生成普通 SDR 像素，不创建 Surface、SurfaceView 或 EGL。 */
internal class FableSolCompositionCanvasView(context: Context, private val presented: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outline = Path()
    private var bitmap: Bitmap? = null
    private var reported = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 旧 Bitmap 可能仍由上一帧的 RenderNode 持有，不能提前 recycle。
        bitmap = null
        if (w < 1 || h < 1) return
        val tw = 256
        val th = maxOf(64, (tw.toFloat() * h / w).toInt())
        val pixels = IntArray(tw * th)
        fun srgb(linear: Float): Int {
            val value = linear.coerceIn(0f, 1f)
            return ((if (value <= 0.0031308f) 12.92f * value else
                1.055f * value.pow(1f / 2.4f) - 0.055f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        for (y in 0 until th) for (x in 0 until tw) {
            val u = (x + 0.5f) / tw
            // Canvas 从上往下；GL 固定图从下往上。
            val v = 1f - (y + 0.5f) / th
            val white = (u - 0.75f).pow(2) + (v - 0.25f).pow(2) * h * h / (w.toFloat() * w) < 0.075f.pow(2)
            pixels[y * tw + x] = if (v > 0.5f || white) Color.WHITE else
                Color.rgb(srgb(0.55f + 0.25f * u), srgb(0.04f + 0.08f * u), srgb(0.15f + 0.5f * u))
        }
        bitmap = Bitmap.createBitmap(pixels, tw, th, Bitmap.Config.ARGB_8888)
        val radius = 18f * resources.displayMetrics.density
        outline.reset()
        outline.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        reported = false
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        val save = canvas.save()
        canvas.clipPath(outline)
        canvas.drawBitmap(image, null, android.graphics.Rect(0, 0, width, height), paint)
        canvas.restoreToCount(save)
        if (!reported) {
            reported = true
            post { if (isAttachedToWindow) presented() }
        }
    }

    override fun onDetachedFromWindow() {
        // Bitmap 仍可能被已记录的硬件帧引用；交由 View／RenderNode 生命周期释放。
        bitmap = null
        super.onDetachedFromWindow()
    }
}
