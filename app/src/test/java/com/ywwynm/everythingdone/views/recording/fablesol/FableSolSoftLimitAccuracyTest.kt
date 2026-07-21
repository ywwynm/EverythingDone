package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `softLimit` 的立方根从 `Math.cbrt` 换成纯算术实现（Android ART 上 `Math.cbrt`
 * 是 native 调用，debuggable 构建里每次约 1µs，而它每帧要跑 41904 次）。
 * 这组测试锁定替换后的数值等价性。
 */
class FableSolSoftLimitAccuracyTest {

    @Test
    fun cbrtMatchesLibraryAcrossTheReachableRange() {
        // softLimit 传入的永远是 1 + ratio^6 ≥ 1；覆盖到远超实际可达的上界。
        var x = 1.0
        var worstRelative = 0.0
        while (x < 1e18) {
            val expected = cbrt(x)
            val actual = FableSolCubicResampler.cbrtAtLeastOne(x)
            val relative = abs(actual - expected) / expected
            if (relative > worstRelative) worstRelative = relative
            assertTrue("x=$x expected=$expected actual=$actual rel=$relative", relative < 1e-14)
            x *= 1.0000173
        }
        assertTrue("最差相对误差 $worstRelative 应在 1ulp 量级", worstRelative < 1e-14)
    }

    @Test
    fun cbrtOfOneIsExact() {
        assertEquals(1.0, FableSolCubicResampler.cbrtAtLeastOne(1.0), 0.0)
    }

    @Test
    fun softLimitIsIdentityAtZeroAndStaysMonotone() {
        assertEquals(0.0, FableSolCubicResampler.softLimit(0.0, 10.0), 0.0)
        assertEquals(-0.0, FableSolCubicResampler.softLimit(-0.0, 10.0), 0.0)

        var previous = FableSolCubicResampler.softLimit(-40.0, 10.0)
        var value = -40.0
        while (value <= 40.0) {
            val current = FableSolCubicResampler.softLimit(value, 10.0)
            assertTrue("softLimit 必须单调不减：value=$value", current >= previous - 1e-12)
            previous = current
            value += 0.01
        }
    }

    @Test
    fun softLimitMatchesTheLibraryFormulaWithinFloatPrecision() {
        // 下游会转成 float（相对精度约 1.2e-7），这里要求好上四个数量级。
        var value = -60.0
        while (value <= 60.0) {
            val reference = if (value == 0.0) {
                value
            } else {
                val ratio = value / 10.0
                val ratio2 = ratio * ratio
                value / sqrt(cbrt(1.0 + ratio2 * ratio2 * ratio2))
            }
            val actual = FableSolCubicResampler.softLimit(value, 10.0)
            assertEquals("value=$value", reference, actual, 1e-11 + abs(reference) * 1e-14)
            value += 0.001
        }
    }

    /** 软饱和的意义在于不出现硬平台：极值处导数必须仍为正。 */
    @Test
    fun softLimitHasNoPlateauAtLargeMagnitude() {
        val a = FableSolCubicResampler.softLimit(200.0, 10.0)
        val b = FableSolCubicResampler.softLimit(200.1, 10.0)
        assertTrue("大幅值处必须仍有正导数：a=$a b=$b", b > a)
    }
}
