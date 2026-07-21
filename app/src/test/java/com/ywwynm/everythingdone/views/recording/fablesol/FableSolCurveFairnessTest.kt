package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class FableSolCurveFairnessTest {

    @Test
    fun cubicBsplineRowsAreC2MeanPreservingAndKeepAuthoredWaves() {
        val count = 217
        val step = 720.0 / (count - 1)
        val controls = DoubleArray(count) { index ->
            val x = -360.0 + index * step
            8.0 * sin(2.0 * PI * x / 72.0)
        }
        val values = DoubleArray(count)
        val slopes = DoubleArray(count)

        FableSolCubicResampler.fairCubicBsplineRow(controls, values, slopes, step)

        assertEquals(controls.average(), values.average(), 1e-12)
        val rawRange = controls.maxOrNull()!! - controls.minOrNull()!!
        val fairRange = values.maxOrNull()!! - values.minOrNull()!!
        assertTrue("amplitude ratio=${fairRange / rawRange}", fairRange / rawRange > 0.985)

        for (index in 1 until count - 1) {
            val leftSecond = (
                6.0 * values[index - 1] + 2.0 * step * slopes[index - 1] -
                    6.0 * values[index] + 4.0 * step * slopes[index]
                ) / (step * step)
            val rightSecond = (
                -6.0 * values[index] - 4.0 * step * slopes[index] +
                    6.0 * values[index + 1] - 2.0 * step * slopes[index + 1]
                ) / (step * step)
            assertEquals("node=$index", leftSecond, rightSecond, 1e-11)
        }
    }

    @Test
    fun softLimitMatchesTheSixthOrderReferenceWithoutAFlatClip() {
        for (value in doubleArrayOf(-36.0, -10.0, -3.0, 0.0, 3.0, 10.0, 36.0)) {
            val expected = value / (1.0 + (value / 10.0).pow(6)).pow(1.0 / 6.0)
            assertEquals(expected, FableSolCubicResampler.softLimit(value, 10.0), 1e-12)
        }
        val before = FableSolCubicResampler.softLimit(10.0 - 1e-4, 10.0)
        val after = FableSolCubicResampler.softLimit(10.0 + 1e-4, 10.0)
        assertTrue(after > before)
        assertTrue(FableSolCubicResampler.softLimit(100.0, 10.0) < 10.0)
    }

    @Test
    fun rowwiseMonotoneRepairUsesOneScaleInsteadOfMakingALocalPlateau() {
        val orbit = doubleArrayOf(0.0, 1.0, 2.0, -8.0, 3.0, 2.0)
        val original = orbit.copyOf()
        val baselineStep = 4.0
        val scale = FableSolCubicResampler.repairOrbitRowMonotone(
            orbit, baselineStep, 0.16
        )

        assertTrue(scale >= 0.0 && scale < 1.0)
        for (index in orbit.indices) assertEquals(original[index] * scale, orbit[index], 1e-12)
        for (index in 0 until orbit.lastIndex) {
            val repairedStep = baselineStep + orbit[index + 1] - orbit[index]
            assertTrue("segment=$index step=$repairedStep", repairedStep >= 0.16 * baselineStep - 1e-12)
        }

        val alreadyValid = doubleArrayOf(0.0, 0.2, -0.1, 0.3)
        val identity = alreadyValid.copyOf()
        assertEquals(1.0, FableSolCubicResampler.repairOrbitRowMonotone(
            alreadyValid, baselineStep, 0.16
        ), 0.0)
        assertTrue(identity.contentEquals(alreadyValid))
    }

    @Test
    fun sampledSurfacePublishesFairGeometryAndAnalyticOrbitTangents() {
        val sim = FableSolSimulation(FableSolParams())
        repeat(180) { sim.update(1.0 / 60.0) }

        val sample = sim.surface2d.sample(sim)
        val step = FableSolSpec.DX_DP
        // sample() 只对渲染窗口求值，窗口外是上一帧的陈旧值。窗口必须完整覆盖
        // buildFrame 会读到的 [i0, i1-1]，否则画面边缘会取到陈旧几何。
        val info = sim.continuousRenderInfo()
        assertTrue(
            "窗口 [${sample.windowLo}, ${sample.windowHi}] 未覆盖渲染区间 " +
                "[${info.i0}, ${info.i1 - 1}]",
            sample.windowLo <= info.i0 && sample.windowHi >= info.i1 - 1
        )
        assertTrue("窗口应窄于全网格才有收益", sample.windowHi - sample.windowLo < FableSolSpec.N_POINTS - 1)
        for (row in 0 until FableSolContinuousSurface.Z_ROWS) {
            for (index in maxOf(sample.windowLo + 1, 1)
                until minOf(sample.windowHi, FableSolSpec.N_POINTS - 1)) {
                val centered = (
                    sample.orbitX[row][index + 1] - sample.orbitX[row][index - 1]
                    ) / (2.0 * step)
                // 中心差分只是解析 B-spline 切线的二阶近似；生产谱包含 58dp
                // 短模态，允许相应截断误差，但两者必须保持同一量级。
                assertEquals(centered, sample.orbitXSlope[row][index], 1e-2)
                val worldStep = step +
                    sample.orbitX[row][index + 1] - sample.orbitX[row][index]
                assertTrue("row=$row index=$index step=$worldStep", worldStep > 0.0)
                assertTrue(sample.orbitZSlope[row][index].isFinite())
                assertTrue(sample.slopeX[row][index].isFinite())
                assertTrue(sample.slopeZ[row][index].isFinite())
            }
        }
        assertSame(sample, sim.surface2d.sample(sim))
    }

    @Test
    fun glVertexBuilderUsesTheSharedFairCurvesAndProjectedRowRepair() {
        val source = rendererSource()

        // 守的是「顶点重建消费共享 fair 曲线的解析切线」这一契约，不是某种写法。
        // C5 把这两个二维数组的行引用提到行循环外（逐顶点 aaload 降为逐行一次），
        // 断言随之改成行级引用形式；消费的元素与数学未变。
        assertTrue(source.contains("sample.orbitXSlope[row]"))
        assertTrue(source.contains("sample.orbitZSlope[row]"))
        assertTrue(source.contains("orbitXSlopeRow[index]"))
        assertTrue(source.contains("orbitZSlopeRow[index]"))
        assertTrue(source.contains("val layerPosition = z01 *"))
        assertTrue(source.contains("monotoneBlendBound("))
        assertTrue(source.contains("private val projectedX = DoubleArray("))
        assertFalse(source.contains("private val sourceBefore"))
        assertFalse(source.contains("private val sourceAfter"))
        assertFalse(source.contains(".coerceIn(-10.0, 10.0)"))
        assertFalse(source.contains("val layerPosition = z01.coerceIn"))
    }

    private fun rendererSource(): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(
                directory,
                "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                    "FableSolGlRenderer.kt"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 FableSolGlRenderer.kt")
    }
}
