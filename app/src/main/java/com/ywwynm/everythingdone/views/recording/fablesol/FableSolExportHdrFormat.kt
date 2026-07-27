package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlin.math.max

/** 编码阶梯上的一个候选：MIME 加 profile。SDR 与各 HDR 格式共用这一结构。 */
internal class FableSolExportCodecEntry(
    val mime: String,
    val profile: Int,
    val eightBit: Boolean,
    val label: String
)

/**
 * 用户可选的 HDR 输出格式。
 *
 * 它与 [FableSolExportTransfer] 不是一回事：传递函数决定**我们怎么画**（EGL 表面色彩空间
 * 与导出 shader），格式还额外决定**用哪个编码器、写什么元数据**。所以"PQ 和 HDR10 并列"
 * 是重复的，两者是同一件事的两个说法：HDR10 = PQ 曲线 + BT.2020 + 静态母版元数据。
 *
 * 五种格式沿两个轴排开——**基层曲线**决定高光余量，**元数据**决定播放端能不能按场景适配：
 *
 * | 格式 | 基层 | 余量 | 元数据 |
 * |---|---|---|---|
 * | 杜比视界 8.1 | PQ | 10000 尼特 | 杜比动态（RPU） |
 * | HDR10+ | PQ | 10000 尼特 | ST 2094-40 动态 |
 * | HDR10 | PQ | 10000 尼特 | 静态，且我们写的是精确值 |
 * | 杜比视界 8.4 | HLG | 约 3.77 倍 | 杜比动态（RPU） |
 * | HLG | HLG | 约 3.77 倍 | 无 |
 *
 * 两个必须记住的事实：
 *
 * - **杜比视界不需要应用自己产 RPU**。按 Dolby 官方第三方样例
 *   （`DolbyLaboratories/dolby-vision-editor`）的做法，把 MIME 设成 `video/dolby-vision`、
 *   profile 设成 `DolbyVisionProfileDvheSt`、配一个按像素率算出的 level，编码器自己生成
 *   元数据层。8.1 与 8.4 的区别**只在传递函数**：PQ 是 8.1，HLG 是 8.4。
 * - **HDR10+ 必须走字节缓冲输入**：`MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO` 在 surface
 *   输入模式下被系统明确禁止，而那是提供 ST 2094-40 动态元数据的唯一接口。改走字节缓冲之后
 *   RGB→YUV 就得我们自己做（[FableSolExportP010Bridge]），统计量也由我们逐帧实测——
 *   三星 S23 Ultra 上已实证这条路能把 SEI 真正写进码流。
 */
@SuppressLint("InlinedApi")
internal enum class FableSolExportHdrFormat(
    val transfer: FableSolExportTransfer,
    /** 用户可见短名；同时进诊断行与完成态提示。 */
    val label: String
) {

    HDR10(FableSolExportTransfer.PQ, "HDR10"),
    HDR10_PLUS(FableSolExportTransfer.PQ, "HDR10+"),

    /**
     * 杜比视界 profile 8.1：**PQ 基层**，与 HDR10 兼容。
     *
     * 它比 8.4 高一档：基层是 PQ 就意味着高光余量到 10000 尼特，我们 9.6 倍强度那约 1949
     * 尼特完全放得下；而 8.4 的基层是 HLG，余量只有约 3.77 倍。Dolby 官方样例只演示了
     * 8.4，但 profile 常量是同一个（`DolbyVisionProfileDvheSt` = 8），**8.1 与 8.4 的区别
     * 就在传递函数**——PQ 是 8.1，HLG 是 8.4。设备认不认由真实编码探测判定。
     */
    /**
     * 杜比视界 profile 5：单层、PQ、IPT-PQ-c2 色彩空间，**不向下兼容**。
     *
     * 规格上它是杜比自家最"纯"的一档，但代价很实在：任何不支持杜比视界的播放端打开它都会
     * 是一片绿紫，而 8.1 在同样场合会正常按 HDR10 播。所以它排在最前只是遵循"能多高就多高"
     * 的排序，选它之前应该知道这个取舍。
     */
    DOLBY_VISION_5(FableSolExportTransfer.PQ, "Dolby Vision 5"),

    DOLBY_VISION_81(FableSolExportTransfer.PQ, "Dolby Vision 8.1"),

    HLG(FableSolExportTransfer.HLG, "HLG"),

    /** 杜比视界 profile 8.4：HLG 基层，余量与 HLG 相同。8.1 建不起来时的退路。 */
    DOLBY_VISION_84(FableSolExportTransfer.HLG, "Dolby Vision 8.4");

    /** PQ 系两种格式都要 CTA-861.3 静态母版元数据，播放端才知道按多高的峰值还原。 */
    val writesStaticMetadata: Boolean
        get() = transfer == FableSolExportTransfer.PQ

    /**
     * 输出格式必须原样回报申请的 profile，不接受任何"等价替换"。
     *
     * **HDR10+ 不在此列**，这一点我判错过：HEVC 层面根本没有"HDR10+ profile"，码流的
     * `general_profile_idc` 本来就是 Main10，`HEVCProfileMain10HDR10Plus`（8192）是 Android
     * 框架层的合成常量。拿它当门槛会把真正带了 HDR10+ SEI 的产物也判死。HDR10+ 的判据是
     * **码流里有没有那段 SEI**，见 [FableSolExportHdr10PlusMetadata.containsSei]。
     *
     * 杜比视界仍然要求原样回报：它换的是 MIME，profile 被改就是真的换了东西。
     */
    val requiresExactProfile: Boolean
        get() = isDolbyVision

    /** 进文件名的短标签：不能带空格，也不能与别的格式撞名。 */
    val fileTag: String
        get() = when (this) {
            HDR10 -> "HDR10"
            HDR10_PLUS -> "HDR10Plus"
            DOLBY_VISION_5 -> "DV5"
            DOLBY_VISION_81 -> "DV81"
            DOLBY_VISION_84 -> "DV84"
            HLG -> "HLG"
        }

    val isDolbyVision: Boolean
        get() = this == DOLBY_VISION_5 ||
            this == DOLBY_VISION_81 ||
            this == DOLBY_VISION_84

    /**
     * 只有 HDR10+ 走字节缓冲输入。
     *
     * 它的动态元数据必须逐帧通过 `PARAMETER_KEY_HDR10_PLUS_INFO` 提供，而该参数在 surface
     * 输入模式下被系统明确禁止。三星 S23 Ultra 上实测印证了这一点：surface 模式下编码器把
     * profile 降回 Main10，换成字节缓冲并附上元数据之后，码流里就真的带上了 HDR10+ SEI。
     *
     * 代价是 RGB→YUV 不再由编码器代劳，得由 [FableSolExportP010Bridge] 自己交出 P010。
     */
    val usesByteBufferInput: Boolean
        get() = this == HDR10_PLUS

    /**
     * 这一档是否需要 EGL 提供对应的 BT.2020 色彩空间扩展。
     *
     * 走字节缓冲的档**不需要**：画面画进我们自己的 RGB10_A2 离屏 framebuffer，PQ 编码由
     * 导出 shader 亲自完成，交给编码器的已经是 P010 字节。那张 1×1 的 pbuffer 只是用来
     * 持有 GL 上下文，压根没打色彩空间属性。拿窗口表面的扩展去卡它，会把"有 P010 HDR10+
     * 编码能力、却没有对应 EGL 窗口扩展"的设备错误地降到 SDR。
     */
    val requiresEglColorSpace: Boolean
        get() = !usesByteBufferInput

    /** level 必须按像素率现算，不能沿用编码器广告的最高档。 */
    val needsDolbyVisionLevel: Boolean
        get() = isDolbyVision

    /**
     * 编码器"收下配置却把 profile 降回去"时，除了陈述事实还能补的一句：这条路还有没有戏。
     *
     * 三星 S23 Ultra 上 HDR10+ 正是这样——`Encoder changed profile 8192 to 2`（8192 =
     * `HEVCProfileMain10HDR10Plus`，2 = `HEVCProfileMain10`）。这不是配置写错了：HDR10+ 的
     * 动态元数据是**随码流内嵌**的 SEI，只能由应用逐帧提供或由编码器自己分析生成；而前者在
     * surface 输入模式下被 Android 明确排除，后者这台机器不做。所以没有别的办法。
     */
    val downgradeHint: String?
        get() = when (this) {
            HDR10_PLUS ->
                "HDR10+ 走的是字节缓冲输入并由我们逐帧提供动态元数据；这台设备连这条路都" +
                    "不接受，就没有别的办法了"
            DOLBY_VISION_5 ->
                "这台设备的杜比视界编码器不做单层 profile 5；8.1 / 8.4 那两档还有机会"
            DOLBY_VISION_81 ->
                "这台设备的杜比视界编码器不接受 PQ 基层；8.4（HLG 基层）那一档还有机会"
            DOLBY_VISION_84 -> "这台设备的杜比视界编码器不接受我们这一档配置"
            else -> null
        }

    val codecEntries: List<FableSolExportCodecEntry>
        get() = when (this) {
            HDR10, HLG -> TEN_BIT_ENTRIES
            HDR10_PLUS -> HDR10_PLUS_ENTRIES
            DOLBY_VISION_5 -> DOLBY_VISION_5_ENTRIES
            DOLBY_VISION_81, DOLBY_VISION_84 -> DOLBY_VISION_ENTRIES
        }

    companion object {

        /**
         * 自动档的尝试顺序。
         *
         * 按**规格从高到低**排（用户 2026-07-27 定：能支持多高规格就支持多高规格）：
         *
         * 1. **杜比视界 5**——单层 PQ + IPT-PQ-c2，杜比自家最纯的一档（代价见该枚举项）；
         * 2. **杜比视界 8.1**——PQ 基层（余量到 10000 尼特）+ 杜比动态元数据，且与 HDR10 兼容；
         * 3. **HDR10+**——PQ + 动态元数据，但没有杜比那套显示端适配；
         * 4. **HDR10**——PQ + 静态元数据，我们的峰值本来就写的是精确值；
         * 5. **杜比视界 8.4**——有动态元数据，但基层退回 HLG，余量只剩约 3.77 倍；
         * 6. **HLG**——余量同上且没有动态元数据，只在前面全都建不起来时兜底。
         *
         * 每一档都要真编出一帧才会进这个列表，所以"排在前面"不等于"一定被选中"。
         */
        /** 非 HDR 产物在文件名与各处提示里的写法。 */
        const val SDR_LABEL = "SDR"

        val AUTO_ORDER = listOf(
            DOLBY_VISION_5, DOLBY_VISION_81, HDR10_PLUS, HDR10, DOLBY_VISION_84, HLG
        )

        private val TEN_BIT_ENTRIES = listOf(
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                eightBit = false,
                label = "HEVC Main10"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                eightBit = false,
                label = "AV1 Main10"
            )
        )

        private val HDR10_PLUS_ENTRIES = listOf(
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                eightBit = false,
                label = "HEVC Main10"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus,
                eightBit = false,
                label = "AV1 Main10"
            )
        )

        private val DOLBY_VISION_5_ENTRIES = listOf(
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
                MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr,
                eightBit = false,
                label = "HEVC"
            )
        )

        private val DOLBY_VISION_ENTRIES = listOf(
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
                MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt,
                eightBit = false,
                label = "HEVC"
            )
        )

        /**
         * 杜比视界要求的 level：它是一条**像素率**阶梯，不是分辨率标签，所以竖幅小画布在
         * 高帧率下照样可能落到名字里带 "Uhd" 的档。阶梯取自 Dolby 官方第三方样例的
         * `VideoEncoder.getDolbyVisionLevel`。
         *
         * @return 0 表示这个尺寸/帧率超出了阶梯，调用方应退回编码器广告的最高档。
         */
        fun dolbyVisionLevel(widthPx: Int, heightPx: Int, frameRate: Int): Int {
            val longest = max(widthPx, heightPx)
            val pixelsPerSecond = widthPx.toLong() * heightPx.toLong() * frameRate.toLong()
            return when {
                longest <= 1280 -> when {
                    pixelsPerSecond <= 22_118_400L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24
                    pixelsPerSecond <= 27_648_000L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd30
                    else -> 0
                }
                longest <= 1920 && pixelsPerSecond <= 49_766_400L ->
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24
                longest <= 2560 && pixelsPerSecond <= 62_208_000L ->
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30
                longest <= 3840 -> when {
                    pixelsPerSecond <= 124_416_000L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60
                    pixelsPerSecond <= 199_065_600L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24
                    pixelsPerSecond <= 248_832_000L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30
                    pixelsPerSecond <= 398_131_200L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48
                    pixelsPerSecond <= 497_664_000L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60
                    pixelsPerSecond <= 995_328_000L ->
                        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd120
                    else -> MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k60
                }
                longest <= 7680 && pixelsPerSecond <= 995_328_000L ->
                    MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k30
                longest <= 7680 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k60
                else -> 0
            }
        }

        fun fromLabel(label: String?): FableSolExportHdrFormat? =
            label?.let { value -> entries.firstOrNull { it.label == value } }
    }
}
