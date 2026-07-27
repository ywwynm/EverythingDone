package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import kotlin.math.roundToInt

/**
 * 导出编码参数。全部对用户开放、release 可见（fablesol-video-export D10）。
 *
 * **CQ 档位区间从设备读**（各厂商不同，写死会让 `configure()` 直接抛）；码率滑杆给的是一个
 * 通用范围，真正下发前由 [FableSolExportTier.clampBitrate] 夹到该编码器的合法区间。
 */
internal data class FableSolExportOptions(
    /** 帧率**上限**；编码器不支持时由调用方降到 60（语义与 MAX_TARGET_FPS 一致）。 */
    val frameRateCap: Int,
    /** true = 恒定质量（CQ），false = 恒定码率。设备不支持 CQ 时自动按 false 处理。 */
    val constantQuality: Boolean,
    /**
     * CQ 档位的**原值**，直接来自设备的 `getQualityRange()`；[UNSET_QUALITY] 表示未设置，
     * 由 [resolvedQuality] 取区间的 80% 处。
     *
     * 注意它既不是 x264 的 CRF、也不是编码器内部的 QP：Android 的 `KEY_QUALITY` 是各厂商
     * 自行映射的一段区间，只保证"越大越好"。因此界面上按原值显示并标注区间，不换算成
     * CRF/QP，也不折成百分比。
     */
    val qualityValue: Int,
    /** 目标码率（Mbps，按 120fps 计）。 */
    val bitrateMbps: Float,
    val keyframeIntervalSeconds: Float,
    /** 是否导出 HDR；关掉就走 FableSol 自己的 SDR 分支重新渲染（D6）。 */
    val hdrEnabled: Boolean,
    /** 指定 HDR 信号格式；默认自动 = 设备支持哪些就按偏好顺序试。 */
    val hdrFormat: HdrFormatPreference = HdrFormatPreference.AUTO,
    /**
     * PQ 系导出里，漫反射白（水体与卡片）钉在多少尼特。
     *
     * PQ 是**绝对**亮度曲线：这个数写多少，正确的显示设备就渲染多亮。BT.2408 的 203 尼特
     * 是给暗室监视器定的，而手机在高亮度下显示普通 SDR 白在 500–800 尼特——所以按 203 导出
     * 的产物在手机上看反而比 SDR 还暗，这就是常说的"HDR 看起来更暗"。
     *
     * 它同时决定**有没有动态感**：锚点太低时，一块 1000 尼特的屏幕对背景和高光都绰绰有余，
     * 永远不需要取舍，画面自然是静的；锚点抬上去，屏幕才会在高光涌上来时把背景让回去。
     *
     * 高光峰值 = 本值 × HDR 强度。HLG 系（含杜比视界 8.4）是相对亮度，用不到这个数。
     */
    val pqWhiteNits: Float = DEFAULT_PQ_WHITE_NITS,
    /**
     * 「高光起点」：画面亮度分布的第几个百分位开始算高光。只有 HDR10+ 用得到——只有它带
     * 色调映射曲线。以下原样保留，以上才压缩。
     */
    val highlightStartPercent: Int =
        FableSolExportHdr10PlusCurve.DEFAULT_HIGHLIGHT_START_PERCENT
) {

    /**
     * 用户对 HDR 输出格式的偏好。
     *
     * 枚举顺序即持久化的序号，**不能重排**：早先只有 AUTO / PQ / HLG 三项，PQ 就是现在的
     * HDR10。新增的两项只能续在末尾，否则老用户存下的 2 会被读成别的格式。
     */
    enum class HdrFormatPreference(val format: FableSolExportHdrFormat?) {
        /** 设备支持哪些就按 [FableSolExportHdrFormat.AUTO_ORDER] 依次试。 */
        AUTO(null),
        HDR10(FableSolExportHdrFormat.HDR10),
        HLG(FableSolExportHdrFormat.HLG),
        HDR10_PLUS(FableSolExportHdrFormat.HDR10_PLUS),
        DOLBY_VISION_84(FableSolExportHdrFormat.DOLBY_VISION_84),
        DOLBY_VISION_81(FableSolExportHdrFormat.DOLBY_VISION_81),
        DOLBY_VISION_5(FableSolExportHdrFormat.DOLBY_VISION_5);

        fun allows(candidate: FableSolExportHdrFormat): Boolean =
            format == null || format == candidate

        companion object {
            fun fromStored(value: Int): HdrFormatPreference =
                entries.getOrElse(value) { AUTO }

            fun of(format: FableSolExportHdrFormat): HdrFormatPreference =
                entries.first { it.format == format }
        }
    }

    /** 解析出要写进 `KEY_QUALITY` 的档位；返回 null 表示该档位走恒定码率。 */
    fun resolvedQuality(tier: FableSolExportTier): Int? {
        if (!constantQuality) return null
        val range = tier.qualityRange ?: return null
        return resolveWithin(range)
    }

    fun resolveWithin(range: Range<Int>): Int {
        val lower = range.lower
        val upper = range.upper
        if (upper <= lower) return lower
        if (qualityValue == UNSET_QUALITY) {
            return (lower + (upper - lower) * DEFAULT_QUALITY_FRACTION).roundToInt()
        }
        return qualityValue.coerceIn(lower, upper)
    }

    /** 60fps 档按 120fps 目标的六成给（与 decisions.md D10 的 24 / 14 两个数对齐）。 */
    fun bitrateBps(frameRate: Int): Int {
        val factor = if (frameRate >= FRAME_RATE_HIGH) 1.0f else 0.6f
        return (bitrateMbps * factor * 1_000_000f).roundToInt().coerceAtLeast(1_000_000)
    }

    companion object {

        @Volatile
        private var settingsQualityRangeResolved = false
        @Volatile
        private var cachedSettingsQualityRange: Range<Int>? = null

        const val FRAME_RATE_HIGH = 120
        const val FRAME_RATE_BASE = 60

        const val UNSET_QUALITY = Int.MIN_VALUE
        const val DEFAULT_QUALITY_FRACTION = 0.8
        const val DEFAULT_BITRATE_MBPS = 24f
        const val DEFAULT_KEYFRAME_SECONDS = 2f
        const val MIN_BITRATE_MBPS = 2f
        const val MAX_BITRATE_MBPS = 60f
        /** 默认 400 尼特：约等于手机 HDR 拍摄内容的常用漫反射白，比 203 明显亮。 */
        const val DEFAULT_PQ_WHITE_NITS = 400f
        const val MIN_PQ_WHITE_NITS = 200f
        const val MAX_PQ_WHITE_NITS = 800f

        const val MIN_KEYFRAME_SECONDS = 0.5f
        const val MAX_KEYFRAME_SECONDS = 10f

        fun read(context: Context): FableSolExportOptions = FableSolExportOptions(
            frameRateCap = FableSolTuning.exportFrameRateCap(context),
            constantQuality = FableSolTuning.exportConstantQuality(context),
            qualityValue = FableSolTuning.exportQualityValue(context),
            bitrateMbps = FableSolTuning.exportBitrateMbps(context),
            keyframeIntervalSeconds = FableSolTuning.exportKeyframeSeconds(context),
            hdrEnabled = FableSolTuning.exportHdrEnabled(context),
            hdrFormat = FableSolTuning.exportHdrFormat(context),
            pqWhiteNits = FableSolTuning.exportPqWhiteNits(context),
            highlightStartPercent = FableSolTuning.exportHighlightStart(context)
        )

        /**
         * 设置界面用的代表性 CQ 区间：优先 HEVC（导出阶梯的首选），退到 H.264。
         * 返回 null 表示本机没有任何编码器支持恒定质量，界面上该档位应当整个不出现。
         */
        fun settingsQualityRange(): Range<Int>? {
            if (settingsQualityRangeResolved) return cachedSettingsQualityRange
            return synchronized(this) {
                if (!settingsQualityRangeResolved) {
                    cachedSettingsQualityRange = if (Build.VERSION.SDK_INT < 28) {
                        null
                    } else {
                        qualityRange(MediaFormat.MIMETYPE_VIDEO_HEVC)
                            ?: qualityRange(MediaFormat.MIMETYPE_VIDEO_AVC)
                    }
                    // 即使结果为 null 也要缓存；否则不支持 CQ 的设备仍会每次打开设置都枚举 codec。
                    settingsQualityRangeResolved = true
                }
                cachedSettingsQualityRange
            }
        }

        private fun qualityRange(mime: String): Range<Int>? {
            if (Build.VERSION.SDK_INT < 28) return null
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val capabilities = try {
                    info.getCapabilitiesForType(mime)
                } catch (ignored: Throwable) {
                    continue
                }
                val encoder = capabilities.encoderCapabilities ?: continue
                if (!encoder.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    )
                ) continue
                val range = try {
                    encoder.qualityRange
                } catch (ignored: Throwable) {
                    null
                }
                if (range != null && range.upper > range.lower) return range
            }
            return null
        }
    }
}
