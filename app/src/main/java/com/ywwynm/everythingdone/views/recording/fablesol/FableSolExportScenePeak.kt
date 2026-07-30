package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `动态映射` 的逐帧超白峰值测量（fablesol-video-export D73～D75）。
 *
 * 一趟把 FableSol 场景纹理归约到 [GRID]×[GRID]，回读之后由 CPU 取完剩下的最大值。分成两级
 * 是为了让回读只有 4 KB：单个 fragment 归约整张画面会把并行度压成 1，而 32×32 的网格既能
 * 把归约留在 GPU 上，回读又小到可以每帧同步做。
 *
 * 测的是 FableSol 场景纹理本身——padding、画框、投影、描边和时钟都在呈现阶段之后才合成，
 * 天然不在范围内（D73）。
 *
 * 任何一步失败都通过 [failure] 上报；调用方据此丢弃本次尝试并从第 1 帧改用稳定映射（D77），
 * 不得继续按"动态映射"标注产物。
 */
internal class FableSolExportScenePeak(
    assets: AssetManager,
    /** 归一化用的上界，取本次 HDR 强度；超出部分本来就被呈现阶段钳掉。 */
    private val maxValue: Float
) {

    /** 第一条失败原因；非 null 即本条通路不可用。声明必须先于 [program]：它的兜底要写这里。 */
    var failure: String? = null
        private set

    // 编译/链接失败同属"动态统计通路失败"（D77）：必须落进 [failure] 让调用方从第 1 帧改用
    // 稳定映射，而不是抛出去被当成候选失败、走公开规格失败/确认流程。
    private val program: FableSolGlProgram? = try {
        FableSolGlProgram(
            assets, "fablesol/glsl/fullscreen.vert", "fablesol/glsl/sdr_peak.frag"
        )
    } catch (error: Throwable) {
        failure = "SDR peak shader unavailable: ${error.message ?: error.javaClass.simpleName}"
        null
    }
    private val vertexArrayId: Int
    private var textureId = 0
    private var framebufferId = 0
    private val pixels: ByteBuffer = ByteBuffer
        .allocateDirect(GRID * GRID * 4)
        .order(ByteOrder.nativeOrder())
    private val bytes = ByteArray(GRID * GRID * 4)

    init {
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
            failure = "SDR peak target incomplete: 0x${Integer.toHexString(status)}"
        }
    }

    /**
     * @return 本帧 FableSol 内容的 maxRGB 峰值；[failure] 已经非 null 时返回 `1.0`，
     *   调用方应当在下一次检查时改走稳定映射，而不是把这个占位值当成测量结果。
     */
    fun measure(sceneTextureId: Int, widthPx: Int, heightPx: Int): Double {
        // program 为 null 时 failure 必然已在构造期写入，这里的守卫同时挡住两者。
        val program = this.program
        if (failure != null || program == null) return 1.0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glViewport(0, 0, GRID, GRID)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vertexArrayId)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
        GLES30.glUniform1i(program.uniform("uSource"), 0)
        GLES30.glUniform1i(program.uniform("uWidth"), widthPx)
        GLES30.glUniform1i(program.uniform("uHeight"), heightPx)
        // 块尺寸向上取整，最后一块可能越界——着色器里按 uWidth/uHeight 提前 break。
        GLES30.glUniform1i(program.uniform("uBlockW"), (widthPx + GRID - 1) / GRID)
        GLES30.glUniform1i(program.uniform("uBlockH"), (heightPx + GRID - 1) / GRID)
        GLES30.glUniform1f(program.uniform("uMaxValue"), maxValue)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 源纹理马上又要作为呈现趟的采样源，先解绑；紧接着的 glReadPixels 会隐式等待绘制完成。
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        pixels.clear()
        GLES30.glReadPixels(
            0, 0, GRID, GRID, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
        )
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (error != GLES30.GL_NO_ERROR) {
            failure = "SDR peak readback failed: 0x${Integer.toHexString(error)}"
            return 1.0
        }
        pixels.clear()
        pixels.get(bytes)
        return decodePeak(bytes, maxValue.toDouble())
    }

    fun release() {
        program?.release()
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

        /** 与 `sdr_peak.frag` 的打包一一对应；JVM 侧可测。 */
        fun encodePeak(value: Double, maxValue: Double): Pair<Int, Int> {
            val normalized = (value / maxValue).coerceIn(0.0, 1.0)
            val code = Math.round(normalized * 65535.0).toInt()
            return (code and 0xFF) to ((code shr 8) and 0xFF)
        }

        /** 从 RGBA8 回读结果里取出全画面峰值。 */
        fun decodePeak(bytes: ByteArray, maxValue: Double): Double {
            var code = 0
            var index = 0
            while (index + 1 < bytes.size) {
                val value = (bytes[index].toInt() and 0xFF) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8)
                if (value > code) code = value
                index += 4
            }
            return code / 65535.0 * maxValue
        }
    }
}
