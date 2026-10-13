package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView

/** 固定内容的真实 Surface 生产者；新候选补重绘协议，并核对恢复缓冲仍含橙蓝两色。 */
internal class ParticlePresentationColorSurface(context: Context) : SurfaceView(context), ParticleSurfaceRedrawParticipant {
    private val diagnostic = ParticlePresentationDiagnostics.of(context)
    private val repaired = diagnostic?.options?.surfaceRecovery == true
    private val orange = Color.rgb(255, 170, 60)
    private val blue = Color.rgb(35, 106, 210)
    private var frame = 0

    init {
        if (repaired && Build.VERSION.SDK_INT >= 34) setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        val baseline = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { paint("created") }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                diagnostic?.event("color_surface_changed", mapOf("width" to width, "height" to height, "format" to format))
                paint("changed")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { diagnostic?.event("color_surface_destroyed") }
        }
        holder.addCallback(if (!repaired) baseline else object : SurfaceHolder.Callback2, SurfaceHolder.Callback by baseline {
            override fun surfaceRedrawNeeded(holder: SurfaceHolder) { paint("redraw") }
            override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
                try { paint("redraw_async") } finally { drawingFinished.run() }
            }
        })
    }

    private fun paint(reason: String): Boolean = try {
        val canvas = holder.lockCanvas()
        if (canvas == null) false else {
            try {
                canvas.drawColor(blue)
                canvas.drawRect(0f, 0f, canvas.width / 2f, canvas.height.toFloat(), Paint().apply { color = orange })
            } finally { holder.unlockCanvasAndPost(canvas) }
            diagnostic?.event("color_surface_frame_submitted", mapOf("reason" to reason, "frame" to ++frame,
                "meaning" to "producer_submitted_not_screen_verified"))
            true
        }
    } catch (failure: Exception) {
        diagnostic?.event("color_surface_draw_failed", mapOf("reason" to reason, "error" to failure.toString()))
        false
    }

    override fun redrawForParticleRecovery(beforeSubmit: () -> Unit, submitted: (Boolean) -> Unit) {
        beforeSubmit(); submitted(paint("recovery"))
    }

    override fun acceptsParticleRecoveryBuffer(bitmap: Bitmap): Boolean {
        // 默认 Surface 可为 RGB_565：实机蓝色 (35,106,210) 读回为 (33,105,214)。
        // 允许 5 位通道的量化误差，灰色仍远超范围；不把颜色正常的缓冲判为未恢复。
        fun close(a: Int, b: Int) = kotlin.math.abs(a - b) <= 8
        fun matches(actual: Int, expected: Int) = Color.alpha(actual) >= 250 &&
            close(Color.red(actual), Color.red(expected)) && close(Color.green(actual), Color.green(expected)) &&
            close(Color.blue(actual), Color.blue(expected))
        val left = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
        val right = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
        val accepted = matches(left, orange) && matches(right, blue)
        diagnostic?.event("color_surface_buffer_colors", mapOf("leftArgb" to Integer.toHexString(left),
            "rightArgb" to Integer.toHexString(right), "accepted" to accepted))
        return accepted
    }
}
