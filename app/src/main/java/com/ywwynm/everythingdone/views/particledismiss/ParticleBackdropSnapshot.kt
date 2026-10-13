package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

/** 诊断完整帧所用底图；不含接管的卡片／前景，且必须完全不透明。 */
@RequiresApi(34)
internal object ParticleBackdropSnapshot {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ParticleBackdropCheck") }
    private val main = Handler(Looper.getMainLooper())

    fun capture(root: ViewGroup, excluded: List<View>, diagnostic: ParticlePresentationDiagnostics.Run?,
                done: (Bitmap?) -> Unit) {
        if (!root.isAttachedToWindow || ParticleSnapshot.hasVisibleSurface(root)) {
            diagnostic?.event("gl_backdrop_unavailable", mapOf("reason" to "detached_or_independent_surface"))
            done(null); return
        }
        val hidden = excluded.distinct().map { it to it.visibility }
        val start = System.nanoTime()
        // 不包含独立 Surface，也没有透明窗口背景时，窗口背景本身覆盖整个取景范围。
        // 复用已有硬件纹理导入，避免整窗 GPU→CPU→GPU 和逐像素 alpha 扫描。
        val keepHardware = diagnostic?.options?.fastOpaqueHandoff == true &&
            root.background?.opacity == PixelFormat.OPAQUE && ParticleHardwareTexture.available
        diagnostic?.event("gl_backdrop_begin", mapOf("hardware" to keepHardware,
            "backgroundOpacity" to root.background?.opacity))
        try {
            // INVISIBLE 不触发布局尺寸变化；同一 UI 调用栈内完成 syncAndDraw 并恢复，
            // 不把这次临时排除作为真实窗口帧提交，也不改卡片 alpha lock。
            hidden.forEach { (view, _) -> view.visibility = View.INVISIBLE }
            val captured: (Bitmap?) -> Unit = captured@{ bitmap ->
                if (bitmap == null) { done(null); return@captured }
                if (keepHardware) {
                    diagnostic?.event("gl_backdrop_ready", mapOf("opaque" to true, "hardware" to true,
                        "width" to bitmap.width, "height" to bitmap.height,
                        "elapsedMs" to (System.nanoTime() - start) / 1e6,
                        "opacityEvidence" to "opaque_window_background_without_independent_surface"))
                    if (root.isAttachedToWindow) done(bitmap) else { bitmap.recycle(); done(null) }
                    return@captured
                }
                worker.execute {
                    val checked = runCatching {
                        val row = IntArray(bitmap.width)
                        var opaque = true
                        for (y in 0 until bitmap.height) {
                            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                            if (row.any { it ushr 24 != 255 }) { opaque = false; break }
                        }
                        opaque
                    }
                    main.post {
                        val opaque = checked.getOrDefault(false)
                        diagnostic?.event("gl_backdrop_ready", mapOf("opaque" to opaque,
                            "width" to bitmap.width, "height" to bitmap.height,
                            "error" to checked.exceptionOrNull()?.toString(),
                            "elapsedMs" to (System.nanoTime() - start) / 1e6,
                            "scope" to "static_background_for_diagnostic_single_card"))
                        if (opaque && root.isAttachedToWindow) done(bitmap)
                        else { bitmap.recycle(); done(null) }
                    }
                }
            }
            if (keepHardware) ParticleHardwareSnapshot.captureKeepingHardware(root, captured)
            else ParticleHardwareSnapshot.capture(root, done = captured)
        } catch (failure: Exception) {
            diagnostic?.event("gl_backdrop_failed", mapOf("error" to failure.toString()))
            done(null)
        } finally {
            hidden.forEach { (view, visibility) -> view.visibility = visibility }
        }
    }
}
