package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.CompletableFuture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap

/** 快照就绪后立即建材，与叠加层布局和 EGL 建链重叠；不在主线程等待。 */
internal class ParticleMicroflakePreparation(
    assets: AssetManager,
    private val density: Float,
    spec: ParticleDismissSpec
) {
    private val started = System.nanoTime()
    data class Pixels(val bitmap: Bitmap, val argb: IntArray)
    private val pixelsReady = CompletableFuture<Unit>()
    @Volatile private var packedPixels: Pixels? = null
    @Volatile var buildMs = 0.0
        private set
    private val task = FutureTask {
        // 这段计算直接阻塞用户可见首帧，使用显示任务优先级，而非普通后台任务。
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        try {
            val bitmap = spec.snapshot
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            packedPixels = Pixels(bitmap, pixels)
            pixelsReady.complete(Unit)
            ParticleMicroflakeRenderer.fromSpec(assets, 0, 0, density, spec, pixels).also {
                buildMs = (System.nanoTime() - started) / 1e6
            }
        } catch (error: Throwable) {
            pixelsReady.completeExceptionally(error)
            throw error
        }
    }.also { executor.execute(it) }

    val isFinished get() = task.isDone
    fun awaitPixels(): Pixels {
        pixelsReady.get()
        return checkNotNull(packedPixels).also { packedPixels = null }
    }

    fun await(width: Int, height: Int): ParticleMicroflakeRenderer.Input {
        val scale = (density / 1.875f).coerceAtLeast(.5f)
        return task.get().copy(frameWidth = width / scale, frameHeight = height / scale)
    }

    companion object {
        fun packColors(pixels: IntArray): ByteBuffer {
            val rgba = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            if (ParticleMaterialNative.enabled) ParticleMaterialNative.packColors(pixels, rgba)
            else {
                val colors = rgba.asIntBuffer()
                for (c in pixels) colors.put((c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16))
            }
            return rgba
        }
        // 快速连续关闭也限制并发。任务只保留本次快照，不保留 Activity 或 View。
        private val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "ParticleMaterial").apply { isDaemon = true }
        }
    }
}
