package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * 导出的最终呈现：把场景纹理放进画框、叠上时钟、套传递函数，写进编码器 input surface。
 *
 * 与屏上的 present 完全分离（fablesol-video-export D4）：独立 program、独立 shader，
 * 逐帧关键路径不为导出背任何分支。
 */
internal class FableSolExportPresenter(
    assets: AssetManager,
    private val plan: FableSolExportPlan,
    private val clock: FableSolExportClock,
    /** 0 = BT.709 SDR；1 = BT.2020 HLG。 */
    private val transfer: Int,
    /** 8-bit 编码档位必须重新启用抖动（D9、D162）。 */
    private val dither: Boolean,
    /**
     * PQ 分支里漫反射白钉在多少尼特。**只有 PQ 用得到**——HLG 是相对亮度，没有绝对锚点。
     * 它决定水体与卡片在正确显示设备上的实际亮度，也决定屏幕会不会被逼着在背景与高光
     * 之间做取舍（锚点太低就永远不需要取舍，画面自然是静的）。
     */
    private val whiteNits: Float = FableSolExportTransfer.SDR_WHITE_NITS.toFloat(),
    /** 本次 SDR 成片语义；null 表示这是 HDR 产物，不做色调映射（D62、D77～D78）。 */
    private val sdrRender: FableSolExportSdrRender? = null,
    /** 用户的 HDR 高光强度；决定固定的基础压缩量与动态峰值的上界（D63、D75）。 */
    private val hdrStrength: Float = FableSolHdrPolicy.STRENGTH_OFF,
    /** 帧间隔，动态映射的时间平滑要用（D74）。 */
    private val frameIntervalSeconds: Double = 1.0 / FableSolExportOptions.FRAME_RATE_HIGH,
    /** 8-bit 抖动的蓝噪声表；null 表示资源不可用，退回三角哈希（D162 第 5 条）。 */
    blueNoise: FableSolExportBlueNoise? = null,
    /**
     * 本次 HLG 输出变换的肩部与信号范围（D126～D134）；非 HLG 档位为 null。
     *
     * 它在正式渲染开始前就已定下，整段不变——同一个 FP16 输入在一次导出内必须得到同一个 HLG
     * 输出，不读取逐帧统计（D127）。
     */
    private val hlgPlan: FableSolExportHlgPlan? = null
) : ScenePresenter {

    private val program = FableSolGlProgram(
        assets,
        "fablesol/glsl/fullscreen.vert",
        "fablesol/glsl/export_present.frag"
    )
    private val vertexArrayId: Int
    private var clockTextureId = 0
    private var clockUploadedWidth = 0
    private var clockUploadedHeight = 0
    private val backdrop = colorToFloats(plan.backdropColor)
    private val rim = colorToFloats(plan.rimColor)

    /**
     * 阈值纹理：蓝噪声表，或资源不可用时一张 1×1 的中性占位。
     *
     * 占位不是装饰——`usampler2D` 绑到不完整纹理是未定义行为，即便那条分支不会执行
     * （批次 2 已经踩过一次）。[noiseEnabled] 为假时着色器走三角哈希。
     *
     * 启用判据必须是"64×64 表**本身**上传成功"（[blueNoiseTextureId] 非空）：`upload()`
     * 失败而占位上传成功时，旧的 `noiseTextureId != 0` 判据仍为 true，shader 会对 1×1
     * 纹理越界 `texelFetch`，而不是 D162 第 5 条要求的退回三角哈希抖动。
     */
    private val blueNoiseTextureId = blueNoise?.upload()?.takeIf { it != 0 }
    private val noiseTextureId = blueNoiseTextureId
        ?: FableSolExportBlueNoise.uploadNeutral()
    private val noiseEnabled = dither && blueNoiseTextureId != null

    /** HLG 的两张查表；非 HLG 档位上传 1×1 占位，同样是为了不让 sampler 悬空。 */
    private val hlgTextures = FableSolExportHlgTextures.upload(hlgPlan)

    /** 动态映射的逐帧峰值归约；其余模式为 null，一趟 GPU 都不多跑。 */
    private val scenePeak = if (sdrRender?.dynamic == true) {
        FableSolExportScenePeak(assets, hdrStrength)
    } else {
        null
    }
    private val peakTracker = FableSolExportSdrToneMap.PeakTracker()

    /**
     * 稳定映射的曲线全片只解一次（D67）；动态映射每帧重解，但只有超白那一段会变（D71）。
     */
    private var curve = FableSolExportSdrToneMap.curveFor(
        strength = hdrStrength.toDouble(),
        controlPeak = hdrStrength.toDouble()
    )

    /**
     * 动态统计通路的失败原因；非 null 时调用方必须丢弃本次尝试并从第 1 帧改用稳定映射
     * （D77），不得把降级结果仍标成动态映射。
     */
    val dynamicStatsFailure: String? get() = scenePeak?.failure

    /** 本帧的音频时间；驱动循环在 render 之前写入。 */
    @Volatile var elapsedMs: Long = 0L

    /**
     * 呈现的目标 framebuffer。0 = 默认帧缓冲（surface 输入那条路，直接画进编码器表面）。
     * HDR10+ 走字节缓冲输入时改画进离屏的 RGB10_A2，再由 [FableSolExportP010Bridge] 转成
     * P010——因为动态元数据在 surface 输入模式下根本没法提供。
     */
    @Volatile var targetFramebufferId: Int = 0

    init {
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArrayId = arrays[0]
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        clockTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTextureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    override fun present(
        sceneTextureId: Int,
        sceneWidthPx: Int,
        sceneHeightPx: Int,
        sceneLinear: Boolean,
        hdrHeadroom: Float
    ) {
        uploadClock()

        // 峰值必须在呈现之前测：曲线用的是**本帧**实测值，不是上一帧的（D74 第 3 条）。
        // 读的是同一张场景纹理，因此统计与最终画面不可能来自两份不同的内容。
        val peakPass = scenePeak
        if (peakPass != null && peakPass.failure == null) {
            val measured = peakPass.measure(sceneTextureId, sceneWidthPx, sceneHeightPx)
            if (peakPass.failure == null) {
                val clamped = measured.coerceIn(1.0, hdrStrength.toDouble().coerceAtLeast(1.0))
                curve = FableSolExportSdrToneMap.curveFor(
                    strength = hdrStrength.toDouble(),
                    controlPeak = peakTracker.next(clamped, frameIntervalSeconds)
                )
            }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebufferId)
        GLES30.glViewport(0, 0, plan.canvasWidthPx, plan.canvasHeightPx)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vertexArrayId)
        program.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
        GLES30.glUniform1i(program.uniform("uScene"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTextureId)
        GLES30.glUniform1i(program.uniform("uClock"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTextureId)
        GLES30.glUniform1i(program.uniform("uNoise"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hlgTextures.shoulderTextureId)
        GLES30.glUniform1i(program.uniform("uHlgShoulder"), 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hlgTextures.deviceTextureId)
        GLES30.glUniform1i(program.uniform("uHlgDevice"), 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        val cardOriginX = plan.cardOriginXPx.toFloat()
        val cardOriginY = plan.cardOriginYPx.toFloat()
        GLES30.glUniform2f(
            program.uniform("uCardOriginPx"), cardOriginX, cardOriginY
        )
        GLES30.glUniform2f(
            program.uniform("uCardSizePx"),
            plan.cardWidthPx.toFloat(),
            plan.cardHeightPx.toFloat()
        )
        GLES30.glUniform1f(program.uniform("uCornerRadiusPx"), plan.cornerRadiusPx)
        GLES30.glUniform3fv(program.uniform("uBackdropColor"), 1, backdrop, 0)
        GLES30.glUniform1f(program.uniform("uShadowOffsetPx"), plan.shadowOffsetPx)
        GLES30.glUniform1f(program.uniform("uShadowRadiusPx"), plan.shadowRadiusPx)
        GLES30.glUniform1f(program.uniform("uShadowAlpha"), plan.shadowAlpha)
        GLES30.glUniform3fv(program.uniform("uRimColor"), 1, rim, 0)
        GLES30.glUniform1f(program.uniform("uRimAlpha"), plan.rimAlpha)
        GLES30.glUniform1f(program.uniform("uRimWidthPx"), plan.rimWidthPx)

        // 时钟矩形：布局按屏幕坐标（y 向下）给出，这里换算成 GL 的 y 向上。
        val clockLeft = cardOriginX + plan.clockLeftPx
        val clockBottom = cardOriginY + plan.cardHeightPx -
            plan.clockTopPx - plan.clockHeightPx
        GLES30.glUniform4f(
            program.uniform("uClockRectPx"),
            clockLeft,
            clockBottom,
            plan.clockWidthPx.toFloat(),
            plan.clockHeightPx.toFloat()
        )
        GLES30.glUniform1f(program.uniform("uClockAlpha"), clock.alphaAt(elapsedMs))

        GLES30.glUniform1i(program.uniform("uSceneLinear"), if (sceneLinear) 1 else 0)
        GLES30.glUniform1f(program.uniform("uHdrHeadroom"), hdrHeadroom)
        GLES30.glUniform1i(program.uniform("uTransfer"), transfer)
        GLES30.glUniform1i(program.uniform("uDither"), if (dither) 1 else 0)
        GLES30.glUniform1i(program.uniform("uNoiseEnabled"), if (noiseEnabled) 1 else 0)
        GLES30.glUniform1f(program.uniform("uSdrWhiteNits"), whiteNits)
        applyHlgUniforms(program)

        // 恒等曲线不必进 shader 分支：强度 1.0× 时 uToneMap 直接为假（D64、D72）。
        val toneMapped = sdrRender?.toneMapped == true && !curve.identity
        GLES30.glUniform1i(program.uniform("uToneMap"), if (toneMapped) 1 else 0)
        GLES30.glUniform1f(program.uniform("uToneKnee"), curve.knee.toFloat())
        GLES30.glUniform1f(program.uniform("uToneWhite"), curve.white.toFloat())
        GLES30.glUniform1f(program.uniform("uTonePeak"), curve.peak.toFloat())
        GLES30.glUniform1f(program.uniform("uToneExponent"), curve.exponent.toFloat())
        GLES30.glUniform1f(program.uniform("uToneTarget"), curve.target.toFloat())

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * HLG 输出变换的全部参数（D126～D134、D164、D165）。
     *
     * 没有 plan 时也要把 uniform 写全：GLSL 的 uniform 默认值是 0，`uHlgKneeNorm = 0` 会让
     * 肩部把整条曲线当成"膝点以上"处理。非 HLG 档位虽然走不到那条分支，但驱动仍会在链接期
     * 保留它，写全的代价只是几次 glUniform。
     */
    private fun applyHlgUniforms(program: FableSolGlProgram) {
        val plan = hlgPlan
        GLES30.glUniform1f(
            program.uniform("uHlgDisplayWhite"),
            FableSolExportHlgTransform.REFERENCE_WHITE_DISPLAY.toFloat()
        )
        GLES30.glUniform1f(
            program.uniform("uHlgKneeNorm"),
            FableSolExportHlgTransform.KNEE_NORMALIZED.toFloat()
        )
        GLES30.glUniform1f(
            program.uniform("uHlgHeadroomNorm"),
            (plan?.shoulder?.normalizedHeadroom ?: 1.0).toFloat()
        )
        // 标准上限恒为 109%：名义范围由 uHlgDeviceCeiling = 1.0 表达，不必再准备第二套常量。
        GLES30.glUniform1f(
            program.uniform("uHlgSignalMax"),
            FableSolExportHlgTransform.SIGNAL_MAX.toFloat()
        )
        GLES30.glUniform2f(
            program.uniform("uHlgShoulderDomain"),
            (plan?.shoulder?.lowCapacity ?: 0.0).toFloat(),
            (plan?.shoulder?.highCapacity ?: 1.0).toFloat()
        )
        GLES30.glUniform1i(program.uniform("uHlgShoulderSize"), hlgTextures.shoulderSize)
        GLES30.glUniform1i(
            program.uniform("uHlgDeviceEnabled"), if (hlgTextures.deviceEnabled) 1 else 0
        )
        GLES30.glUniform1f(
            program.uniform("uHlgDeviceCeiling"),
            FableSolExportHlgTransform.SIGNAL_NOMINAL.toFloat()
        )
        GLES30.glUniform1i(program.uniform("uHlgDeviceGrid"), hlgTextures.deviceGridSize)
    }

    private fun uploadClock() {
        val bitmap: Bitmap = clock.bitmapAt(elapsedMs)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTextureId)
        if (clockUploadedWidth != bitmap.width || clockUploadedHeight != bitmap.height) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            clockUploadedWidth = bitmap.width
            clockUploadedHeight = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        program.release()
        scenePeak?.release()
        hlgTextures.release()
        if (noiseTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(noiseTextureId), 0)
        }
        if (clockTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(clockTextureId), 0)
            clockTextureId = 0
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        }
    }

    private fun colorToFloats(color: Int): FloatArray = floatArrayOf(
        ((color shr 16) and 0xff) / 255f,
        ((color shr 8) and 0xff) / 255f,
        (color and 0xff) / 255f
    )

    companion object {
        const val TRANSFER_BT709 = 0
        const val TRANSFER_HLG = 1
        const val TRANSFER_PQ = 2

        /**
         * 全片亮度预分析（D86）：线性 BT.2020，不套 OETF。
         *
         * 它不是一种输出格式，而是同一条呈现流水线的另一个出口——统计因此与最终可见合成
         * 逐像素一致，不会出现"统计读的是一张画、编码写的是另一张"。
         */
        const val TRANSFER_LINEAR_BT2020 = 3

        /** 把导出信号类型换算成 shader 里的分支号。 */
        fun shaderTransfer(transfer: FableSolExportTransfer): Int = when (transfer) {
            FableSolExportTransfer.SDR -> TRANSFER_BT709
            FableSolExportTransfer.HLG -> TRANSFER_HLG
            FableSolExportTransfer.PQ -> TRANSFER_PQ
        }
    }
}
