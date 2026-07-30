package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 6 的 HLG 输出变换、方向域设备上限与 super-white 回环判定
 * （fablesol-video-export D126～D144、D164、D165）。
 *
 * 这一批的正确性几乎全在数字上：源语义错了（D132）画面整体偏暗或偏亮，查表键错了（D164）
 * 某些颜色会提前压缩而另一些越界后被硬钳——两种错误都不会报任何异常，只会让产物悄悄变成
 * 另一个样子。因此这里把决策里写死的每个数值都钉住。
 */
class FableSolExportHlgTest {

    private val hlg = FableSolExportHlgTransform

    // ---- 逆 OOTF 与 75% 参考白（D126、D128、D132）----

    @Test
    fun referenceWhiteLandsOnSeventyFivePercentSignal() {
        // FableSol 显示线性 1.0 固定映射到 75% HLG 信号（D126）。
        assertEquals(0.75, hlg.hlgOetf(FableSolExportHlgTransform.REFERENCE_WHITE_SCENE), 1e-4)
        assertEquals(0.203159, hlg.REFERENCE_WHITE_DISPLAY, 1e-6)
        // 中性白经完整变换后确实落在 75%，三个通道相等——白仍是白（D128）。容差取 1e-7 是因为
        // Rec.709→BT.2020 矩阵第一行按规范舍入后求和为 1.00000001，白点有 1e-8 量级的固有
        // 偏差；一个 10-bit 码值是 1.1e-3，这点残差在产物里表达不出来。
        val table = hlg.buildShoulderTable(9.6)
        val signal = hlg.mapDisplayLinear(1.0, 1.0, 1.0, 9.6, table) { 1.0 }
        assertEquals(0.75, signal[0], 1e-4)
        assertEquals(signal[0], signal[1], 1e-7)
        assertEquals(signal[0], signal[2], 1e-7)
    }

    @Test
    fun hlgOetfAndItsInverseRoundTripAboveTheNominalPeak() {
        // 上界**不能**钳到 1.0：super-white 正是靠这条曲线在名义峰值以上的延拓表达的。
        for (signal in listOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.045, 1.09)) {
            assertEquals(signal, hlg.hlgOetf(hlg.hlgInverseOetf(signal)), 1e-12)
        }
        assertTrue(hlg.hlgOetf(hlg.hlgInverseOetf(1.09)) > 1.0)
    }

    @Test
    fun inverseOotfIsHomogeneousOfDegreeOneOverGamma() {
        // 方向相关的全部推导都建立在这条性质上：m = q(u) · s^(1/γ)（D129）。
        val direction = doubleArrayOf(1.0, 0.42, 0.17)
        val unit = hlg.sceneLinear(direction[0], direction[1], direction[2])
        for (scale in listOf(0.3, 1.0, 2.0, 5.5, 9.6)) {
            val scaled = hlg.sceneLinear(
                direction[0] * scale, direction[1] * scale, direction[2] * scale
            )
            val factor = scale.pow(hlg.INVERSE_GAMMA)
            for (channel in 0..2) {
                assertEquals(unit[channel] * factor, scaled[channel], 1e-12)
            }
        }
    }

    @Test
    fun blackStaysBlackThroughTheWholeTransform() {
        val table = hlg.buildShoulderTable(9.6)
        val signal = hlg.mapDisplayLinear(0.0, 0.0, 0.0, 9.6, table) { 1.0 }
        assertEquals(0.0, signal[0], 0.0)
        assertEquals(0.0, signal[1], 0.0)
        assertEquals(0.0, signal[2], 0.0)
    }

    // ---- 颜色方向容量（D129、D134、D164）----

    @Test
    fun neutralDirectionNeverUsesSuperWhite() {
        // 中性色止于 100%：super-white 只用来补足高饱和颜色的色容积，不用来把白提亮（D134）。
        val white = doubleArrayOf(1.0, 1.0, 1.0)
        assertEquals(1.0, hlg.matchCapacity(white), 1e-8)
        // BT.2100 的 HLG 常量按规范舍入后 OETF(1) = 1.0000000012，不是精确的 1。
        assertEquals(1.0, hlg.standardCeiling(white), 1e-6)
        assertEquals(
            FableSolExportHlgTransform.REFERENCE_WHITE_SCENE, hlg.directionScale(white), 1e-8
        )
    }

    @Test
    fun primaryDirectionsMatchTheDocumentedSuperWhiteValues() {
        // D134 逐项列出的匹配值；它们同时证明 W_MAX = 1.09 在当前源色域下只是防御性钳制。
        assertEquals(1.0330, hlg.standardCeiling(doubleArrayOf(1.0, 0.0, 0.0)), 5e-4)
        assertEquals(1.0077, hlg.standardCeiling(doubleArrayOf(0.0, 1.0, 0.0)), 5e-4)
        assertEquals(1.0765, hlg.standardCeiling(doubleArrayOf(0.0, 0.0, 1.0)), 5e-4)
        hlg.forEachDirection(17) { u ->
            assertTrue(hlg.standardCeiling(u) <= FableSolExportHlgTransform.SIGNAL_MAX + 1e-12)
        }
    }

    @Test
    fun compressionStartsAtTheDocumentedDisplayLinearScales() {
        // 肩部起点 = C_n^γ（源显示线性尺度）。三个数分别来自 D126、D129 与 D133。
        assertEquals(4.92, capacityScale(doubleArrayOf(1.0, 1.0, 1.0), superWhite = true), 0.01)
        assertEquals(5.50, capacityScale(doubleArrayOf(0.0, 0.0, 1.0), superWhite = true), 0.01)
        // 名义范围下同一个蓝方向提前到约 3.32× 就要压——这正是 super-white 换来的差距。
        assertEquals(3.32, capacityScale(doubleArrayOf(0.0, 0.0, 1.0), superWhite = false), 0.01)
    }

    @Test
    fun directionScaleCannotServeAsTheLookupKey() {
        // D164 的反例：q 与归一化容量不是一一对应，甚至不同向。以 q 作键必然给错其中一个。
        val red = doubleArrayOf(1.0, 0.0, 0.0)
        val white = doubleArrayOf(1.0, 1.0, 1.0)
        assertTrue(hlg.directionScale(red) < hlg.directionScale(white))
        assertTrue(normalizedCapacity(red) > normalizedCapacity(white))
        assertEquals(0.215, hlg.directionScale(red), 5e-4)
        assertEquals(5.57, normalizedCapacity(red), 0.01)
        assertEquals(3.77, normalizedCapacity(white), 0.01)

        // 同一个 q 上的两个方向要求两个不同的形状：红→白与红→品红两条路径各取一点。
        val towardsWhite = firstDirectionWithScale(red, white, 0.2335)
        val towardsMagenta = firstDirectionWithScale(red, doubleArrayOf(1.0, 0.0, 1.0), 0.2335)
        assertEquals(
            hlg.directionScale(towardsWhite), hlg.directionScale(towardsMagenta), 2e-3
        )
        assertTrue(
            abs(normalizedCapacity(towardsWhite) - normalizedCapacity(towardsMagenta)) > 0.3
        )
    }

    // ---- 指数肩部（D131）----

    @Test
    fun shoulderMeetsItsThreeBoundaryConditions() {
        val headroom = 9.6.pow(hlg.INVERSE_GAMMA)
        val capacity = 4.0
        val xi = hlg.shoulderXi(capacity, headroom)
        assertTrue(xi > 0.0)
        val knee = FableSolExportHlgTransform.KNEE_NORMALIZED
        // F(K) = K：膝点原样通过。
        assertEquals(knee, hlg.shoulder(knee, headroom, capacity, xi), 1e-12)
        // F(H) = C：用户设置的最高源高光沿该方向恰好用满其可用上限。
        assertEquals(capacity, hlg.shoulder(headroom, headroom, capacity, xi), 1e-9)
        // F'(K) = 1：膝点没有一阶折角。
        val step = 1e-6
        val slopeAtKnee =
            (hlg.shoulder(knee + step, headroom, capacity, xi) - knee) / step
        assertEquals(1.0, slopeAtKnee, 1e-4)
    }

    @Test
    fun shoulderIsMonotoneAndNeverExpandsLocalContrast() {
        val headroom = 9.6.pow(hlg.INVERSE_GAMMA)
        val knee = FableSolExportHlgTransform.KNEE_NORMALIZED
        for (capacity in listOf(2.2, 3.0, 4.137, 5.565)) {
            val xi = hlg.shoulderXi(capacity, headroom)
            var previous = hlg.shoulder(knee, headroom, capacity, xi)
            var value = knee + 1e-3
            while (value <= headroom) {
                val mapped = hlg.shoulder(value, headroom, capacity, xi)
                assertTrue("肩部必须单调", mapped >= previous - 1e-12)
                // 局部斜率位于 0～1：只压缩，不放大对比度。
                assertTrue("肩部不得放大局部对比度", mapped - previous <= 1e-3 + 1e-9)
                assertTrue("肩部不得抬高信号", mapped <= value + 1e-12)
                previous = mapped
                value += 1e-3
            }
        }
    }

    @Test
    fun shoulderDegeneratesToIdentityWhenCapacityCoversTheHeadroom() {
        // H_S(u) <= C_S(u) 时该方向在整个 0～H_D 范围内都装得下，保持恒等映射（D129）。
        val headroom = 2.5.pow(hlg.INVERSE_GAMMA)
        val capacity = headroom + 0.5
        assertEquals(0.0, hlg.shoulderXi(capacity, headroom), 0.0)
        val sample = FableSolExportHlgTransform.KNEE_NORMALIZED + 0.2
        assertEquals(sample, hlg.shoulder(sample, headroom, capacity, 0.0), 0.0)
    }

    // ---- 完整逐像素映射（D128、D133）----

    @Test
    fun commonGainKeepsTheLinearColourDirection() {
        val table = hlg.buildShoulderTable(9.6)
        val source = doubleArrayOf(9.6, 2.4, 0.6)
        val signal = hlg.mapDisplayLinear(source[0], source[1], source[2], 9.6, table) { 1.09 }
        // 肩部只对 maxRGB 求映射再给三通道共同增益，因此场景线性 RGB 比例不变（D128 第 3 步）。
        val scene = hlg.sceneLinear(source[0], source[1], source[2])
        val mapped = DoubleArray(3) { hlg.hlgInverseOetf(signal[it]) }
        assertEquals(scene[1] / scene[0], mapped[1] / mapped[0], 1e-9)
        assertEquals(scene[2] / scene[0], mapped[2] / mapped[0], 1e-9)
    }

    @Test
    fun highlightsReachButNeverExceedTheDirectionCeiling() {
        val strength = 9.6
        val table = hlg.buildShoulderTable(strength)
        hlg.forEachDirection(9) { u ->
            val ceiling = hlg.standardCeiling(u)
            val signal = hlg.mapDisplayLinear(
                u[0] * strength, u[1] * strength, u[2] * strength, strength, table
            ) { FableSolExportHlgTransform.SIGNAL_MAX }
            val peak = signal.max()
            assertTrue("信号不得越过该方向的上限", peak <= ceiling + 1e-6)
            // 需要肩部的方向应当恰好用满上限；容量本就够用的方向保持恒等，不会顶到上限。
            if (normalizedCapacity(u) < strength.pow(hlg.INVERSE_GAMMA)) {
                assertEquals(ceiling, peak, 1e-4)
            }
        }
    }

    @Test
    fun neighbouringDirectionsDoNotStep() {
        // D133 要求连续插值不得在颜色渐变里产生可见台阶：沿红→蓝扫一圈，逐点信号连续。
        val strength = 9.6
        val table = hlg.buildShoulderTable(strength)
        val grid = FableSolExportHlgDeviceRange.buildGrid(
            FableSolExportHlgDeviceRange.SafeCodes(1019, 4, 1019, 4, 1019)
        )
        assertNotNull(grid)
        var previous: Double? = null
        var t = 0.0
        while (t <= 1.0) {
            val u = doubleArrayOf(1.0 - t, 0.0, t).let {
                val peak = maxOf(it[0], it[1], it[2]).coerceAtLeast(1e-9)
                doubleArrayOf(it[0] / peak, it[1] / peak, it[2] / peak)
            }
            val signal = hlg.mapDisplayLinear(
                u[0] * strength, u[1] * strength, u[2] * strength, strength, table
            ) { direction -> grid!!.ceilingFor(direction) }
            val peak = signal.max()
            previous?.let { assertTrue("相邻方向不得跳变", abs(peak - it) < 0.02) }
            previous = peak
            t += 1.0 / 256.0
        }
    }

    // ---- 方向域设备上限（D140、D165）----

    @Test
    fun signalSamplesCoverTheWholeSuperWhiteInterval() {
        val samples = FableSolExportHlgDeviceRange.signalSamples()
        assertEquals(FableSolExportHlgTransform.SIGNAL_NOMINAL, samples.first(), 1e-12)
        assertTrue(samples.last() <= FableSolExportHlgTransform.SIGNAL_MAX + 1e-9)
        // 固定步长是二分成立的前提：较大 W 的检查点集合必须包含较小 W 的（D165）。
        for (index in 1 until samples.size) {
            assertEquals(
                FableSolExportHlgDeviceRange.SIGNAL_STEP,
                samples[index] - samples[index - 1],
                1e-12
            )
        }
    }

    @Test
    fun nothingBeyondNominalMeansNoGridAndTheNominalPlan() {
        val nominal = FableSolExportHlgDeviceRange.SafeCodes.NOMINAL
        assertFalse(nominal.extended)
        assertNull(FableSolExportHlgDeviceRange.buildGrid(nominal))
        val plan = FableSolExportHlgPlan.of(9.6, nominal)
        assertFalse(plan.extended)
        assertEquals(FableSolExportHlgRange.NOMINAL, plan.range)
        assertEquals(
            FableSolExportP010Math.SignalRange.NOMINAL.lumaMaxCode,
            plan.signalRange.lumaMaxCode,
            0.0
        )
        // 无法验证时同样是名义范围，而不是"HLG 编码失败"（D135）。
        assertEquals(FableSolExportHlgRange.NOMINAL, FableSolExportHlgPlan.of(9.6, null).range)
    }

    @Test
    fun partialComponentHeadroomLimitsOnlyTheDirectionsThatNeedIt() {
        // 只有亮度分量拿到余量、色度仍停在名义范围：接近中性的方向能用上，高饱和方向不能。
        val safe = FableSolExportHlgDeviceRange.SafeCodes(
            lumaMaxCode = 1019,
            cbMinCode = 64,
            cbMaxCode = 960,
            crMinCode = 64,
            crMaxCode = 960
        )
        assertTrue(safe.extended)
        val grid = FableSolExportHlgDeviceRange.buildGrid(safe)
        assertNotNull(grid)
        val table = requireNotNull(grid)
        // 每个方向的上限都落在合法区间内，且至少有一个方向真的越过了名义 100%。
        var extendedDirections = 0
        // 表项存的是 Float（要上传给 GL），因此比较留 1e-5 的余量：1.09f 回到 double 是
        // 1.09000003，差的是 Float 的精度，不是算法。
        hlg.forEachDirection(9) { u ->
            val ceiling = table.ceilingFor(u)
            assertTrue(ceiling >= FableSolExportHlgTransform.SIGNAL_NOMINAL - 1e-5)
            assertTrue(ceiling <= FableSolExportHlgTransform.SIGNAL_MAX + 1e-5)
            if (ceiling > FableSolExportHlgTransform.SIGNAL_NOMINAL + 1e-5) extendedDirections++
        }
        assertTrue("至少一个方向应当用得上亮度余量", extendedDirections > 0)
        // 色度受限时最饱和的方向拿不到全部余量：它的上限必须低于全开时的结论。
        val blue = doubleArrayOf(0.0, 0.0, 1.0)
        val full = requireNotNull(
            FableSolExportHlgDeviceRange.buildGrid(
                FableSolExportHlgDeviceRange.SafeCodes(1019, 4, 1019, 4, 1019)
            )
        )
        assertTrue(table.ceilingFor(blue) < full.ceilingFor(blue) + 1e-9)
    }

    @Test
    fun fullyOpenSafeCodesDegenerateToAConstantTable() {
        // 三分量完整扩展区间均可用时 W_device 恒为 W_MAX，查表退化为常数，不引入额外误差。
        val grid = requireNotNull(
            FableSolExportHlgDeviceRange.buildGrid(
                FableSolExportHlgDeviceRange.SafeCodes(1019, 4, 1019, 4, 1019)
            )
        )
        assertEquals(FableSolExportHlgTransform.SIGNAL_MAX, grid.peakCeiling, 1e-6)
        assertNotNull(grid.uniformCeiling)
    }

    @Test
    fun representabilityIsMonotoneInTheCandidateSignal() {
        // 二分的前提：可行性对 W 单调。任取几个方向逐点核对。
        val safe = FableSolExportHlgDeviceRange.SafeCodes(1000, 40, 990, 40, 990)
        val samples = FableSolExportHlgDeviceRange.signalSamples()
        hlg.forEachDirection(5) { u ->
            val scene = hlg.sceneDirection(u)
            var seenFailure = false
            for (signal in samples) {
                val ok = FableSolExportHlgDeviceRange.representable(
                    scene, hlg.hlgInverseOetf(signal), safe
                )
                if (!ok) seenFailure = true
                if (seenFailure) {
                    assertFalse("可行性一旦失败就不得再成立", ok && signal > samples.first())
                }
            }
        }
    }

    // ---- 回环测试图与判定（D139、D140）----

    @Test
    fun laddersStartAtTheNominalEndpointAndReachTheVideoRangeLimit() {
        for (axis in FableSolExportHlgLoopback.Axis.entries) {
            val ladder = FableSolExportHlgLoopback.ladder(axis)
            assertEquals(axis.nominalCode, ladder.first())
            assertEquals(axis.limitCode, ladder.last())
            assertTrue(ladder.size >= 3)
            for (index in 1 until ladder.size) {
                if (axis.ascending) {
                    assertTrue(ladder[index] > ladder[index - 1])
                } else {
                    assertTrue(ladder[index] < ladder[index - 1])
                }
            }
        }
        // 亮度阶梯必须真的到 1019：那一级正是 W_MAX 能不能用满的唯一证据。
        assertEquals(
            1019, FableSolExportHlgLoopback.ladder(FableSolExportHlgLoopback.Axis.LUMA_HIGH).last()
        )
    }

    @Test
    fun patchRectsAreEvenAlignedAndDisjoint() {
        val patches = FableSolExportHlgLoopback.patches()
        val rects = patches.indices.map {
            FableSolExportHlgLoopback.patchRect(it, patches.size, 1152, 1472)
        }
        for (rect in rects) {
            // 4:2:0 的一个色度样本覆盖 2×2 亮度样本；奇数边界会让边界色度混进两块内容。
            assertEquals(0, rect.left % 2)
            assertEquals(0, rect.top % 2)
            assertEquals(0, rect.right % 2)
            assertEquals(0, rect.bottom % 2)
            assertNotNull(FableSolExportHlgLoopback.interiorRect(rect))
        }
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val overlaps = a.left < b.right && b.left < a.right &&
                    a.top < b.bottom && b.top < a.bottom
                assertFalse("色块不得重叠", overlaps)
            }
        }
    }

    @Test
    fun collapsedLaddersStopAtTheLastDistinguishableStep() {
        val axis = FableSolExportHlgLoopback.Axis.LUMA_HIGH
        val ladder = FableSolExportHlgLoopback.ladder(axis)
        val cutoff = ladder[3]
        // 编码器把 cutoff 以上的码值全部钳到 cutoff：误差本身很小，靠"与名义端点的距离"
        // 这一条才判得出来（D139）。
        val readings = ladder.map { code ->
            reading(axis, code, minOf(code, cutoff).toDouble())
        }
        assertEquals(cutoff, FableSolExportHlgLoopback.safeBound(axis, readings))
    }

    /**
     * 末级容差必须小于它与前一级的档距（D172 修订，2026-07-30）。
     *
     * 色度高端末级 1019 与前一级 1016 只差 3 个码值：编码器恰在 1016 钳制时，1019 的读回
     * 误差 3 落在整体容差 6 之内、与名义端点的拉开判据也过——旧判定会把安全上限高报到
     * 1019，随后写出的码值被编码器钳掉。逐级容差 `min(6, 档距 − 1)` 把这一级挡回 1016。
     */
    @Test
    fun aClampAtThePenultimateStepMustNotOverreportTheFinalStep() {
        val axis = FableSolExportHlgLoopback.Axis.CB_HIGH
        val ladder = FableSolExportHlgLoopback.ladder(axis)
        val penultimate = ladder[ladder.size - 2]
        assertEquals(1016, penultimate)
        val readings = ladder.map { code ->
            reading(axis, code, minOf(code, penultimate).toDouble())
        }
        assertEquals(penultimate, FableSolExportHlgLoopback.safeBound(axis, readings))
    }

    @Test
    fun aWrongNominalEndpointInvalidatesTheWholeMeasurement() {
        val axis = FableSolExportHlgLoopback.Axis.CB_HIGH
        val readings = FableSolExportHlgLoopback.ladder(axis).map { code ->
            // 名义端点自己就偏了 40 个码值：其余读数没有可信的比较基准。
            reading(axis, code, code - 40.0)
        }
        assertNull(FableSolExportHlgLoopback.safeBound(axis, readings))
        assertNull(FableSolExportHlgLoopback.deriveSafeCodes(readings))
    }

    @Test
    fun perfectLoopbackYieldsTheFullVideoDataRange() {
        val readings = FableSolExportHlgLoopback.patches().map {
            FableSolExportHlgLoopback.Reading(
                patch = it,
                lumaMedian = it.lumaCode.toDouble(),
                cbMedian = it.cbCode.toDouble(),
                crMedian = it.crCode.toDouble()
            )
        }
        val safe = requireNotNull(FableSolExportHlgLoopback.deriveSafeCodes(readings))
        assertEquals(FableSolExportHlgDeviceRange.SafeCodes.VIDEO_MAX_CODE, safe.lumaMaxCode)
        assertEquals(FableSolExportHlgDeviceRange.SafeCodes.VIDEO_MAX_CODE, safe.cbMaxCode)
        assertEquals(FableSolExportHlgDeviceRange.SafeCodes.VIDEO_MIN_CODE, safe.cbMinCode)
        assertTrue(safe.extended)
        assertEquals(safe, FableSolExportHlgDeviceRange.SafeCodes.decode(safe.encode()))
    }

    @Test
    fun cbAndCrKeepSeparateSafeIntervals() {
        // 两个色度分量在实际编解码路径上未必一样宽；合并成一条会让较窄的那个越界（D140）。
        val cbCutoff = 984
        val readings = FableSolExportHlgLoopback.patches().map { patch ->
            val cb = if (patch.axis == FableSolExportHlgLoopback.Axis.CB_HIGH) {
                minOf(patch.cbCode, cbCutoff)
            } else {
                patch.cbCode
            }
            FableSolExportHlgLoopback.Reading(
                patch = patch,
                lumaMedian = patch.lumaCode.toDouble(),
                cbMedian = cb.toDouble(),
                crMedian = patch.crCode.toDouble()
            )
        }
        val safe = requireNotNull(FableSolExportHlgLoopback.deriveSafeCodes(readings))
        assertEquals(cbCutoff, safe.cbMaxCode)
        assertEquals(FableSolExportHlgDeviceRange.SafeCodes.VIDEO_MAX_CODE, safe.crMaxCode)
    }

    // ---- 请求语义（D137、D141、D143、D144）----

    // 「自动档忽略隐藏的名义范围历史值」（D137）由
    // FableSolExportRequestModelTest.automaticFormatsIgnoreTheHiddenNominalRangePreference
    // 覆盖，不在这里重复一遍。

    @Test
    fun dolbyVision84SharesTheHlgBaseLayerAndKeepsASameFormatFallback() {
        val dolby = FableSolExportHdrFormat.DOLBY_VISION_84
        // 8.4 的兼容基层就是 BT.2020 HLG，因此与普通 HLG 共用同一条变换与同一份验证（D143）。
        assertTrue(dolby.usesHlgBaseLayer)
        assertEquals(FableSolExportTransfer.HLG, dolby.transfer)
        // 不强制字节缓冲输入，因此 P010 之外仍生成同格式 Surface 子候选作为后备（D143 第 3 条）。
        assertFalse(dolby.usesByteBufferInput)
        // 产品能力收敛为 8.4：候选顺序里不得再出现 Profile 5 / 8.1（D141）。
        assertEquals(
            listOf(
                FableSolExportHdrFormat.HDR10_PLUS,
                FableSolExportHdrFormat.DOLBY_VISION_84,
                FableSolExportHdrFormat.HDR10,
                FableSolExportHdrFormat.HLG
            ),
            FableSolExportHdrFormat.AUTO_ORDER
        )
        assertEquals(
            1,
            FableSolExportHdrFormat.entries.count { it.stableLabel.startsWith("Dolby Vision") }
        )
    }

    // ---- 着色器逐行对照 ----

    @Test
    fun exportShaderImplementsTheSameHlgTransform() {
        val source = shader("export_present.frag")

        // 源语义（D132）：先转 BT.2020、按 D_ref 归一、再逆 OOTF。旧代码直接乘 E_ref 套 OETF。
        assertFalse(source.contains("color * 0.26497"))
        assertTrue(source.contains("float scale = uHlgDisplayWhite *"))
        assertTrue(
            source.contains("pow(uHlgDisplayWhite * yD, (1.0 - HLG_GAMMA) / HLG_GAMMA)")
        )
        // q 由本像素反解，与 Kotlin 侧同一条路：m = q(u) · s^(1/γ)。
        assertTrue(source.contains("float normalized = pow(s, 1.0 / HLG_GAMMA)"))
        assertTrue(source.contains("float q = m / normalized"))
        // 查表键是 C_n，不是 q（D164）。
        assertTrue(source.contains("float capacity = hlgInverseOetfChannel(ceiling) / q"))
        assertTrue(source.contains("hlgShoulderXi(capacity)"))
        // 共同增益，不是逐通道软肩。
        assertTrue(source.contains("float gain = mapped / normalized"))
        assertFalse(source.contains("softKnee(scene.r"))

        assertEquals(hlg.GAMMA, constantOf(source, "const float HLG_GAMMA"), 0.0)
        assertEquals(
            FableSolExportHlgTransform.SHOULDER_XI_EPSILON,
            constantOf(source, "const float HLG_XI_EPSILON"),
            0.0
        )
        // HLG OETF 的三个常量：B 与 C 是由 A 导出的，写错一个就整条曲线偏。
        assertEquals(0.17883277, constantOf(source, "const float HLG_A"), 0.0)
        assertEquals(1.0 - 4.0 * 0.17883277, constantOf(source, "const float HLG_B"), 1e-8)
        assertEquals(
            0.5 - 0.17883277 * kotlin.math.ln(4.0 * 0.17883277),
            constantOf(source, "const float HLG_C"),
            1e-8
        )
        // 逆 OOTF 的亮度权重与 BT.2020 NCL 系数同源，写错一项就整幅画面偏色。按数值比对而
        // 不是按字面：着色器写 0.6780、Kotlin 打印成 0.678，两者是同一个数。
        val weights = source
            .substringAfter("const vec3  HLG_LUMA = vec3(")
            .substringBefore(')')
            .split(',')
            .map { it.trim().toDouble() }
        assertEquals(listOf(hlg.LUMA_R, hlg.LUMA_G, hlg.LUMA_B), weights)
    }

    @Test
    fun deviceGridLookupMatchesTheKotlinLayout() {
        val source = shader("export_present.frag")
        // 面的选择规则与 FableSolExportHlgTransform.directionAt 必须完全一致，
        // 纹理下标也必须与 Grid.at 的 face * grid² + j * grid + i 对上。
        assertTrue(source.contains("if (u.r >= u.g && u.r >= u.b) {"))
        assertTrue(source.contains("face = 0; a = u.g; b = u.b;"))
        assertTrue(source.contains("face = 1; a = u.r; b = u.b;"))
        assertTrue(source.contains("face = 2; a = u.r; b = u.g;"))
        assertTrue(source.contains("int base = face * uHlgDeviceGrid"))
        assertTrue(source.contains("texelFetch(uHlgDevice, ivec2(x0, base + y0), 0).r"))
        // 关掉方向表时全方向共用一个上限，名义范围就靠它表达。
        assertTrue(source.contains("if (!uHlgDeviceEnabled) return uHlgDeviceCeiling;"))
    }

    // ---- 辅助 ----

    /** 该方向开始压缩的源显示线性尺度 `C_n^γ`。 */
    private fun capacityScale(u: DoubleArray, superWhite: Boolean): Double {
        val ceiling = if (superWhite) {
            hlg.standardCeiling(u)
        } else {
            FableSolExportHlgTransform.SIGNAL_NOMINAL
        }
        return (hlg.hlgInverseOetf(ceiling) / hlg.directionScale(u)).pow(hlg.GAMMA)
    }

    private fun normalizedCapacity(u: DoubleArray): Double =
        hlg.hlgInverseOetf(hlg.standardCeiling(u)) / hlg.directionScale(u)

    /** 从 [from] 向 [to] 插值，取第一个方向尺度达到 [target] 的单位方向。 */
    private fun firstDirectionWithScale(
        from: DoubleArray,
        to: DoubleArray,
        target: Double
    ): DoubleArray {
        var best = from
        var bestError = Double.MAX_VALUE
        var t = 0.0
        while (t <= 1.0) {
            val mixed = DoubleArray(3) { from[it] + (to[it] - from[it]) * t }
            val peak = maxOf(mixed[0], mixed[1], mixed[2]).coerceAtLeast(1e-9)
            val unit = DoubleArray(3) { mixed[it] / peak }
            val error = abs(hlg.directionScale(unit) - target)
            if (error < bestError) {
                bestError = error
                best = unit
            }
            t += 1.0 / 2048.0
        }
        return best
    }

    private fun reading(
        axis: FableSolExportHlgLoopback.Axis,
        code: Int,
        median: Double
    ): FableSolExportHlgLoopback.Reading {
        val patch = FableSolExportHlgLoopback.patches().first {
            it.axis == axis && it.code == code
        }
        return FableSolExportHlgLoopback.Reading(
            patch = patch,
            lumaMedian = if (axis == FableSolExportHlgLoopback.Axis.LUMA_HIGH) {
                median
            } else {
                patch.lumaCode.toDouble()
            },
            cbMedian = when (axis) {
                FableSolExportHlgLoopback.Axis.CB_HIGH,
                FableSolExportHlgLoopback.Axis.CB_LOW -> median
                else -> patch.cbCode.toDouble()
            },
            crMedian = when (axis) {
                FableSolExportHlgLoopback.Axis.CR_HIGH,
                FableSolExportHlgLoopback.Axis.CR_LOW -> median
                else -> patch.crCode.toDouble()
            }
        )
    }

    private fun shader(name: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(directory, "shared/fablesol/glsl/$name")
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        throw AssertionError("找不到着色器 $name")
    }

    private fun constantOf(source: String, declaration: String): Double {
        val index = source.indexOf(declaration)
        assertTrue("找不到 $declaration", index >= 0)
        val value = source.substring(index + declaration.length)
            .substringAfter('=')
            .substringBefore(';')
            .trim()
            .removePrefix("(")
        return value.substringBefore(',').trim().toDouble()
    }
}
