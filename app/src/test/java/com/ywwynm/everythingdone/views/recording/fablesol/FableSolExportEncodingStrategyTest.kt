package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 7 的离线编码画质策略（fablesol-video-export D145～D152、D161、D163、D167、D168）。
 *
 * 这一批改的全是**申请给编码器的那份 `MediaFormat`**：码控形态、目标码率、Level/Tier、
 * B 帧、复杂度、QP 上限。它们既不改像素也不改元数据，错了不会有任何异常——只会让产物悄悄
 * 变小、变糊，或者被某些只支持较低 Level 的设备直接拒收。因此每个键都要有正反两面的用例。
 */
class FableSolExportEncodingStrategyTest {

    /** 本项目实际的导出画布之一（120 fps 下像素率约 2.03e8）。 */
    private val width = 1152
    private val height = 1472

    // ---- 自动码率模型（D147）----

    @Test
    fun autoBitrateScalesWithThePixelRateNotWithAFixedTable() {
        // 旧实现是两个写死的数（120 fps → 24 Mbps、60 fps → 14.4），与实际画幅无关。
        val at120 = auto(width, height, 120, FableSolExportCodecFamily.HEVC, true, true)
        val at60 = auto(width, height, 60, FableSolExportCodecFamily.HEVC, true, true)
        assertEquals(at120 / 2.0, at60.toDouble(), 1.0)

        val wide = auto(1344, height, 120, FableSolExportCodecFamily.HEVC, true, true)
        assertEquals(at120 * (1344.0 / width), wide.toDouble(), 1.0)
    }

    @Test
    fun familyAndSignalCoefficientsOrderAsDocumented() {
        val avc = auto(width, height, 120, FableSolExportCodecFamily.AVC, false, false)
        val hevc = auto(width, height, 120, FableSolExportCodecFamily.HEVC, false, false)
        val av1 = auto(width, height, 120, FableSolExportCodecFamily.AV1, false, false)
        // AV1 最省，AVC 最费；系数取的都是公开压缩率区间的保守端。
        assertTrue(av1 < hevc)
        assertTrue(hevc < avc)

        // 位深与信号是同一条轴上的两个乘数（D147）。
        val hdr10Bit = auto(width, height, 120, FableSolExportCodecFamily.HEVC, true, true)
        assertEquals(
            hevc * FableSolExportBitrateModel.FACTOR_TEN_BIT *
                FableSolExportBitrateModel.FACTOR_HDR,
            hdr10Bit.toDouble(),
            2.0
        )
    }

    @Test
    fun theDefaultCombinationStaysCloseToTheLongStandingTwentyFourMbps() {
        // 标定锚点：最大画布 1344×1472、120 fps 的 HEVC 10-bit HDR，与此前用了很久的
        // 24 Mbps 默认值基本持平——换算模型不该顺手把所有人的产物体积改一遍。
        val bitrate = auto(1344, height, 120, FableSolExportCodecFamily.HEVC, true, true)
        assertEquals(24_000_000.0, bitrate.toDouble(), 1_500_000.0)
        // 另一个锚点：YouTube 对 1440p60 SDR 上传给出的 24 Mbps。
        val youtube = auto(2560, 1440, 60, FableSolExportCodecFamily.AVC, false, false)
        assertEquals(24_000_000.0, youtube.toDouble(), 2_000_000.0)
    }

    @Test
    fun customBitrateIsAbsoluteAndNeverScaled() {
        // 用户拖过滑杆之后就是绝对 Mbps，不再按 0.6 或任何比例随帧率缩放（D147）。
        assertEquals(30_000_000, FableSolExportBitrateModel.customBitrateBps(30f))
        assertEquals(
            FableSolExportBitrateModel.MIN_BITRATE_BPS,
            FableSolExportBitrateModel.customBitrateBps(0.1f)
        )
    }

    // ---- 最低充分 Level/Tier（D152、D168）----

    @Test
    fun hevcPicksTheLowestLevelThatCarriesTheActualPixelRate() {
        // 1152×1472 = 1_695_744 样本，×120 = 2.03e8 样本/秒。
        // MaxLumaPs：L4 就够；MaxLumaSr：L4 与 L4.1 都不够，L5 才够。
        val all = hevcLevels()
        val selection = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all,
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel5, selection?.level)
        assertFalse(selection!!.highTier)

        // 同一张画布 60 fps 时像素率减半，L4.1 就够了——Level 是解码要求，不是画质档位。
        val slower = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all,
            widthPx = width,
            heightPx = height,
            frameRate = 60,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel41, slower?.level)
    }

    @Test
    fun highTierOnlyAppearsWhenTheSameLevelMainTierCannotCarryTheBitrate() {
        val all = hevcLevels()
        // L5 Main Tier 的上限是 25 Mbps；40 Mbps 装不下，同档 High Tier（100 Mbps）可以。
        val high = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all,
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 40_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.HEVCHighTierLevel5, high?.level)
        assertTrue(high!!.highTier)

        // 编码器不广告 High Tier 时不得凭空使用它：升到下一档 Main Tier（L5.1，40 Mbps）。
        val mainOnly = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all.filter { it != CodecProfileLevel.HEVCHighTierLevel5 },
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 40_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel51, mainOnly?.level)
        assertFalse(mainOnly!!.highTier)
    }

    @Test
    fun qpGuardHeadroomIsPinnedAtTheLevelBitrateBoundary() {
        // D151 的 QP 保护允许实际码率超过目标，D152 因此要求定档吃下这段上浮：
        // 开保护时码率需求 ×1.25 再定档，边界回归把余量数值钉死在这里。
        assertEquals(1.25, FableSolExportLevel.QP_GUARD_BITRATE_HEADROOM, 0.0)
        val all = hevcLevels()
        // 60fps 像素率 L4.1 就够；20 Mbps ×1.25 = 25_000 kbps 超出 L4.1 Main Tier 的
        // 20_000 上限，必须抬到同档 High Tier（50_000），而不是留在被上浮击穿的 Main。
        val guarded = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all,
            widthPx = width,
            heightPx = height,
            frameRate = 60,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = false,
            qpGuard = true
        )
        assertEquals(CodecProfileLevel.HEVCHighTierLevel41, guarded?.level)
        assertTrue(guarded!!.highTier)

        // 16 Mbps ×1.25 = 20_000 kbps 恰好压线：`>=` 仍容纳，不得多抬一档。
        val boundary = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all,
            widthPx = width,
            heightPx = height,
            frameRate = 60,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 16_000_000,
            bFrames = false,
            qpGuard = true
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel41, boundary?.level)
        assertFalse(boundary!!.highTier)

        // 保护关闭时没有余量：20 Mbps 恰好卡在 L4.1 Main 上限，仍取最低充分档。
        val unguarded = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = all,
            widthPx = width,
            heightPx = height,
            frameRate = 60,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = false,
            qpGuard = false
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel41, unguarded?.level)
    }

    @Test
    fun constantQualityNeverRaisesTheLevelForAnUnknownBitrate() {
        // CQ 没有解析码率（D168）：只按尺寸与像素率取档、保持 Main Tier，接受实际码率名义
        // 超出该档上限。抬档会把约 1400p 的文件声明成更高级别，缩小可播放设备面。
        val cq = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = hevcLevels(),
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = null,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel5, cq?.level)
        assertFalse(cq!!.highTier)
    }

    @Test
    fun avcCountsMacroblocksAndHonoursTheHighProfileBitrateFactor() {
        // 72×92 = 6624 个宏块，×120 = 794_880 MB/s：L5（589_824）不够，L5.1（983_040）够。
        val levels = listOf(
            CodecProfileLevel.AVCLevel4,
            CodecProfileLevel.AVCLevel41,
            CodecProfileLevel.AVCLevel42,
            CodecProfileLevel.AVCLevel5,
            CodecProfileLevel.AVCLevel51,
            CodecProfileLevel.AVCLevel52
        )
        val selection = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.AVC,
            advertised = levels,
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.AVCProfileHigh,
            bitrateBps = 24_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.AVCLevel51, selection?.level)
        assertEquals(
            6624L,
            FableSolExportLevel.frameUnits(FableSolExportCodecFamily.AVC, width, height)
        )
        // High Profile 的 VCL 码率上限是基准的 1.25 倍；系数取小了会平白多抬一档。
        assertEquals(
            1.25,
            FableSolExportLevel.profileBitrateFactor(
                FableSolExportCodecFamily.AVC, CodecProfileLevel.AVCProfileHigh
            ),
            0.0
        )
        assertEquals(
            1.0,
            FableSolExportLevel.profileBitrateFactor(
                FableSolExportCodecFamily.HEVC, CodecProfileLevel.HEVCProfileMain10
            ),
            0.0
        )
    }

    @Test
    fun av1CountsLumaSamplesAndHasNoTierAxis() {
        val selection = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.AV1,
            advertised = listOf(
                CodecProfileLevel.AV1Level4,
                CodecProfileLevel.AV1Level41,
                CodecProfileLevel.AV1Level5,
                CodecProfileLevel.AV1Level51
            ),
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.AV1ProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.AV1Level5, selection?.level)
        // Android 的 AV1 常量不编码 Tier，因此这条轴永远为假。
        assertFalse(selection!!.highTier)
        assertEquals(
            width.toLong() * height,
            FableSolExportLevel.frameUnits(FableSolExportCodecFamily.AV1, width, height)
        )
    }

    @Test
    fun advertisedLevelsBelowTheRequirementFallBackToTheHighestAdvertised() {
        // 广告档位全都不够时保持既有行为（取最高档），由真实编码探测判定这个候选成不成立——
        // 在这里直接判失败等于替编码器下结论。
        val selection = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = listOf(
                CodecProfileLevel.HEVCMainTierLevel3,
                CodecProfileLevel.HEVCMainTierLevel31
            ),
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = false
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel31, selection?.level)
        // 一个档位都不广告时返回 null，调用方据此不写 KEY_LEVEL。
        assertNull(
            FableSolExportLevel.select(
                family = FableSolExportCodecFamily.HEVC,
                advertised = emptyList(),
                widthPx = width,
                heightPx = height,
                frameRate = 120,
                profile = CodecProfileLevel.HEVCProfileMain10,
                bitrateBps = null,
                bFrames = false
            )
        )
    }

    @Test
    fun referenceStructureEntersThroughTheDecodedPictureBuffer() {
        // B 帧要多一个重排缓冲（D152 的"参考结构"输入）。本项目的画布远小于任何一档的 DPB
        // 上限，所以这一项不会成为决定档位的那一条——但它必须真的参与，否则 D152 的输入清单
        // 就少了一项而没人看得出来。
        assertEquals(2, FableSolExportLevel.requiredDpbFrames(bFrames = false))
        assertEquals(4, FableSolExportLevel.requiredDpbFrames(bFrames = true))
        // H.265 A.4.2：图像不到 MaxLumaPs 的四分之一时可缓存 16 张（上限）。
        assertEquals(16, FableSolExportLevel.hevcMaxDpbSize(8_912_896, 1_695_744))
        // 图像正好占满该档时只剩 6 张。
        assertEquals(6, FableSolExportLevel.hevcMaxDpbSize(2_228_224, 2_228_224))
        val withB = FableSolExportLevel.select(
            family = FableSolExportCodecFamily.HEVC,
            advertised = hevcLevels(),
            widthPx = width,
            heightPx = height,
            frameRate = 120,
            profile = CodecProfileLevel.HEVCProfileMain10,
            bitrateBps = 20_000_000,
            bFrames = true
        )
        assertEquals(CodecProfileLevel.HEVCMainTierLevel5, withB?.level)
    }

    // ---- 码控形态（D145、D167）----

    @Test
    fun onlyTheCompatibilityFormCarriesABitrateAlongsideQuality() {
        // D145：默认不同时下发 KEY_QUALITY 与 KEY_BIT_RATE。
        assertFalse(FableSolExportRateControlForm.CONSTANT_QUALITY.carriesBitrate)
        assertTrue(
            FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT.carriesBitrate
        )
        assertTrue(FableSolExportRateControlForm.VARIABLE_BITRATE.carriesBitrate)
        assertTrue(FableSolExportRateControlForm.CONSTANT_BITRATE.carriesBitrate)
        // 两种 CQ 形态的用户可见模式完全相同：措辞不因兼容形态改变（D167）。
        assertEquals(
            FableSolExportRateControl.CONSTANT_QUALITY,
            FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT.userVisibleMode
        )
        assertTrue(
            FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT.isConstantQuality
        )
        // 稳定标识往返，缓存靠它区分形态。
        for (form in FableSolExportRateControlForm.entries) {
            assertEquals(form, FableSolExportRateControlForm.fromStableId(form.stableId))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun constantQualityNeverResolvesToVbrWhenTheExactTierDoesNotSupportCq() {
        val options = FableSolExportOptions(
            frameRate = FableSolExportOptions.FRAME_RATE_HIGH,
            colorMode = FableSolExportColorMode.HDR10,
            sdrMapping = FableSolExportSdrMapping.DEFAULT,
            sdrBitDepth = FableSolExportSdrBitDepth.AUTO,
            hlgSignalRange = FableSolExportHlgSignalRange.DEFAULT,
            rateControl = FableSolExportRateControl.CONSTANT_QUALITY,
            qualityBySignature = emptyMap(),
            pendingLegacyQuality = null,
            bitrateMbps = null,
            keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS
        )

        // 用户选择 CQ 后，不支持 CQ 的精确编码档位必须被候选层排除；若仍流入形态解析，
        // 应立即暴露契约错误，不能把用户意图静默解释成 VBR。
        FableSolExportRateControlForm.resolve(
            options,
            tier(FableSolExportCodecFamily.HEVC, CodecProfileLevel.HEVCProfileMain10)
        )
    }

    @Test
    fun capabilityMatrixRoundTripsTheRateControlForm() {
        val matrix = FableSolExportCapabilityMatrix.Builder().apply {
            put(
                FableSolExportHdrFormat.HDR10,
                FableSolExportCodecFamily.HEVC,
                120,
                true,
                FableSolExportCombinationOutcome(
                    codecName = "c2.qti.hevc.encoder",
                    softwareOnly = false,
                    failure = null,
                    rateControlFormId =
                        FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT.stableId
                )
            )
        }.build()
        val decoded = FableSolExportCapabilityMatrix.decode(matrix.encode())
        assertEquals(
            FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT,
            decoded.rateControlForm(
                FableSolExportHdrFormat.HDR10,
                FableSolExportCodecFamily.HEVC,
                120,
                true,
                "c2.qti.hevc.encoder"
            )
        )
        // 换了编码器实现就换了码流：不采纳上一个实现的结论。
        assertNull(
            decoded.rateControlForm(
                FableSolExportHdrFormat.HDR10,
                FableSolExportCodecFamily.HEVC,
                120,
                true,
                "c2.android.hevc.encoder"
            )
        )
    }

    // ---- 编码工具的适用范围（D148）----

    @Test
    fun bFramesApplyToHevcAndTheTwoAvcProfilesButNeverToAv1() {
        assertTrue(tier(FableSolExportCodecFamily.HEVC, 0).supportsBFrames)
        assertTrue(
            tier(FableSolExportCodecFamily.AVC, CodecProfileLevel.AVCProfileHigh)
                .supportsBFrames
        )
        assertTrue(
            tier(FableSolExportCodecFamily.AVC, CodecProfileLevel.AVCProfileMain)
                .supportsBFrames
        )
        // Baseline 的语法里就没有 B 片。
        assertFalse(
            tier(FableSolExportCodecFamily.AVC, CodecProfileLevel.AVCProfileBaseline)
                .supportsBFrames
        )
        // AV1 不套用 H.26x 的 B 帧概念，也就不下发 KEY_MAX_B_FRAMES。
        assertFalse(tier(FableSolExportCodecFamily.AV1, 0).supportsBFrames)
    }

    @Test
    fun aospAv1HdrMaximumQualityUsesTheMeasuredGradientProtection() {
        fun guard(
            codecName: String = FableSolExportCqQualityGuard.AOSP_AV1_ENCODER,
            hdr: Boolean = true,
            supportsQpBounds: Boolean = true,
            maximumQuality: Boolean = true
        ) = FableSolExportCqQualityGuard.maxQp(
            codecName = codecName,
            videoMime = MediaFormat.MIMETYPE_VIDEO_AV1,
            family = FableSolExportCodecFamily.AV1,
            softwareOnly = true,
            hdr = hdr,
            supportsQpBounds = supportsQpBounds,
            maximumQuality = maximumQuality
        )

        assertEquals(
            FableSolExportCqQualityGuard.AOSP_AV1_HDR_MAX_QP,
            guard()
        )
        // 用户降低质量值时不得由内部保护擅自覆盖其文件大小取舍。
        assertNull(guard(maximumQuality = false))
        // 厂商 AV1、SDR 与未声明 QP bounds 的实现没有同一份实测依据。
        assertNull(guard(codecName = "c2.vendor.av1.encoder"))
        assertNull(guard(hdr = false))
        assertNull(guard(supportsQpBounds = false))
    }

    // ---- 下发的那份 MediaFormat（D145～D152、D163、D167）----

    @Test
    fun encoderWritesEachKeyOnlyWhereItApplies() {
        val encoder = source("FableSolExportEncoder.kt")

        // 离线导出声明为非实时，且**绝不**设置 operating rate（D150）。
        assertTrue(encoder.contains("setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_NON_REALTIME)"))
        assertEquals(1, FableSolExportEncoder.PRIORITY_NON_REALTIME)
        // 判据落在**实际的写入调用**上，不能只搜键名：源码里那条注释正是在讲为什么不设它，
        // 按字面搜会逼着后来的人删掉注释才能过测试。
        assertFalse(
            Regex("set(Integer|Float)\\(\\s*MediaFormat\\.KEY_OPERATING_RATE")
                .containsMatchIn(encoder)
        )

        // 纯 CQ 不带码率；只有兼容形态才附码率提示（D145、D167）。
        assertTrue(
            encoder.contains(
                "if (form == FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT)"
            )
        )
        // 用户可配置的复杂帧保护只作用于 VBR（D151）。CQ 仅允许经过实机验证的
        // AOSP 软件 AV1 HDR 最高质量保护；CBR 不得附加任何质量约束。
        assertTrue(encoder.contains("options.complexFrameGuardEnabled && tier.supportsQpBounds"))
        assertTrue(encoder.contains("MediaFormat.KEY_VIDEO_QP_MAX, QP_MAX_GUARD"))
        assertEquals(40, FableSolExportEncoder.QP_MAX_GUARD)
        assertTrue(encoder.contains("FableSolExportCqQualityGuard.maxQp(tier, quality)"))

        // B 帧：API 29 起可请求，上限固定 1；关闭或初始化被拒后的重试（applyBFrames=false）
        // 显式写 0 而不是省略（D148）。
        assertTrue(encoder.contains("Build.VERSION.SDK_INT >= 29 && tier.supportsBFrames"))
        assertTrue(encoder.contains("if (options.bFramesEnabled && applyBFrames) 1 else 0"))
        // API 26～28 没有 KEY_MAX_B_FRAMES：按 D148 采用可用的低延迟约束。
        assertTrue(encoder.contains("setInteger(MediaFormat.KEY_LATENCY, 1)"))

        // 高复杂度：关闭时或探测阶梯确认被拒时（applyHighComplexity=false）**省略**这个键，
        // 保留厂商默认，而不是下发下限（D149）。
        assertTrue(encoder.contains("if (options.highComplexityEnabled && applyHighComplexity)"))
        assertTrue(encoder.contains("setInteger(MediaFormat.KEY_COMPLEXITY, it.upper)"))
        assertFalse(encoder.contains("complexityRange.lower"))

        // 关键帧间隔一律 setFloat；D163 已删除"旧系统整数秒兼容"分支，不得实现。
        assertTrue(
            encoder.contains("setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, options.keyframeIntervalSeconds)")
        )
        assertFalse(encoder.contains("KEY_I_FRAME_INTERVAL, options.keyframeIntervalSeconds.toInt()"))
        assertFalse(encoder.contains("setInteger(MediaFormat.KEY_I_FRAME_INTERVAL"))

        // 普通 AVC/HEVC/AV1 路径不再取编码器广告的最高档（D152）。
        assertTrue(encoder.contains("FableSolExportLevel.select("))
        // "取最高档"只剩杜比视界那一处兜底——它有自己的像素率阶梯，先按 D152 取刚好够用的
        // 一档，全都不够时才退到最高档。多出第二处就说明有路径绕开了最低充分 Level。
        assertEquals(
            1,
            Regex("advertised\\.maxByOrNull \\{ it\\.level \\}").findAll(encoder).count()
        )
        assertTrue(encoder.contains("FableSolExportHdrFormat.dolbyVisionLevel("))
    }

    @Test
    fun probeAndRealExportShareTheSameResolvedForm() {
        val capability = source("FableSolHdrExportCapability.kt")
        val exporter = source("FableSolVideoExporter.kt")
        // 探测走 D167 的阶梯，并把通过的那一种记进可行组合表。
        assertTrue(capability.contains("for (form in formLadder(options, tier))"))
        assertTrue(capability.contains("lastProbeRateControlForm = form"))
        assertTrue(capability.contains("rateControlFormId = rateControlForm?.stableId"))
        // 正式导出读**已经得出**的结论，不在关键路径上现探。
        assertTrue(exporter.contains("cachedRateControlForm(options, tier, frameRate)"))
        assertTrue(exporter.contains("cachedMatrix(context).rateControlForm("))
        // 探测与正式导出的候选生成用同一组码率与 B 帧输入（D51）。
        assertTrue(capability.contains("customBitrateMbps = options.bitrateMbps"))
        assertTrue(exporter.contains("customBitrateMbps = options.bitrateMbps"))
    }

    // ---- 辅助 ----

    private fun auto(
        widthPx: Int,
        heightPx: Int,
        frameRate: Int,
        family: FableSolExportCodecFamily,
        tenBit: Boolean,
        hdr: Boolean
    ): Int = FableSolExportBitrateModel.autoBitrateBps(
        widthPx, heightPx, frameRate, family, tenBit, hdr
    )

    private fun hevcLevels() = listOf(
        CodecProfileLevel.HEVCMainTierLevel4,
        CodecProfileLevel.HEVCHighTierLevel4,
        CodecProfileLevel.HEVCMainTierLevel41,
        CodecProfileLevel.HEVCHighTierLevel41,
        CodecProfileLevel.HEVCMainTierLevel5,
        CodecProfileLevel.HEVCHighTierLevel5,
        CodecProfileLevel.HEVCMainTierLevel51,
        CodecProfileLevel.HEVCHighTierLevel51,
        CodecProfileLevel.HEVCMainTierLevel52,
        CodecProfileLevel.HEVCHighTierLevel52
    )

    private fun tier(family: FableSolExportCodecFamily, profile: Int) = FableSolExportTier(
        codecName = "test.encoder",
        videoMime = "video/test",
        profile = profile,
        level = 0,
        transfer = FableSolExportTransfer.SDR,
        hdrFormat = null,
        family = family,
        softwareOnly = false,
        eightBit = true,
        supportsCbr = true,
        supportsVbr = true,
        qualityRange = null,
        bitrateRange = null,
        encodedWidthPx = width,
        encodedHeightPx = height,
        label = "test"
    )

    private fun source(name: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative =
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/$name"
        repeat(8) {
            val candidate = File(directory, relative)
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        throw AssertionError("找不到源文件 $name")
    }
}
