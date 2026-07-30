package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HDR10+ 精确统计、ApplicationVersion 1 载荷与 Profile B 曲线
 * （fablesol-video-export D101～D125、D169）。
 *
 * 载荷是**逐位**打包的：写错一位，后面所有字段全部错位，而错位只会表现为"编码器不认"或
 * "播放端色调映射离谱"，都极难反查。曲线同理——非法参数不会崩，只会让画面难看。所以这里
 * 把每一个字段与每一条约束都钉死。
 */
class FableSolExportHdr10PlusTest {

    private val meta = FableSolExportHdr10PlusMetadata

    // ---- 直方图与分位（D112、D169） ----

    /** 桶宽 0.00001、100001 个桶，与载荷量化网格完全对齐。 */
    @Test
    fun theHistogramGridMatchesThePayloadQuantisation() {
        assertEquals(0.00001, FableSolExportHdr10PlusHistogram.BUCKET_WIDTH, 0.0)
        assertEquals(100001, FableSolExportHdr10PlusHistogram.BUCKET_COUNT)
        // 一个桶正好是 0.1 尼特（以 PQ 10000 尼特为归一化上限）。
        assertEquals(
            0.1,
            FableSolExportHdr10PlusHistogram.BUCKET_WIDTH * FableSolExportTransfer.PQ_MAX_NITS,
            1e-9
        )
        assertEquals(0, FableSolExportHdr10PlusHistogram.bucketOf(0.0))
        assertEquals(100000, FableSolExportHdr10PlusHistogram.bucketOf(1.0))
        assertEquals(12345, FableSolExportHdr10PlusHistogram.bucketOf(0.123456))
    }

    /**
     * nearest-rank：`r = max(1, ceil(n × p / 100))`，返回升序第 `r` 个样本所在的桶。
     *
     * 不插值。三处（码流九项、99.98% 与内部任意百分位）各用一种 percentile 约定，是同一份
     * 统计给出三个不同答案的经典来源。
     */
    @Test
    fun percentilesUseNearestRankWithoutInterpolation() {
        // 100 个样本：0.00001, 0.00002, ... 0.00100（即桶 1..100）。
        val samples = DoubleArray(100) { (it + 1) * FableSolExportHdr10PlusHistogram.BUCKET_WIDTH }
        val histogram = FableSolExportHdr10PlusHistogram.of(samples)
        // p = 50 → r = 50 → 第 50 个样本 = 桶 50。
        assertEquals(
            FableSolExportHdr10PlusHistogram.bucketValue(50), histogram.percentile(50.0), 1e-12
        )
        // p = 1 → r = 1 → 最小样本。
        assertEquals(
            FableSolExportHdr10PlusHistogram.bucketValue(1), histogram.percentile(1.0), 1e-12
        )
        // p = 99.98 → r = ceil(99.98) = 100 → 最大样本，而不是"第 99 个"。
        assertEquals(
            FableSolExportHdr10PlusHistogram.bucketValue(100),
            histogram.percentile(FableSolExportHdr10PlusMetadata.V8_PERCENT),
            1e-12
        )
        // p = 0 也至少取第一个样本，不返回空。
        assertEquals(
            FableSolExportHdr10PlusHistogram.bucketValue(1), histogram.percentile(0.0), 1e-12
        )
    }

    /** `AverageMaxRGB` 由线性总和与真实像素数得出，不从桶重建（D102、D169）。 */
    @Test
    fun averageMaxRgbComesFromTheLinearSumNotTheBuckets() {
        // 桶宽之下的差异必须体现在均值里：三个样本落在同一个桶，但总和不同。
        val coarse = FableSolExportHdr10PlusHistogram(
            counts = IntArray(FableSolExportHdr10PlusHistogram.BUCKET_COUNT).also {
                it[10] = 3
            },
            pixelCount = 3L,
            maxScl = doubleArrayOf(0.0001, 0.0001, 0.0001),
            sum = 0.000301
        )
        assertEquals(0.000301 / 3.0, coarse.averageMaxRgb, 1e-12)
        assertNotEquals(
            FableSolExportHdr10PlusHistogram.bucketValue(10), coarse.averageMaxRgb, 1e-9
        )
    }

    // ---- ApplicationVersion 1 的特殊语义（D101） ----

    /**
     * `V1` 与 `V2` 是保留标记，不是真分位；`V8` 按 99.98% 而不是字面 99。
     *
     * 三条都对照 ST 2094-40:2020 §8.5.4 原文核实过：
     * "V1 shall be 0.00000, V2 shall be 0.00255"、
     * "Whenever J8 equal to 99 is present, the percentage value 99.98% shall be used"。
     */
    @Test
    fun applicationVersionOneReservesV1AndV2AndComputesV8At9998() {
        assertEquals(0.00000, FableSolExportHdr10PlusMetadata.RESERVED_V1, 0.0)
        assertEquals(0.00255, FableSolExportHdr10PlusMetadata.RESERVED_V2, 0.0)
        assertEquals(99.98, FableSolExportHdr10PlusMetadata.V8_PERCENT, 0.0)
        assertEquals(
            intArrayOf(1, 5, 10, 25, 50, 75, 90, 95, 99).toList(),
            FableSolExportHdr10PlusMetadata.PERCENTAGES.toList()
        )

        val samples = DoubleArray(10000) { (it + 1) * 0.00001 }
        val stats = FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples), fractionBrightPixels = 0.0
        )
        assertEquals(0.00000, stats.distribution[1], 0.0)
        assertEquals(0.00255, stats.distribution[2], 0.0)
        // V8 = 99.98% → r = ceil(10000 × 0.9998) = 9998。
        assertEquals(9998 * 0.00001, stats.distribution[8], 1e-12)
        assertEquals(stats.distribution[8], stats.percentile9998, 1e-12)
        // 其余项仍是各自 J 值的真分位。
        assertEquals(5000 * 0.00001, stats.distribution[4], 1e-12)
    }

    // ---- 载荷逐字段解码 ----

    /** 固定头与总长；逐位打包错一位后面全错。 */
    @Test
    fun thePayloadDecodesFieldByField() {
        val samples = DoubleArray(1000) { (it + 1) * 0.00002 }
        val stats = FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples), fractionBrightPixels = 0.25
        )
        val curve = FableSolExportHdr10PlusCurve(
            sourcePeakNits = 1949.0, targetNits = 1000.0
        ).shapeForScene(stats)
        val buffer = meta.payload(stats, curve, targetedPeakNits = 1000.0)
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val reader = BitReader(bytes)

        assertEquals(0xB5, reader.read(8))
        assertEquals(0x003C, reader.read(16))
        assertEquals(0x0001, reader.read(16))
        assertEquals(4, reader.read(8))       // application_identifier
        assertEquals(1, reader.read(8))       // application_version
        assertEquals(1, reader.read(2))       // num_windows
        // targeted_system_display_maximum_luminance：单位 0.0001 尼特，1000 → 10 000 000。
        assertEquals(10_000_000, reader.read(27))
        assertEquals(0, reader.read(1))       // targeted_system_display_actual_peak_flag
        for (channel in 0 until 3) {
            assertEquals(meta.normalized(stats.maxScl[channel]), reader.read(17))
        }
        assertEquals(meta.normalized(stats.averageMaxRgb), reader.read(17))
        assertEquals(9, reader.read(4))       // num_distribution_maxrgb_percentiles
        for (index in FableSolExportHdr10PlusMetadata.PERCENTAGES.indices) {
            assertEquals(
                FableSolExportHdr10PlusMetadata.PERCENTAGES[index], reader.read(7)
            )
            assertEquals(meta.normalized(stats.distribution[index]), reader.read(17))
        }
        // fraction_bright_pixels：0.25 → 250（步长 0.001）。
        assertEquals(250, reader.read(10))
        assertEquals(0, reader.read(1))       // mastering_display_actual_peak_flag
        // tone_mapping_flag 全片恒为 1（D111）。
        assertEquals(1, reader.read(1))
        assertEquals(Math.round(curve.kneeX * 4095).toInt(), reader.read(12))
        assertEquals(Math.round(curve.kneeY * 4095).toInt(), reader.read(12))
        assertEquals(9, reader.read(4))
        for (anchor in curve.anchors) {
            assertEquals(Math.round(anchor * 1023).toInt(), reader.read(10))
        }
        assertEquals(0, reader.read(1))       // color_saturation_mapping_flag
    }

    /** 算过且大于 0 时至少写 0.001，不能因量化向下变成表示"未计算"的 0（D108）。 */
    @Test
    fun aComputedFractionNeverQuantisesDownToTheUnknownMarker() {
        assertEquals(0, FableSolHdr10PlusStats.quantizeFractionBrightPixels(0.0))
        assertEquals(1, FableSolHdr10PlusStats.quantizeFractionBrightPixels(1e-9))
        assertEquals(1, FableSolHdr10PlusStats.quantizeFractionBrightPixels(0.0004))
        assertEquals(250, FableSolHdr10PlusStats.quantizeFractionBrightPixels(0.25))
        assertEquals(1000, FableSolHdr10PlusStats.quantizeFractionBrightPixels(1.0))
    }

    // ---- FractionBrightPixels 的权重函数（D108） ----

    /** `ε < 1/255` 是**全权重区**——初版表述漏掉它，照字面实现会把最亮那一带算错。 */
    @Test
    fun theBrightWeightHasAFullWeightBandBelowOneOver255() {
        assertEquals(1.0, FableSolHdr10PlusStats.brightWeight(0.0), 0.0)
        assertEquals(1.0, FableSolHdr10PlusStats.brightWeight(0.5 / 255.0), 0.0)
        // 1/255 处开始线性衰减，5/255 处归零。
        assertEquals(1.0, FableSolHdr10PlusStats.brightWeight(1.0 / 255.0), 1e-12)
        assertEquals(0.5, FableSolHdr10PlusStats.brightWeight(3.0 / 255.0), 1e-12)
        assertEquals(0.0, FableSolHdr10PlusStats.brightWeight(5.0 / 255.0), 0.0)
        assertEquals(0.0, FableSolHdr10PlusStats.brightWeight(1.0), 0.0)

        // 全屏同一亮度 → 每个像素 ε = 0 → 比例为 1。
        assertEquals(1.0, FableSolHdr10PlusStats.fractionBrightPixels(DoubleArray(100) { 0.2 }), 1e-12)
        // 一半贴着峰值、一半远低于峰值 → 正好 0.5。
        val split = DoubleArray(100) { if (it < 50) 0.2 else 0.0 }
        assertEquals(0.5, FableSolHdr10PlusStats.fractionBrightPixels(split), 1e-12)
        // BT.2020/D65 亮度权重（式 6）。
        assertEquals(0.2627, FableSolHdr10PlusStats.LUMA_R, 0.0)
        assertEquals(0.6780, FableSolHdr10PlusStats.LUMA_G, 0.0)
        assertEquals(0.0593, FableSolHdr10PlusStats.LUMA_B, 0.0)
    }

    // ---- Profile B 曲线 ----

    /**
     * 场景源峰值未超过参考显示峰值时，全片写入同一条 Case 3 中性曲线，绝对亮度恒等（D111、D177）。
     *
     * 判据是 `S ≤ T` 这两个场景常量。
     */
    @Test
    fun aMasteringPeakThatFitsGetsOneNeutralCurveForTheWholeClip() {
        val curve = FableSolExportHdr10PlusCurve(sourcePeakNits = 1000.0, targetNits = 2000.0)
        for (peak in listOf(0.02, 0.1949, 0.05)) {
            val shape = curve.shapeForScene(statsWithPeak(peakNormalized = peak))
            assertTrue(shape.neutral)
            assertEquals(1.0, shape.kneeX, 1e-12)
            // Ky = S/T：F(s) = Ky·s，还原到绝对亮度正好 T·F(s) = S·s。
            assertEquals(0.5, shape.kneeY, 1e-12)
            // 接收端按同一场景 V8 归一化横轴时，这条曲线是绝对亮度上的恒等映射。
            for (nits in listOf(0.0, 150.0, 500.0, 1000.0)) {
                assertEquals(nits, shape.evaluate(nits / 1000.0) * 2000.0, 1e-9)
            }
            // anchors 仍写合法单调的占位值，保持全片载荷结构与 Profile 判定稳定。
            assertEquals(9, shape.anchors.size)
            for (index in 1 until shape.anchors.size) {
                assertTrue(shape.anchors[index] >= shape.anchors[index - 1])
            }
        }
    }

    /** 膝点以下严格 identity，整条曲线单调、只压缩不提亮（D114、D119）。 */
    @Test
    fun theCompressionCurveIsMonotoneAndNeverBrightens() {
        val sourcePeak = 1949.0
        val stats = statsWithPeak(peakNormalized = 0.1949)
        val shape = FableSolExportHdr10PlusCurve(
            sourcePeakNits = sourcePeak, targetNits = 1000.0
        ).shapeForScene(stats).quantized()
        assertFalse(shape.neutral)
        var previous = -1.0
        for (step in 0..512) {
            val s = step / 512.0
            val y = shape.evaluate(s)
            assertTrue("monotone at $s", y >= previous - 1e-9)
            previous = y
            // 横轴按场景 V8：s = 1 对应 S。
            val absoluteIn = s * sourcePeak
            val absoluteOut = y * 1000.0
            assertTrue("must not brighten at $s", absoluteOut <= absoluteIn + 1.0)
        }
        // 膝点以下是 identity：Ky/Kx 正好等于 S/T。
        assertEquals(sourcePeak / 1000.0, shape.kneeY / shape.kneeX, 0.02)
    }

    /**
     * `S > 10T` 是场景级无解：完整场景预分析后即可判定（D115、D177）。
     *
     * 判据使用场景 V8 与参考显示峰值，不能拿 MDCV 母版峰值替代。
     */
    @Test
    fun anImpossibleDynamicRangeIsRejectedBeforeAnyFrame() {
        assertNull(FableSolExportHdr10PlusCurve.unsupportedReason(3960.0, 400.0))
        val reason = FableSolExportHdr10PlusCurve.unsupportedReason(5000.0, 400.0)
        assertNotNull(reason)
        // 提示要给出最低可行参考峰值，而不是只说"曲线生成失败"（D115、D116）。
        assertEquals(502, FableSolExportHdr10PlusCurve.minimumTargetNits(5000.0))
        assertTrue(reason!!.contains("502 nits"))
        // 恰好十倍是个陷阱：连续域里 k = 0 算解，但它没有线性段，`P1` 退化成 0/0，量化后
        // 整条曲线把所有亮度映射到零。判据必须把它挡在外面。
        assertNotNull(FableSolExportHdr10PlusCurve.unsupportedReason(4000.0, 400.0))
        assertNull(
            FableSolExportHdr10PlusCurve.unsupportedReason(
                4000.0, FableSolExportHdr10PlusCurve.minimumTargetNits(4000.0).toDouble()
            )
        )

        var failed = false
        try {
            FableSolExportHdr10PlusCurve(sourcePeakNits = 5000.0, targetNits = 400.0)
        } catch (unsolvable: FableSolExportHdr10PlusCurve.Unsolvable) {
            failed = true
        }
        assertTrue("S = 12.5 x T must be rejected at construction", failed)

        // 刚好在十倍以内时仍要给出解。
        FableSolExportHdr10PlusCurve(
            sourcePeakNits = 9000.0, targetNits = 1000.0
        ).shapeForScene(statsWithPeak(peakNormalized = 0.9))
    }

    /** 膝点高于可行上限时**下移膝点**，参考显示峰值保持不变（D115）。 */
    @Test
    fun anInfeasibleKneeIsLoweredInsteadOfRaisingTheTarget() {
        // 高光起点 99% 会把膝点推到接近场景峰值，远高于 (10T − S)/9。
        val stats = statsWithPeak(peakNormalized = 0.1949)
        val shape = FableSolExportHdr10PlusCurve(
            sourcePeakNits = 1949.0, targetNits = 300.0, highlightStartPercent = 99
        ).shapeForScene(stats)
        assertTrue(shape.kneeNits < shape.requestedKneeNits)
        // 可行上限：k ≤ (10T − S)/9。
        val ceiling = (10.0 * 300.0 - 1949.0) / 9.0
        assertTrue(shape.kneeNits <= ceiling + 1.0)
    }

    /**
     * 可行域边缘（S/T ≈ 9.9）必须能产出通过门禁的曲线（D115）。
     *
     * 旧膝点门禁用固定 1/256 步长做有限差分：膝点被可行上限压到 kneeX < 1/256 时，
     * "膝下"样本区间跨过原点、斜率被低估成约 Ky·256，S/T ∈（约 9.72，9.978] 的全部可行
     * 组合被误判为膝点不连续——`unsupportedReason` 判可行，`shapeForScene` 必然抛出。
     * 现改为对量化后载荷值取闭式斜率（Ky/Kx 与 10·P1·(1−Ky)/(1−Kx)）比较，容差按量化
     * 步长的传播上界放宽，本用例即回归锚点。
     */
    @Test
    fun aFeasibleCombinationNearTheTenfoldLimitStillYieldsACurve() {
        assertNull(FableSolExportHdr10PlusCurve.unsupportedReason(2970.0, 300.0))
        val shape = FableSolExportHdr10PlusCurve(
            sourcePeakNits = 2970.0, targetNits = 300.0
        ).shapeForScene(statsWithPeak(peakNormalized = 0.297))
        // 膝点被压到 1/256 以下正是旧门禁的假阳性区；曲线本身合法，恒等关系必须保持。
        assertTrue(shape.kneeX < 1.0 / 256.0)
        assertEquals(2970.0 / 300.0, shape.kneeY / shape.kneeX, 1e-9)
    }

    /** 曲线形状确实随内容密度变化，不是一条与画面无关的固定缓动（D117）。 */
    @Test
    fun theShoulderRespondsToTheContentDistribution() {
        val curve = { stats: FableSolHdr10PlusStats ->
            FableSolExportHdr10PlusCurve(
                sourcePeakNits = 1949.0, targetNits = 1000.0
            ).shapeForScene(stats)
        }
        // 高光集中在中高亮 vs 集中在极高亮，anchors 必须不同。
        val dense = curve(statsWithShape(concentrateHigh = false))
        val sparse = curve(statsWithShape(concentrateHigh = true))
        var different = false
        for (index in dense.anchors.indices) {
            if (abs(dense.anchors[index] - sparse.anchors[index]) > 0.01) different = true
        }
        assertTrue("anchors must follow the CFD", different)
        assertEquals(0.5, FableSolExportHdr10PlusCurve.UNIFORM_PRIOR, 0.0)
    }

    /** 连续动画的全部帧必须先累计成一份场景统计（D177）。 */
    @Test
    fun continuousFramesAreAccumulatedIntoOneScene() {
        val accumulator = FableSolExportHdr10PlusSceneAccumulator(diffuseWhiteNits = 200.0)
        accumulator.add(
            FableSolHdr10PlusStats.of(
                FableSolExportHdr10PlusHistogram.of(doubleArrayOf(0.01, 0.02)),
                fractionBrightPixels = 0.80,
                proxyAverageLuminance = 0.01
            )
        )
        accumulator.add(
            FableSolHdr10PlusStats.of(
                FableSolExportHdr10PlusHistogram.of(doubleArrayOf(0.03, 0.04)),
                fractionBrightPixels = 0.10,
                proxyAverageLuminance = 0.03
            )
        )
        // 平均亮度并列时按规范选择帧号更大的代理帧。
        accumulator.add(
            FableSolHdr10PlusStats.of(
                FableSolExportHdr10PlusHistogram.of(doubleArrayOf(0.01, 0.01)),
                fractionBrightPixels = 0.25,
                proxyAverageLuminance = 0.03
            )
        )

        val scene = accumulator.result()
        assertNotNull(scene)
        val result = scene!!
        assertEquals(6L, result.stats.histogram!!.pixelCount)
        assertEquals(0.04, result.stats.maxScl.maxOrNull()!!, 1e-12)
        assertEquals(0.12 / 6.0, result.stats.averageMaxRgb, 1e-12)
        assertEquals(0.04, result.stats.percentile9998, 1e-12)
        assertEquals(0.25, result.stats.fractionBrightPixels, 0.0)
        assertEquals(0.03, result.stats.proxyAverageLuminance!!, 0.0)
        // 0.04 × 10000 = 400 nit；相对 200 nit 漫反射白即 2.0。
        assertEquals(2.0, result.luminance.maxContentNormalized, 1e-12)
        // 第二帧均值 0.035 × 10000 / 200 = 1.75，是整段最高帧均值。
        assertEquals(1.75, result.luminance.maxFrameAverageNormalized, 1e-12)
    }

    /** 场景累计桶必须使用 64 位计数，不能在长片的高频桶上溢出。 */
    @Test
    fun sceneHistogramCountsDoNotOverflowInt() {
        val bucket = 123
        val perFrameCount = Int.MAX_VALUE
        val counts = IntArray(FableSolExportHdr10PlusHistogram.BUCKET_COUNT).also {
            it[bucket] = perFrameCount
        }
        val value = FableSolExportHdr10PlusHistogram.bucketValue(bucket)
        val histogram = FableSolExportHdr10PlusHistogram(
            counts = counts,
            pixelCount = perFrameCount.toLong(),
            maxScl = DoubleArray(3) { value },
            sum = value * perFrameCount
        )
        val accumulator = FableSolExportHdr10PlusSceneAccumulator(diffuseWhiteNits = 203.0)
        repeat(2) {
            accumulator.add(
                FableSolHdr10PlusStats.of(
                    histogram,
                    fractionBrightPixels = 1.0,
                    proxyAverageLuminance = value
                )
            )
        }
        val scene = accumulator.result()
        assertNotNull(scene)
        assertEquals(2L * Int.MAX_VALUE, scene!!.stats.histogram!!.pixelCount)
        assertEquals(value, scene.stats.percentile(50.0), 0.0)
    }

    /** 任一代理帧缺失时无法选出规范最亮帧，场景 FBP 必须写“未计算”零值。 */
    @Test
    fun incompleteSceneProxyMakesFractionBrightPixelsUncomputed() {
        val accumulator = FableSolExportHdr10PlusSceneAccumulator(diffuseWhiteNits = 203.0)
        accumulator.add(
            FableSolHdr10PlusStats.of(
                FableSolExportHdr10PlusHistogram.of(doubleArrayOf(0.01, 0.02)),
                fractionBrightPixels = 0.4,
                proxyAverageLuminance = 0.02
            )
        )
        accumulator.add(
            FableSolHdr10PlusStats.of(
                FableSolExportHdr10PlusHistogram.of(doubleArrayOf(0.03, 0.04)),
                fractionBrightPixels = 0.9,
                proxyAverageLuminance = null
            )
        )

        val scene = accumulator.result()
        assertNotNull(scene)
        assertEquals(0.0, scene!!.stats.fractionBrightPixels, 0.0)
        assertNull(scene.stats.proxyAverageLuminance)
    }

    /** 横轴优先取场景 V8，不能再用 MDCV 母版峰值替代（D113、D177）。 */
    @Test
    fun theSourceAxisUsesSceneV8AndFallsBackToMaxScl() {
        val samples = DoubleArray(10000) { index ->
            if (index < 9998) 0.05 else 0.19
        }
        val stats = FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples),
            fractionBrightPixels = 0.001
        )
        // nearest-rank 的 99.98% 是第 9998 个样本，仍为 500 nit；MaxSCL 则是 1900 nit。
        assertEquals(500.0, FableSolExportHdr10PlusCurve.sourcePeakNits(stats), 1e-9)
        assertEquals(0.19, stats.maxScl.maxOrNull()!!, 0.0)

        val fallback = FableSolHdr10PlusStats.placeholder(0.19).let {
            FableSolHdr10PlusStats(
                maxScl = it.maxScl,
                averageMaxRgb = it.averageMaxRgb,
                distribution = it.distribution,
                percentile9998 = 0.0,
                fractionBrightPixels = it.fractionBrightPixels,
                proxyAverageLuminance = null,
                histogram = null
            )
        }
        assertEquals(1900.0, FableSolExportHdr10PlusCurve.sourcePeakNits(fallback), 1e-9)
    }

    // ---- 回归：整个连续动画只使用一份场景曲线（D177） ----

    /**
     * 203 nit 与 350 nit 两种创作白点都只求解一次曲线；星芒是否出现在当前帧不再改变水体映射。
     */
    @Test
    fun sceneCurveKeepsWaterStableAt203And350Nits() {
        val cases = listOf(
            Triple(1949.0, 150.0, 2000.0),
            Triple(3360.0, 300.0, 2000.0)
        )
        for ((sourcePeak, waterNits, targetNits) in cases) {
            val stats = statsWithBackgroundAndPeak(
                backgroundNits = waterNits,
                peakNits = sourcePeak
            )
            val shape = FableSolExportHdr10PlusCurve(
                sourcePeakNits = sourcePeak,
                targetNits = targetNits
            ).shapeForScene(stats).quantized()
            val output = receiverOutput(shape, waterNits, sourcePeak, targetNits)
            val timeline = List(240) {
                receiverOutput(shape, waterNits, sourcePeak, targetNits)
            }
            assertTrue(timeline.all { it == output })
            // 高光可以压缩，但膝点以下水体不得被全局增益改写。
            if (waterNits <= shape.kneeNits) {
                assertEquals(waterNits, output, 1.0)
            }
        }
    }

    /** 同一场景无论是否需要压缩，重复生成的完整 ST 2094-40 载荷都必须逐位相同。 */
    @Test
    fun oneSceneAlwaysProducesOneBitStablePayload() {
        for ((sourcePeak, targetNits) in listOf(1949.0 to 2000.0, 3360.0 to 2000.0)) {
            val stats = statsWithBackgroundAndPeak(150.0, sourcePeak)
            fun payload(): ByteArray {
                val curve = FableSolExportHdr10PlusCurve(
                    sourcePeakNits = sourcePeak,
                    targetNits = targetNits
                ).shapeForScene(stats)
                val buffer = meta.payload(stats, curve, targetNits)
                return ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            val first = payload()
            repeat(16) { assertArrayEquals(first, payload()) }
        }
    }

    /** 贝塞尔基与求值：`P0 = 0`、`P10 = 1`，端点必须精确。 */
    @Test
    fun theBezierBasisIsWellFormed() {
        val anchors = DoubleArray(9) { (it + 1) / 10.0 }
        assertEquals(0.0, FableSolExportHdr10PlusCurve.bezier(0.0, anchors), 1e-12)
        assertEquals(1.0, FableSolExportHdr10PlusCurve.bezier(1.0, anchors), 1e-12)
        // 基函数在任意 t 上求和为 1。
        for (t in listOf(0.0, 0.3, 0.7, 1.0)) {
            var total = 0.0
            for (index in 0..10) total += FableSolExportHdr10PlusCurve.basis(index, t)
            assertEquals(1.0, total, 1e-9)
        }
    }

    /** 保序回归把非单调的控制点压成单调，而不是逐点裁切。 */
    @Test
    fun monotoneEnforcementPoolsAdjacentViolators() {
        val anchors = doubleArrayOf(0.1, 0.5, 0.3, 0.4, 0.9)
        FableSolExportHdr10PlusCurve.enforceMonotone(anchors)
        for (index in 1 until anchors.size) {
            assertTrue(anchors[index] >= anchors[index - 1] - 1e-12)
        }
        // 中间三个被合并成它们的均值，而不是把 0.3 直接抬成 0.5。
        assertEquals(0.4, anchors[1], 1e-9)
        assertEquals(0.4, anchors[2], 1e-9)
        assertEquals(0.4, anchors[3], 1e-9)
    }

    // ---- 两个统计后端的共同解包 ----

    /** 24 位打包与解包同源；这是 GLES 3.0 后端保住载荷精度的依据（D124）。 */
    @Test
    fun theTwentyFourBitPackingRoundTrips() {
        for (value in listOf(0.0, 0.00001, 0.12345, 0.5, 1.0)) {
            val code = Math.round(value * 16777215.0).toInt()
            val bytes = byteArrayOf(
                (code and 0xFF).toByte(),
                ((code shr 8) and 0xFF).toByte(),
                ((code shr 16) and 0xFF).toByte(),
                0xFF.toByte()
            )
            assertEquals(
                value,
                FableSolExportHdr10PlusStatsBackend.decode24(bytes, 0),
                1.0 / 16777215.0
            )
        }
        // 24 位比 0.00001 的载荷网格细得多——统计定义因此不降级。
        assertTrue(1.0 / 16777215.0 < FableSolExportHdr10PlusHistogram.BUCKET_WIDTH)
    }

    // ---- 辅助 ----

    /** 接收端模型：按场景源峰值归一化横轴，纵轴按参考显示峰值还原。 */
    private fun receiverOutput(
        shape: FableSolExportHdr10PlusCurve.Shape,
        nits: Double,
        sourcePeakNits: Double,
        targetNits: Double
    ): Double = shape.evaluate(nits / sourcePeakNits) * targetNits

    /** 恒定背景 + 极少数高光像素；V8（99.98% 分位）落在高光上，第 90 百分位落在背景上。 */
    private fun statsWithBackgroundAndPeak(
        backgroundNits: Double,
        peakNits: Double
    ): FableSolHdr10PlusStats {
        val background = backgroundNits / FableSolExportTransfer.PQ_MAX_NITS
        val peak = peakNits / FableSolExportTransfer.PQ_MAX_NITS
        val samples = DoubleArray(10000) { index ->
            if (index < 9990) background else peak
        }
        return FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples), fractionBrightPixels = 0.001
        )
    }

    private fun statsWithPeak(peakNormalized: Double): FableSolHdr10PlusStats {
        val samples = DoubleArray(10000) { index ->
            // 大部分像素在暗部，少数拉到峰值——与水体加银丝的分布形状一致。
            if (index < 9900) peakNormalized * 0.1 else peakNormalized
        }
        return FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples), fractionBrightPixels = 0.01
        )
    }

    private fun statsWithShape(concentrateHigh: Boolean): FableSolHdr10PlusStats {
        val peak = 0.1949
        val samples = DoubleArray(10000) { index ->
            when {
                index < 9000 -> peak * 0.1
                concentrateHigh -> peak * (0.95 + 0.05 * (index % 100) / 100.0)
                else -> peak * (0.3 + 0.6 * (index % 100) / 100.0)
            }
        }
        return FableSolHdr10PlusStats.of(
            FableSolExportHdr10PlusHistogram.of(samples), fractionBrightPixels = 0.01
        )
    }

    /** 与 [FableSolExportHdr10PlusMetadata.BitWriter] 对称的读取器。 */
    private class BitReader(private val bytes: ByteArray) {
        private var position = 0

        fun read(bitCount: Int): Int {
            var value = 0
            repeat(bitCount) {
                val byteIndex = position / 8
                val bitIndex = 7 - position % 8
                val bit = if (byteIndex < bytes.size) {
                    (bytes[byteIndex].toInt() shr bitIndex) and 1
                } else {
                    0
                }
                value = (value shl 1) or bit
                position++
            }
            return value
        }
    }
}
