package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDR 三种成片语义的**整条通路**门禁（fablesol-video-export D62、D73、D77～D78、D81、D162）。
 *
 * 曲线本身的数学在 [FableSolExportSdrToneMapTest] 里钉住；这里钉的是它接进导出之后的东西：
 * 实际语义怎么解析、降级往哪儿退、文件名与完成态读的是哪一份、以及 `export_present.frag`
 * 与 Kotlin 侧是不是同一套实现。
 */
class FableSolExportSdrPipelineTest {

    // ---- 语义解析（D62、D81） ----

    /** 三种 SDR 语义各自可分，HDR 产物不带 SDR 语义。 */
    @Test
    fun eachSdrSemanticResolvesToItsOwnTag() {
        assertEquals(
            FableSolExportSdrRender.NATIVE,
            render(FableSolExportColorMode.SDR_NATIVE, FableSolExportSdrMapping.STABLE)
        )
        assertEquals(
            FableSolExportSdrRender.TONE_MAPPED_STABLE,
            render(FableSolExportColorMode.SDR_TONE_MAPPED, FableSolExportSdrMapping.STABLE)
        )
        assertEquals(
            FableSolExportSdrRender.TONE_MAPPED_DYNAMIC,
            render(FableSolExportColorMode.SDR_TONE_MAPPED, FableSolExportSdrMapping.DYNAMIC)
        )
        assertEquals("SDR", FableSolExportSdrRender.NATIVE.fileTag)
        assertEquals("SDR-TM", FableSolExportSdrRender.TONE_MAPPED_STABLE.fileTag)
        assertEquals("SDR-DTM", FableSolExportSdrRender.TONE_MAPPED_DYNAMIC.fileTag)
        assertNull(
            FableSolExportSdrRender.of(
                colorMode = FableSolExportColorMode.HDR10,
                mapping = FableSolExportSdrMapping.DYNAMIC,
                hdrResult = true
            )
        )
    }

    /**
     * 「HDR 自动」的 HDR 候选全部失败之后退到的是**原生** SDR。
     *
     * 色调映射是显式的创作选择，不该由一次能力降级替用户做主；隐藏的映射方式偏好同样不参与
     * 自动档求值。
     */
    @Test
    fun autoHdrFallsBackToNativeSdrRatherThanToneMapping() {
        assertEquals(
            FableSolExportSdrRender.NATIVE,
            render(FableSolExportColorMode.HDR_AUTO, FableSolExportSdrMapping.DYNAMIC)
        )
    }

    /** 显式 HDR 格式失败时不发布任何 SDR 产物，因此也不该解析出 SDR 语义（D106）。 */
    @Test
    fun explicitHdrNeverPublishesAnySdrResult() {
        assertFalse(FableSolExportColorMode.HDR10_PLUS.allowsSdrResult)
        assertFalse(FableSolExportColorMode.DOLBY_VISION_84.allowsSdrResult)
        assertTrue(FableSolExportColorMode.HDR_AUTO.allowsSdrResult)
        assertTrue(FableSolExportColorMode.SDR_TONE_MAPPED.allowsSdrResult)
    }

    /**
     * 运行时降级之后，文件名短标签跟着**实际**产物走，不保留失败尝试的标签（D81）。
     *
     * 请求侧的 `sdrMapping` 仍如实记着用户选了动态映射——两者分开正是这条模型的意义。
     */
    @Test
    fun theFileTagFollowsTheActualResultAfterARuntimeDowngrade() {
        val requested = candidate(
            colorMode = FableSolExportColorMode.SDR_TONE_MAPPED,
            mapping = FableSolExportSdrMapping.DYNAMIC,
            render = FableSolExportSdrRender.TONE_MAPPED_DYNAMIC
        )
        assertEquals("SDR-DTM", requested.fileTag)

        // D77：动态统计失败 → 稳定映射。
        val degradedToStable = requested.copy(
            sdrRender = FableSolExportSdrRender.TONE_MAPPED_STABLE
        )
        assertEquals("SDR-TM", degradedToStable.fileTag)
        assertEquals(FableSolExportSdrMapping.DYNAMIC, degradedToStable.sdrMapping)

        // D78：FP16 不可用 → 原生 SDR。
        val degradedToNative = requested.copy(sdrRender = FableSolExportSdrRender.NATIVE)
        assertEquals("SDR", degradedToNative.fileTag)
        assertEquals(FableSolExportColorMode.SDR_TONE_MAPPED, degradedToNative.colorMode)
    }

    /** 稳定标识不重复、可往返；持久化不依赖会被新增枚举项破坏的 ordinal。 */
    @Test
    fun stableIdsRoundTripAndStayUnique() {
        val ids = FableSolExportSdrRender.entries.map { it.stableId }
        assertEquals(ids.size, ids.toSet().size)
        for (value in FableSolExportSdrRender.entries) {
            assertEquals(value, FableSolExportSdrRender.fromStableId(value.stableId))
        }
        assertEquals(FableSolExportSdrRender.NATIVE, FableSolExportSdrRender.fromStableId("nope"))
    }

    /** 只有保留高光 SDR 用用户的 HDR 强度渲染；原生 SDR 关掉额外高光重新渲染（D62、D68）。 */
    @Test
    fun onlyToneMappedModesRenderWithTheHdrSource() {
        assertFalse(FableSolExportSdrRender.NATIVE.usesHdrSource)
        assertTrue(FableSolExportSdrRender.TONE_MAPPED_STABLE.usesHdrSource)
        assertTrue(FableSolExportSdrRender.TONE_MAPPED_DYNAMIC.usesHdrSource)
        assertFalse(FableSolExportSdrRender.NATIVE.toneMapped)
        assertFalse(FableSolExportSdrRender.TONE_MAPPED_STABLE.dynamic)
        assertTrue(FableSolExportSdrRender.TONE_MAPPED_DYNAMIC.dynamic)
    }

    // ---- 8-bit 抖动（D162） ----

    /**
     * 蓝噪声阈值舍入在 8-bit 码值域上无偏：整表跑一遍的平均值等于原值。
     *
     * 秩表是 0…4095 的一个排列，阈值取 `(rank + 0.5) / 4096`；因此 `floor(v*255 + t)` 在
     * 整张表上的期望正好是 `v*255`，误差始终不足一个码值——这与"往编码值上叠一层噪声"
     * 是两回事。
     */
    @Test
    fun blueNoiseQuantisationIsUnbiasedInTheEightBitCodeDomain() {
        val levels = FableSolExportBlueNoise.LEVELS
        for (target in listOf(0.1, 0.25, 0.5, 0.732, 0.9)) {
            val scaled = target * 255.0
            var sum = 0L
            for (rank in 0 until levels) {
                sum += Math.floor(scaled + FableSolExportBlueNoise.thresholdOf(rank)).toLong()
            }
            assertEquals(scaled, sum.toDouble() / levels, 1.0 / levels)
        }
    }

    /** 精确的 0 与 1 必须落在真黑与真白码值上，任何阈值都不例外（D162 第 4 条）。 */
    @Test
    fun trueBlackAndTrueWhiteSurviveEveryThreshold() {
        for (rank in 0 until FableSolExportBlueNoise.LEVELS) {
            val threshold = FableSolExportBlueNoise.thresholdOf(rank)
            assertTrue(threshold > 0.0 && threshold < 1.0)
            assertEquals(0.0, Math.floor(0.0 * 255.0 + threshold), 0.0)
            assertEquals(255.0, Math.floor(1.0 * 255.0 + threshold), 0.0)
        }
    }

    // ---- 着色器对照 ----

    /** 三段曲线在 shader 与 Kotlin 里必须是同一套写法，改一处就要改另一处。 */
    @Test
    fun thePresentShaderImplementsTheSameThreeSegmentCurve() {
        val present = shader("export_present.frag")
        assertTrue(present.contains("if (m <= uToneKnee) return m;"))
        assertTrue(present.contains("return 1.0 - span * exp(-(m - uToneKnee) / span);"))
        assertTrue(
            present.contains(
                "return uToneTarget - (uToneTarget - uToneWhite) * pow(1.0 - u, uToneExponent);"
            )
        )
        // 峰值退到 1.0 时超白段整段是常数，与 Curve.scalar 的同一条早退一致。
        assertTrue(present.contains("if (uTonePeak <= 1.0) return uToneWhite;"))
        // 共同增益：亮度尺度取 maxRGB，三个通道乘同一个数（D69、D76）。
        assertTrue(present.contains("float m = max(c.r, max(c.g, c.b));"))
        assertTrue(present.contains("return c * (sdrToneScalar(m) / m);"))
    }

    /**
     * 色调映射只作用于场景纹理，且必须在时钟合成**之前**（D73）。
     *
     * padding、画框底色、投影、描边和时钟都是 SDR 图形元素，本来就在 0～1 之内；把它们一起
     * 压一遍只会平白变暗，那是原生 SDR 与保留高光 SDR 之间不该有的差别。
     */
    @Test
    fun toneMappingRunsOnTheSceneOnlyAndBeforeTheClock() {
        val present = shader("export_present.frag")
        val toneMap = present.indexOf("if (uToneMap) sceneLinear = sdrToneMap(sceneLinear);")
        val clock = present.indexOf("vec4 ink = texture(uClock,")
        val composite = present.indexOf("color = mix(color, sceneLinear, cardCoverage);")
        assertTrue("tone map call missing", toneMap > 0)
        assertTrue("tone map must precede the clock blend", toneMap < clock)
        assertTrue("tone map must precede the card composite", toneMap < composite)
        // 画框底色、投影与描边都在色调映射之外。
        val backdrop = present.indexOf("vec3 color = srgbToLinear(uBackdropColor);")
        val rim = present.indexOf("color = mix(color, srgbToLinear(uRimColor), rim * uRimAlpha);")
        assertTrue(backdrop in 1 until toneMap)
        assertTrue(rim > composite)
    }

    /** 8-bit 抖动改成蓝噪声阈值舍入；三角哈希只作资源不可用时的同格式后备。 */
    @Test
    fun theEightBitPathQuantisesWithBlueNoiseAndKeepsTheHashAsFallback() {
        val present = shader("export_present.frag")
        assertTrue(
            present.contains("floor(encoded * 255.0 + threshold) / 255.0")
        )
        // R'G'B' 共用同一个阈值：一次采样、三个通道同用（D162 第 3 条）。
        assertTrue(present.contains("vec3 blueNoiseQuantize(vec3 encoded, ivec2 pixel)"))
        assertEquals(1, Regex("texelFetch\\(uNoise").findAll(present).count())
        // 图案钉在画布像素坐标上，不随帧变化（D162 第 1 条）。
        assertTrue(present.contains("ivec2 p = pixel % NOISE_SIZE;"))
        assertFalse(present.contains("uFrameIndex"))
        // 后备仍在，且只在 uNoiseEnabled 为假时才走。
        assertTrue(present.contains("float triangularDither(vec2 p)"))
        assertTrue(
            present.contains(
                "encoded = uNoiseEnabled\n            ? blueNoiseQuantize(encoded, ivec2(pointPx))"
            )
        )
        assertEquals(
            FableSolExportBlueNoise.SIZE,
            Regex("const int NOISE_SIZE = (\\d+);").find(present)!!.groupValues[1].toInt()
        )
        assertEquals(
            FableSolExportBlueNoise.LEVELS.toDouble(),
            Regex("const float NOISE_LEVELS = ([\\d.]+);").find(present)!!.groupValues[1].toDouble(),
            0.0
        )
    }

    /** 归约趟读的是场景纹理，并按 uWidth/uHeight 提前 break，最后一块不会越界。 */
    @Test
    fun thePeakShaderReducesTheSceneTextureWithinBounds() {
        val peak = shader("sdr_peak.frag")
        assertTrue(peak.contains("if (y >= uHeight) break;"))
        assertTrue(peak.contains("if (x >= uWidth) break;"))
        assertTrue(peak.contains("peak = max(peak, max(c.r, max(c.g, c.b)));"))
        // 与 FableSolExportScenePeak.encodePeak 同一套打包。
        assertTrue(
            peak.contains("float code = floor(clamp(peak / uMaxValue, 0.0, 1.0) * 65535.0 + 0.5);")
        )
        assertTrue(peak.contains("outColor = vec4(lo / 255.0, hi / 255.0, 0.0, 1.0);"))
    }

    private fun render(
        colorMode: FableSolExportColorMode,
        mapping: FableSolExportSdrMapping
    ) = FableSolExportSdrRender.of(colorMode, mapping, hdrResult = false)

    private fun candidate(
        colorMode: FableSolExportColorMode,
        mapping: FableSolExportSdrMapping,
        render: FableSolExportSdrRender
    ) = FableSolExportResolvedCandidate(
        colorMode = colorMode,
        sdrMapping = mapping,
        sdrRender = render,
        hdrFormat = null,
        transfer = FableSolExportTransfer.SDR,
        widthPx = 1152,
        heightPx = 1472,
        frameRate = 120,
        tenBit = false,
        family = FableSolExportCodecFamily.HEVC,
        codecName = "c2.qti.hevc.encoder",
        softwareOnly = false,
        profile = 1,
        level = 1,
        highTier = false,
        rateControl = FableSolExportRateControlForm.CONSTANT_QUALITY,
        qualityValue = 100,
        bitrateBps = null,
        inputPath = FableSolExportInputPath.SURFACE,
        hlgSignalRange = null,
        keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS,
        dither = FableSolExportDither.BLUE_NOISE,
        bFramesRequested = false,
        highComplexityRequested = true,
        qpGuardRequested = false,
        pqWhiteNits = 0.0,
        peakNits = 0.0,
        highlightStartPercent = 0
    )

    private fun shader(name: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(directory, "shared/fablesol/glsl/$name")
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 $name")
    }
}
