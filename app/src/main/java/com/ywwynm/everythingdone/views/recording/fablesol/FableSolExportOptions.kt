package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.util.Range
import kotlin.math.roundToInt

/**
 * 导出请求：用户**意图**的完整快照，全部对用户开放、release 可见（fablesol-video-export D10）。
 *
 * 本类只表达"想要什么"。"实际落到什么"归 [FableSolExportResolvedCandidate]，两者只在候选
 * 解析这一处交汇。此前二者混在一起——`hdrEnabled` 既是选择又被当成结论——于是文件名、
 * 进度、完成态和诊断各自推一遍落点，四处说法可以不一致。
 *
 * **CQ 档位区间从设备读**（各厂商不同，写死会让 `configure()` 直接抛）；码率滑杆给的是一个
 * 通用范围，真正下发前由 [FableSolExportTier.clampBitrate] 夹到该编码器的合法区间。
 */
internal data class FableSolExportOptions(
    /** 用户选择的严格输出帧率；编码器不支持时本次导出失败（D179）。 */
    val frameRate: Int,
    /** 单一互斥的导出色彩模式（D62）：两种 SDR、HDR 自动或具体 HDR 格式。 */
    val colorMode: FableSolExportColorMode,
    /** 保留高光 SDR 的映射方式；其余模式下不参与求值（D65）。 */
    val sdrMapping: FableSolExportSdrMapping,
    /** 明确选择 SDR 时的位深意图；HDR 下不读取（D160）。 */
    val sdrBitDepth: FableSolExportSdrBitDepth,
    /** HLG 系格式的信号范围意图；只在显式 HLG / 杜比视界 8.4 下读取（D137）。 */
    val hlgSignalRange: FableSolExportHlgSignalRange,
    /** 用户可见的码控模式（D145）；CBR 只是编码器不支持 VBR 时的内部后备。 */
    val rateControl: FableSolExportRateControl,
    /**
     * CQ 自定义原值，按**实际编码器路径**分别保存（D146）。
     *
     * 键是 [fableSolExportQualitySignature]。Android 的 `KEY_QUALITY` 是各厂商自行映射的一段
     * 区间，只保证"越大越好"：同一个数字在两个编码器上不表示同一件事，因此不跨编码器、
     * MIME/Profile 或实质不同的输入路径套用。
     */
    val qualityBySignature: Map<String, Int>,
    /**
     * 旧版全局 CQ 自定义值，尚未归属到任何候选签名。
     *
     * 迁移时不做同步能力探测（冷启动时能力矩阵可能尚未就绪），改为**延迟绑定**：第一次真正
     * 解析出候选时归属给那一个签名，之后不再扩散。
     */
    val pendingLegacyQuality: Int?,
    /** 目标码率（Mbps，按 120fps 计）。 */
    /** 用户拖过滑杆之后的绝对目标码率（Mbps）；null 表示自动态（D147）。 */
    val bitrateMbps: Float?,
    val keyframeIntervalSeconds: Float,
    /**
     * 指定视频编码器族。自动档按硬件 HEVC、硬件 AV1、硬件 AVC、软件同序生成公开规格建议；
     * 首个规格失败后，编码器族或软硬件类型的变化必须经用户确认（D53、D161、D179）。
     */
    val codec: CodecPreference = CodecPreference.AUTO,
    /**
     * PQ 系导出里，漫反射白（水体与卡片）钉在多少尼特。
     *
     * PQ 是**绝对**亮度曲线：这个数写多少，正确的显示设备就渲染多亮。默认取 ITU-R BT.2408
     * 的名义 HDR Reference White `203 cd/m²`，与导出设备无关（D82/D83）——本机屏幕的声明
     * 峰值只作诊断或用户主动采用的一次性参考值，不隐式改写母版意图。
     *
     * 高光峰值 = 本值 × HDR 强度。HLG 系（含杜比视界 8.4）是相对亮度，用不到这个数。
     */
    val pqWhiteNits: Float = DEFAULT_PQ_WHITE_NITS,
    /** `标准（203 尼特）`还是`自定义（N 尼特）`（D84）。 */
    val pqWhiteMode: FableSolExportPqWhiteMode = FableSolExportPqWhiteMode.DEFAULT,
    /** HDR10+ 的参考显示峰值（尼特，D94）；只改动态元数据的引导曲线，不改 PQ 基础像素。 */
    val referenceDisplayPeakNits: Float = DEFAULT_REFERENCE_PEAK_NITS,
    /**
     * 「高光起点」：画面亮度分布的第几个百分位开始算高光。只有 HDR10+ 用得到——只有它带
     * 色调映射曲线。以下原样保留，以上才压缩。
     */
    val highlightStartPercent: Int =
        FableSolExportHdr10PlusCurve.DEFAULT_HIGHLIGHT_START_PERCENT,
    /** B 帧独立开关，默认关闭（D148）。 */
    val bFramesEnabled: Boolean = false,
    /** 高复杂度编码，默认开启（D149）。 */
    val highComplexityEnabled: Boolean = true,
    /** VBR 的复杂帧质量保护，默认开启（D151）。 */
    val complexFrameGuardEnabled: Boolean = true,
    /**
     * 是否重放录音期间的手机倾斜（重力轨迹，D13）。关掉即按竖直渲染。
     *
     * 只有本应用录制的 WAV 带得动这份轨迹，所以这个开关实际只对实时录音产生的音频有效；
     * 导入的音频与本功能之前的历史录音本来就没有轨迹可放。
     */
    val tiltEnabled: Boolean = true
) {

    /**
     * 用户对视频编码器的偏好。持久化使用 [stableId]，不使用 ordinal。
     *
     * [AUTO] 与任意选择不同：它确定公开规格的建议顺序。具体 `codecName` 和输入通路仍可在
     * 同一公开规格内自动切换；编码器族或软硬件类型变化则按 D179 请求用户确认。
     */
    enum class CodecPreference(
        override val stableId: String,
        val family: FableSolExportCodecFamily?
    ) : FableSolExportStableChoice {

        AUTO("auto", null),
        HEVC("hevc", FableSolExportCodecFamily.HEVC),
        AV1("av1", FableSolExportCodecFamily.AV1),
        AVC("avc", FableSolExportCodecFamily.AVC);

        companion object {
            fun fromStableId(value: String?): CodecPreference =
                entries.firstOrNull { it.stableId == value } ?: AUTO

            /** 旧版按 ordinal 持久化，顺序即 AUTO, HEVC, AV1, AVC。 */
            fun fromLegacyOrdinal(value: Int): CodecPreference = entries.getOrElse(value) { AUTO }

            fun of(family: FableSolExportCodecFamily): CodecPreference =
                entries.first { it.family == family }
        }
    }

    /** 用户希望的输出是不是 HDR；实际落点仍以候选解析为准。 */
    val requestsHdr: Boolean get() = colorMode.requestsHdr

    /**
     * 目标码率模式下优先 VBR：离线文件导出没有固定瞬时带宽的要求，CBR 只在实际编码器不支持
     * VBR 时作为内部后备（D145）。
     */
    val prefersVariableBitrate: Boolean
        get() = rateControl == FableSolExportRateControl.TARGET_BITRATE

    /** 候选排序里"编码器是否满足用户所选模式"的平局判据。 */
    val prefersConstantQuality: Boolean
        get() = rateControl == FableSolExportRateControl.CONSTANT_QUALITY

    /**
     * 本次产物实际适用的 HLG 信号范围意图。
     *
     * "自动"格式最终落到 HLG 或杜比视界 8.4 时固定采用自动增强，不读取当前不可见的
     * "名义范围"历史值（D137）——隐藏设置不得让自动档静默放弃可用的 HLG 色容积。
     */
    fun effectiveHlgSignalRange(
        format: FableSolExportHdrFormat?
    ): FableSolExportHlgSignalRange? {
        if (format?.usesHlgBaseLayer != true) return null
        return if (colorMode.explicitFormat == format) {
            hlgSignalRange
        } else {
            FableSolExportHlgSignalRange.AUTO_ENHANCED
        }
    }

    /** 解析出要写进 `KEY_QUALITY` 的档位；返回 null 表示该档位不走恒定质量。 */
    fun resolvedQuality(tier: FableSolExportTier): Int? {
        if (!prefersConstantQuality) return null
        val range = tier.qualityRange ?: return null
        return resolveWithin(range, tier.qualitySignature)
    }

    /**
     * @param signature 候选签名；null 表示设置页尚未解析出具体候选，此时只看未绑定的旧值。
     */
    fun resolveWithin(range: Range<Int>, signature: String? = null): Int {
        val lower = range.lower
        val upper = range.upper
        if (upper <= lower) return lower
        val stored = signature?.let { qualityBySignature[it] } ?: pendingLegacyQuality
        if (stored == null || stored == UNSET_QUALITY) {
            // 画质优先：默认与"恢复默认"都取该编码器公开的最高质量值（D146）。此前的
            // `区间 80%` 没有任何跨编码器的质量含义，Android 也从未赋予它这样的语义。
            return upper
        }
        return stored.coerceIn(lower, upper)
    }

    /**
     * 本次实际使用的目标码率（bps）。
     *
     * @param tier 已解析的候选；它自带按 D147 推导并夹取过的码率。
     */
    fun bitrateBps(tier: FableSolExportTier): Int =
        tier.bitrateBps ?: tier.clampBitrate(
            FableSolExportBitrateModel.autoBitrateBps(
                widthPx = tier.encodedWidthPx,
                heightPx = tier.encodedHeightPx,
                frameRate = frameRate,
                family = tier.family,
                tenBit = !tier.eightBit,
                hdr = tier.transfer.isHdr
            )
        )

    companion object {

        const val FRAME_RATE_HIGH = 120
        const val FRAME_RATE_BASE = 60

        const val UNSET_QUALITY = Int.MIN_VALUE
        const val DEFAULT_BITRATE_MBPS = 24f
        const val DEFAULT_KEYFRAME_SECONDS = 2f
        const val MIN_BITRATE_MBPS = 2f
        const val MAX_BITRATE_MBPS = 60f

        /** ITU-R BT.2408 的名义 HDR Reference White；与导出设备无关（D82/D83）。 */
        const val DEFAULT_PQ_WHITE_NITS = 203f
        const val MIN_PQ_WHITE_NITS = 200f
        const val MAX_PQ_WHITE_NITS = 800f

        /** HDR10+ 参考显示峰值（D94）：标准 1000 尼特，滑杆范围 300～10000。 */
        const val DEFAULT_REFERENCE_PEAK_NITS = 1000f
        const val MIN_REFERENCE_PEAK_NITS = 300f
        const val MAX_REFERENCE_PEAK_NITS = 10000f

        const val MIN_KEYFRAME_SECONDS = 0.5f
        const val MAX_KEYFRAME_SECONDS = 10f

        fun read(context: Context): FableSolExportOptions = FableSolExportOptions(
            frameRate = FableSolTuning.exportFrameRate(context),
            colorMode = FableSolTuning.exportColorMode(context),
            sdrMapping = FableSolTuning.exportSdrMapping(context),
            sdrBitDepth = FableSolTuning.exportSdrBitDepth(context),
            hlgSignalRange = FableSolTuning.exportHlgSignalRange(context),
            rateControl = FableSolTuning.exportRateControl(context),
            qualityBySignature = FableSolTuning.exportQualityValues(context),
            pendingLegacyQuality = FableSolTuning.pendingLegacyQualityValue(context),
            bitrateMbps = FableSolTuning.exportBitrateMbps(context),
            keyframeIntervalSeconds = FableSolTuning.exportKeyframeSeconds(context),
            codec = FableSolTuning.exportCodec(context),
            pqWhiteNits = FableSolTuning.exportPqWhiteNits(context),
            pqWhiteMode = FableSolTuning.exportPqWhiteMode(context),
            referenceDisplayPeakNits = FableSolTuning.exportReferenceDisplayPeakNits(context),
            highlightStartPercent = FableSolTuning.exportHighlightStart(context),
            bFramesEnabled = FableSolTuning.exportBFramesEnabled(context),
            highComplexityEnabled = FableSolTuning.exportHighComplexityEnabled(context),
            complexFrameGuardEnabled = FableSolTuning.exportComplexFrameGuardEnabled(context),
            tiltEnabled = FableSolTuning.exportTiltEnabled(context)
        )

    }
}

/**
 * CQ 自定义原值的归属签名（D146）。
 *
 * 编码器实现名、输出格式（决定 MIME 与 Profile）、位深与输入路径任一不同，就是"实质不同的
 * 编码器路径"，不共用同一个质量原值。
 */
internal fun fableSolExportQualitySignature(
    codecName: String,
    format: FableSolExportHdrFormat?,
    tenBit: Boolean,
    inputPath: FableSolExportInputPath
): String = listOf(
    codecName,
    format?.stableLabel ?: FableSolExportHdrFormat.SDR_LABEL,
    if (tenBit) "10" else "8",
    inputPath.stableId
).joinToString("|")
