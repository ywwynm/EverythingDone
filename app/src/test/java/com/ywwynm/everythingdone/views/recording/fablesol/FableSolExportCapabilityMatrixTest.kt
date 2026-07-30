package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可行组合表是置灰规则的唯一依据，所以它的查询语义必须钉死：格式轴上 `null` 是 SDR 而不是
 * “不限”，位深是独立的一轴（D160），帧率是严格约束（D179），软件实现是同规格内的最后
 * 候选而不是被排除项（D53 修订）。
 */
class FableSolExportCapabilityMatrixTest {

    private val hardwareHevc = FableSolExportCombinationOutcome(
        codecName = "c2.qti.hevc.encoder", softwareOnly = false, failure = null
    )
    private val softwareAv1 = FableSolExportCombinationOutcome(
        codecName = "c2.android.av1.encoder", softwareOnly = true, failure = null
    )

    private fun failed(detail: String) = FableSolExportCombinationOutcome(
        codecName = null,
        softwareOnly = false,
        failure = FableSolExportFailure(
            code = FableSolExportFailure.Code.ENCODER_ERROR, detail = detail
        )
    )

    @Test
    fun rateControlAvailabilityBelongsToTheSameExactCombination() {
        val matrix = FableSolExportCapabilityMatrix.Builder().apply {
            // HDR10 + HEVC + 120 fps 只能使用目标码率。
            put(
                FableSolExportHdrFormat.HDR10,
                FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH,
                true,
                FableSolExportRateControl.CONSTANT_QUALITY,
                failed("CQ unavailable")
            )
            put(
                FableSolExportHdrFormat.HDR10,
                FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH,
                true,
                FableSolExportRateControl.TARGET_BITRATE,
                hardwareHevc.copy(
                    rateControlFormId =
                        FableSolExportRateControlForm.VARIABLE_BITRATE.stableId
                )
            )
            // 另一条 AV1 60 fps 路径支持 CQ，不能据此把上面的 HEVC 120 fps 判成 CQ 可用。
            put(
                FableSolExportHdrFormat.HDR10,
                FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_BASE,
                true,
                FableSolExportRateControl.CONSTANT_QUALITY,
                softwareAv1.copy(
                    rateControlFormId =
                        FableSolExportRateControlForm.CONSTANT_QUALITY.stableId,
                    qualityLower = 0,
                    qualityUpper = 100
                )
            )
        }.build().let { FableSolExportCapabilityMatrix.decode(it.encode()) }

        assertNull(
            matrix.resolve(
                colorMode = FableSolExportColorMode.HDR10,
                codec = FableSolExportOptions.CodecPreference.HEVC,
                frameRate = FableSolExportOptions.FRAME_RATE_HIGH,
                sdrBitDepth = FableSolExportSdrBitDepth.AUTO,
                rateControl = FableSolExportRateControl.CONSTANT_QUALITY
            )
        )
        assertEquals(
            FableSolExportCodecFamily.HEVC,
            matrix.resolve(
                colorMode = FableSolExportColorMode.HDR10,
                codec = FableSolExportOptions.CodecPreference.HEVC,
                frameRate = FableSolExportOptions.FRAME_RATE_HIGH,
                sdrBitDepth = FableSolExportSdrBitDepth.AUTO,
                rateControl = FableSolExportRateControl.TARGET_BITRATE
            )?.family
        )
        val cqAv1 = matrix.resolve(
            colorMode = FableSolExportColorMode.HDR10,
            codec = FableSolExportOptions.CodecPreference.AV1,
            frameRate = FableSolExportOptions.FRAME_RATE_BASE,
            sdrBitDepth = FableSolExportSdrBitDepth.AUTO,
            rateControl = FableSolExportRateControl.CONSTANT_QUALITY
        )
        assertEquals(0, cqAv1?.outcome?.qualityLower)
        assertEquals(100, cqAv1?.outcome?.qualityUpper)
    }

    /**
     * 三星 Z Fold4 的实测形状：HDR10 只有软件 AV1 能编，且只到 60fps；SDR 有硬件 HEVC。
     * 该机没有任何可用的 10 位硬件输入通路，8 位一切正常。
     */
    private fun foldLikeMatrix(): FableSolExportCapabilityMatrix =
        FableSolExportCapabilityMatrix.Builder().apply {
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH, true, failed("IllegalStateException")
            )
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_BASE, true, failed("IllegalStateException")
            )
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH, true,
                FableSolExportCombinationOutcome(
                    codecName = null, softwareOnly = false,
                    failure = FableSolExportFailure.NO_CANDIDATE
                )
            )
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_BASE, true, softwareAv1
            )
            for (rate in FableSolExportCapabilityMatrix.FRAME_RATES) {
                put(null, FableSolExportCodecFamily.HEVC, rate, true, failed("no samples"))
                put(null, FableSolExportCodecFamily.HEVC, rate, false, hardwareHevc)
            }
        }.build()

    /**
     * 三星 Z Fold4 回归：HDR10 + AV1 只在 60 fps 成立，120 fps 只有 SDR。格式“自动”在
     * 120 fps 下必须解析为 SDR，不能借用 60 fps 的 HDR10 结论。
     */
    @Test
    fun automaticFormatUsesTheExactSelectedFrameRate() {
        val matrix = foldLikeMatrix()

        assertNull(
            matrix.autoFormat(
                family = null,
                frameRate = FableSolExportOptions.FRAME_RATE_HIGH
            )
        )
        assertEquals(
            FableSolExportHdrFormat.HDR10,
            matrix.autoFormat(
                family = null,
                frameRate = FableSolExportOptions.FRAME_RATE_BASE
            )
        )

        val highRate = matrix.resolve(
            colorMode = FableSolExportColorMode.HDR_AUTO,
            codec = FableSolExportOptions.CodecPreference.AUTO,
            frameRate = FableSolExportOptions.FRAME_RATE_HIGH,
            sdrBitDepth = FableSolExportSdrBitDepth.AUTO
        )
        assertNull(highRate?.format)
        assertEquals(FableSolExportCodecFamily.HEVC, highRate?.family)
        assertEquals(false, highRate?.tenBit)
        assertEquals(false, highRate?.outcome?.softwareOnly)

        val baseRate = matrix.resolve(
            colorMode = FableSolExportColorMode.HDR_AUTO,
            codec = FableSolExportOptions.CodecPreference.AUTO,
            frameRate = FableSolExportOptions.FRAME_RATE_BASE,
            sdrBitDepth = FableSolExportSdrBitDepth.AUTO
        )
        assertEquals(FableSolExportHdrFormat.HDR10, baseRate?.format)
        assertEquals(FableSolExportCodecFamily.AV1, baseRate?.family)
        assertEquals(true, baseRate?.tenBit)
        assertEquals(true, baseRate?.outcome?.softwareOnly)
    }

    /**
     * **软件实现是同规格内的最后回退，不是被排除项。**
     *
     * D53 原先规定自动档完全不使用软件编码器；三星 Z Fold4 的实测（D58）证明该机的 HDR 只能
     * 由软件 AV1 承担，据此撤销了完全排除规则。自动档因此不得仅仅因为路径是软件实现就整体
     * 落到 SDR，速度与发热的代价由信息栏说明。
     */
    @Test
    fun automaticSelectionFallsBackToSoftwareInsteadOfDroppingTheFormat() {
        val matrix = foldLikeMatrix()
        assertEquals(
            FableSolExportCodecFamily.AV1,
            matrix.autoFamily(
                FableSolExportHdrFormat.HDR10, FableSolExportOptions.FRAME_RATE_BASE
            )
        )
        assertEquals(
            FableSolExportCodecFamily.HEVC,
            matrix.autoFamily(null, FableSolExportOptions.FRAME_RATE_HIGH)
        )
        assertTrue(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter
                    .Exactly(FableSolExportHdrFormat.HDR10),
                family = FableSolExportCodecFamily.AV1
            )
        )
        // 但"只看硬件"这个查询本身仍要能回答：诊断行要分得清硬件落点与软件落点。
        assertFalse(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter
                    .Exactly(FableSolExportHdrFormat.HDR10),
                family = FableSolExportCodecFamily.AV1,
                allowSoftware = false
            )
        )
    }

    /** 格式轴上 `null` 是 SDR，不是通配——两者混淆会让 SDR 把 HDR 的结论也算进去。 */
    @Test
    fun sdrIsAFormatValueNotAWildcard() {
        val matrix = foldLikeMatrix()
        assertTrue(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.Exactly(null)
            )
        )
        assertFalse(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.Exactly(null),
                family = FableSolExportCodecFamily.AV1
            )
        )
        assertTrue(
            matrix.hasUsable(format = FableSolExportCapabilityMatrix.FormatFilter.AnyHdr)
        )
        assertFalse(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.AnyHdr,
                allowSoftware = false
            )
        )
    }

    /**
     * **位深是独立的一轴（D160）。**
     *
     * 严格 10-bit 与严格 8-bit 都是用户可以明确要求的规格，因此"这台机器的 SDR 能用 HEVC"
     * 不足以回答"它的 10-bit SDR 能不能用 HEVC"：三星 Z Fold4 上 8 位通过、10 位全灭。
     */
    @Test
    fun bitDepthIsAnIndependentAxis() {
        val matrix = foldLikeMatrix()
        assertFalse(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.Exactly(null),
                tenBit = true
            )
        )
        assertTrue(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.Exactly(null),
                tenBit = false
            )
        )
        // 10 位优先：bestOutcome 先看 10 位，不成立才给 8 位的结论。
        val best = matrix.bestOutcome(
            null, FableSolExportCodecFamily.HEVC, FableSolExportOptions.FRAME_RATE_HIGH
        )
        assertEquals(false, best?.first)
        assertEquals("c2.qti.hevc.encoder", best?.second?.codecName)
    }

    /** 设备能力报告可以查询最高可用帧率；该方法不得用于修改用户选择的严格帧率。 */
    @Test
    fun bestFrameRateReportsCapabilityWithoutChangingTheRequestedRate() {
        val matrix = foldLikeMatrix()
        assertEquals(
            FableSolExportOptions.FRAME_RATE_BASE,
            matrix.bestFrameRate(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.AV1
            )
        )
        assertEquals(
            FableSolExportOptions.FRAME_RATE_HIGH,
            matrix.bestFrameRate(null, FableSolExportCodecFamily.HEVC)
        )
        assertNull(
            matrix.bestFrameRate(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.HEVC
            )
        )
    }

    /** 缓存要连整张表一起存，否则命中缓存时置灰规则就没有依据了。 */
    @Test
    fun encodedMatrixRoundTripsThroughTheCache() {
        val original = foldLikeMatrix()
        val restored = FableSolExportCapabilityMatrix.decode(original.encode())
        for (format in listOf(FableSolExportHdrFormat.HDR10, null)) {
            for (family in FableSolExportCodecFamily.entries) {
                for (rate in FableSolExportCapabilityMatrix.FRAME_RATES) {
                    for (tenBit in FableSolExportCapabilityMatrix.BIT_DEPTHS) {
                        assertEquals(
                            original.outcome(format, family, rate, tenBit),
                            restored.outcome(format, family, rate, tenBit)
                        )
                    }
                }
            }
        }
        assertTrue(FableSolExportCapabilityMatrix.decode(null).isEmpty)
        assertTrue(FableSolExportCapabilityMatrix.decode("").isEmpty)
    }

    /**
     * 失败原因保存的是**结构**加厂商原文，不是拼好的句子：换系统语言之后同一份缓存仍要能
     * 生成当前语言的说明。原文里若混进分隔符也不能把整张表解析歪。
     */
    @Test
    fun failuresAreStoredStructurallyAndSeparatorsCannotCorruptTheTable() {
        val noisy = FableSolExportFailure(
            code = FableSolExportFailure.Code.TRANSFER_MISMATCH,
            detail = "boom\u0001still\u0002same\u0003row",
            requested = 7,
            actual = 3
        )
        val matrix = FableSolExportCapabilityMatrix.Builder().apply {
            put(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH, true,
                FableSolExportCombinationOutcome(
                    codecName = null, softwareOnly = false, failure = noisy
                )
            )
            put(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH, true, hardwareHevc
            )
        }.build()
        val restored = FableSolExportCapabilityMatrix.decode(matrix.encode())
        val failure = restored.outcome(
            FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.HEVC,
            FableSolExportOptions.FRAME_RATE_HIGH, true
        )?.failure
        assertEquals(FableSolExportFailure.Code.TRANSFER_MISMATCH, failure?.code)
        assertEquals("boom still same row", failure?.detail)
        assertEquals(7, failure?.requested)
        assertEquals(3, failure?.actual)
        assertTrue(
            restored.outcome(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH, true
            )?.usable == true
        )
    }

    /**
     * OPPO 的实测形状：HDR10+ 只有 HEVC 能编，HDR10 与 HLG 两条路都成立。
     */
    private fun oppoLikeMatrix(): FableSolExportCapabilityMatrix =
        FableSolExportCapabilityMatrix.Builder().apply {
            for (rate in FableSolExportCapabilityMatrix.FRAME_RATES) {
                put(
                    FableSolExportHdrFormat.HDR10_PLUS, FableSolExportCodecFamily.HEVC,
                    rate, true, hardwareHevc
                )
                put(
                    FableSolExportHdrFormat.HDR10_PLUS, FableSolExportCodecFamily.AV1,
                    rate, true,
                    FableSolExportCombinationOutcome(
                        codecName = null, softwareOnly = false,
                        failure = FableSolExportFailure.NO_CANDIDATE
                    )
                )
                for (format in listOf(
                    FableSolExportHdrFormat.HDR10, FableSolExportHdrFormat.HLG
                )) {
                    put(format, FableSolExportCodecFamily.HEVC, rate, true, hardwareHevc)
                    put(
                        format, FableSolExportCodecFamily.AV1, rate, true,
                        FableSolExportCombinationOutcome(
                            codecName = "c2.qti.av1.encoder", softwareOnly = false,
                            failure = null
                        )
                    )
                }
            }
        }.build()

    /**
     * **「自动」的落点必须带上编码器这条约束。**
     *
     * OPPO 上把编码器钉成 AV1 之后，格式胶囊已经正确地只留下 HDR10 与 HLG，说明文字却仍然
     * 写着「当前为 HDR10+」——那是"编码器也取自动"时的答案，而 AV1 根本编不出 HDR10+
     * （2026-07-27）。
     */
    @Test
    fun automaticFormatIsResolvedUnderThePinnedCodec() {
        val matrix = oppoLikeMatrix()
        assertEquals(
            FableSolExportHdrFormat.HDR10_PLUS,
            matrix.autoFormat(
                family = null,
                frameRate = FableSolExportOptions.FRAME_RATE_HIGH
            )
        )
        assertEquals(
            FableSolExportHdrFormat.HDR10_PLUS,
            matrix.autoFormat(
                FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH
            )
        )
        // AUTO_ORDER 里 HDR10+ 在 HDR10 之前，AV1 编不出它，于是应当落到 HDR10。
        assertEquals(
            FableSolExportHdrFormat.HDR10,
            matrix.autoFormat(
                FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH
            )
        )
        // H.264 在任何 HDR 格式下都不成立，「自动」只能落到 SDR。
        assertNull(
            matrix.autoFormat(
                FableSolExportCodecFamily.AVC,
                FableSolExportOptions.FRAME_RATE_HIGH
            )
        )
    }

    /** 只有软件 AV1 能编 HDR10 时，自动档仍应落到它，而不是整体退回 SDR（D53 修订、D58）。 */
    @Test
    fun automaticFormatKeepsHdrEvenWhenOnlySoftwareCanEncodeIt() {
        val matrix = foldLikeMatrix()
        assertEquals(
            FableSolExportHdrFormat.HDR10,
            matrix.autoFormat(
                family = null,
                frameRate = FableSolExportOptions.FRAME_RATE_BASE
            )
        )
        assertEquals(
            FableSolExportHdrFormat.HDR10,
            matrix.autoFormat(
                FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_BASE
            )
        )
    }

    /**
     * 能力报告要列出**全部**可用组合。OPPO 上 HLG 的 HEVC 与 AV1 两条路都成立，报告却只
     * 写了 HEVC（2026-07-27）。
     */
    @Test
    fun reachListsEveryUsableCodecNotJustTheFirst() {
        val reach = oppoLikeMatrix().reach(FableSolExportHdrFormat.HLG)
        assertEquals(
            listOf(FableSolExportCodecFamily.HEVC, FableSolExportCodecFamily.AV1),
            reach.map { it.family }
        )
        assertTrue(reach.all { it.frameRate == FableSolExportOptions.FRAME_RATE_HIGH })
        assertTrue(reach.none { it.softwareOnly })

        // 不成立的编码器不进列表；帧率取各自能达到的最高档。
        val fold = foldLikeMatrix().reach(FableSolExportHdrFormat.HDR10)
        assertEquals(listOf(FableSolExportCodecFamily.AV1), fold.map { it.family })
        assertEquals(FableSolExportOptions.FRAME_RATE_BASE, fold.single().frameRate)
        assertTrue(fold.single().softwareOnly)
    }

    /**
     * **SDR 阶梯的 HEVC 有 10 位与 8 位两档，10 位编不出来时必须落到 8 位，而不是整族放弃。**
     *
     * 华为平板上 10 位表面拿不到带 recordable 的 EGL config，10 位一档一个样本都不产出；此前
     * 它被判为"通过"，于是 8 位那一档根本没机会被尝试，产物是 0 字节。位深因此要进结论并显示
     * 给用户：10 位画质更好，但分享兼容性明显差于 8 位（2026-07-28）。
     */
    @Test
    fun sdrHevcFallsBackFromTenBitToEightBit() {
        val entries = FableSolExportTier.ladderFor(null)
            .filter { it.family == FableSolExportCodecFamily.HEVC }
        assertEquals(2, entries.size)
        // 顺序即优先级：先试 10 位，编不出来才退 8 位。
        assertFalse(entries[0].eightBit)
        assertTrue(entries[1].eightBit)

        val reach = foldLikeMatrix().reach(null)
            .single { it.family == FableSolExportCodecFamily.HEVC }
        assertFalse(reach.tenBit)
        assertEquals("HEVC 8-bit", reach.compactLabel)
    }

    /** 杜比视界换的是 MIME，编码器仍是那颗 HEVC；界面上不该为它单列一个编码器选项。 */
    @Test
    fun dolbyVisionBelongsToTheHevcFamily() {
        for (format in FableSolExportHdrFormat.entries) {
            val families = FableSolExportTier.familiesFor(format)
            if (format.isDolbyVision) {
                assertEquals(listOf(FableSolExportCodecFamily.HEVC), families)
            } else {
                assertTrue(families.contains(FableSolExportCodecFamily.HEVC))
                assertTrue(families.contains(FableSolExportCodecFamily.AV1))
            }
            assertFalse(families.contains(FableSolExportCodecFamily.AVC))
        }
    }

    /**
     * SDR 那一列必须三个族齐全。AV1 此前只出现在 HDR 阶梯里，于是"用户想选 AV1"这件事在
     * 关掉 HDR 之后根本无从谈起；H.264 则反过来只在 SDR 成立。
     */
    @Test
    fun sdrLadderCoversEveryCodecFamily() {
        assertEquals(
            FableSolExportCodecFamily.entries.toList(),
            FableSolExportTier.familiesFor(null)
        )
    }

    /** HDR 一律 10-bit；H.264 没有 10 位档。位深轴的结构性存在性由此判定。 */
    @Test
    fun structuralBitDepthAvailabilityMatchesTheLadders() {
        for (format in FableSolExportHdrFormat.entries) {
            assertTrue(FableSolExportTier.supportsBitDepth(format, tenBit = true))
            assertFalse(FableSolExportTier.supportsBitDepth(format, tenBit = false))
        }
        assertTrue(FableSolExportTier.supportsBitDepth(null, tenBit = true))
        assertTrue(FableSolExportTier.supportsBitDepth(null, tenBit = false))
    }
}
