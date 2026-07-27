package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 曲线写错的后果是"播放端色调映射离谱"，而那在设备上极难反查——只能在这里钉住。
 *
 * 三件必须成立的事：曲线**单调**（否则画面会出现亮度反转）、膝点处**斜率连续**（否则能看到
 * 一道折痕）、膝点**随时间平滑**（否则背景亮度会一跳一跳地呼吸）。
 */
class FableSolExportHdr10PlusCurveTest {

    private val masteringPeak = 1949.0

    @Test
    fun curveIsMonotonicFromZeroToOne() {
        val shape = curveFor(peakNits = 1949.0, diffuseTopNits = 400.0)
        var previous = -1.0
        var x = 0.0
        while (x <= 1.0001) {
            val y = evaluate(shape, x)
            assertTrue("y 必须单调不减，x=$x", y >= previous - 1e-6)
            previous = y
            x += 0.005
        }
        // 端点：0 映到 0，母版峰值映到目标显示峰值。
        assertEquals(0.0, evaluate(shape, 0.0), 1e-9)
        assertEquals(1.0, evaluate(shape, 1.0), 1e-6)
    }

    /**
     * 膝点两侧斜率必须接上。第一个锚点正是为此解出来的——接不上就会在水体主体与高光的
     * 交界处看到一道折痕。
     */
    @Test
    fun slopeIsContinuousAcrossTheKnee() {
        val shape = curveFor(peakNits = 1949.0, diffuseTopNits = 400.0)
        val delta = 1e-4
        val below = (evaluate(shape, shape.kneeX) - evaluate(shape, shape.kneeX - delta)) / delta
        val above = (evaluate(shape, shape.kneeX + delta) - evaluate(shape, shape.kneeX)) / delta
        assertTrue("膝点两侧斜率相差过大：$below vs $above", abs(below - above) < 0.05 * below)
    }

    /**
     * 整帧都装得进目标显示时不该压缩：膝点落在峰值上，也就是说峰值以下全线性。
     */
    @Test
    fun framesThatFitTheTargetAreNotCompressed() {
        val peak = 600.0
        val shape = curveFor(peakNits = peak, diffuseTopNits = 200.0)
        assertEquals(peak / masteringPeak, shape.kneeX, 1e-6)
        assertEquals(peak / FableSolExportHdr10PlusCurve.DEFAULT_TARGET_NITS, shape.kneeY, 1e-6)
    }

    /**
     * 膝点必须**快起慢落**：高光涌上来时迅速让出空间（慢了会削顶），退去后缓慢回升
     * （快了背景会闪）。这里用同样的帧数分别驱动两个方向，落的那次必须走得更远。
     */
    @Test
    fun kneeFallsFastAndRisesSlowly() {
        val dt = 1.0 / 120.0
        val bright = stats(peakNits = 1949.0, diffuseTopNits = 300.0)
        val calm = stats(peakNits = 600.0, diffuseTopNits = 500.0)

        val falling = FableSolExportHdr10PlusCurve(masteringPeak)
        falling.next(calm, dt)
        val restingKnee = falling.next(calm, dt).kneeX
        repeat(12) { falling.next(bright, dt) }
        val afterFall = falling.next(bright, dt).kneeX

        val rising = FableSolExportHdr10PlusCurve(masteringPeak)
        rising.next(bright, dt)
        val loweredKnee = rising.next(bright, dt).kneeX
        repeat(12) { rising.next(calm, dt) }
        val afterRise = rising.next(calm, dt).kneeX

        val fallen = restingKnee - afterFall
        val risen = afterRise - loweredKnee
        assertTrue("膝点下降必须比上升快得多：$fallen vs $risen", fallen > risen * 3)
    }

    /**
     * **第一个控制点永远不能被夹到 1。**
     *
     * 它是斜率连续解出来的 `P[1] = (M − k) / (N(T − k))`；一旦超过 1 只能夹死，那样所有控制点
     * 都变成 1，肩部从膝点几乎垂直冲到顶——膝点以上的一切被压成同一个亮度。各通道于是在不同
     * 位置撞顶，播放端逐通道处理时就会偏色（用户把漫反射白拉到 800 后，星芒出现时背景发青，
     * 正是这个形态）。这里覆盖整条漫反射白滑杆 × 各种强度。
     */
    @Test
    fun shoulderNeverDegeneratesAcrossTheWholeWhitePointRange() {
        for (white in 200..800 step 25) {
            for (strength in listOf(2.0, 4.0, 6.0, 9.6)) {
                val mastering = white * strength
                val target = FableSolExportHdr10PlusCurve.targetNitsFor(
                    masteringPeakNits = mastering,
                    whiteNits = white.toDouble(),
                    panelPeakNits = 2000f
                )
                val shape = FableSolExportHdr10PlusCurve(mastering, target).next(
                    stats(peakNits = mastering, diffuseTopNits = white.toDouble()),
                    1.0 / 120.0
                )
                val first = shape.anchors.first()
                assertTrue(
                    "白点 $white、强度 $strength：第一个控制点被夹死了（$first）",
                    first < 0.999
                )
                // 夹死的另一个特征是所有控制点都变成 1。
                assertTrue("白点 $white、强度 $strength：肩部退化成平顶",
                    shape.anchors.any { it < 0.999 })
            }
        }
    }

    /** 目标峰值必须落在"至少两倍漫反射白"与"不超过母版峰值"之间。 */
    @Test
    fun targetLuminanceStaysBetweenDiffuseWhiteAndMasteringPeak() {
        val target = FableSolExportHdr10PlusCurve.targetNitsFor(
            masteringPeakNits = 800.0 * 9.6,
            whiteNits = 800.0,
            panelPeakNits = 2000f
        )
        assertTrue("目标必须给高光留下至少两倍空间", target >= 1600.0)
        assertTrue("目标不能高过母版峰值", target <= 800.0 * 9.6)

        // 屏幕没声明峰值时退回 1000，但仍不得低于两倍漫反射白。
        val fallback = FableSolExportHdr10PlusCurve.targetNitsFor(
            masteringPeakNits = 800.0 * 9.6,
            whiteNits = 800.0,
            panelPeakNits = null
        )
        assertTrue("读不到屏幕时也要保住两倍空间", fallback >= 1600.0)
    }

    /**
     * 「高光起点」是连续可调的，而码流里只带 9 个标准分位点，所以中间要插值。
     * 插错的表现是滑杆推到某些位置时膝点原地不动（取了最近的那个分位点），手感发涩。
     */
    @Test
    fun highlightStartInterpolatesBetweenTheStandardPercentiles() {
        val stats = FableSolHdr10PlusStats(
            maxsclNits = doubleArrayOf(1949.0, 1949.0, 1949.0),
            averageMaxRgbNits = 200.0,
            // 与 PERCENTAGES = [1,5,10,25,50,75,90,95,99] 一一对应。
            percentileNits = doubleArrayOf(
                100.0, 140.0, 180.0, 260.0, 400.0, 600.0, 800.0, 900.0, 1000.0
            )
        )
        assertEquals(800.0, stats.nitsAtPercent(90), 1e-9)
        assertEquals(600.0, stats.nitsAtPercent(75), 1e-9)
        // 75 与 90 正中间：(600+800)/2。
        assertEquals(700.0, stats.nitsAtPercent(82), 20.0)
        // 越界两端各自夹住，不能外推。
        assertEquals(100.0, stats.nitsAtPercent(0), 1e-9)
        assertEquals(1000.0, stats.nitsAtPercent(100), 1e-9)

        // 起点调高 ⇒ 更多画面留在恒等段里 ⇒ 膝点更高。
        // 峰值必须**高过**目标显示峰值，否则整帧都装得下、膝点直接落在峰值上，
        // 高光起点根本不参与——那是另一条分支，不是这条要测的。
        val bright = FableSolHdr10PlusStats(
            maxsclNits = doubleArrayOf(7680.0, 7680.0, 7680.0),
            averageMaxRgbNits = stats.averageMaxRgbNits,
            percentileNits = stats.percentileNits
        )
        val low = FableSolExportHdr10PlusCurve(7680.0, 2000.0, 50)
            .next(bright, 1.0 / 120.0).kneeX
        val high = FableSolExportHdr10PlusCurve(7680.0, 2000.0, 99)
            .next(bright, 1.0 / 120.0).kneeX
        assertTrue("高光起点调高必须让膝点上移：$low vs $high", high > low)
    }

    private fun curveFor(peakNits: Double, diffuseTopNits: Double) =
        FableSolExportHdr10PlusCurve(
            masteringPeak,
            FableSolExportHdr10PlusCurve.DEFAULT_TARGET_NITS
        ).next(stats(peakNits, diffuseTopNits), 1.0 / 120.0)

    private fun stats(peakNits: Double, diffuseTopNits: Double): FableSolHdr10PlusStats {
        val percentiles = DoubleArray(FableSolExportHdr10PlusMetadata.PERCENTAGES.size) {
            diffuseTopNits
        }
        return FableSolHdr10PlusStats(
            maxsclNits = doubleArrayOf(peakNits, peakNits * 0.9, peakNits * 0.8),
            averageMaxRgbNits = diffuseTopNits * 0.6,
            percentileNits = percentiles
        )
    }

    /** 与 libplacebo 的 ST2094-40 实现同一套公式：线性段 + 伯恩斯坦多项式肩部。 */
    private fun evaluate(shape: FableSolExportHdr10PlusCurve.Shape, x: Double): Double {
        if (x <= shape.kneeX) return x * shape.kneeY / shape.kneeX
        val t = (x - shape.kneeX) / (1.0 - shape.kneeX)
        val degree = shape.anchors.size + 1
        val points = DoubleArray(degree + 1)
        points[0] = 0.0
        for (index in shape.anchors.indices) points[index + 1] = shape.anchors[index]
        points[degree] = 1.0
        var bezier = 0.0
        for (p in 0..degree) {
            bezier += binomial(degree, p) * t.pow(p) * (1.0 - t).pow(degree - p) * points[p]
        }
        return shape.kneeY + (1.0 - shape.kneeY) * bezier
    }

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }

    private fun binomial(n: Int, k: Int): Double {
        var result = 1.0
        for (index in 1..k) {
            result = result * (n - k + index) / index
        }
        return result
    }
}
