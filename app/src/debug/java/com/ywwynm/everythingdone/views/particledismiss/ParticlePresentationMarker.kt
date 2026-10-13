package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.DataSpace
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/** 同一双色内容，对照自有合成器缓冲与共享 GL；不隐藏原控件。 */
@RequiresApi(34)
internal class ParticlePresentationMarker(private val activity: Activity,
                                          private val report: ParticlePresentationReports.Report) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var layer: SurfaceControl? = null
    private var buffer: ParticleCompositorBitmap? = null
    private var glLayer: ParticleGlBitmapLayer? = null
    private var glWindow: ParticleWindowSurface? = null
    private var attempts = 0
    private val showFrame = Runnable { attach() }

    fun show() { attach() }
    private fun attach() {
        if (closed || activity.isDestroyed) return
        val decor = activity.window.decorView as ViewGroup
        val shared = ParticleWindowSurface.of(activity, decor)
        val particle = shared?.view as? SurfaceView
        val parent = particle?.surfaceControl
        val root = decor.rootSurfaceControl
        val useGl = report.options.glForeground
        val surfaceReady = if (useGl) particle?.holder?.surface?.isValid == true else parent?.isValid == true
        if (!surfaceReady || root == null || !decor.isAttachedToWindow || shared == null) {
            if (++attempts < 60) main.postDelayed(showFrame, 16)
            else report.event("marker_failed", mapOf("reason" to "surface_not_ready"))
            return
        }
        try {
            val size = (96 * activity.resources.displayMetrics.density).toInt()
            val pixels = Bitmap.createBitmap(size * 2, size, Bitmap.Config.ARGB_8888)
            Canvas(pixels).apply {
                drawColor(Color.rgb(35, 106, 210))
                drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { color = Color.rgb(255, 170, 60) })
            }
            ParticlePresentationDiagnostics.sample(report, "marker-source", pixels)
            val x = (decor.width - size * 2) / 2f
            val y = decor.height / 2f
            if (useGl) {
                val target = ParticleGlBitmapLayer("marker-${System.identityHashCode(this)}", 64, true)
                glWindow = shared; glLayer = target
                shared.uploadGlBitmap(target, pixels, x, y, 1f) { ready ->
                    if (closed) return@uploadGlBitmap
                    if (!ready) {
                        report.event("marker_failed", mapOf("reason" to "gl_upload_failed")); close()
                    } else shared.changeGlLayers("gl-window-marker", afterFrame = {
                        report.event("marker_gl_frame", mapOf("accepted" to it, "meaning" to "not_screen_verified"))
                    }) { if (!target.closed) target.visible = true }
                }
                report.event("marker_submitted", mapOf("parent" to "shared_gl", "x" to x, "y" to y, "size" to size))
                report.event("particle_backend", mapOf("backend" to "SurfaceView / GL 色块"))
                return
            }
            ParticleCompositorBitmap.prepare(pixels) prepared@{ result ->
                val content = result.getOrNull()
                if (closed) { content?.close(); return@prepared }
                if (content == null) {
                    report.event("marker_failed", mapOf("error" to result.exceptionOrNull().toString()))
                    close(); return@prepared
                }
                buffer = content
                try {
                    val builder = SurfaceControl.Builder().setName("ParticlePresentationMarker").setOpaque(false)
                        .setFormat(PixelFormat.RGBA_8888).setBufferSize(size * 2, size)
                    if (!report.options.rootForeground) builder.setParent(parent)
                    val control = builder.build().also { layer = it }
                    (if (report.options.rootForeground) checkNotNull(root.buildReparentTransaction(control))
                        else SurfaceControl.Transaction()).use { transaction ->
                        content.submit(transaction, control, report, "marker")
                        transaction.setDataSpace(control, DataSpace.DATASPACE_SRGB)
                            .setLayer(control, if (report.options.rootForeground) ParticlePresentationDiagnostics.rootForegroundLayer(64) else 64)
                            .setPosition(control, x, y).setAlpha(control, 1f).setVisibility(control, true)
                        transaction.addTransactionCommittedListener(activity.mainExecutor) {
                            report.event("marker_committed", mapOf("meaning" to "not_screen_verified"))
                        }
                        if (root.applyTransactionOnDraw(transaction)) decor.invalidate() else transaction.apply()
                    }
                    report.event("marker_submitted", mapOf("parent" to if (report.options.rootForeground) "window_root" else "particle_surface",
                        "x" to x, "y" to y, "size" to size, "surfaceValid" to parent?.isValid))
                    report.event("particle_backend", mapOf("backend" to "SurfaceView / 自有 SurfaceControl 色块"))
                } catch (failure: Exception) {
                    report.event("marker_failed", mapOf("error" to failure.toString())); close()
                }
            }
        } catch (failure: Exception) {
            report.event("marker_failed", mapOf("error" to failure.toString()))
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(showFrame)
        layer?.let { control ->
            runCatching { SurfaceControl.Transaction().use { it.setBuffer(control, null).setVisibility(control, false).reparent(control, null).apply() } }
            control.release()
        }
        layer = null; buffer?.close(); buffer = null
        glLayer?.let { glWindow?.closeGlLayer(it) }; glLayer = null; glWindow = null
        report.event("marker_closed")
    }
}
