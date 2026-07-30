package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.Build
import com.ywwynm.everythingdone.R

/**
 * 一次导出**实际落到**的完整规格：只读，由候选解析一次性算出。
 *
 * 它取代此前"文件名猜一次、进度猜一次、完成 Dialog 猜一次、诊断再猜一次"的做法。四处各自
 * 推导时，任何一条轴的降级都可能只被其中一两处察觉——软件 AV1 的 60fps 曾被完成提示写成
 * "HDR10，60 fps"，看不出编码器已经退到软件实现（2026-07-27）。
 *
 * 请求侧的意图在 [FableSolExportRequest]（[FableSolExportColorMode] 等）与
 * [FableSolExportOptions]；本类只描述结论，不含任何"用户想要什么"。
 */
internal data class FableSolExportResolvedCandidate(
    /** 用户请求的色彩模式；与 [hdrFormat] 一起才说得清"申请了什么、落到了什么"。 */
    val colorMode: FableSolExportColorMode,
    /** 用户请求的映射方式；仅保留高光 SDR 有意义，其余模式为 null。 */
    val sdrMapping: FableSolExportSdrMapping?,
    /**
     * SDR 产物**实际采用**的成片语义；HDR 产物为 null。
     *
     * 与 [colorMode] / [sdrMapping] 那一对请求值分开：D77、D78 的两条运行时降级只改这里。
     * 文件名、完成 Dialog、通知和日志一律读本值（D81），不得沿用失败尝试的标签。
     */
    val sdrRender: FableSolExportSdrRender?,
    /** 实际输出的 HDR 格式；null 表示 SDR。 */
    val hdrFormat: FableSolExportHdrFormat?,
    val transfer: FableSolExportTransfer,
    val widthPx: Int,
    val heightPx: Int,
    val frameRate: Int,
    val tenBit: Boolean,
    val family: FableSolExportCodecFamily,
    /** 具体编码器实现名，例如 `c2.qti.hevc.encoder`。 */
    val codecName: String,
    val softwareOnly: Boolean,
    val profile: Int,
    val level: Int,
    /** HEVC/AV1 的 High Tier；仅在解析后的码率确实需要时为 true（D152）。 */
    val highTier: Boolean,
    val rateControl: FableSolExportRateControlForm,
    /** CQ 形态下发的原始质量值；VBR/CBR 为 null。 */
    val qualityValue: Int?,
    /** VBR/CBR 的目标码率，以及 CQ 兼容形态的码率提示；纯 CQ 为 null。 */
    val bitrateBps: Int?,
    val inputPath: FableSolExportInputPath,
    /** 仅 HLG 系格式有意义：用户**申请**的信号范围意图。 */
    val hlgSignalRange: FableSolExportHlgSignalRange?,
    /**
     * 仅 HLG 系格式有意义：本次产物**实际**落到的信号范围（D135、D136）。
     *
     * 与 [hlgSignalRange] 分开是必须的：`自动增强` 是意图，回环验证未通过时产物仍是名义范围，
     * 而完成 Dialog、通知和诊断只允许报告实际产物。null 表示这条轴尚未定（设置页的预测），
     * 或本候选不是 HLG 系。
     */
    val hlgRange: FableSolExportHlgRange? = null,
    val keyframeIntervalSeconds: Float,
    val dither: FableSolExportDither,
    /** 是否向编码器申请了 B 帧；申请成功不等于码流一定含 B 帧（D148）。 */
    val bFramesRequested: Boolean,
    /** 是否申请了编码器公开的最高复杂度（D149）。 */
    val highComplexityRequested: Boolean,
    /** 是否申请了 VBR 复杂帧质量保护（D151）。 */
    val qpGuardRequested: Boolean,
    /** 仅 PQ 系有意义：本次母版的漫反射白（尼特）。 */
    val pqWhiteNits: Double,
    /** 仅 PQ 系有意义：峰值 = 漫反射白 × HDR 强度。 */
    val peakNits: Double,
    /** 仅 HDR10+ 有意义：高光起点百分位。 */
    val highlightStartPercent: Int
) {

    val isHdr: Boolean get() = hdrFormat != null

    /**
     * 进文件名的短标签：SDR 三种语义各自可分（D81），HDR 用格式自己的稳定标签。
     *
     * 标签跟着**实际**产物走：发生 D77/D78 的运行时降级时，文件名同样降到 `SDR-TM` 或
     * `SDR`，不保留失败尝试的 `SDR-DTM`。
     */
    val fileTag: String
        get() = hdrFormat?.fileTag
            ?: sdrRender?.fileTag
            ?: FableSolExportHdrFormat.SDR_LABEL

    /**
     * 当前 locale 的完整模式名，例如 “HDR10+”“杜比视界 8.4”“SDR（原生渲染）”
     * “SDR（保留高光层次）· 稳定映射”。
     *
     * SDR 不能只写 “SDR”：三种成片语义在标准视频元数据里都标成 BT.709 SDR，文件离开应用后
     * 无从分辨，完成态与通知必须写全（D81）。
     */
    fun formatLabel(context: Context): String =
        hdrFormat?.displayName(context)
            ?: context.getString(
                when (sdrRender) {
                    FableSolExportSdrRender.TONE_MAPPED_STABLE ->
                        R.string.fablesol_export_color_mode_sdr_tone_mapped_stable
                    FableSolExportSdrRender.TONE_MAPPED_DYNAMIC ->
                        R.string.fablesol_export_color_mode_sdr_tone_mapped_dynamic
                    else -> R.string.fablesol_export_color_mode_sdr_native
                }
            )

    /** 面向用户的编码器写法：族名加位深，例如 “HEVC 10-bit”。 */
    val codecLabel: String
        get() = family.stableLabel + if (tenBit) " 10-bit" else " 8-bit"

    /**
     * CQ 自定义原值的归属签名（D146）。
     *
     * 质量值不跨编码器、MIME/Profile 或实质不同的输入路径套用：厂商各自映射自己的区间，
     * 同一个数字在两个编码器上不表示同一件事。
     */
    val qualitySignature: String
        get() = fableSolExportQualitySignature(codecName, hdrFormat, tenBit, inputPath)

    companion object {

        /**
         * 由已经通过筛选的编码档位与本次请求解析出实际落点。
         *
         * 编码工具（B 帧、复杂度、QP 上限）与 Level/Tier 的最低充分值属于批次 7；本批先如实
         * 记录"申请了什么"，形态解析仍沿用现有 [FableSolExportEncoder] 的行为。
         */
        fun of(
            options: FableSolExportOptions,
            tier: FableSolExportTier,
            frameRate: Int,
            widthPx: Int,
            heightPx: Int,
            pqWhiteNits: Double,
            peakNits: Double,
            /** 实际采用的 SDR 成片语义；null 表示按请求推导（设置页与测试用）。 */
            sdrRender: FableSolExportSdrRender? = FableSolExportSdrRender.of(
                colorMode = options.colorMode,
                mapping = options.sdrMapping,
                hdrResult = tier.hdrFormat != null
            ),
            /**
             * 本次 HLG 系产物实际落到的信号范围；null 表示尚未验证（设置页只能预测意图，
             * 结论要到导出准备阶段跑完回环才有，见 D138）。
             */
            hlgRange: FableSolExportHlgRange? = null,
            /**
             * 本次实际下发的码控形态；null 表示按当前档位能力解析。
             *
             * CQ 有纯 CQ 与 CQ+码率提示两种同模式形态（D167），走哪一种由短探测的阶梯决定，
             * 调用方拿到结论后显式传进来——诊断要分得出，用户可见语义则完全相同。
             */
            rateControlForm: FableSolExportRateControlForm? = null,
            /** 本次是否真的申请了 B 帧；编码器初始化拒绝后以 0 B 帧重试时为假（D148）。 */
            applyBFrames: Boolean = true,
            /** 本次是否真的申请了最高复杂度；探测阶梯确认被拒时为假（D149）。 */
            applyHighComplexity: Boolean = true
        ): FableSolExportResolvedCandidate {
            val form = rateControlForm ?: FableSolExportRateControlForm.resolve(options, tier)
            return FableSolExportResolvedCandidate(
                colorMode = options.colorMode,
                sdrMapping = options.sdrMapping.takeIf {
                    options.colorMode == FableSolExportColorMode.SDR_TONE_MAPPED
                },
                sdrRender = sdrRender,
                hdrFormat = tier.hdrFormat,
                transfer = tier.transfer,
                widthPx = widthPx,
                heightPx = heightPx,
                frameRate = frameRate,
                tenBit = !tier.eightBit,
                family = tier.family,
                codecName = tier.codecName,
                softwareOnly = tier.softwareOnly,
                profile = tier.profile,
                level = tier.level,
                highTier = tier.highTier,
                rateControl = form,
                qualityValue = if (form.isConstantQuality) {
                    tier.qualityRange?.let { options.resolveWithin(it, tier.qualitySignature) }
                } else {
                    null
                },
                // 纯 CQ 不带码率；CQ 的兼容形态带的是**提示**，与 VBR 的目标码率来自同一个
                // 模型，但语义不同，完成信息据 [rateControl] 区分措辞（D167）。
                bitrateBps = if (form.carriesBitrate) options.bitrateBps(tier) else null,
                inputPath = tier.inputPath,
                hlgSignalRange = options.effectiveHlgSignalRange(tier.hdrFormat),
                hlgRange = hlgRange,
                keyframeIntervalSeconds = options.keyframeIntervalSeconds,
                dither = when {
                    // 8-bit Surface：在 BT.709 编码 RGB 上、紧邻写出 Surface 之前做蓝噪声
                    // 阈值舍入（D162）。资源不可用时着色器内部退回三角哈希并继续同一格式，
                    // 那是同格式内部后备，不改变本候选的任何一条轴。
                    tier.eightBit -> FableSolExportDither.BLUE_NOISE
                    // 应用自有 P010：Y′、Cb、Cr 在各自的码值域做蓝噪声阈值舍入（D157）。
                    tier.usesAppP010 -> FableSolExportDither.BLUE_NOISE
                    // 10-bit Surface：RGB→Y′CbCr 与最终量化都在厂商那一侧，应用不介入。
                    else -> FableSolExportDither.NONE
                },
                // 三项都是**本次真的向编码器申请了没有**，不是用户开关的原样回声：开关打开
                // 但当前档位不适用时（AV1 没有 B 帧、编码器不公开复杂度区间、系统低于 API 31
                // 或不声明 FEATURE_QpBounds），我们根本不会写那个键，完成信息就该如实说没写
                // （D148、D149、D151）。申请了也不等于厂商一定启用了对应的内部工具。
                bFramesRequested = options.bFramesEnabled &&
                    tier.supportsBFrames &&
                    Build.VERSION.SDK_INT >= 29 &&
                    applyBFrames,
                highComplexityRequested = options.highComplexityEnabled &&
                    tier.complexityRange != null &&
                    applyHighComplexity,
                qpGuardRequested = options.complexFrameGuardEnabled &&
                    form == FableSolExportRateControlForm.VARIABLE_BITRATE &&
                    tier.supportsQpBounds,
                pqWhiteNits = if (tier.transfer == FableSolExportTransfer.PQ) pqWhiteNits else 0.0,
                peakNits = if (tier.transfer == FableSolExportTransfer.PQ) peakNits else 0.0,
                highlightStartPercent =
                    if (tier.hdrFormat == FableSolExportHdrFormat.HDR10_PLUS) {
                        options.highlightStartPercent
                    } else {
                        0
                    }
            )
        }
    }
}
