package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可行组合表是置灰规则的唯一依据，所以它的查询语义必须钉死：格式轴上 `null` 是 SDR 而不是
 * "不限"，软件编码器不参与自动档，帧率是上限而不是硬约束。
 */
class FableSolExportCapabilityMatrixTest {

    private val hardwareHevc = FableSolExportCombinationOutcome(
        codecName = "c2.qti.hevc.encoder", softwareOnly = false, failure = null
    )
    private val softwareAv1 = FableSolExportCombinationOutcome(
        codecName = "c2.android.av1.encoder", softwareOnly = true, failure = null
    )
    private fun failed(reason: String) = FableSolExportCombinationOutcome(
        codecName = null, softwareOnly = false, failure = reason
    )

    /**
     * 三星 Z Fold4 的实测形状：HDR10 只有软件 AV1 能编，且只到 60fps；SDR 有硬件 HEVC。
     * 这台机器上"HDR 导出能力：可用"读起来像是一切正常，实际落到的是软件 AV1 的 60fps。
     */
    private fun foldLikeMatrix(): FableSolExportCapabilityMatrix =
        FableSolExportCapabilityMatrix.Builder().apply {
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH, failed("IllegalStateException")
            )
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_BASE, failed("IllegalStateException")
            )
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH, failed("没有候选")
            )
            put(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_BASE, softwareAv1
            )
            put(
                null, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH, hardwareHevc
            )
            put(
                null, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_BASE, hardwareHevc
            )
        }.build()

    /**
     * **自动档不使用软件编码器。** 本项目的画布接近两百万像素，软件编码与硬件编码耗时差一到
     * 两个数量级，让它作为静默退路等于在用户毫不知情的情况下把一次导出拖长几十倍。
     */
    @Test
    fun automaticSelectionSkipsSoftwareEncoders() {
        val matrix = foldLikeMatrix()
        assertNull(
            matrix.autoFamily(
                FableSolExportHdrFormat.HDR10, FableSolExportOptions.FRAME_RATE_BASE
            )
        )
        assertEquals(
            FableSolExportCodecFamily.HEVC,
            matrix.autoFamily(null, FableSolExportOptions.FRAME_RATE_HIGH)
        )
        // 但用户显式选 AV1 时它必须是可用的：界面要摆出这个选项，只是标明是软件编码。
        assertTrue(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter
                    .Exactly(FableSolExportHdrFormat.HDR10),
                family = FableSolExportCodecFamily.AV1,
                allowSoftware = true
            )
        )
        assertFalse(
            matrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter
                    .Exactly(FableSolExportHdrFormat.HDR10),
                family = FableSolExportCodecFamily.AV1,
                allowSoftware = false
            )
        )
    }

    /** 格式轴上 `null` 是 SDR，不是通配——两者混淆会让「关闭」把 HDR 的结论也算进去。 */
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

    /** 120fps 编不出来时必须解出 60fps，而不是"这一组合不可用"。 */
    @Test
    fun bestFrameRateFallsBackInsteadOfFailing() {
        val matrix = foldLikeMatrix()
        assertEquals(
            FableSolExportOptions.FRAME_RATE_BASE,
            matrix.bestFrameRate(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.AV1,
                allowSoftware = true
            )
        )
        assertEquals(
            FableSolExportOptions.FRAME_RATE_HIGH,
            matrix.bestFrameRate(null, FableSolExportCodecFamily.HEVC, allowSoftware = true)
        )
        assertNull(
            matrix.bestFrameRate(
                FableSolExportHdrFormat.HDR10, FableSolExportCodecFamily.HEVC,
                allowSoftware = true
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
                    assertEquals(
                        original.outcome(format, family, rate),
                        restored.outcome(format, family, rate)
                    )
                }
            }
        }
        assertTrue(FableSolExportCapabilityMatrix.decode(null).isEmpty)
        assertTrue(FableSolExportCapabilityMatrix.decode("").isEmpty)
    }

    /** 失败原因是任意异常文本，里面若混进分隔符会把整张表解析歪。 */
    @Test
    fun separatorsInsideAFailureReasonCannotCorruptTheTable() {
        val matrix = FableSolExportCapabilityMatrix.Builder().apply {
            put(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH,
                failed("boom\u0001still\u0002same row")
            )
            put(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH, hardwareHevc
            )
        }.build()
        val restored = FableSolExportCapabilityMatrix.decode(matrix.encode())
        assertEquals(
            "boom still same row",
            restored.outcome(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH
            )?.failure
        )
        assertTrue(
            restored.outcome(
                FableSolExportHdrFormat.HLG, FableSolExportCodecFamily.AV1,
                FableSolExportOptions.FRAME_RATE_HIGH
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
                    rate, hardwareHevc
                )
                put(
                    FableSolExportHdrFormat.HDR10_PLUS, FableSolExportCodecFamily.AV1,
                    rate, failed("没有候选")
                )
                for (format in listOf(
                    FableSolExportHdrFormat.HDR10, FableSolExportHdrFormat.HLG
                )) {
                    put(format, FableSolExportCodecFamily.HEVC, rate, hardwareHevc)
                    put(
                        format, FableSolExportCodecFamily.AV1, rate,
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
            matrix.autoFormat(family = null, allowSoftware = false)
        )
        assertEquals(
            FableSolExportHdrFormat.HDR10_PLUS,
            matrix.autoFormat(FableSolExportCodecFamily.HEVC, allowSoftware = true)
        )
        // AUTO_ORDER 里 HDR10+ 在 HDR10 之前，AV1 编不出它，于是应当落到 HDR10。
        assertEquals(
            FableSolExportHdrFormat.HDR10,
            matrix.autoFormat(FableSolExportCodecFamily.AV1, allowSoftware = true)
        )
        // H.264 在任何 HDR 格式下都不成立，「自动」只能落到 SDR。
        assertNull(matrix.autoFormat(FableSolExportCodecFamily.AVC, allowSoftware = true))
    }

    /** 只有硬件 AV1 能编 HDR10 时，自动档仍可用它；软件实现才是被排除的那一类。 */
    @Test
    fun automaticFormatUnderTheSoftwareRestriction() {
        val matrix = foldLikeMatrix()
        assertNull(matrix.autoFormat(family = null, allowSoftware = false))
        assertEquals(
            FableSolExportHdrFormat.HDR10,
            matrix.autoFormat(FableSolExportCodecFamily.AV1, allowSoftware = true)
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

        val matrix = FableSolExportCapabilityMatrix.Builder().apply {
            put(
                null, FableSolExportCodecFamily.HEVC,
                FableSolExportOptions.FRAME_RATE_HIGH,
                FableSolExportCombinationOutcome(
                    codecName = "OMX.hisi.video.encoder.hevc", softwareOnly = false,
                    failure = null, profileLabel = "HEVC Main SDR", tenBit = false
                )
            )
        }.build()
        val reach = matrix.reach(null).single { it.family == FableSolExportCodecFamily.HEVC }
        assertFalse(reach.tenBit)
        assertEquals("HEVC 8-bit", reach.compactLabel)
        // 位深要跨缓存保留，否则命中缓存时这一层信息就没了。
        assertEquals(
            reach.tenBit,
            FableSolExportCapabilityMatrix.decode(matrix.encode())
                .outcome(
                    null, FableSolExportCodecFamily.HEVC,
                    FableSolExportOptions.FRAME_RATE_HIGH
                )?.tenBit
        )
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
}
