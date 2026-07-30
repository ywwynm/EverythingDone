package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 全片静态亮度预分析的 GPU 侧（fablesol-video-export D85、D86）。
 *
 * 每帧一趟：把最终可见合成的**线性 BT.2020** 画面归约到 [GRID]×[GRID]，回读 4 KB，由 CPU
 * 取全画面最大值与加权平均值，再跨帧累计成 `MaxCLL` 与 `MaxFALL`。
 *
 * 呈现中间面必须是 `RGBA16F`：分析读的是未经 OETF 的线性值，最高可到 HDR 强度（9.6），
 * 归一化定点面会把高光一律截成 1.0。建不出来时 [create] 返回 null，调用方按 D90 的理论
 * 回退处理，而不是发布一个错误的实测值。
 */
internal class FableSolExportLuminanceReducer private constructor(
    assets: AssetManager,
    private val presentTarget: FableSolExportPresentTarget,
    private val widthPx: Int,
    private val heightPx: Int,
    /** 归一化上界，取本次 HDR 强度；呈现阶段已把场景钳在这个值内。 */
    private val maxValue: Float
) {

    private val program = FableSolGlProgram(
        assets, "fablesol/glsl/fullscreen.vert", "fablesol/glsl/hdr_stats.frag"
    )
    private val vertexArrayId: Int
    private var textureId = 0
    private var framebufferId = 0
    private val pixels: ByteBuffer = ByteBuffer
        .allocateDirect(GRID * GRID * 4)
        .order(ByteOrder.nativeOrder())
    private val bytes = ByteArray(GRID * GRID * 4)

    /**
     * 每个归约块实际覆盖的像素数。
     *
     * 边缘块可能不满——着色器按 `uWidth/uHeight` 提前 break——所以帧平均必须按这份权重加权，
     * 不能把 1024 个块均值直接算术平均。
     */
    private val blockWeights = DoubleArray(GRID * GRID)
    private val totalPixels: Double

    private var peakNormalized = 0.0
    private var maxFrameAverage = 0.0
    private var frames = 0L

    /** 非 null 即本次归约不可用；调用方按 D90 回退，不得把占位值当成实测结果。 */
    var failure: String? = null
        private set

    init {
        val blockW = (widthPx + GRID - 1) / GRID
        val blockH = (heightPx + GRID - 1) / GRID
        var pixelSum = 0.0
        for (by in 0 until GRID) {
            val rows = (heightPx - by * blockH).coerceIn(0, blockH)
            for (bx in 0 until GRID) {
                val columns = (widthPx - bx * blockW).coerceIn(0, blockW)
                val weight = (rows.toDouble() * columns.toDouble())
                blockWeights[by * GRID + bx] = weight
                pixelSum += weight
            }
        }
        totalPixels = pixelSum

        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArrayId = arrays[0]

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, GRID, GRID)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            failure = "HDR stats target incomplete: 0x${Integer.toHexString(status)}"
        }
    }

    /** 呈现阶段要绑定的 framebuffer；写进来的是线性 BT.2020 的最终可见合成。 */
    val presentFramebufferId: Int get() = presentTarget.framebufferId

    /** 归约本帧并累计。失败一次即整段作废，由调用方走 D90 回退。 */
    fun accumulate() {
        if (failure != null) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glViewport(0, 0, GRID, GRID)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vertexArrayId)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, presentTarget.textureId)
        GLES30.glUniform1i(program.uniform("uSource"), 0)
        GLES30.glUniform1i(program.uniform("uWidth"), widthPx)
        GLES30.glUniform1i(program.uniform("uHeight"), heightPx)
        GLES30.glUniform1i(program.uniform("uBlockW"), (widthPx + GRID - 1) / GRID)
        GLES30.glUniform1i(program.uniform("uBlockH"), (heightPx + GRID - 1) / GRID)
        GLES30.glUniform1f(program.uniform("uMaxValue"), maxValue)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        pixels.clear()
        GLES30.glReadPixels(
            0, 0, GRID, GRID, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
        )
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (error != GLES30.GL_NO_ERROR) {
            failure = "HDR stats readback failed: 0x${Integer.toHexString(error)}"
            return
        }
        pixels.clear()
        pixels.get(bytes)

        var framePeak = 0.0
        var weighted = 0.0
        for (index in blockWeights.indices) {
            val offset = index * 4
            val peak = decode16(bytes[offset], bytes[offset + 1]) * maxValue
            val mean = decode16(bytes[offset + 2], bytes[offset + 3]) * maxValue
            if (peak > framePeak) framePeak = peak
            weighted += mean * blockWeights[index]
        }
        if (framePeak > peakNormalized) peakNormalized = framePeak
        val frameAverage = if (totalPixels > 0.0) weighted / totalPixels else 0.0
        if (frameAverage > maxFrameAverage) maxFrameAverage = frameAverage
        frames++
    }

    /** @return null 表示归约失败或一帧都没跑；调用方按 D90 使用理论回退。 */
    fun result(): FableSolExportLuminanceStats? {
        if (failure != null || frames <= 0L) return null
        return FableSolExportLuminanceStats(
            maxContentNormalized = peakNormalized,
            maxFrameAverageNormalized = maxFrameAverage,
            measured = true
        )
    }

    fun release() {
        program.release()
        presentTarget.release()
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        }
    }

    companion object {

        /** 归约网格边长；回读 [GRID]×[GRID]×4 = 4 KB。 */
        const val GRID = 32

        /** @return null 表示本机建不出 FP16 呈现中间面，按 D90 回退。 */
        fun create(
            assets: AssetManager,
            widthPx: Int,
            heightPx: Int,
            maxValue: Float
        ): FableSolExportLuminanceReducer? {
            val target = FableSolExportPresentTarget.createHighPrecision(widthPx, heightPx)
                ?: return null
            return FableSolExportLuminanceReducer(
                assets, target, widthPx, heightPx, maxValue
            )
        }

        private fun decode16(low: Byte, high: Byte): Double =
            (((low.toInt() and 0xFF) or ((high.toInt() and 0xFF) shl 8)).toDouble()) / 65535.0

        /**
         * 逐帧平均值的加权口径；JVM 可测。
         *
         * @param blockMeans 每个块的均值。
         * @param blockWeights 每个块实际覆盖的像素数——边缘块不满，直接算术平均会高估边缘。
         */
        fun frameAverage(blockMeans: DoubleArray, blockWeights: DoubleArray): Double {
            var weighted = 0.0
            var total = 0.0
            for (index in blockMeans.indices) {
                weighted += blockMeans[index] * blockWeights[index]
                total += blockWeights[index]
            }
            return if (total > 0.0) weighted / total else 0.0
        }

        /** 归约网格的块权重；与 [FableSolExportLuminanceReducer] 的构造完全同源。 */
        fun blockWeights(widthPx: Int, heightPx: Int): DoubleArray {
            val blockW = (widthPx + GRID - 1) / GRID
            val blockH = (heightPx + GRID - 1) / GRID
            val weights = DoubleArray(GRID * GRID)
            for (by in 0 until GRID) {
                val rows = (heightPx - by * blockH).coerceIn(0, blockH)
                for (bx in 0 until GRID) {
                    val columns = (widthPx - bx * blockW).coerceIn(0, blockW)
                    weights[by * GRID + bx] = rows.toDouble() * columns.toDouble()
                }
            }
            return weights
        }
    }
}
