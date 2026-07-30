package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求模型（用户意图）与已解析候选（实际落点）的语义门禁。
 *
 * 这一层此前混在一起：`hdrEnabled` 既是选择又被当成结论，于是文件名、进度、完成态和诊断
 * 各自推一遍落点，四处说法可以不一致。拆开之后，"想要什么"只出现在本文件测的这些类型里。
 */
class FableSolExportRequestModelTest {

    /**
     * **持久化用稳定字符串，不用 ordinal。**
     *
     * 序号会被新增枚举项破坏：旧 `HdrFormatPreference` 就因为只能往末尾追加，把"按规格排序"
     * 与"按存储序号排序"两件事绑死了。稳定标识还必须两两不同，否则两个选项会互相覆盖。
     */
    @Test
    fun everyPersistedChoiceRoundTripsThroughItsStableId() {
        assertStableIds(FableSolExportColorMode.entries) {
            FableSolExportColorMode.fromStableId(it)
        }
        assertStableIds(FableSolExportSdrMapping.entries) {
            FableSolExportSdrMapping.fromStableId(it)
        }
        assertStableIds(FableSolExportSdrBitDepth.entries) {
            FableSolExportSdrBitDepth.fromStableId(it)
        }
        assertStableIds(FableSolExportHlgSignalRange.entries) {
            FableSolExportHlgSignalRange.fromStableId(it)
        }
        assertStableIds(FableSolExportRateControl.entries) {
            FableSolExportRateControl.fromStableId(it)
        }
        assertStableIds(FableSolExportRateControlForm.entries) {
            FableSolExportRateControlForm.fromStableId(it)
        }
        assertStableIds(FableSolExportInputPath.entries) {
            FableSolExportInputPath.fromStableId(it)
        }
        assertStableIds(FableSolExportOptions.CodecPreference.entries) {
            FableSolExportOptions.CodecPreference.fromStableId(it)
        }
        // 读不出来的值一律落到默认，不抛异常：偏好文件可能来自更新的版本。
        assertEquals(
            FableSolExportColorMode.HDR_AUTO,
            FableSolExportColorMode.fromStableId("nope")
        )
        assertEquals(FableSolExportColorMode.HDR_AUTO, FableSolExportColorMode.fromStableId(null))
    }

    /**
     * 旧设置迁移（D62、D141、D145）：
     *
     * - `hdrEnabled=false` → 原生 SDR，不得改变既有用户的输出语义；
     * - `hdrEnabled=true` 按原 HDR 格式迁移；
     * - 杜比视界 Profile 5 与 8.1 统一迁移为显式 8.4——保留"用户明确要杜比视界"的意图，
     *   即使当前设备不支持也不再进一步静默改成"自动"。
     */
    @Test
    fun legacyHdrPreferencesMigrateWithoutChangingIntent() {
        // 旧枚举顺序：AUTO, HDR10, HLG, HDR10_PLUS, DV84, DV81, DV5。
        assertEquals(
            FableSolExportColorMode.SDR_NATIVE,
            FableSolExportColorMode.migrateFromLegacy(hdrEnabled = false, legacyFormatOrdinal = 3)
        )
        assertEquals(
            FableSolExportColorMode.HDR_AUTO,
            FableSolExportColorMode.migrateFromLegacy(hdrEnabled = true, legacyFormatOrdinal = 0)
        )
        assertEquals(
            FableSolExportColorMode.HDR10,
            FableSolExportColorMode.migrateFromLegacy(hdrEnabled = true, legacyFormatOrdinal = 1)
        )
        assertEquals(
            FableSolExportColorMode.HLG,
            FableSolExportColorMode.migrateFromLegacy(hdrEnabled = true, legacyFormatOrdinal = 2)
        )
        assertEquals(
            FableSolExportColorMode.HDR10_PLUS,
            FableSolExportColorMode.migrateFromLegacy(hdrEnabled = true, legacyFormatOrdinal = 3)
        )
        for (dolby in 4..6) {
            assertEquals(
                FableSolExportColorMode.DOLBY_VISION_84,
                FableSolExportColorMode.migrateFromLegacy(
                    hdrEnabled = true, legacyFormatOrdinal = dolby
                )
            )
        }
        // 编码器族的旧序号同样按声明顺序迁移。
        assertEquals(
            FableSolExportOptions.CodecPreference.AV1,
            FableSolExportOptions.CodecPreference.fromLegacyOrdinal(2)
        )
        assertEquals(
            FableSolExportOptions.CodecPreference.AUTO,
            FableSolExportOptions.CodecPreference.fromLegacyOrdinal(99)
        )
    }

    /**
     * **显式 HDR 格式是严格请求（D106）。**
     *
     * 只穷尽该格式内部的候选，全部失败后结束任务；不切换其它 HDR 格式，也不发布 SDR。
     * 只有"HDR 自动"与两种 SDR 模式才允许最终产出 SDR。
     */
    @Test
    fun explicitHdrModesNeverAllowAnSdrResult() {
        for (mode in FableSolExportColorMode.entries) {
            when {
                mode.isSdr -> {
                    assertTrue(mode.allowsSdrResult)
                    assertFalse(mode.requestsHdr)
                }
                mode.automaticHdr -> {
                    assertTrue(mode.allowsSdrResult)
                    assertTrue(mode.requestsHdr)
                    assertNull(mode.explicitFormat)
                }
                else -> {
                    assertFalse(mode.allowsSdrResult)
                    assertTrue(mode.isExplicitHdr)
                }
            }
        }
        // 每一种可导出的 HDR 格式都必须有对应的显式模式，否则界面上摆得出、请求不出来。
        assertEquals(
            FableSolExportHdrFormat.AUTO_ORDER.toSet(),
            FableSolExportColorMode.entries.mapNotNull { it.explicitFormat }.toSet()
        )
    }

    /**
     * **自动档不读取隐藏的"名义范围"历史值（D137）。**
     *
     * 信号范围是显式 HLG 系格式的专属设置。"自动"最终落到 HLG 或杜比视界 8.4 时固定采用
     * 自动增强，否则一个当前不可见的历史选项会让自动档静默放弃可用的 HLG 色容积。
     */
    @Test
    fun automaticFormatsIgnoreTheHiddenNominalRangePreference() {
        val nominalRequest = options(
            colorMode = FableSolExportColorMode.HDR_AUTO,
            hlgSignalRange = FableSolExportHlgSignalRange.NOMINAL
        )
        assertEquals(
            FableSolExportHlgSignalRange.AUTO_ENHANCED,
            nominalRequest.effectiveHlgSignalRange(FableSolExportHdrFormat.HLG)
        )
        assertEquals(
            FableSolExportHlgSignalRange.AUTO_ENHANCED,
            nominalRequest.effectiveHlgSignalRange(FableSolExportHdrFormat.DOLBY_VISION_84)
        )
        // 显式选择该格式时才读用户的选项。
        val explicitHlg = options(
            colorMode = FableSolExportColorMode.HLG,
            hlgSignalRange = FableSolExportHlgSignalRange.NOMINAL
        )
        assertEquals(
            FableSolExportHlgSignalRange.NOMINAL,
            explicitHlg.effectiveHlgSignalRange(FableSolExportHdrFormat.HLG)
        )
        // 显式 HLG 的选择不会漏到杜比视界 8.4 上：那是另一个显式格式。
        assertEquals(
            FableSolExportHlgSignalRange.AUTO_ENHANCED,
            explicitHlg.effectiveHlgSignalRange(FableSolExportHdrFormat.DOLBY_VISION_84)
        )
        // PQ 系与 SDR 根本没有这一项。
        assertNull(explicitHlg.effectiveHlgSignalRange(FableSolExportHdrFormat.HDR10))
        assertNull(explicitHlg.effectiveHlgSignalRange(null))
    }

    /** 自动位深先穷尽 10-bit 再进 8-bit；严格位深不跨位深后备（D160）。 */
    @Test
    fun strictBitDepthNeverCrossesToTheOtherDepth() {
        assertEquals(listOf(true, false), FableSolExportSdrBitDepth.AUTO.candidateOrder)
        assertEquals(listOf(true), FableSolExportSdrBitDepth.TEN_BIT.candidateOrder)
        assertEquals(listOf(false), FableSolExportSdrBitDepth.EIGHT_BIT.candidateOrder)
        assertFalse(FableSolExportSdrBitDepth.AUTO.isStrict)
        assertTrue(FableSolExportSdrBitDepth.TEN_BIT.isStrict)
        assertTrue(FableSolExportSdrBitDepth.EIGHT_BIT.isStrict)
    }

    /**
     * **CQ 自定义原值按实际编码器路径分别保存（D146）。**
     *
     * Android 的 `KEY_QUALITY` 是各厂商自行映射的一段区间，同一个数字在两个编码器上不表示
     * 同一件事；MIME/Profile 与输入路径不同也一样。
     */
    @Test
    fun qualitySignatureSeparatesEveryMateriallyDifferentPath() {
        val base = fableSolExportQualitySignature(
            codecName = "c2.qti.hevc.encoder",
            format = FableSolExportHdrFormat.HDR10,
            tenBit = true,
            inputPath = FableSolExportInputPath.SURFACE
        )
        assertNotEquals(
            base,
            fableSolExportQualitySignature(
                codecName = "c2.qti.avc.encoder",
                format = FableSolExportHdrFormat.HDR10,
                tenBit = true,
                inputPath = FableSolExportInputPath.SURFACE
            )
        )
        assertNotEquals(
            base,
            fableSolExportQualitySignature(
                codecName = "c2.qti.hevc.encoder",
                format = FableSolExportHdrFormat.HDR10_PLUS,
                tenBit = true,
                inputPath = FableSolExportInputPath.SURFACE
            )
        )
        assertNotEquals(
            base,
            fableSolExportQualitySignature(
                codecName = "c2.qti.hevc.encoder",
                format = FableSolExportHdrFormat.HDR10,
                tenBit = false,
                inputPath = FableSolExportInputPath.SURFACE
            )
        )
        assertNotEquals(
            base,
            fableSolExportQualitySignature(
                codecName = "c2.qti.hevc.encoder",
                format = FableSolExportHdrFormat.HDR10,
                tenBit = true,
                inputPath = FableSolExportInputPath.APP_P010
            )
        )
    }

    /**
     * **文件名从实际候选生成**，三种 SDR 语义各自可分（D81）。
     *
     * 稳定标签同时是判断"这次导出到底出了什么"的依据，不能与申请值混用。
     */
    @Test
    fun fileTagComesFromTheResolvedCandidate() {
        assertEquals(
            "SDR",
            candidate(FableSolExportColorMode.SDR_NATIVE, null, null).fileTag
        )
        assertEquals(
            "SDR-TM",
            candidate(
                FableSolExportColorMode.SDR_TONE_MAPPED,
                FableSolExportSdrMapping.STABLE,
                null
            ).fileTag
        )
        assertEquals(
            "SDR-DTM",
            candidate(
                FableSolExportColorMode.SDR_TONE_MAPPED,
                FableSolExportSdrMapping.DYNAMIC,
                null
            ).fileTag
        )
        // 自动档最终落到某种 HDR 格式时，文件名写的是**实际**格式而不是"自动"。
        assertEquals(
            "HDR10Plus",
            candidate(
                FableSolExportColorMode.HDR_AUTO, null, FableSolExportHdrFormat.HDR10_PLUS
            ).fileTag
        )
        assertEquals(
            "DV84",
            candidate(
                FableSolExportColorMode.HDR_AUTO, null, FableSolExportHdrFormat.DOLBY_VISION_84
            ).fileTag
        )
    }

    /** HDR10+ 走应用自有 P010，其余走 Surface；输入路径进候选签名与诊断。 */
    @Test
    fun inputPathFollowsTheFormatNotTheRequest() {
        assertEquals(
            FableSolExportInputPath.APP_P010,
            candidate(
                FableSolExportColorMode.HDR10_PLUS, null, FableSolExportHdrFormat.HDR10_PLUS
            ).inputPath
        )
        for (format in FableSolExportHdrFormat.entries) {
            if (format == FableSolExportHdrFormat.HDR10_PLUS) continue
            assertEquals(
                FableSolExportInputPath.SURFACE,
                candidate(FableSolExportColorMode.HDR_AUTO, null, format).inputPath
            )
        }
    }

    private fun <T> assertStableIds(
        values: List<T>,
        parse: (String) -> T
    ) where T : FableSolExportStableChoice {
        assertEquals(values.size, values.map { it.stableId }.toSet().size)
        for (value in values) {
            assertEquals(value, parse(value.stableId))
        }
    }

    private fun options(
        colorMode: FableSolExportColorMode,
        hlgSignalRange: FableSolExportHlgSignalRange
    ) = FableSolExportOptions(
        frameRate = FableSolExportOptions.FRAME_RATE_HIGH,
        colorMode = colorMode,
        sdrMapping = FableSolExportSdrMapping.DEFAULT,
        sdrBitDepth = FableSolExportSdrBitDepth.AUTO,
        hlgSignalRange = hlgSignalRange,
        rateControl = FableSolExportRateControl.CONSTANT_QUALITY,
        qualityBySignature = emptyMap(),
        pendingLegacyQuality = null,
        bitrateMbps = FableSolExportOptions.DEFAULT_BITRATE_MBPS,
        keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS
    )

    private fun candidate(
        colorMode: FableSolExportColorMode,
        sdrMapping: FableSolExportSdrMapping?,
        hdrFormat: FableSolExportHdrFormat?
    ) = FableSolExportResolvedCandidate(
        colorMode = colorMode,
        sdrMapping = sdrMapping,
        sdrRender = FableSolExportSdrRender.of(
            colorMode = colorMode,
            mapping = sdrMapping ?: FableSolExportSdrMapping.DEFAULT,
            hdrResult = hdrFormat != null
        ),
        hdrFormat = hdrFormat,
        transfer = hdrFormat?.transfer ?: FableSolExportTransfer.SDR,
        widthPx = 1024,
        heightPx = 1472,
        frameRate = 120,
        tenBit = hdrFormat != null,
        family = FableSolExportCodecFamily.HEVC,
        codecName = "c2.qti.hevc.encoder",
        softwareOnly = false,
        profile = 2,
        level = 1,
        highTier = false,
        rateControl = FableSolExportRateControlForm.CONSTANT_QUALITY,
        qualityValue = 100,
        bitrateBps = null,
        inputPath = if (hdrFormat?.usesByteBufferInput == true) {
            FableSolExportInputPath.APP_P010
        } else {
            FableSolExportInputPath.SURFACE
        },
        hlgSignalRange = null,
        keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS,
        dither = FableSolExportDither.NONE,
        bFramesRequested = false,
        highComplexityRequested = true,
        qpGuardRequested = false,
        pqWhiteNits = 0.0,
        peakNits = 0.0,
        highlightStartPercent = 0
    )
}
