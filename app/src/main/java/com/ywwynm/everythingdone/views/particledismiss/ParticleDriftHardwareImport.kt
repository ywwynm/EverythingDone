package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 飘散的零拷贝快照（API 31+，只在 GL 线程上用）。
 *
 * 新建页的截图本来就渲染在一块 GPU 可采样的 HardwareBuffer 里（[ParticleHardwareSnapshot] 的 ImageReader）。老路要把它
 * 拷回内存（`Bitmap.copy`）、再拷一份字节、最后整张上传回 GPU，整屏快照合计 30 ms 上下。这里经 EGLImage 直接把那块缓冲
 * 绑成纹理；内容分析只在 GPU 上按 [ParticleDriftContent.samplePositions] 取它要读的那些像素读回（整屏快照约 8.6 万个、0.34 MB），
 * 喂给同一个 [ParticleDriftContent.analyseSamples]。纹理内容与样本都与老路逐位相同（调试键
 * [ParticleTuning.KEY_DEBUG_VERIFY_ZERO_COPY] 打开时逐字节核对）。
 *
 * 任何一步不满足条件或失败都返回 null，调用方退回老路。
 */
internal class ParticleDriftHardwareImport private constructor(
    val texture: Int,
    val eglImage: Long,
    val width: Int,
    val height: Int,
    private val count: Int,
    private var output: Int,
    private var fence: Long
) {

    /** GPU 取样完成后返回样本（只返回一次）；还没完成返回 null。不阻塞。 */
    fun pollSamples(): IntArray? {
        if (fence == 0L) return null
        val status = GLES30.glClientWaitSync(fence, 0, 0)
        if (status == GLES30.GL_TIMEOUT_EXPIRED) return null
        check(status != GLES30.GL_WAIT_FAILED) { "零拷贝取样等待失败" }
        GLES30.glDeleteSync(fence)
        fence = 0L
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, output)
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 4, GLES30.GL_MAP_READ_BIT)
            as? ByteBuffer
        val samples = IntArray(count)
        try {
            checkNotNull(mapped) { "零拷贝取样读回失败" }.order(ByteOrder.nativeOrder()).asIntBuffer().get(samples)
        } finally {
            if (mapped != null) GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            GLES30.glDeleteBuffers(1, intArrayOf(output), 0)
            output = 0
        }
        return samples
    }

    /** 纹理交给渲染器之前就退出：纹理、EGLImage、取样缓冲与栅栏全部放掉。 */
    fun release() {
        if (fence != 0L) GLES30.glDeleteSync(fence)
        if (output != 0) GLES30.glDeleteBuffers(1, intArrayOf(output), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        ParticleHardwareTexture.destroy(ParticleGl.display.nativeHandle, eglImage)
        fence = 0L
        output = 0
    }

    /**
     * 仅调试包：导入的纹理整张读回，与老路那份 `Bitmap.copy(ARGB_8888)` 的字节逐字节比；GPU 取来的样本与从那份字节取的样本
     * 逐个比。慢（整屏两份 17.8 MB），只在调试键打开时调用。
     */
    fun verify(snapshot: Bitmap, samples: IntArray): String {
        val software = snapshot.copy(Bitmap.Config.ARGB_8888, false) ?: return "软件拷贝失败"
        val expected = ByteBuffer.allocateDirect(width * height * 4)
        software.copyPixelsToBuffer(expected)
        software.recycle()
        val actual = ByteBuffer.allocateDirect(width * height * 4)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (complete) {
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, actual)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, ids, 0)
        var pixels = 0
        if (complete) for (p in 0 until width * height) {
            val o = p * 4
            if (actual.getInt(o) != expected.getInt(o)) pixels++
        }
        val reference = ParticleDriftContent.samplesFrom(expected, ParticleDriftContent.samplePositions(width, height))
        var differing = 0
        for (k in samples.indices) if (samples[k] != reference[k]) differing++
        return "全图不一致像素=${if (complete) pixels.toString() else "帧缓冲不完整"} 样本不一致=$differing/${samples.size}"
    }

    companion object {
        /** 仅调试包：为 true 时每次零拷贝都逐字节核对（[verify]），由 [ParticleTuning.apply] 读调试键。 */
        @Volatile var verifyForTests = false

        /** 取样位置的存储缓冲按尺寸缓存一份（新建页尺寸固定，0.5 MB）。 */
        private var positionsBuffer = 0
        private var positionsWidth = 0
        private var positionsHeight = 0

        /** [snapshot] 是 HardwareBuffer 时绑成纹理并发起取样；不满足条件或任何一步失败返回 null。 */
        fun begin(snapshot: Bitmap, assets: AssetManager): ParticleDriftHardwareImport? {
            if (Build.VERSION.SDK_INT < 31 || snapshot.config != Bitmap.Config.HARDWARE) return null
            if (!ParticleHardwareTexture.available) return null
            return runCatching { import(snapshot, assets) }
                .onFailure { Log.w(ParticleDriftRenderer.TAG, "飘散零拷贝失败，退回拷贝上传", it) }
                .getOrNull()
        }

        @RequiresApi(31)
        private fun import(snapshot: Bitmap, assets: AssetManager): ParticleDriftHardwareImport? {
            val width = snapshot.width
            val height = snapshot.height
            val display = ParticleGl.display.nativeHandle
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            val texture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            val image = snapshot.hardwareBuffer.use { buffer ->
                if (buffer.format != HardwareBuffer.RGBA_8888 || buffer.width != width || buffer.height != height ||
                    buffer.usage and HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE == 0L) 0L
                else ParticleHardwareTexture.bind(display, buffer)
            }
            if (image == 0L || GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                GLES30.glDeleteTextures(1, ids, 0)
                if (image != 0L) ParticleHardwareTexture.destroy(display, image)
                if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG, "飘散零拷贝不可用：EGLImage 绑定失败")
                return null
            }
            ParticleDriftRenderer.nearestClamp()

            // 内容取样：按下标逐个 texelFetch，写进输出缓冲；栅栏到了再读回（不阻塞 GL 线程）
            val positions = ParticleDriftContent.samplePositions(width, height)
            val count = positions.size
            if (positionsBuffer == 0 || positionsWidth != width || positionsHeight != height) {
                if (positionsBuffer == 0) {
                    GLES30.glGenBuffers(1, ids, 0)
                    positionsBuffer = ids[0]
                }
                val bytes = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
                bytes.asIntBuffer().put(positions)
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, positionsBuffer)
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, count * 4, bytes, GLES30.GL_STATIC_DRAW)
                positionsWidth = width
                positionsHeight = height
            }
            GLES30.glGenBuffers(1, ids, 0)
            val output = ids[0]
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, output)
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, count * 4, null, GLES30.GL_STREAM_READ)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

            val program = ParticleDriftRenderer.gatherProgram(assets)
            GLES30.glUseProgram(program.id)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glUniform1i(program["u_image"], 0)
            GLES30.glUniform1i(program["u_width"], width)
            GLES30.glUniform1i(program["u_count"], count)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, positionsBuffer)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, output)
            GLES31.glDispatchCompute((count + 63) / 64, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, 0)
            GLES30.glUseProgram(0)
            val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()
            val error = GLES30.glGetError()
            val created = ParticleDriftHardwareImport(texture, image, width, height, count, output, fence)
            if (error != GLES30.GL_NO_ERROR) {
                created.release()
                if (BuildConfig.DEBUG) Log.i(ParticleDriftRenderer.TAG, "飘散零拷贝不可用：取样出错 0x${Integer.toHexString(error)}")
                return null
            }
            return created
        }
    }
}
