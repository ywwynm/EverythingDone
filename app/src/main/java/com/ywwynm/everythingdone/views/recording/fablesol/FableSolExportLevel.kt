package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaCodecInfo.CodecProfileLevel

/**
 * 按实际输出规格选择**最低充分**的 Level/Tier（fablesol-video-export D152、D168）。
 *
 * 现状是 `advertised.maxByOrNull { it.level }`——直接申请编码器广告的最高档。那是把 Level
 * 当成了画质档位，而它不是：Level 描述的是**解码**这段码流所需的画面尺寸、像素吞吐、码率与
 * 图像缓存上限。申请更高的 Level 不会让同一 Profile 的画面更清晰，只会让那些本来放得动这段
 * 视频、却只支持较低 Level 的设备直接拒收。
 *
 * 因此这里按各标准的正式表算出最低需求，再从**实际编码器广告的档位**里取刚好够用的一档：
 *
 * - **尺寸**：AVC 按宏块数（16×16），HEVC/AV1 按亮度样本数；
 * - **吞吐**：同上乘以帧率；
 * - **码率**：只有 VBR 与 CBR 后备参与。CQ 模式没有解析码率，按 D168 只以尺寸与像素率定档、
 *   保持 Main Tier，不为未知码率抬档；QP 保护开启的 VBR 按 [QP_GUARD_BITRATE_HEADROOM]
 *   加保守余量（D152）；
 * - **参考结构**：B 帧要多一个重排缓冲，计入 DPB 需求。本项目的画布远小于任何一档的 DPB
 *   上限，这一项实际不会成为决定档位的那一条，但它是 D152 列出的输入，写出来才说得清。
 *
 * Tier 只对 HEVC 有意义，且只在**同一档 Level 的 Main Tier 容不下解析后的码率**时才升到
 * High Tier（D152）；不得把 High Tier 当画质开关。AV1 的 Android 常量不编码 Tier，
 * AVC 没有 Tier 概念。
 */
internal object FableSolExportLevel {

    /** 一次定档的结果。 */
    data class Selection(
        /** 要写进 `KEY_LEVEL` 的 Android 常量。 */
        val level: Int,
        /** 仅 HEVC 有意义：本次是否用到了 High Tier。 */
        val highTier: Boolean
    )

    /**
     * QP 保护生效时给码率定档加的保守余量（D151、D152）。
     *
     * `KEY_VIDEO_QP_MAX` 挡住编码器在复杂帧上继续压缩，实际码率可以持续高于目标；目标值
     * 又恰好贴着档位上限定档时，产物就可能超出所声明 Level 的码率约束。25% 与 H.264
     * High Profile 自身的 1.25 倍 VCL 因子同量级。只作用于"VBR 且保护开启"的定档输入，
     * 数值以边界回归钉住，不拆成产品选项；CQ 按 D168 根本不以码率定档。
     */
    const val QP_GUARD_BITRATE_HEADROOM = 1.25

    /** AVC 一个宏块的边长。 */
    private const val MACROBLOCK = 16

    /**
     * 一档 Level 的标准约束。
     *
     * [maxBitrateKbps] 的单位是标准里的 `CpbBrVclFactor` 倍数（AVC/HEVC 均为 1000 bit/s），
     * 换算成 bps 时还要乘 Profile 相关的系数，见 [profileBitrateFactor]。
     */
    private data class Limits(
        val androidLevel: Int,
        /** AVC 为宏块数，HEVC/AV1 为亮度样本数。 */
        val maxFrameUnits: Long,
        /** 上一项乘帧率之后的上限。 */
        val maxUnitsPerSecond: Long,
        val maxBitrateKbps: Long,
        /** AVC 的 MaxDpbMbs；HEVC/AV1 按公式推导，填 0 表示不用这张表。 */
        val maxDpbUnits: Long = 0L,
        val highTier: Boolean = false
    )

    // ---- H.264 / AVC：ITU-T H.264 表 A-1 ----

    private val AVC_LIMITS = listOf(
        Limits(CodecProfileLevel.AVCLevel1, 99, 1_485, 64, 396),
        Limits(CodecProfileLevel.AVCLevel1b, 99, 1_485, 128, 396),
        Limits(CodecProfileLevel.AVCLevel11, 396, 3_000, 192, 900),
        Limits(CodecProfileLevel.AVCLevel12, 396, 6_000, 384, 2_376),
        Limits(CodecProfileLevel.AVCLevel13, 396, 11_880, 768, 2_376),
        Limits(CodecProfileLevel.AVCLevel2, 396, 11_880, 2_000, 2_376),
        Limits(CodecProfileLevel.AVCLevel21, 792, 19_800, 4_000, 4_752),
        Limits(CodecProfileLevel.AVCLevel22, 1_620, 20_250, 4_000, 8_100),
        Limits(CodecProfileLevel.AVCLevel3, 1_620, 40_500, 10_000, 8_100),
        Limits(CodecProfileLevel.AVCLevel31, 3_600, 108_000, 14_000, 18_000),
        Limits(CodecProfileLevel.AVCLevel32, 5_120, 216_000, 20_000, 20_480),
        Limits(CodecProfileLevel.AVCLevel4, 8_192, 245_760, 20_000, 32_768),
        Limits(CodecProfileLevel.AVCLevel41, 8_192, 245_760, 50_000, 32_768),
        Limits(CodecProfileLevel.AVCLevel42, 8_704, 522_240, 50_000, 34_816),
        Limits(CodecProfileLevel.AVCLevel5, 22_080, 589_824, 135_000, 110_400),
        Limits(CodecProfileLevel.AVCLevel51, 36_864, 983_040, 240_000, 184_320),
        Limits(CodecProfileLevel.AVCLevel52, 36_864, 2_073_600, 240_000, 184_320),
        Limits(CodecProfileLevel.AVCLevel6, 139_264, 4_177_920, 240_000, 696_320),
        Limits(CodecProfileLevel.AVCLevel61, 139_264, 8_355_840, 480_000, 696_320),
        Limits(CodecProfileLevel.AVCLevel62, 139_264, 16_711_680, 800_000, 696_320)
    )

    // ---- HEVC：ITU-T H.265 表 A.1（MaxLumaPs）与表 A.2（MaxLumaSr、MaxBR）----

    private val HEVC_LIMITS = listOf(
        hevc(CodecProfileLevel.HEVCMainTierLevel1, 36_864, 552_960, 128),
        hevc(CodecProfileLevel.HEVCMainTierLevel2, 122_880, 3_686_400, 1_500),
        hevc(CodecProfileLevel.HEVCMainTierLevel21, 245_760, 7_372_800, 3_000),
        hevc(CodecProfileLevel.HEVCMainTierLevel3, 552_960, 16_588_800, 6_000),
        hevc(CodecProfileLevel.HEVCMainTierLevel31, 983_040, 33_177_600, 10_000),
        hevc(CodecProfileLevel.HEVCMainTierLevel4, 2_228_224, 66_846_720, 12_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel4, 2_228_224, 66_846_720, 30_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel41, 2_228_224, 133_693_440, 20_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel41, 2_228_224, 133_693_440, 50_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel5, 8_912_896, 267_386_880, 25_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel5, 8_912_896, 267_386_880, 100_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel51, 8_912_896, 534_773_760, 40_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel51, 8_912_896, 534_773_760, 160_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel52, 8_912_896, 1_069_547_520, 60_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel52, 8_912_896, 1_069_547_520, 240_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel6, 35_651_584, 1_069_547_520, 60_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel6, 35_651_584, 1_069_547_520, 240_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel61, 35_651_584, 2_139_095_040, 120_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel61, 35_651_584, 2_139_095_040, 480_000, true),
        hevc(CodecProfileLevel.HEVCMainTierLevel62, 35_651_584, 4_278_190_080, 240_000),
        hevc(CodecProfileLevel.HEVCHighTierLevel62, 35_651_584, 4_278_190_080, 800_000, true)
    )

    private fun hevc(
        androidLevel: Int,
        maxLumaPs: Long,
        maxLumaSr: Long,
        maxBitrateKbps: Long,
        highTier: Boolean = false
    ) = Limits(androidLevel, maxLumaPs, maxLumaSr, maxBitrateKbps, highTier = highTier)

    // ---- AV1：AV1 规范附录 A 表 A.1（Main Tier 码率）----
    //
    // 规范里 2.2/2.3、3.2/3.3、4.2/4.3 为保留档，不列入本表；Android 虽有对应常量，
    // 编码器不应广告它们，真广告了也只会走 [chooseFromAdvertised] 的兜底分支。

    private val AV1_LIMITS = listOf(
        Limits(CodecProfileLevel.AV1Level2, 147_456, 4_423_680, 1_500),
        Limits(CodecProfileLevel.AV1Level21, 278_784, 8_363_520, 3_000),
        Limits(CodecProfileLevel.AV1Level3, 665_856, 19_975_680, 6_000),
        Limits(CodecProfileLevel.AV1Level31, 1_065_024, 31_950_720, 10_000),
        Limits(CodecProfileLevel.AV1Level4, 2_359_296, 70_778_880, 12_000),
        Limits(CodecProfileLevel.AV1Level41, 2_359_296, 141_557_760, 20_000),
        Limits(CodecProfileLevel.AV1Level5, 8_912_896, 267_386_880, 30_000),
        Limits(CodecProfileLevel.AV1Level51, 8_912_896, 534_773_760, 40_000),
        Limits(CodecProfileLevel.AV1Level52, 8_912_896, 1_069_547_520, 60_000),
        Limits(CodecProfileLevel.AV1Level53, 8_912_896, 1_069_547_520, 60_000),
        Limits(CodecProfileLevel.AV1Level6, 35_651_584, 1_069_547_520, 60_000),
        Limits(CodecProfileLevel.AV1Level61, 35_651_584, 2_139_095_040, 100_000),
        Limits(CodecProfileLevel.AV1Level62, 35_651_584, 4_278_190_080, 160_000),
        Limits(CodecProfileLevel.AV1Level63, 35_651_584, 4_278_190_080, 160_000)
    )

    private fun limitsFor(family: FableSolExportCodecFamily): List<Limits> = when (family) {
        FableSolExportCodecFamily.AVC -> AVC_LIMITS
        FableSolExportCodecFamily.HEVC -> HEVC_LIMITS
        FableSolExportCodecFamily.AV1 -> AV1_LIMITS
    }

    /**
     * Profile 相关的码率倍数（H.264 的 `cpbBrVclFactor`）。
     *
     * H.264 High Profile 的 VCL 码率上限是基准的 1.25 倍；HEVC Main 与 Main10 同为 1000，
     * AV1 不按 Profile 缩放。取得准一点是有意义的：倍数取小了会让算法多抬一档 Level，
     * 而"多抬一档"正是这次要消除的行为。
     */
    fun profileBitrateFactor(family: FableSolExportCodecFamily, profile: Int): Double = when {
        family != FableSolExportCodecFamily.AVC -> 1.0
        profile == CodecProfileLevel.AVCProfileHigh -> 1.25
        profile == CodecProfileLevel.AVCProfileHigh10 -> 3.0
        else -> 1.0
    }

    /** 本次需要的解码图像缓存帧数：B 帧要多一个重排缓冲（D152 的"参考结构"输入）。 */
    fun requiredDpbFrames(bFrames: Boolean): Int = if (bFrames) 4 else 2

    /**
     * 选出最低充分的 Level/Tier。
     *
     * @param advertised 实际编码器对**当前 Profile** 广告的全部 Level 常量。
     * @param bitrateBps 解析后的目标码率；`null` 表示 CQ 模式——按 D168 不以码率定档，
     *   也不为未知码率改用 High Tier。
     * @return `null` 表示编码器一个档位都没广告，调用方保持既有行为（不写 `KEY_LEVEL`）。
     */
    fun select(
        family: FableSolExportCodecFamily,
        advertised: List<Int>,
        widthPx: Int,
        heightPx: Int,
        frameRate: Int,
        profile: Int,
        bitrateBps: Int?,
        bFrames: Boolean,
        /** 本候选是否会带着 QP 保护跑 VBR：是则码率需求乘保守余量再定档（D152）。 */
        qpGuard: Boolean = false
    ): Selection? {
        if (advertised.isEmpty()) return null
        val table = limitsFor(family)
        val frameUnits = frameUnits(family, widthPx, heightPx)
        val unitsPerSecond = frameUnits * frameRate
        val requiredKbps = bitrateBps?.let {
            val factor = profileBitrateFactor(family, profile)
            // QP 保护允许实际码率超过目标（D151），定档要吃下这段上浮（D152 的保守余量）。
            val headroom = if (qpGuard) QP_GUARD_BITRATE_HEADROOM else 1.0
            // 向上取整：正好卡在档位上限的码率仍要被这一档容纳。
            Math.ceil(it * headroom / 1000.0 / factor).toLong()
        }
        val dpbFrames = requiredDpbFrames(bFrames)

        val requiredIndex = table.indexOfFirst { limits ->
            limits.maxFrameUnits >= frameUnits &&
                limits.maxUnitsPerSecond >= unitsPerSecond &&
                dpbFits(family, limits, frameUnits, dpbFrames) &&
                // CQ 模式（requiredKbps == null）不看码率，也就不会为未知码率抬档（D168）。
                (requiredKbps == null || limits.maxBitrateKbps >= requiredKbps) &&
                // CQ 保持 Main Tier；只有解析出码率、且同档 Main Tier 装不下时才轮到
                // High Tier——而那正是上一行把 Main Tier 淘汰掉之后的下一个表项。
                (requiredKbps != null || !limits.highTier)
        }
        if (requiredIndex < 0) {
            // 连最高档都不够（本项目的画布不可能走到这里）：保持既有行为，交给真实探测判定。
            return advertised.maxOrNull()?.let { Selection(it, isHighTier(table, it)) }
        }
        return chooseFromAdvertised(table, advertised, requiredIndex)
    }

    /**
     * 从广告档位里取标准序不低于 [requiredIndex] 的最低一档。
     *
     * 不在表里的常量（保留档、厂商私有值）只参与最后的兜底：拿一个我们解释不了的档位去比较
     * 大小，得到的结论也解释不了。
     */
    private fun chooseFromAdvertised(
        table: List<Limits>,
        advertised: List<Int>,
        requiredIndex: Int
    ): Selection {
        val ranked = advertised
            .mapNotNull { value ->
                val index = table.indexOfFirst { it.androidLevel == value }
                if (index >= 0) index to value else null
            }
            .sortedBy { it.first }
        val chosen = ranked.firstOrNull { it.first >= requiredIndex }
        if (chosen != null) return Selection(chosen.second, table[chosen.first].highTier)
        // 广告档位全都低于需求，或全都不在表里：沿用"取广告最高档"的既有行为，由真实编码
        // 探测决定这个候选成不成立。
        val fallback = advertised.maxOrNull() ?: table[requiredIndex].androidLevel
        return Selection(fallback, isHighTier(table, fallback))
    }

    private fun isHighTier(table: List<Limits>, androidLevel: Int): Boolean =
        table.firstOrNull { it.androidLevel == androidLevel }?.highTier == true

    /** AVC 数宏块，HEVC/AV1 数亮度样本。 */
    fun frameUnits(family: FableSolExportCodecFamily, widthPx: Int, heightPx: Int): Long =
        if (family == FableSolExportCodecFamily.AVC) {
            ceilDiv(widthPx, MACROBLOCK).toLong() * ceilDiv(heightPx, MACROBLOCK).toLong()
        } else {
            widthPx.toLong() * heightPx.toLong()
        }

    /**
     * 该档的解码图像缓存放不放得下所需帧数。
     *
     * AVC 用表里的 `MaxDpbMbs`；HEVC 按 H.265 A.4.2 的 `MaxDpbSize` 公式推导；AV1 的
     * 参考帧数固定为 8，与画面尺寸无关，不构成定档约束。
     */
    private fun dpbFits(
        family: FableSolExportCodecFamily,
        limits: Limits,
        frameUnits: Long,
        requiredFrames: Int
    ): Boolean = when (family) {
        FableSolExportCodecFamily.AVC ->
            frameUnits <= 0L || limits.maxDpbUnits / frameUnits >= requiredFrames
        FableSolExportCodecFamily.HEVC ->
            hevcMaxDpbSize(limits.maxFrameUnits, frameUnits) >= requiredFrames
        FableSolExportCodecFamily.AV1 -> true
    }

    /** H.265 A.4.2：图像越小于 `MaxLumaPs`，可缓存的图像数越多，上限 16。 */
    fun hevcMaxDpbSize(maxLumaPs: Long, pictureSamples: Long): Int {
        if (pictureSamples <= 0L) return 16
        val buffer = 6L
        val size = when {
            pictureSamples <= maxLumaPs / 4 -> 4 * buffer
            pictureSamples <= maxLumaPs / 2 -> 2 * buffer
            pictureSamples <= 3 * maxLumaPs / 4 -> 4 * buffer / 3
            else -> buffer
        }
        return minOf(size, 16L).toInt()
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
