package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ambient 与 Hero 的空间采样在等距网格上改用相位旋转递推，把每帧的 libm 调用
 * 从 9 层 × 216 点 × (4 + 6×2) = 31104 次降到常数级。这组测试锁定：
 * 递推结果与逐点直接求值在生产网格上一致，且非均匀网格会落回直接求值。
 */
class FableSolWavePhaseRecurrenceTest {

    @Test
    fun productionGridIsDetectedAsUniform() {
        val uniform = DoubleArray(FableSolSpec.N_POINTS) {
            (it - (FableSolSpec.N_POINTS - 1) / 2.0) * FableSolSpec.DX_DP
        }
        assertTrue(FableSolWaveRecurrence.isUniform(uniform, uniform.size))

        // 生产里 Hero 拿到的是 uGrid 加一个常量平移，仍然等距。
        val shifted = DoubleArray(uniform.size) { uniform[it] + 17.25 }
        assertTrue(FableSolWaveRecurrence.isUniform(shifted, shifted.size))
    }

    @Test
    fun nonUniformGridFallsBackToDirectEvaluation() {
        val ramp = DoubleArray(64) { it.toDouble() * it.toDouble() * 0.01 }
        assertFalse(FableSolWaveRecurrence.isUniform(ramp, ramp.size))

        val kinked = DoubleArray(64) { it * 3.0 }
        kinked[40] += 1.5
        assertFalse(FableSolWaveRecurrence.isUniform(kinked, kinked.size))

        val constant = DoubleArray(8) { 4.0 }
        assertFalse(FableSolWaveRecurrence.isUniform(constant, constant.size))
    }

    /**
     * C11 把 Hero 的「均匀性判定」与「最小采样间隔」合成一趟扫描。两条判定路径
     * 必须永远同结论，最小步长必须与旧的 `MAX_VALUE 起步、i=1 开始扫` 逐位相同。
     */
    @Test
    fun 合并扫描与独立判定同结论且最小步长逐位相同() {
        val grids = listOf(
            DoubleArray(FableSolSpec.N_POINTS) {
                (it - (FableSolSpec.N_POINTS - 1) / 2.0) * FableSolSpec.DX_DP
            },
            DoubleArray(64) { it.toDouble() * it.toDouble() * 0.01 },
            DoubleArray(64) { it * 3.0 }.also { it[40] += 1.5 },
            DoubleArray(8) { 4.0 },
            DoubleArray(1) { 7.0 },
            DoubleArray(2) { it * -2.5 },
            DoubleArray(6) { if (it == 3) Double.NaN else it * 1.5 }
        )
        val scan = FableSolWaveRecurrence.StepScan()
        for (grid in grids) {
            val lenX = grid.size
            FableSolWaveRecurrence.scanSteps(grid, lenX, scan)
            assertEquals(
                FableSolWaveRecurrence.isUniform(grid, lenX),
                scan.uniform
            )
            var expected = Double.MAX_VALUE
            for (i in 1 until lenX) {
                val d = grid[i] - grid[i - 1]
                if (d < expected) expected = d
            }
            assertEquals(
                java.lang.Double.doubleToRawLongBits(expected),
                java.lang.Double.doubleToRawLongBits(scan.minimumStep)
            )
        }
    }

    @Test
    fun ambientRecurrenceMatchesDirectEvaluation() {
        val grid = DoubleArray(FableSolSpec.N_POINTS) {
            (it - (FableSolSpec.N_POINTS - 1) / 2.0) * FableSolSpec.DX_DP
        }
        // 同 seed 两个实例、同步推进相位，一个走递推、一个被强制走逐点直接求值。
        val recurrent = FableSolAmbientSet(4242L, 160.0)
        val direct = FableSolAmbientSet(4242L, 160.0).apply {
            forceDirectEvaluationForTest = true
        }
        val outRecurrent = DoubleArray(grid.size)
        val outDirect = DoubleArray(grid.size)
        var worst = 0.0

        repeat(30) { step ->
            recurrent.advance(1.0 / 120.0, -120.0)
            direct.advance(1.0 / 120.0, -120.0)
            val t = step * (1.0 / 120.0)
            recurrent.sampleInto(grid, t, 5.5, 0.27, outRecurrent)
            direct.sampleInto(grid, t, 5.5, 0.27, outDirect)
            for (i in outRecurrent.indices) {
                worst = maxOf(worst, abs(outDirect[i] - outRecurrent[i]))
            }
        }
        // 幅度量级为 dp；下游转 float32 的精度约 6e-8 相对，这里要求好几个数量级。
        assertTrue("递推与直接求值最大偏差 $worst 应远低于可见精度", worst < 1e-11)
    }

    @Test
    fun heroRecurrenceMatchesDirectEvaluation() {
        val grid = DoubleArray(FableSolSpec.N_POINTS) {
            (it - (FableSolSpec.N_POINTS - 1) / 2.0) * FableSolSpec.DX_DP + 17.25
        }
        val recurrent = FableSolHeroWave(9001L, 0.25).apply { retune(360.0) }
        val direct = FableSolHeroWave(9001L, 0.25).apply {
            retune(360.0)
            forceDirectEvaluationForTest = true
        }
        val bands = Array(3) { band -> DoubleArray(grid.size) { i -> 8.0 + band * 2.0 + i * 0.01 } }
        val outRecurrent = DoubleArray(grid.size)
        val outDirect = DoubleArray(grid.size)
        var worst = 0.0

        repeat(30) { step ->
            recurrent.advance(1.0 / 120.0, -150.0)
            direct.advance(1.0 / 120.0, -150.0)
            val t = step * (1.0 / 120.0)
            recurrent.sampleInto(grid, bands, t, 0.42, null, 0.35, outRecurrent)
            direct.sampleInto(grid, bands, t, 0.42, null, 0.35, outDirect)
            for (i in outRecurrent.indices) {
                worst = maxOf(worst, abs(outDirect[i] - outRecurrent[i]))
            }
        }
        assertTrue("递推与直接求值最大偏差 $worst 应远低于可见精度", worst < 1e-9)
    }

    /**
     * 递推的真正数值合同：`cos(φ + iΔ)` 的旋转递推在 216 步内的漂移必须远小于
     * 下游 float32 的精度（约 6e-8）。这里直接验证递推本身。
     */
    @Test
    fun rotationRecurrenceDriftStaysFarBelowFloatPrecision() {
        val steps = FableSolSpec.N_POINTS
        val delta = 2.0 * Math.PI / 137.0
        val start = 0.937
        var s = kotlin.math.sin(start)
        var c = kotlin.math.cos(start)
        val stepSin = kotlin.math.sin(delta)
        val stepCos = kotlin.math.cos(delta)
        var worst = 0.0
        for (i in 0 until steps) {
            val exactSin = kotlin.math.sin(start + i * delta)
            val exactCos = kotlin.math.cos(start + i * delta)
            worst = maxOf(worst, abs(s - exactSin), abs(c - exactCos))
            val nextCos = c * stepCos - s * stepSin
            s = s * stepCos + c * stepSin
            c = nextCos
        }
        assertTrue("216 步递推漂移 $worst 应远低于 float32 精度", worst < 1e-12)
    }

    @Test
    fun heroSampleStaysFiniteAndZeroWhenBandEnergyIsNegligible() {
        val hero = FableSolHeroWave(9001L, 0.25)
        hero.retune(360.0)
        val grid = DoubleArray(FableSolSpec.N_POINTS) {
            (it - (FableSolSpec.N_POINTS - 1) / 2.0) * FableSolSpec.DX_DP
        }
        val out = DoubleArray(grid.size)

        val silent = Array(3) { DoubleArray(grid.size) }
        hero.sampleInto(grid, silent, 0.0, 0.42, null, 0.0, out)
        for (value in out) assertEquals(0.0, value, 0.0)

        val loud = Array(3) { band -> DoubleArray(grid.size) { 12.0 + band * 3.0 } }
        repeat(20) { step ->
            hero.advance(1.0 / 120.0, -150.0)
            hero.sampleInto(grid, loud, step * (1.0 / 120.0), 0.42, null, 0.35, out)
            for (value in out) assertTrue("step=$step 输出必须有限", value.isFinite())
        }
    }
}
