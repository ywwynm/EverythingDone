package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 同一生产者反复写入三张缓冲，对照 Bitmap 路径与直接导入；覆盖透明像素及缓存重用。 */
@androidx.annotation.RequiresApi(34)
internal object ParticleHardwarePageParity {
    fun compare(renderer: ParticleGlBitmapRenderer): String {
        val width = 67; val height = 93
        val buffers = List(3) { HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE) }
        val producers = buffers.map(::HardwareBufferRenderer)
        val pool = ParticleGlHardwareImages()
        val layer = ParticleGlBitmapLayer("direct-buffer-parity", -2, false, underlay = true).apply {
            hardwareImages = pool
        }
        val node = RenderNode("direct-buffer-parity").apply { setPosition(0, 0, width, height) }
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        val expected = ByteArray(pixels.capacity()); val actual = ByteArray(expected.size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var maximum = 0
        try {
            producers.forEach { it.setContentRoot(node) }
            for (frame in 0 until 12) {
                val index = frame % buffers.size
                val canvas = node.beginRecording(width, height)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                paint.color = Color.argb(64 + frame * 13, frame * 19, 180, 240 - frame * 17)
                canvas.drawCircle(12f + frame * 3, 17f + frame * 4, 13f, paint)
                paint.color = Color.WHITE; paint.textSize = 14f
                canvas.drawText("$frame", 3f, 80f, paint)
                node.endRecording()
                val latch = CountDownLatch(1)
                var result: HardwareBufferRenderer.RenderResult? = null
                producers[index].obtainRenderRequest().draw({ it.run() }) { result = it; latch.countDown() }
                check(latch.await(5, TimeUnit.SECONDS))
                val rendered = checkNotNull(result)
                check(rendered.status == HardwareBufferRenderer.RenderResult.SUCCESS)
                rendered.fence.use { check(!it.isValid || it.await(Duration.ofSeconds(5))) }
                // Bitmap 旧路径会关闭 getHardwareBuffer 返回的 Java 句柄，给它独立句柄，
                // 与生产者仍持有同一个原生缓冲的实际租约方式一致。
                val parcel = android.os.Parcel.obtain()
                val referenceBuffer = try {
                    buffers[index].writeToParcel(parcel, 0); parcel.setDataPosition(0)
                    HardwareBuffer.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(referenceBuffer, null))
                try {
                    for (direct in listOf(false, true)) {
                        if (direct) renderer.upload(layer, buffers[index]) else renderer.upload(layer, bitmap)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        GLES30.glViewport(0, 0, width, height)
                        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        renderer.draw(listOf(layer), width, height)
                        pixels.clear()
                        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        pixels.get(if (direct) actual else expected)
                    }
                    for (i in expected.indices) maximum = maxOf(maximum,
                        abs((expected[i].toInt() and 255) - (actual[i].toInt() and 255)))
                    check(maximum == 0) { "直接硬件缓冲改变像素：frame=$frame max=$maximum" }
                } finally { bitmap.recycle(); referenceBuffer.close() }
            }
        } finally {
            renderer.release(layer); pool.close()
            producers.forEach { it.close() }; buffers.forEach { it.close() }; node.discardDisplayList()
        }
        return "12 帧三缓冲复用像素完全一致"
    }
}
