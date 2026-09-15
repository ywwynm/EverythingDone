package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.CompletableFuture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap

/**
 * 快照就绪后立即建材，与叠加层布局和 EGL 建链重叠；不在主线程等待。
 *
 * [deferred] 为 true 时**只解像素、不建材**：本次新建命中了提前算好的轨迹（第四批 ②），
 * 材料与轨迹都现成，建材那 40–70 ms 完全可以省掉。GL 线程若在收养那一刻发现预算不可用
 * （共享资源尺寸变了之类），调 [requireModel] 补一次即可，仍然走原路径，只是晚了一点。
 */
internal class ParticleMicroflakePreparation(
    private val assets: AssetManager,
    private val density: Float,
    private val spec: ParticleDismissSpec,
    private val deferred: Boolean = false
) {
    private val started = System.nanoTime()

    /**
     * 上传用的一份字节（R,G,B,A，可直接 `glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE)`）。
     * [argb] 只给材料模型用，由同一份字节反解，不再从 Bitmap 二次取像素。
     */
    class Pixels(val width: Int, val height: Int, val rgba: ByteBuffer, val argb: IntArray?)

    private val pixelsReady = CompletableFuture<Unit>()
    @Volatile private var packedPixels: Pixels? = null
    @Volatile var buildMs = 0.0
        private set
    // 构造发生在主线程、动画层创建的那一刻：此时读到的「已经有反馈或播放在跑」才是本次的真实处境。
    private val contended = ParticlePerfTuning.defaultPriorityPreparation && ParticleGpuWork.busy

    /** 只解像素、不建材那一档留下的 ARGB；[requireModel] 补建材时直接用它。 */
    @Volatile private var sourcePixels: IntArray? = null

    private val task = FutureTask {
        // 这段计算直接阻塞用户可见首帧，所以默认仍用显示优先级。
        // 但涟漪或上一层粒子正在播时，它与主线程、RenderThread 抢同一批大核——
        // 那种处境下让出优先级，只让真正在出帧的线程留在 DISPLAY（见 1.7）。
        android.os.Process.setThreadPriority(
            if (contended) android.os.Process.THREAD_PRIORITY_DEFAULT
            else android.os.Process.THREAD_PRIORITY_DISPLAY)
        try {
            val bitmap = spec.snapshot
            // 圆角遮罩是一遍全图软件绘制（创建页是整屏 4 Mpx），放在材料线程里、取像素之前做：
            // 动画输入与之前逐位相同，主线程少一次同量级的阻塞绘制。
            if (ParticlePerfTuning.maskOnMaterialThread && spec.roundedCornerRadiusPx > 0f) {
                ParticleDismissController.applyRoundedCornerMask(bitmap, spec.roundedCornerRadiusPx)
            }
            // 3.2：只从 Bitmap 拷一次。copyPixelsToBuffer 对 ARGB_8888 给出的就是 R,G,B,A，
            // 与旧路径 getPixels + packColors 的结果逐字节相同（ParticleSnapshotBytesTest 守住）。
            val rgba = copyPixels(bitmap)
            val count = bitmap.width * bitmap.height
            if (rgba != null) {
                // 先把上传字节交出去：GL 线程可以立刻建前景纹理，不等材料模型算完。
                packedPixels = Pixels(bitmap.width, bitmap.height, rgba, null)
                pixelsReady.complete(Unit)
            }
            val argb = if (rgba != null) unpackColors(rgba, count)
                else IntArray(count).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            if (rgba == null) {
                packedPixels = Pixels(bitmap.width, bitmap.height, packColors(argb), argb)
                pixelsReady.complete(Unit)
            }
            sourcePixels = argb
            // 第四批 ②：这一屏就是**下一次**新建的预测素材。这里只多留一个引用，不复制像素。
            ParticleCreationPrewarm.remember(spec, argb)
            if (deferred) null else buildModel(argb)
        } catch (error: Throwable) {
            pixelsReady.completeExceptionally(error)
            throw error
        }
    }.also { executor.execute(it) }

    @Volatile private var modelTask: FutureTask<ParticleMicroflakeRenderer.Input>? = null

    private fun buildModel(argb: IntArray): ParticleMicroflakeRenderer.Input =
        ParticleTrace.section("particle-material") {
            ParticleMicroflakeRenderer.fromSpec(assets, 0, 0, density, spec, argb).also {
                buildMs = (System.nanoTime() - started) / 1e6
            }
        }

    /**
     * 收养提前算好的轨迹失败时补一次建材（GL 线程调用）。
     *
     * 像素那一步此时已经完成（收养发生在拿到像素之后），所以这个任务不会在只有两条线程的
     * 池子里与自己的像素任务互等。
     */
    fun requireModel() {
        if (!deferred) return
        synchronized(this) {
            if (modelTask != null) return
            val pixels = sourcePixels ?: return
            val created = FutureTask {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                buildModel(pixels)
            }
            modelTask = created
            executor.execute(created)
        }
    }

    val isFinished get() = task.isDone && modelTask?.isDone != false

    /** 非阻塞：尚未就绪时返回 null。取走之后本对象不再持有那份字节。 */
    fun pollPixels(): Pixels? {
        if (!pixelsReady.isDone) return null
        pixelsReady.get()
        return packedPixels?.also { packedPixels = null }
    }

    fun awaitPixels(): Pixels {
        pixelsReady.get()
        return checkNotNull(packedPixels).also { packedPixels = null }
    }

    /** 非阻塞：材料模型尚未算完时返回 null（延后建材且还没调 [requireModel] 时恒为 null）。 */
    fun poll(width: Int, height: Int): ParticleMicroflakeRenderer.Input? {
        val fallback = modelTask
        if (fallback != null) return if (fallback.isDone) framed(fallback.get(), width, height) else null
        if (!task.isDone) return null
        val built = task.get() ?: return null
        return framed(built, width, height)
    }

    fun await(width: Int, height: Int): ParticleMicroflakeRenderer.Input {
        modelTask?.let { return framed(it.get(), width, height) }
        return framed(checkNotNull(task.get()) { "本次建材被延后，先调 requireModel" }, width, height)
    }

    private fun framed(input: ParticleMicroflakeRenderer.Input, width: Int, height: Int):
        ParticleMicroflakeRenderer.Input {
        val scale = (density / 1.875f).coerceAtLeast(.5f)
        return input.copy(frameWidth = width / scale, frameHeight = height / scale)
    }

    companion object {
        /**
         * ARGB_8888 的内存布局就是 R,G,B,A；行距等于宽度 × 4 时可以整块拷走。
         * 配置或行距不符（理论上只有极少见的对齐情况）时返回 null，退回旧的
         * `getPixels` + [packColors] 路径。
         */
        fun copyPixels(bitmap: Bitmap): ByteBuffer? {
            if (bitmap.config != Bitmap.Config.ARGB_8888) return null
            if (bitmap.rowBytes != bitmap.width * 4) return null
            val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            return runCatching {
                bitmap.copyPixelsToBuffer(buffer)
                buffer.rewind()
                buffer
            }.getOrNull()
        }

        /** 上传字节 → Android ARGB int；与 [packColors] 是同一个 R↔B 对调。 */
        fun unpackColors(rgba: ByteBuffer, count: Int): IntArray {
            val out = IntArray(count)
            rgba.rewind()
            if (ParticleMaterialNative.enabled) ParticleMaterialNative.unpackColors(rgba, out)
            else {
                val colors = rgba.asIntBuffer()
                for (i in 0 until count) {
                    val c = colors.get(i)
                    out[i] = (c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16)
                }
            }
            rgba.rewind()
            return out
        }

        fun packColors(pixels: IntArray): ByteBuffer {
            val rgba = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            if (ParticleMaterialNative.enabled) ParticleMaterialNative.packColors(pixels, rgba)
            else {
                val colors = rgba.asIntBuffer()
                for (c in pixels) colors.put((c and 0xff00ff00.toInt()) or ((c ushr 16) and 255) or ((c and 255) shl 16))
            }
            rgba.rewind()
            return rgba
        }
        // 快速连续关闭也限制并发。任务只保留本次快照，不保留 Activity 或 View。
        private val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "ParticleMaterial").apply { isDaemon = true }
        }
    }
}
