package com.ywwynm.everythingdone.views.recording.fablesol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.DebugFileLogger

/**
 * 卓易通音频窗口的最终清角。保留轮廓供阴影／粒子快照读取，但不依赖 Decor 的
 * RenderNode 轮廓裁剪；在普通窗口绘制结束后明确清除四角，不改变独立 Surface 或 HDR。
 * 调用方仅在 C 系统暗层分支安装。诊断页可独立比较关闭轮廓裁剪与增加清角的效果。
 */
internal class FableSolDialogCornerMask(private val window: Window) : AutoCloseable {
    private val decor = window.decorView
    private val originalClip = decor.clipToOutline
    private var closed = false
    private var sampled = false
    private var sampleAttempts = 0
    private val outline = Outline()
    private val outlineBounds = Rect()
    private val clear = CornerDrawable()
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    private val detached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = update()
        override fun onViewDetachedFromWindow(v: View) = close()
    }
    private val sample = object : Runnable {
        override fun run() {
            if (closed || !decor.isAttachedToWindow) return
            // 凝聚期间原窗口被隐藏；避免把那时全透明的缓冲记成稳态结果。
            if (decor.alpha < .99f || window.attributes.alpha < .99f) {
                if (++sampleAttempts < 5) decor.postDelayed(this, 300L)
                else record("sample_skipped", "reason=window_hidden")
                return
            }
            sampleCornerAlpha(window) { record("buffer_alpha", it) }
        }
    }

    init {
        decor.clipToOutline = false
        decor.overlay.add(clear)
        decor.addOnLayoutChangeListener(layout)
        decor.addOnAttachStateChangeListener(detached)
        record("installed", "previousClip=$originalClip")
        update()
    }

    /** Dialog 再次 onStart 时基类会重装轮廓；保留同一遮罩，恢复本窗口的裁剪策略。 */
    fun refresh() {
        if (closed) return
        decor.clipToOutline = false
        update()
    }

    private fun update() {
        if (closed || decor.width <= 0 || decor.height <= 0) return
        outline.setEmpty()
        decor.outlineProvider?.getOutline(decor, outline)
        val hasRoundRect = outline.getRect(outlineBounds) && outline.radius > 0f
        if (!hasRoundRect) outlineBounds.set(0, 0, decor.width, decor.height)
        val radius = if (hasRoundRect) outline.radius else
            decor.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        clear.configure(decor.width, decor.height, outlineBounds, radius)
        decor.invalidate()
    }

    override fun close() {
        if (closed) return
        closed = true
        decor.removeCallbacks(sample)
        decor.removeOnLayoutChangeListener(layout)
        decor.removeOnAttachStateChangeListener(detached)
        decor.overlay.remove(clear)
        decor.clipToOutline = originalClip
        record("removed", "restoredClip=$originalClip")
    }

    private inner class CornerDrawable : Drawable() {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private var geometry = ""
        private var drawn = false

        fun configure(width: Int, height: Int, roundRect: Rect, radius: Float) {
            val next = "size=${width}x$height rect=${roundRect.toShortString()} radius=$radius"
            if (next == geometry) return
            geometry = next
            drawn = false
            setBounds(0, 0, width, height)
            path.rewind()
            path.fillType = Path.FillType.EVEN_ODD
            // 外边略超出画布，避免窗口边缘多出一条抗锯齿残留；内部圆弧正常抗锯齿。
            path.addRect(-1f, -1f, width + 1f, height + 1f, Path.Direction.CW)
            path.addRoundRect(roundRect.left.toFloat(), roundRect.top.toFloat(),
                roundRect.right.toFloat(), roundRect.bottom.toFloat(), radius, radius, Path.Direction.CW)
            invalidateSelf()
            record("geometry", geometry)
        }

        override fun draw(canvas: Canvas) {
            if (path.isEmpty) return
            // 不使用 saveLayer：需要清除窗口目标中的像素，而不是清除一张临时空图。
            canvas.drawPath(path, paint)
            if (!drawn) {
                drawn = true
                record("draw", "$geometry hardware=${canvas.isHardwareAccelerated}")
            }
            if (BuildConfig.DEBUG && !sampled) {
                sampled = true
                decor.postDelayed(sample, 1100L)
            }
        }

        // CLEAR 的含义不能被 Drawable alpha 或颜色滤镜改变。
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable 平台约定")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun record(event: String, detail: String) {
        if (BuildConfig.DEBUG) DebugFileLogger.log("fablesol_dialog_dim.log",
            "event=window_corner_$event window=${System.identityHashCode(window)} " +
                "decorClip=${decor.clipToOutline} decorLayer=${decor.layerType} " +
                "format=${window.attributes.format} $detail", "[DEBUG-fablesol-corners]")
    }

    companion object {
        /** 仅诊断四个角的窗口缓冲 alpha；不读取颜色、不保存截图，也不冒充屏幕合成结果。 */
        fun sampleCornerAlpha(window: Window, done: (String) -> Unit) {
            if (!BuildConfig.DEBUG) return
            val decor = window.decorView
            val width = decor.width
            val height = decor.height
            if (!decor.isAttachedToWindow || width < 6 || height < 6) return
            val points = arrayOf(2 to 2, width - 3 to 2, 2 to height - 3, width - 3 to height - 3)
            val values = arrayOfNulls<String>(points.size)
            val handler = Handler(Looper.getMainLooper())
            var remaining = points.size
            points.forEachIndexed { index, (x, y) ->
                val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                fun complete(result: String) {
                    values[index] = result
                    pixel.recycle()
                    if (--remaining == 0) done("order=TL,TR,BL,BR alpha=${values.joinToString(",")} " +
                        "size=${width}x$height attached=${decor.isAttachedToWindow} " +
                        "windowAlpha=${window.attributes.alpha} decorAlpha=${decor.alpha}")
                }
                try {
                    PixelCopy.request(window, Rect(x, y, x + 1, y + 1), pixel, { status ->
                        complete(if (status == PixelCopy.SUCCESS) Color.alpha(pixel.getPixel(0, 0)).toString()
                            else "error:$status")
                    }, handler)
                } catch (failure: Exception) {
                    complete("error:${failure.javaClass.simpleName}")
                }
            }
        }
    }
}
