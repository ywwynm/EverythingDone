package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FableSolLightColorPolicyTest {

    @Test
    fun darkerLongitudinalLightStaysOnTheNoteToBlackAxis() {
        val bases = intArrayOf(
            rgb(240, 42, 75), rgb(46, 139, 87), rgb(61, 111, 224),
            rgb(229, 185, 61), rgb(170, 104, 205)
        )
        val grayBlackCandidate = rgb(35, 42, 50)
        for (base in bases) {
            assertEquals(expectedShadow(base, grayBlackCandidate, 0.0),
                FableSolLightColorPolicy.resolveLongitudinal(base, grayBlackCandidate, 0.0))
            assertEquals(expectedShadow(base, grayBlackCandidate, 0.625),
                FableSolLightColorPolicy.resolveLongitudinal(base, grayBlackCandidate, 0.625))
        }
    }

    @Test
    fun positiveLongitudinalLightKeepsItsPhysicalCandidateColor() {
        val base = rgb(80, 90, 120)
        val lit = rgb(150, 165, 210)
        assertEquals(lit, FableSolLightColorPolicy.resolveLongitudinal(base, lit, 0.5))
    }

    @Test
    fun farLongitudinalShadowIsAlmostInvisible() {
        val base = rgb(230, 150, 175)
        val candidate = rgb(20, 24, 28)
        val near = FableSolLightColorPolicy.resolveLongitudinal(base, candidate, 0.0)
        val far = FableSolLightColorPolicy.resolveLongitudinal(base, candidate, 1.0)
        assertTrue(colorDistance(base, far) * 8 < colorDistance(base, near))
    }

    private fun expectedShadow(base: Int, candidate: Int, depth01: Double): Int {
        val baseL = luminance(base)
        val candidateL = luminance(candidate)
        if (candidateL >= baseL) return candidate
        val darkness = ((baseL - candidateL) / max(baseL, 1.0)).coerceIn(0.0, 1.0)
        val remain = 1.0 - depthScale(depth01) *
            FableSolLightColorPolicy.MAX_LIGHT_SHADOW_BLACK_MIX * sqrt(darkness)
        return rgb(
            (red(base) * remain).roundToInt(),
            (green(base) * remain).roundToInt(),
            (blue(base) * remain).roundToInt()
        )
    }

    private fun depthScale(depth01: Double): Double =
        (1.0 - depth01.coerceIn(0.0, 1.0)).let { it * it }.coerceAtLeast(0.05)

    private fun luminance(c: Int): Double =
        0.2126 * red(c) + 0.7152 * green(c) + 0.0722 * blue(c)

    private fun colorDistance(a: Int, b: Int): Int =
        kotlin.math.abs(red(a) - red(b)) + kotlin.math.abs(green(a) - green(b)) +
            kotlin.math.abs(blue(a) - blue(b))

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun red(c: Int): Int = c ushr 16 and 0xff
    private fun green(c: Int): Int = c ushr 8 and 0xff
    private fun blue(c: Int): Int = c and 0xff
}
