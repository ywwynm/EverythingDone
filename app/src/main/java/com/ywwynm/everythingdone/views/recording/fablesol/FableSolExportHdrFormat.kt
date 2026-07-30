package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.annotation.StringRes
import com.ywwynm.everythingdone.R
import kotlin.math.max

/** 编码阶梯上的一个候选：MIME 加 profile。SDR 与各 HDR 格式共用这一结构。 */
internal class FableSolExportCodecEntry(
    val mime: String,
    val profile: Int,
    val eightBit: Boolean,
    val label: String
) {
    /**
     * 这一候选属于哪个用户可选的编码器族。
     *
     * 杜比视界的 MIME 是 `video/dolby-vision`，但基层与编码器都是 HEVC，所以归 HEVC 族；
     * 界面上不该为它单列一个编码器选项。
     */
    val family: FableSolExportCodecFamily
        get() = checkNotNull(FableSolExportCodecFamily.of(mime)) {
            "Unmapped codec family for $mime"
        }
}

/**
 * 用户可选的 HDR 输出格式。
 *
 * 它与 [FableSolExportTransfer] 不是一回事：传递函数决定**我们怎么画**（EGL 表面色彩空间
 * 与导出 shader），格式还额外决定**用哪个编码器、写什么元数据**。所以"PQ 和 HDR10 并列"
 * 是重复的，两者是同一件事的两个说法：HDR10 = PQ 曲线 + BT.2020 + 静态母版元数据。
 *
 * 四种格式沿两个轴区分——**基层曲线**决定高光余量，**元数据**决定播放端是否具备按场景
 * 适配的依据：
 *
 * | 格式 | 基层 | 余量 | 元数据 |
 * |---|---|---|---|
 * | HDR10+ | PQ | 10000 尼特 | ST 2094-40 动态 |
 * | 杜比视界 8.4 | HLG | 约 3.77 倍 | 杜比动态（RPU） |
 * | HDR10 | PQ | 10000 尼特 | 静态，且我们写的是精确值 |
 * | HLG | HLG | 约 3.77 倍 | 无 |
 *
 * **杜比视界只保留 Profile 8.4**（D141）：Dolby 官方 Android 第三方编辑样例只声明并演示编码
 * 到 8.4；Profile 8.1 需要 PQ 兼容基层，而实机在申请 PQ 时把传递函数改回 HLG；Profile 5 的
 * 单层 IPT-PQ-c2 表示没有可验证的公开输入与 RPU 创作契约。设备诊断仍可展示编码器广告的
 * 其它 Profile，但那是设备声明，不是本应用可导出的格式。
 *
 * 两项实现约束：
 *
 * - **杜比视界不需要应用自己产 RPU**。按 Dolby 官方第三方样例
 *   （`DolbyLaboratories/dolby-vision-editor`）的做法，把 MIME 设成 `video/dolby-vision`、
 *   profile 设成 `DolbyVisionProfileDvheSt`、配一个按像素率算出的 level，编码器自己生成
 *   元数据层；基层传递函数为 HLG 即 Profile 8.4。
 * - **HDR10+ 必须走字节缓冲输入**：`MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO` 在 surface
 *   输入模式下被系统明确禁止，而那是提供 ST 2094-40 动态元数据的唯一接口。改走字节缓冲之后
 *   RGB→YUV 就得我们自己做（[FableSolExportP010Bridge]），统计量也由我们逐帧实测——
 *   三星 S23 Ultra 上已实证这条路能把 SEI 真正写进码流。
 */
@SuppressLint("InlinedApi")
internal enum class FableSolExportHdrFormat(
    val transfer: FableSolExportTransfer,
    /** 固定英文内部标识；用于能力缓存、诊断关联和编码档位，不得随 locale 改变。 */
    val stableLabel: String,
    /** 面向用户的本地化名称。 */
    @StringRes val displayNameRes: Int
) {

    HDR10(
        FableSolExportTransfer.PQ,
        "HDR10",
        R.string.fablesol_export_hdr_format_name_hdr10
    ),
    HDR10_PLUS(
        FableSolExportTransfer.PQ,
        "HDR10+",
        R.string.fablesol_export_hdr_format_name_hdr10_plus
    ),

    HLG(
        FableSolExportTransfer.HLG,
        "HLG",
        R.string.fablesol_export_hdr_format_name_hlg
    ),

    /** 杜比视界 profile 8.4：HLG 基层，余量与 HLG 相同；本应用唯一支持的杜比视界档。 */
    DOLBY_VISION_84(
        FableSolExportTransfer.HLG,
        "Dolby Vision 8.4",
        R.string.fablesol_export_hdr_format_name_dolby_vision_84
    );

    fun displayName(context: Context): String = context.getString(displayNameRes)

    /** PQ 系两种格式都要 CTA-861.3 静态母版元数据，播放端才知道按多高的峰值还原。 */
    val writesStaticMetadata: Boolean
        get() = transfer == FableSolExportTransfer.PQ

    /**
     * 基层是不是 HLG。
     *
     * HLG 与杜比视界 8.4 共用同一条输出变换、同一套 super-white 验证与信号范围设置
     * （D143～D144）；杜比视界 8.4 的那一项在界面上叫“HLG 基层信号范围”。
     */
    val usesHlgBaseLayer: Boolean
        get() = transfer == FableSolExportTransfer.HLG

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
            DOLBY_VISION_84 -> "DV84"
            HLG -> "HLG"
        }

    val isDolbyVision: Boolean
        get() = this == DOLBY_VISION_84

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

    /** 编码器改写 profile 或传递函数时，对该格式验证范围的正式补充说明。 */
    val validationFollowUp: String?
        get() = when (this) {
            HDR10_PLUS ->
                "当前验证已使用字节缓冲输入并逐帧提交 ST 2094-40 动态元数据；Android " +
                    "公开接口未提供其他可用的应用级 HDR10+ 动态元数据注入路径"
            DOLBY_VISION_84 ->
                "该结论仅适用于采用 HLG 基层的 Profile 8.4；Profile 5 与 Profile 8.1 " +
                    "不属于本应用的产品能力"
            else -> null
        }

    val codecEntries: List<FableSolExportCodecEntry>
        get() = when (this) {
            HDR10, HLG -> TEN_BIT_ENTRIES
            HDR10_PLUS -> HDR10_PLUS_ENTRIES
            DOLBY_VISION_84 -> DOLBY_VISION_ENTRIES
        }

    companion object {

        /** 非 HDR 产物在文件名与各处提示里的写法。 */
        const val SDR_LABEL = "SDR"

        /**
         * 自动档的尝试顺序（D141 收敛后固定为四项）。
         *
         * 按**规格从高到低**排（用户 2026-07-27 定：能支持多高规格就支持多高规格）：
         *
         * 1. **HDR10+**——PQ 基层（余量到 10000 尼特）+ ST 2094-40 逐帧动态元数据；
         * 2. **杜比视界 8.4**——HLG 基层 + 杜比动态元数据；
         * 3. **HDR10**——PQ + 静态元数据，峰值按本次导出参数精确写入；
         * 4. **HLG**——HLG 基层且没有动态元数据，作为最后候选。
         *
         * 每一种格式均须通过单帧编码与封装验证；排序只决定候选优先级，不代表设备必然支持。
         */
        val AUTO_ORDER = listOf(HDR10_PLUS, DOLBY_VISION_84, HDR10, HLG)

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

        fun fromStableLabel(label: String?): FableSolExportHdrFormat? =
            label?.let { value -> entries.firstOrNull { it.stableLabel == value } }

        /**
         * 将旧缓存或内部档位文本中的固定格式标识转换为当前 locale 的显示名称。
         *
         * 先替换完整名称，再替换不带 profile 的品牌名，避免中文 locale 在通知、完成态和
         * 诊断技术详情中泄漏英文 “Dolby Vision”。
         */
        fun localizeStableLabels(context: Context, text: String): String {
            var localized = text
            for (format in entries.sortedByDescending { it.stableLabel.length }) {
                localized = localized.replace(format.stableLabel, format.displayName(context))
            }
            return localized.replace(
                "Dolby Vision",
                context.getString(R.string.fablesol_export_hdr_brand_dolby_vision)
            )
        }
    }
}
