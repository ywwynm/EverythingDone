package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import androidx.annotation.RequiresApi

/** 单次绘制的合成器缓冲。显式请求 setBuffer 所需用途，保留 Image 直到图层撤下。 */
@RequiresApi(34)
internal class ParticleCompositorBitmap private constructor(private val pixels: Bitmap,
    private var ready: ((Result<ParticleCompositorBitmap>) -> Unit)?) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private var node: RenderNode? = null
    private var image: Image? = null
    private var closed = false
    val width = pixels.width
    val height = pixels.height
    private val timeout = Runnable { fail(IllegalStateException("合成器缓冲准备超时")) }

    private fun start() {
        val target = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2, REQUIRED_USAGE)
        reader = target
        target.setOnImageAvailableListener({ source ->
            if (closed || image != null) return@setOnImageAvailableListener
            try {
                image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                main.removeCallbacks(timeout)
                checkNotNull(image?.hardwareBuffer).use { buffer ->
                    check(buffer.usage and REQUIRED_COMPOSITOR_USAGE == REQUIRED_COMPOSITOR_USAGE) {
                        "缓冲用途不满足合成要求：${buffer.usage}"
                    }
                }
                ready?.let { ready = null; it(Result.success(this)) }
            } catch (failure: Exception) { fail(failure) }
        }, main)
        val content = RenderNode("ParticleCompositorBitmap").also { node = it }
        content.setPosition(0, 0, width, height)
        val canvas = content.beginRecording(width, height)
        try { canvas.drawBitmap(pixels, 0f, 0f, null) } finally { content.endRecording() }
        val hardware = HardwareRenderer().also { renderer = it }
        hardware.isOpaque = false
        hardware.setSurface(target.surface); hardware.setContentRoot(content); hardware.start()
        main.postDelayed(timeout, 1500)
        val status = hardware.createRenderRequest().setWaitForPresent(false).syncAndDraw()
        check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
            HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED) == 0) {
            "合成器缓冲绘制失败：$status"
        }
    }

    fun submit(transaction: SurfaceControl.Transaction, layer: SurfaceControl,
               diagnostic: ParticlePresentationDiagnostics.Run?, name: String) {
        check(!closed)
        val current = checkNotNull(image)
        current.hardwareBuffer.use { buffer ->
            val actual = checkNotNull(buffer)
            diagnostic?.event("compositor_buffer_usage", mapOf("layer" to name, "usage" to actual.usage,
                "requiredUsage" to REQUIRED_COMPOSITOR_USAGE, "width" to width, "height" to height,
                "composerOverlay" to (actual.usage and HardwareBuffer.USAGE_COMPOSER_OVERLAY != 0L),
                "gpuSampled" to (actual.usage and HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE != 0L)))
            current.fence.use { fence -> transaction.setBuffer(layer, actual, fence) }
        }
    }

    private fun fail(failure: Exception) {
        val callback = ready; ready = null
        close()
        callback?.invoke(Result.failure(failure))
    }

    override fun close() {
        if (closed) return
        closed = true; ready = null; main.removeCallbacks(timeout)
        renderer?.let { runCatching { it.destroy() } }; renderer = null
        image?.let { runCatching { it.close() } }; image = null
        reader?.let { runCatching { it.close() } }; reader = null
        node?.let { runCatching { it.discardDisplayList() } }; node = null
        pixels.recycle()
    }

    companion object {
        const val REQUIRED_COMPOSITOR_USAGE = HardwareBuffer.USAGE_COMPOSER_OVERLAY or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        const val REQUIRED_USAGE = REQUIRED_COMPOSITOR_USAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT

        /** 接管软件 bitmap 的所有权；调用及回调均在主线程。 */
        fun prepare(pixels: Bitmap, ready: (Result<ParticleCompositorBitmap>) -> Unit) {
            val target = ParticleCompositorBitmap(pixels, ready)
            try { target.start() } catch (failure: Exception) { target.fail(failure) }
        }
    }
}
