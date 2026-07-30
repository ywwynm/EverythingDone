package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 64×64 蓝噪声阈值表（D157、D162）。
 *
 * 表本身由 `docs/features/fablesol-video-export/tools/generate_blue_noise.py` 用
 * void-and-cluster 离线生成并入 assets。**不拷贝 libplacebo 的表数据或代码**——那是 LGPL
 * 项目，只参考它"在目标位深码值域以蓝噪声阈值做舍入"的策略。
 *
 * 存的是**秩**（0…4095）而不是 8 位归一化值：目标是 10-bit 码值域的无偏舍入，而 8 位阈值
 * 的量化台阶本身就接近一个目标码值，等于用一把比刻度还粗的尺子去量。阈值取
 * `(rank + 0.5) / 4096`。
 *
 * 图案固定在导出画布坐标里，不随帧旋转、镜像或重新随机化：逐帧噪声会在视频里变成闪烁，
 * 还会平白增加编码压力。Y′、Cb、Cr 各用一个固定且互不相同的相位偏移，避免三个量化器形成
 * 规则相关性。
 */
internal class FableSolExportBlueNoise private constructor(private val ranks: IntArray) {

    private var textureId = 0

    /**
     * 上传成 `R16UI` 单通道整数纹理。
     *
     * 不用 8 位归一化纹理：那样阈值只有 256 级，10-bit 舍入的无偏性会被阈值自身的量化吃掉。
     *
     * @return 纹理 id；创建失败返回 0，调用方据此退回普通四舍五入。
     */
    fun upload(): Int {
        if (textureId != 0) return textureId
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val id = textures[0]
        if (id == 0) return 0
        val pixels = ByteBuffer
            .allocateDirect(SIZE * SIZE * 2)
            .order(ByteOrder.nativeOrder())
        for (rank in ranks) {
            pixels.putShort((rank and 0xFFFF).toShort())
        }
        pixels.rewind()
        textureId = uploadRanks(id, SIZE, pixels)
        return textureId
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    /** CPU 侧取阈值；参考实现与测试用，正式路径在着色器里做同一件事。 */
    fun thresholdAt(x: Int, y: Int, phase: Phase): Double {
        val sx = Math.floorMod(x + phase.offsetX, SIZE)
        val sy = Math.floorMod(y + phase.offsetY, SIZE)
        return thresholdOf(ranks[sy * SIZE + sx])
    }

    /** Y′、Cb、Cr 三个量化器各自的固定相位偏移，避免它们形成规则相关性。 */
    enum class Phase(val offsetX: Int, val offsetY: Int) {
        LUMA(0, 0),
        CB(23, 41),
        CR(47, 13)
    }

    companion object {

        const val SIZE = 64
        const val LEVELS = SIZE * SIZE
        const val ASSET_PATH = "fablesol/bluenoise64.bin"

        fun thresholdOf(rank: Int): Double = (rank + 0.5) / LEVELS

        /**
         * 一张 1×1 的中性阈值纹理，供蓝噪声资源不可用时占位。
         *
         * 采样器必须绑到一张完整纹理——ESSL 的 `usampler2D` 绑不完整纹理是未定义行为，即便
         * 那条分支不会执行。着色器此时按 `uNoiseEnabled = false` 走固定 0.5 阈值，也就是
         * D157 第 6 条要求的"退回普通四舍五入并继续同一格式导出"。
         *
         * @return 纹理 id；创建失败返回 0。
         */
        fun uploadNeutral(): Int {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            val id = textures[0]
            if (id == 0) return 0
            val pixels = ByteBuffer
                .allocateDirect(2)
                .order(ByteOrder.nativeOrder())
            pixels.putShort((LEVELS / 2).toShort())
            pixels.rewind()
            return uploadRanks(id, 1, pixels)
        }

        /** @return 成功时的纹理 id；任何 GL 错误都删掉纹理并返回 0。 */
        private fun uploadRanks(id: Int, size: Int, pixels: ByteBuffer): Int {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, size, size)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, size, size,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, pixels
            )
            // 整数纹理只允许 NEAREST；阈值本来也必须逐 texel 取，不能插值。
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                GLES30.glDeleteTextures(1, intArrayOf(id), 0)
                return 0
            }
            return id
        }

        /**
         * @return null 表示资源缺失或自检未通过；调用方退回普通四舍五入并继续同一格式导出
         *   （D157 第 6 条、D162 第 5 条），不得据此切换位深、编码器族或报导出失败。
         */
        fun load(assets: AssetManager): FableSolExportBlueNoise? = try {
            val bytes = assets.open(ASSET_PATH).use { it.readBytes() }
            decode(bytes)?.let { FableSolExportBlueNoise(it) }
        } catch (ignored: Throwable) {
            null
        }

        /**
         * 解码并自检：4096 个小端 uint16，必须恰好是 0…4095 的一个排列。
         *
         * 自检不是形式主义——表若被截断或写坏，画面上表现为一片规则纹理，而"抖动看起来有点
         * 脏"这种现象很难被归因到资源本身。
         */
        fun decode(bytes: ByteArray): IntArray? {
            if (bytes.size != LEVELS * 2) return null
            val ranks = IntArray(LEVELS)
            val seen = BooleanArray(LEVELS)
            for (index in 0 until LEVELS) {
                val low = bytes[index * 2].toInt() and 0xFF
                val high = bytes[index * 2 + 1].toInt() and 0xFF
                val rank = low or (high shl 8)
                if (rank >= LEVELS || seen[rank]) return null
                seen[rank] = true
                ranks[index] = rank
            }
            return ranks
        }
    }
}
