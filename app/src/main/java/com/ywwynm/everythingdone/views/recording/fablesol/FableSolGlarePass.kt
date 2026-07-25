package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * 人眼眩光 PSF pass（D206~D213，与 Python glare_pass 一比一）：把 CPU 星光
 * 轨迹铺成近核光轮 + 梦幻宽晕 + 逐星针芒精灵。
 *
 * 管线位置：场景 resolve 之后、present 之前。半分辨率注入星点高斯 →
 * σ1.2dp 弥散成 S → σ7.2dp 二级模糊成宽晕 W → 光轮+宽晕合成 → 逐星针芒
 * 精灵（每颗星各带自己的针表：朝向偏移/转速/参差/长度终生稳定、互不相同
 * ——不同视场方向的光穿过泪膜/晶状体的不同区域，星芒图案本就各不相同）
 * → 全分辨率 resolve `min(min(scene, cap) + glare, cap)`，
 * cap = 1 + (headroom−1)×hdrGain——SDR 态 cap=1（sRGB 解码/回编码），
 * 录音态 cap=headroom 与既有钳制一致。
 *
 * 幅度合同：PSF **形状**取物理（1/r² 针剖面 + 长度-强度耦合 → 针长 ∝
 * √亮度；λ 径向色散暖尖；主芒偏水平的黄金角线表），**幅度**做显示补偿。
 */
internal class FableSolGlarePass(private val assets: AssetManager) {

    companion object {
        /** 针芒线方向表：黄金角序列（低差异分布）。D213：整表旋转 90°——
         * 主芒（最长线）偏**水平**起始；逐星另叠随机朝向偏移。i=9..15 为
         * 线数上限 16 的延展（~3.2° 近邻簇 = 复合粗芒）。 */
        private val NEEDLE_TABLE = floatArrayOf(
            // 角度°, 正向长度因子, 负向长度因子。
            0.0f, 1.00f, 0.92f,
            102.4f, 0.55f, 0.44f,
            24.8f, 0.62f, 0.48f,
            127.2f, 0.42f, 0.56f,
            49.6f, 0.52f, 0.40f,
            152.0f, 0.70f, 0.46f,
            74.4f, 0.40f, 0.52f,
            176.8f, 0.58f, 0.78f,
            99.2f, 0.48f, 0.42f,
            21.6f, 0.66f, 0.38f,
            124.0f, 0.36f, 0.50f,
            46.4f, 0.46f, 0.60f,
            148.8f, 0.54f, 0.36f,
            71.2f, 0.38f, 0.44f,
            173.6f, 0.72f, 0.50f,
            96.0f, 0.44f, 0.34f
        )
        const val MAX_NEEDLE_LINES = 16
        private const val NEEDLE_KNEE_DP = 3.0
        /** 针芒精灵的垂向细线 σ（D213 解析针芒）。 */
        private const val NEEDLE_PERP_SIGMA_DP = 0.9
        private const val NEEDLE_WEIGHT = 0.78f
        private const val AURA_SIGMA_DP = 1.2
        /** 梦幻宽晕（D210）：迷雾眩光宽裙的二级模糊 σ。 */
        private const val HALO_SIGMA_DP = 7.2
        private const val STAR_DOT_SIGMA_DP = 0.9
        private const val STAR_DOT_EXTENT_SIGMAS = 3.0
        private const val BLUR_TAP_RADIUS = 12
        private const val FLOATS_PER_QUAD_VERTEX = 8
        /** σ0.9dp 点源经两级归一化模糊后的峰值稀释率（uHaloGain 反归一用）。 */
        private const val HALO_DOT_DILUTION =
            (STAR_DOT_SIGMA_DP * STAR_DOT_SIGMA_DP) /
                (STAR_DOT_SIGMA_DP * STAR_DOT_SIGMA_DP +
                    AURA_SIGMA_DP * AURA_SIGMA_DP + HALO_SIGMA_DP * HALO_SIGMA_DP)
        // 泪膜/瞳孔动力学（D210）：星芒模式整体慢旋（有界摆动）+ 每道半芒
        // 长短呼吸；与 Python needle_uniform_table 一比一。
        private const val SWAY_AMPLITUDE_A = 0.20
        private const val SWAY_PERIOD_A_S = 9.6
        private const val SWAY_AMPLITUDE_B = 0.09
        private const val SWAY_PERIOD_B_S = 23.4
        private const val BREATHE_BASE = 0.72
        private const val BREATHE_SPAN = 0.36
        private const val BREATHE_RATE_HZ = 0.29

        private fun hash01(seed: Double, salt: Double): Double {
            val raw = sin(seed * 127.1 + salt * 311.7) * 43758.5453
            return raw - floor(raw)
        }
    }

    private var starProgram: FableSolGlProgram? = null
    private var blurProgram: FableSolGlProgram? = null
    private var combineProgram: FableSolGlProgram? = null
    private var needleProgram: FableSolGlProgram? = null
    private var resolveProgram: FableSolGlProgram? = null

    private var excessTextureId = 0
    private var pingTextureId = 0
    private var auraTextureId = 0
    private var wideTextureId = 0
    private var outputTextureId = 0
    private var excessFramebufferId = 0
    private var pingFramebufferId = 0
    private var auraFramebufferId = 0
    private var wideFramebufferId = 0
    private var outputFramebufferId = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private var halfWidth = 0
    private var halfHeight = 0
    private var halfFloatTargets = false

    private var starBufferId = 0
    private var starVaoId = 0
    private var needleBufferId = 0
    private var needleVaoId = 0
    private val starVertexData =
        FloatArray(FableSolStarField.MAX_STARS * 6 * FLOATS_PER_QUAD_VERTEX)
    private val starVertexUpload: FloatBuffer =
        ByteBuffer.allocateDirect(starVertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val needleVertexData =
        FloatArray(FableSolStarField.MAX_STARS * 6 * FLOATS_PER_QUAD_VERTEX)
    private val needleVertexUpload: FloatBuffer =
        ByteBuffer.allocateDirect(needleVertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val blurWeights = FloatArray(BLUR_TAP_RADIUS + 1)
    private val needleUniform = FloatArray(MAX_NEEDLE_LINES * 4)
    private var failed = false

    /**
     * 逐星生成针芒线表（D210~D213）：慢旋（有界摆动，逐星转速/相位不同）
     * + 每道半芒独立长短呼吸 + 参差指数（对静态长度因子取幂，主芒恒 1）。
     * timeSeconds 用 sim.t——冻结时摆动/呼吸随之静止。
     */
    private fun buildNeedleUniform(
        timeSeconds: Double,
        lengthVariance: Double,
        seed: Double
    ) {
        // 逐星图案（D213，D214 起零分配）：种子终生稳定 → 差异终生稳定；
        // 朝向偏移 ±26° 保持主芒整体偏水平的基调。
        val rotationOffset = (hash01(seed, 1.0) - 0.5) * 0.9
        val swayRate = 0.6 + 0.8 * hash01(seed, 2.0)
        val swayPhase = hash01(seed, 3.0) * 37.0
        val varianceScale = 0.7 + 0.6 * hash01(seed, 4.0)
        val breatheOffset = hash01(seed, 6.0) * 19.0
        val swayTime = timeSeconds * swayRate + swayPhase
        val sway = SWAY_AMPLITUDE_A * sin(2.0 * Math.PI * swayTime / SWAY_PERIOD_A_S) +
            SWAY_AMPLITUDE_B * sin(2.0 * Math.PI * swayTime / SWAY_PERIOD_B_S) +
            rotationOffset
        val breathePhase = timeSeconds * BREATHE_RATE_HZ + breatheOffset
        val variance = max(lengthVariance * varianceScale, 0.0)
        var cursor = 0
        for (line in 0 until MAX_NEEDLE_LINES) {
            val theta = Math.toRadians(NEEDLE_TABLE[line * 3].toDouble()) + sway
            val breathePositive = BREATHE_BASE + BREATHE_SPAN *
                FableSolStarField.valueNoise01(breathePhase + line * 3.1, line * 7.7)
            val breatheNegative = BREATHE_BASE + BREATHE_SPAN *
                FableSolStarField.valueNoise01(
                    breathePhase + line * 3.1 + 11.7, line * 7.7 + 3.3
                )
            needleUniform[cursor++] = cos(theta).toFloat()
            needleUniform[cursor++] = sin(theta).toFloat()
            needleUniform[cursor++] = (Math.pow(
                NEEDLE_TABLE[line * 3 + 1].toDouble(), variance
            ) * breathePositive).toFloat()
            needleUniform[cursor++] = (Math.pow(
                NEEDLE_TABLE[line * 3 + 2].toDouble(), variance
            ) * breatheNegative).toFloat()
        }
    }

    /**
     * 返回叠加眩光后的全分辨率纹理 id；任何一步失败返回 0（调用方回退到
     * 原场景纹理，画面无眩光但不黑屏）。星坐标为本地未旋转 px，这里按
     * water.vert 同一变换转屏幕像素。
     */
    fun render(
        sceneTextureId: Int,
        width: Int,
        height: Int,
        sceneLinear: Boolean,
        displayCap: Float,
        rotationRad: Float,
        density: Double,
        strength: Float,
        haloStrength: Float,
        needleLengthDp: Float,
        needleCount: Int,
        needleVariance: Float,
        timeSeconds: Double,
        starField: FableSolStarField
    ): Int {
        if (failed) return 0
        try {
            ensurePrograms()
            ensureTargets(width, height)
        } catch (error: RuntimeException) {
            // 编译/FBO 建立失败只发生在首次；记忆失败态避免每帧重试刷日志。
            failed = true
            release()
            return 0
        }
        GLES30.glDisable(GLES30.GL_BLEND)

        val halfPerDp = density * 0.5
        val kneePx = (NEEDLE_KNEE_DP * density).toFloat()
        val perpSigmaPx = (NEEDLE_PERP_SIGMA_DP * density).toFloat()
        val sigmaPx = (AURA_SIGMA_DP * halfPerDp).toFloat()
        val spacingPx = max(sigmaPx / 3f, 1f)
        val wideSigmaPx = (HALO_SIGMA_DP * halfPerDp).toFloat()
        // 12 tap × σ/4 间距 = ±3σ 覆盖且 tap 栅格 < 源平滑尺度
        //（σ/2 会织出格点阵——"小方格"伪影，D212）。
        val wideSpacingPx = max(wideSigmaPx / 4f, 1f)

        // 1) 星点注入半分辨率 E 场（光轮/宽晕的源）。
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, excessFramebufferId)
        GLES30.glViewport(0, 0, halfWidth, halfHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val starCount = starField.starCount
        if (starCount > 0) {
            drawStarDots(starField, width, height, rotationRad, density)
        }

        // 2) 近核弥散 E → S（blurH 进 ping，blurV 进 aura）。
        computeBlurWeights(sigmaPx, spacingPx)
        val blur = blurProgram!!
        blur.use()
        GLES30.glUniform1i(blur.uniform("uSource"), 0)
        GLES30.glUniform1fv(blur.uniform("uWeights[0]"), blurWeights.size, blurWeights, 0)
        // sigma1.2 只需 ±4 tap（3.3σ 覆盖），宽晕 ±12（D214 性能）。
        GLES30.glUniform1i(blur.uniform("uTapCount"), 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFramebufferId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, excessTextureId)
        GLES30.glUniform2f(blur.uniform("uTexelStep"), spacingPx / halfWidth, 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, auraFramebufferId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTextureId)
        GLES30.glUniform2f(blur.uniform("uTexelStep"), 0f, spacingPx / halfHeight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 2b) 梦幻宽晕 S → W（σ7.2dp 二级模糊；ping 作横向 scratch）。
        computeBlurWeights(wideSigmaPx, wideSpacingPx)
        GLES30.glUniform1fv(blur.uniform("uWeights[0]"), blurWeights.size, blurWeights, 0)
        GLES30.glUniform1i(blur.uniform("uTapCount"), 12)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFramebufferId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, auraTextureId)
        GLES30.glUniform2f(blur.uniform("uTexelStep"), wideSpacingPx / halfWidth, 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, wideFramebufferId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTextureId)
        GLES30.glUniform2f(blur.uniform("uTexelStep"), 0f, wideSpacingPx / halfHeight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 3) 光轮 + 宽晕合成写回 ping。
        val combine = combineProgram!!
        combine.use()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingFramebufferId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, auraTextureId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wideTextureId)
        GLES30.glUniform1i(combine.uniform("uSpread"), 0)
        GLES30.glUniform1i(combine.uniform("uSpreadWide"), 1)
        GLES30.glUniform1f(combine.uniform("uStrength"), strength)
        GLES30.glUniform1f(
            combine.uniform("uHaloGain"),
            (max(haloStrength, 0f) / HALO_DOT_DILUTION).toFloat()
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 4) 逐星针芒精灵（D213）：每颗星各带自己的针表，加性叠进 ping。
        if (starCount > 0) {
            drawNeedleSprites(
                starField, width, height, rotationRad, density,
                strength, needleLengthDp, needleCount, needleVariance,
                kneePx, perpSigmaPx, timeSeconds
            )
        }

        // 5) 全分辨率 resolve：显示 cap + 眩光叠加。
        val resolve = resolveProgram!!
        resolve.use()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingTextureId)
        GLES30.glUniform1i(resolve.uniform("uScene"), 0)
        GLES30.glUniform1i(resolve.uniform("uGlare"), 1)
        GLES30.glUniform1f(resolve.uniform("uDisplayCap"), displayCap)
        GLES30.glUniform1i(resolve.uniform("uSceneLinear"), if (sceneLinear) 1 else 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return outputTextureId
    }

    /** 星点/针芒精灵共用的四边形填充（extent 按用途不同）。 */
    private fun fillQuad(
        target: FloatArray,
        quadIndex: Int,
        screenX: Float,
        screenY: Float,
        extent: Float,
        amplitude: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        var cursor = quadIndex * 6 * FLOATS_PER_QUAD_VERTEX
        for (corner in 0 until 6) {
            val cornerX = if (corner == 2 || corner == 3 || corner == 5) {
                screenX + extent
            } else {
                screenX - extent
            }
            val cornerY = if (corner == 1 || corner == 4 || corner == 5) {
                screenY + extent
            } else {
                screenY - extent
            }
            target[cursor++] = cornerX
            target[cursor++] = cornerY
            target[cursor++] = screenX
            target[cursor++] = screenY
            target[cursor++] = amplitude
            target[cursor++] = r
            target[cursor++] = g
            target[cursor++] = b
        }
    }

    private fun drawStarDots(
        starField: FableSolStarField,
        width: Int,
        height: Int,
        rotationRad: Float,
        density: Double
    ) {
        val extent = (STAR_DOT_SIGMA_DP * STAR_DOT_EXTENT_SIGMAS * density).toFloat()
        val cosine = cos(-rotationRad.toDouble()).toFloat()
        val sine = sin(-rotationRad.toDouble()).toFloat()
        val halfViewX = width * 0.5f
        val halfViewY = height * 0.5f
        for (index in 0 until starField.starCount) {
            val base = index * FableSolStarField.FLOATS_PER_STAR
            val localX = starField.starData[base]
            val localY = starField.starData[base + 1]
            // water.vert 同构：R(−θ)·p + viewport/2（uRasterScale 恒 1）。
            val screenX = cosine * localX - sine * localY + halfViewX
            val screenY = sine * localX + cosine * localY + halfViewY
            fillQuad(
                starVertexData, index, screenX, screenY, extent,
                starField.starData[base + 2],
                starField.starData[base + 3],
                starField.starData[base + 4],
                starField.starData[base + 5]
            )
        }
        val floatCount = starField.starCount * 6 * FLOATS_PER_QUAD_VERTEX
        starVertexUpload.clear()
        starVertexUpload.put(starVertexData, 0, floatCount)
        starVertexUpload.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starBufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0, floatCount * 4, starVertexUpload
        )
        val star = starProgram!!
        star.use()
        GLES30.glUniform2f(star.uniform("uViewportPx"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(star.uniform("uDotSigmaPx"), (STAR_DOT_SIGMA_DP * density).toFloat())
        GLES30.glBindVertexArray(starVaoId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, starField.starCount * 6)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawNeedleSprites(
        starField: FableSolStarField,
        width: Int,
        height: Int,
        rotationRad: Float,
        density: Double,
        strength: Float,
        needleLengthDp: Float,
        needleCount: Int,
        needleVariance: Float,
        kneePx: Float,
        perpSigmaPx: Float,
        timeSeconds: Double
    ) {
        val cosine = cos(-rotationRad.toDouble()).toFloat()
        val sine = sin(-rotationRad.toDouble()).toFloat()
        val halfViewX = width * 0.5f
        val halfViewY = height * 0.5f
        val count = starField.starCount
        val lengthsPx = FloatArray(count)
        for (index in 0 until count) {
            val base = index * FableSolStarField.FLOATS_PER_STAR
            val seed = starField.starData[base + 6].toDouble()
            val lengthScale = 0.75 + 0.5 * hash01(seed, 5.0)
            lengthsPx[index] =
                (max(needleLengthDp * lengthScale, 1.0) * density).toFloat()
            val localX = starField.starData[base]
            val localY = starField.starData[base + 1]
            val screenX = cosine * localX - sine * localY + halfViewX
            val screenY = sine * localX + cosine * localY + halfViewY
            fillQuad(
                needleVertexData, index, screenX, screenY,
                lengthsPx[index] * 1.35f,
                starField.starData[base + 2],
                starField.starData[base + 3],
                starField.starData[base + 4],
                starField.starData[base + 5]
            )
        }
        val floatCount = count * 6 * FLOATS_PER_QUAD_VERTEX
        needleVertexUpload.clear()
        needleVertexUpload.put(needleVertexData, 0, floatCount)
        needleVertexUpload.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, needleBufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0, floatCount * 4, needleVertexUpload
        )
        val needle = needleProgram!!
        needle.use()
        GLES30.glUniform2f(needle.uniform("uViewportPx"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(needle.uniform("uStrength"), strength * NEEDLE_WEIGHT)
        GLES30.glUniform1f(needle.uniform("uNeedleKneePx"), kneePx)
        GLES30.glUniform1f(needle.uniform("uNeedlePerpSigmaPx"), perpSigmaPx)
        GLES30.glUniform1i(
            needle.uniform("uNeedleCount"),
            needleCount.coerceIn(1, MAX_NEEDLE_LINES)
        )
        GLES30.glBindVertexArray(needleVaoId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        for (index in 0 until count) {
            val base = index * FableSolStarField.FLOATS_PER_STAR
            buildNeedleUniform(
                timeSeconds, needleVariance.toDouble(),
                starField.starData[base + 6].toDouble()
            )
            GLES30.glUniform1f(needle.uniform("uNeedleLengthPx"), lengthsPx[index])
            GLES30.glUniform4fv(
                needle.uniform("uNeedles[0]"), MAX_NEEDLE_LINES, needleUniform, 0
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, index * 6, 6)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun computeBlurWeights(sigmaPx: Float, spacingPx: Float) {
        var total = 0.0
        for (j in 0..BLUR_TAP_RADIUS) {
            val offset = j * spacingPx.toDouble()
            val weight = exp(-0.5 * (offset / max(sigmaPx.toDouble(), 1e-3)).let { it * it })
            blurWeights[j] = weight.toFloat()
            total += if (j == 0) weight else 2.0 * weight
        }
        val scale = (1.0 / max(total, 1e-6)).toFloat()
        for (j in 0..BLUR_TAP_RADIUS) blurWeights[j] *= scale
    }

    private fun ensurePrograms() {
        if (starProgram != null) return
        starProgram = FableSolGlProgram(
            assets, "fablesol/glsl/glare_star.vert", "fablesol/glsl/glare_star.frag"
        )
        blurProgram = FableSolGlProgram(
            assets, "fablesol/glsl/fullscreen.vert", "fablesol/glsl/glare_blur.frag"
        )
        combineProgram = FableSolGlProgram(
            assets, "fablesol/glsl/fullscreen.vert", "fablesol/glsl/glare_halo.frag"
        )
        needleProgram = FableSolGlProgram(
            assets, "fablesol/glsl/glare_star.vert", "fablesol/glsl/glare_needle.frag"
        )
        resolveProgram = FableSolGlProgram(
            assets, "fablesol/glsl/fullscreen.vert", "fablesol/glsl/glare_resolve.frag"
        )
        ensureQuadBuffers()
    }

    private fun ensureQuadBuffers() {
        if (starBufferId != 0) return
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        starBufferId = buffers[0]
        needleBufferId = buffers[1]
        // 首帧一次定容，逐帧 SubData——逐帧 glBufferData 重分配是部分
        // 驱动的偶发卡顿源（D214）。
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starBufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, starVertexData.size * 4, null,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, needleBufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, needleVertexData.size * 4, null,
            GLES30.GL_DYNAMIC_DRAW
        )
        val arrays = IntArray(2)
        GLES30.glGenVertexArrays(2, arrays, 0)
        starVaoId = arrays[0]
        needleVaoId = arrays[1]
        configureQuadVao(starVaoId, starBufferId)
        configureQuadVao(needleVaoId, needleBufferId)
    }

    private fun configureQuadVao(vaoId: Int, bufferId: Int) {
        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        val stride = FLOATS_PER_QUAD_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, stride, 20)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun ensureTargets(width: Int, height: Int) {
        if (targetWidth == width && targetHeight == height && outputTextureId != 0) return
        releaseTargets()
        halfWidth = max(width / 2, 1)
        halfHeight = max(height / 2, 1)
        // 半分辨率 fp16（振幅可超 1）；不支持 FP16 渲染的设备回退 RGBA8，
        // 超 1 振幅被钳、针芒略短，但整条链仍工作。
        if (!createTargets(width, height, halfFloat = true)) {
            releaseTargets()
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* 清误码 */ }
            if (!createTargets(width, height, halfFloat = false)) {
                releaseTargets()
                error("glare targets are incomplete")
            }
        }
        targetWidth = width
        targetHeight = height
    }

    private fun createTargets(width: Int, height: Int, halfFloat: Boolean): Boolean {
        halfFloatTargets = halfFloat
        val internalFormat = if (halfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        val componentType = if (halfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        val textures = IntArray(5)
        GLES30.glGenTextures(5, textures, 0)
        excessTextureId = textures[0]
        pingTextureId = textures[1]
        auraTextureId = textures[2]
        wideTextureId = textures[3]
        outputTextureId = textures[4]
        val framebuffers = IntArray(5)
        GLES30.glGenFramebuffers(5, framebuffers, 0)
        excessFramebufferId = framebuffers[0]
        pingFramebufferId = framebuffers[1]
        auraFramebufferId = framebuffers[2]
        wideFramebufferId = framebuffers[3]
        outputFramebufferId = framebuffers[4]
        if (textures.any { it == 0 } || framebuffers.any { it == 0 }) return false
        if (!setupTarget(excessTextureId, excessFramebufferId, halfWidth, halfHeight,
                internalFormat, componentType, GLES30.GL_LINEAR)) return false
        if (!setupTarget(pingTextureId, pingFramebufferId, halfWidth, halfHeight,
                internalFormat, componentType, GLES30.GL_LINEAR)) return false
        if (!setupTarget(auraTextureId, auraFramebufferId, halfWidth, halfHeight,
                internalFormat, componentType, GLES30.GL_LINEAR)) return false
        if (!setupTarget(wideTextureId, wideFramebufferId, halfWidth, halfHeight,
                internalFormat, componentType, GLES30.GL_LINEAR)) return false
        // 输出必须与场景同尺寸：present 直接采样它。
        return setupTarget(outputTextureId, outputFramebufferId, width, height,
            internalFormat, componentType, GLES30.GL_NEAREST)
    }

    private fun setupTarget(
        textureId: Int,
        framebufferId: Int,
        width: Int,
        height: Int,
        internalFormat: Int,
        componentType: Int,
        filter: Int
    ): Boolean {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            GLES30.GL_RGBA, componentType, null
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
        return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
    }

    private fun releaseTargets() {
        val framebuffers = intArrayOf(
            excessFramebufferId, pingFramebufferId, auraFramebufferId,
            wideFramebufferId, outputFramebufferId
        )
        if (framebuffers.any { it != 0 }) GLES30.glDeleteFramebuffers(5, framebuffers, 0)
        val textures = intArrayOf(
            excessTextureId, pingTextureId, auraTextureId, wideTextureId,
            outputTextureId
        )
        if (textures.any { it != 0 }) GLES30.glDeleteTextures(5, textures, 0)
        excessFramebufferId = 0
        pingFramebufferId = 0
        auraFramebufferId = 0
        wideFramebufferId = 0
        outputFramebufferId = 0
        excessTextureId = 0
        pingTextureId = 0
        auraTextureId = 0
        wideTextureId = 0
        outputTextureId = 0
        targetWidth = 0
        targetHeight = 0
    }

    fun release() {
        releaseTargets()
        starProgram?.release()
        blurProgram?.release()
        combineProgram?.release()
        needleProgram?.release()
        resolveProgram?.release()
        starProgram = null
        blurProgram = null
        combineProgram = null
        needleProgram = null
        resolveProgram = null
        if (starBufferId != 0 || needleBufferId != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(starBufferId, needleBufferId), 0)
            starBufferId = 0
            needleBufferId = 0
        }
        if (starVaoId != 0 || needleVaoId != 0) {
            GLES30.glDeleteVertexArrays(2, intArrayOf(starVaoId, needleVaoId), 0)
            starVaoId = 0
            needleVaoId = 0
        }
    }

    /** 表面初始化时预编译（消除首星帧的 program 编译卡顿，D214）。 */
    fun prewarm() {
        if (failed) return
        try {
            ensurePrograms()
        } catch (error: RuntimeException) {
            failed = true
            release()
        }
    }

    /** EGL 上下文重建后由 renderer 调用：GL 名字已随旧上下文失效。 */
    fun invalidateGlObjects() {
        starProgram = null
        blurProgram = null
        combineProgram = null
        needleProgram = null
        resolveProgram = null
        starBufferId = 0
        needleBufferId = 0
        starVaoId = 0
        needleVaoId = 0
        excessFramebufferId = 0
        pingFramebufferId = 0
        auraFramebufferId = 0
        wideFramebufferId = 0
        outputFramebufferId = 0
        excessTextureId = 0
        pingTextureId = 0
        auraTextureId = 0
        wideTextureId = 0
        outputTextureId = 0
        targetWidth = 0
        targetHeight = 0
        failed = false
    }
}
