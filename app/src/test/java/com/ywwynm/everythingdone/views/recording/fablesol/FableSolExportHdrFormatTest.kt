package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolExportHdrFormatTest {

    /**
     * 自动档按**规格从高到低**排（用户 2026-07-27 定：能支持多高规格就支持多高规格），
     * D141 收敛杜比视界后固定为四项：HDR10+ → 杜比视界 8.4 → HDR10 → HLG。
     */
    @Test
    fun autoIsOrderedFromHighestSpecDown() {
        assertEquals(
            listOf(
                FableSolExportHdrFormat.HDR10_PLUS,
                FableSolExportHdrFormat.DOLBY_VISION_84,
                FableSolExportHdrFormat.HDR10,
                FableSolExportHdrFormat.HLG
            ),
            FableSolExportHdrFormat.AUTO_ORDER
        )
        assertFalse(FableSolExportHdrFormat.HDR_VIVID in FableSolExportHdrFormat.AUTO_ORDER)
    }

    /** HDR Vivid 首版可显式选择，但终端/平台兼容性验证完成前不改变“自动”的既有结果。 */
    @Test
    fun selectableFormatsIncludeExplicitOnlyHdrVivid() {
        assertEquals(
            listOf(
                FableSolExportHdrFormat.HDR10_PLUS,
                FableSolExportHdrFormat.DOLBY_VISION_84,
                FableSolExportHdrFormat.HDR10,
                FableSolExportHdrFormat.HLG,
                FableSolExportHdrFormat.HDR_VIVID
            ),
            FableSolExportHdrFormat.SELECTABLE_ORDER
        )
        assertEquals(
            FableSolExportHdrFormat.entries.toSet(),
            FableSolExportHdrFormat.SELECTABLE_ORDER.toSet()
        )
    }

    /** 两种动态格式共享参考显示峰值和高光起点，但各自生成独立标准载荷。 */
    @Test
    fun authoredToneMappingParametersBelongToHdr10PlusAndHdrVivid() {
        assertTrue(FableSolExportHdrFormat.HDR10_PLUS.usesAuthoredToneMappingCurve)
        assertTrue(FableSolExportHdrFormat.HDR_VIVID.usesAuthoredToneMappingCurve)
        for (format in FableSolExportHdrFormat.entries) {
            if (
                format != FableSolExportHdrFormat.HDR10_PLUS &&
                format != FableSolExportHdrFormat.HDR_VIVID
            ) {
                assertFalse(format.usesAuthoredToneMappingCurve)
            }
        }
    }

    /**
     * **杜比视界只剩 Profile 8.4**（D141）。
     *
     * Profile 5 的单层 IPT-PQ-c2 没有可验证的公开输入与 RPU 创作契约；Profile 8.1 需要 PQ
     * 兼容基层，而实机在申请 PQ 时把传递函数改回 HLG。两者都不得再作为产品选项、自动候选
     * 或正式导出路径出现。
     */
    @Test
    fun dolbyVisionIsNarrowedToProfileEightFour() {
        val dolby = FableSolExportHdrFormat.entries.filter { it.isDolbyVision }
        assertEquals(listOf(FableSolExportHdrFormat.DOLBY_VISION_84), dolby)
        assertEquals(
            FableSolExportTransfer.HLG,
            FableSolExportHdrFormat.DOLBY_VISION_84.transfer
        )
        assertTrue(FableSolExportHdrFormat.DOLBY_VISION_84.usesHlgBaseLayer)
        assertEquals(
            listOf(CodecProfileLevel.DolbyVisionProfileDvheSt),
            FableSolExportHdrFormat.DOLBY_VISION_84.codecEntries.map { it.profile }
        )
    }

    /**
     * **HDR10+ 不能拿 profile 当判据**——这一点我判错过。HEVC 层面没有"HDR10+ profile"，
     * 码流的 `general_profile_idc` 本来就是 Main10，8192 是 Android 框架层的合成常量；
     * 真正作数的是码流里那段 SEI。杜比视界换的是 MIME，profile 被改就是真的换了东西，
     * 所以它仍然要求原样回报。
     */
    @Test
    fun onlyDolbyVisionDemandsTheExactProfile() {
        assertTrue(FableSolExportHdrFormat.DOLBY_VISION_84.requiresExactProfile)
        assertFalse(FableSolExportHdrFormat.HDR10_PLUS.requiresExactProfile)
        assertFalse(FableSolExportHdrFormat.HDR_VIVID.requiresExactProfile)
        assertFalse(FableSolExportHdrFormat.HDR10.requiresExactProfile)
        assertFalse(FableSolExportHdrFormat.HLG.requiresExactProfile)
    }

    /**
     * 只有 HDR10+ 走字节缓冲输入。走错路的代价是实打实的：surface 模式下那个元数据接口
     * 被系统禁止，而字节缓冲模式下 RGB→YUV 得我们自己做，两边不能混。
     */
    @Test
    fun onlyHdr10PlusTakesTheByteBufferPath() {
        assertTrue(FableSolExportHdrFormat.HDR10_PLUS.usesByteBufferInput)
        for (format in FableSolExportHdrFormat.entries) {
            if (format != FableSolExportHdrFormat.HDR10_PLUS) {
                assertFalse(format.usesByteBufferInput)
            }
        }
    }

    /** 杜比视界基层是 HLG，所以高光余量与 HLG 相同——它不是"更好的 HDR10"。 */
    @Test
    fun dolbyVisionRidesOnTheHlgBaseLayer() {
        assertEquals(
            FableSolExportTransfer.HLG,
            FableSolExportHdrFormat.DOLBY_VISION_84.transfer
        )
        assertFalse(FableSolExportHdrFormat.DOLBY_VISION_84.writesStaticMetadata)
        assertTrue(FableSolExportHdrFormat.HDR10.writesStaticMetadata)
        assertTrue(FableSolExportHdrFormat.HDR10_PLUS.writesStaticMetadata)
        assertTrue(FableSolExportHdrFormat.HDR_VIVID.writesStaticMetadata)
    }

    /** 首版承载严格限定为 PQ/HEVC Main10；不把 AV1 Main10 当成 HDR Vivid。 */
    @Test
    fun hdrVividUsesOnlyHevcMain10() {
        val entries = FableSolExportHdrFormat.HDR_VIVID.codecEntries
        assertEquals(1, entries.size)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, entries.single().mime)
        assertEquals(CodecProfileLevel.HEVCProfileMain10, entries.single().profile)
        assertFalse(entries.single().eightBit)
        assertFalse(FableSolExportHdrFormat.HDR_VIVID.usesByteBufferInput)
    }

    @Test
    fun dolbyVisionLevelFollowsTheDolbySampleLadder() {
        assertEquals(
            CodecProfileLevel.DolbyVisionLevelHd24,
            FableSolExportHdrFormat.dolbyVisionLevel(1280, 720, 24)
        )
        assertEquals(
            CodecProfileLevel.DolbyVisionLevelFhd24,
            FableSolExportHdrFormat.dolbyVisionLevel(1920, 1080, 24)
        )
        assertEquals(
            CodecProfileLevel.DolbyVisionLevelUhd30,
            FableSolExportHdrFormat.dolbyVisionLevel(1920, 1080, 120)
        )
    }

    /**
     * level 是一条**像素率**阶梯，不是分辨率标签：本项目的导出画布是窄竖幅，但 120fps
     * 会把它推到名字带 "Uhd" 的档。这里钉住"竖幅小画布在高帧率下也必须解出非零 level"
     * ——解不出会让 `configure()` 因为缺 level 而直接失败。
     */
    @Test
    fun tallNarrowCanvasAtHighFrameRateStillResolvesALevel() {
        val level = FableSolExportHdrFormat.dolbyVisionLevel(896, 1472, 120)
        assertNotEquals(0, level)
    }

    /**
     * **HDR10+ 申请 8192、编码器回报 2，必须判为通过。**
     *
     * 这是实打实踩过的坑：HEVC 层面没有"HDR10+ profile"，码流的 `general_profile_idc` 本来
     * 就是 Main10（2），8192 是 Android 框架层的合成常量。校验表里少了 Main10 这一项，
     * 一个真正带上了 HDR10+ SEI 的产物照样会被判成"编码器降了档"，于是设置页永远只显示
     * HDR10、显不出 HDR10+。
     */
    @Test
    fun hdr10PlusAcceptsMain10AsTheReportedProfile() {
        val tier = tier(
            FableSolExportHdrFormat.HDR10_PLUS,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus
        )
        assertTrue(tier.acceptsTenBitProfile(CodecProfileLevel.HEVCProfileMain10))
        assertTrue(tier.acceptsTenBitProfile(CodecProfileLevel.HEVCProfileMain10HDR10Plus))
        // 8-bit 的 Main 仍然要拒：那是真的降了档。
        assertFalse(tier.acceptsTenBitProfile(CodecProfileLevel.HEVCProfileMain))
    }

    /** 杜比视界换的是 MIME，profile 被改就是真的换了东西，所以仍然只认原样回报。 */
    @Test
    fun dolbyVisionStillRejectsAnySubstituteProfile() {
        val tier = tier(
            FableSolExportHdrFormat.DOLBY_VISION_84,
            CodecProfileLevel.DolbyVisionProfileDvheSt
        )
        assertTrue(tier.acceptsTenBitProfile(CodecProfileLevel.DolbyVisionProfileDvheSt))
        assertFalse(tier.acceptsTenBitProfile(CodecProfileLevel.HEVCProfileMain10))
    }

    private fun tier(format: FableSolExportHdrFormat, profile: Int) = FableSolExportTier(
        codecName = "test",
        videoMime = format.codecEntries.first().mime,
        profile = profile,
        level = 1,
        transfer = format.transfer,
        hdrFormat = format,
        family = format.codecEntries.first().family,
        softwareOnly = false,
        eightBit = false,
        supportsCbr = true,
        supportsVbr = true,
        qualityRange = null,
        bitrateRange = null,
        encodedWidthPx = 640,
        encodedHeightPx = 640,
        label = "test"
    )

    @Test
    fun stableLabelRoundTripsSoCachedDiagnosticsCanBeRestored() {
        for (format in FableSolExportHdrFormat.entries) {
            assertEquals(
                format,
                FableSolExportHdrFormat.fromStableLabel(format.stableLabel)
            )
        }
        assertEquals(null, FableSolExportHdrFormat.fromStableLabel("Nope"))
        assertEquals(null, FableSolExportHdrFormat.fromStableLabel(null))
    }
}
