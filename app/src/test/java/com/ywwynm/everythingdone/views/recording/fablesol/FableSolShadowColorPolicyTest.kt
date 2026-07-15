package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolShadowColorPolicyTest {

    private val noteColors = arrayOf(
        intArrayOf(240, 42, 75),
        intArrayOf(46, 139, 87),
        intArrayOf(61, 111, 224),
        intArrayOf(229, 185, 61),
        intArrayOf(170, 104, 205)
    )

    @Test
    fun backShadeDeepensLayerColorByReducingLightnessNotMixingBlack() {
        for (base in noteColors) {
            val expected = FableSolColor.darkenOklab(base, FableSolShadowColorPolicy.BACK_DARKEN_L)
            assertArrayEquals(expected,
                FableSolShadowColorPolicy.backShade(base, hueTemperatureDeg = 5.0, depth01 = 0.0))
        }
    }

    @Test
    fun colorDepthIsUniformAndLayerPresenceIsControlledByGeometryAlpha() {
        val depths = doubleArrayOf(0.0, 0.25, 0.625, 1.0)
        for (base in noteColors) {
            for (depth in depths) {
                assertArrayEquals(
                    FableSolColor.darkenOklab(base, FableSolShadowColorPolicy.BACK_DARKEN_L),
                    FableSolShadowColorPolicy.backShade(base, 5.0, depth)
                )
            }
        }
    }

    @Test
    fun macroShadeUsesEachCurrentLayerColorInsteadOfReusingIdentityDeepColor() {
        val near = intArrayOf(61, 111, 224)
        val far = FableSolLayerColorPolicy.ramp(
            near, lightenFar = 0.6, moodBright = 0.2, breath = 0.0
        ).colorAt(0.75)

        val nearShade = FableSolShadowColorPolicy.macroShade(near)
        val farShade = FableSolShadowColorPolicy.macroShade(far)

        assertFalse(nearShade.contentEquals(farShade))
        assertTrue(FableSolColor.rgbToOklab(farShade)[0] >
            FableSolColor.rgbToOklab(nearShade)[0])
        assertTrue(hueDistanceDeg(far, farShade) <= 1.5)
    }

    @Test
    fun shadeIsFixedHueAndNearBlackSafe() {
        val samples = noteColors + arrayOf(
            intArrayOf(9, 3, 7),
            intArrayOf(2, 2, 2)
        )
        for (base in samples) {
            val shade = FableSolShadowColorPolicy.shade(base, 0.10)
            assertTrue(shade.all { it in 0..255 })
            assertTrue(FableSolColor.rgbToOklab(shade)[0] <=
                FableSolColor.rgbToOklab(base)[0] + 0.002)
            if (chroma(base) >= 0.02 && chroma(shade) >= 0.01) {
                assertTrue(hueDistanceDeg(base, shade) <= 2.0)
            }
        }
    }

    private fun chroma(color: IntArray): Double {
        val lab = FableSolColor.rgbToOklab(color)
        return kotlin.math.hypot(lab[1], lab[2])
    }

    private fun hueDistanceDeg(first: IntArray, second: IntArray): Double {
        fun hue(color: IntArray): Double {
            val lab = FableSolColor.rgbToOklab(color)
            return atan2(lab[2], lab[1])
        }
        var delta = abs(hue(first) - hue(second))
        if (delta > PI) delta = 2.0 * PI - delta
        return Math.toDegrees(delta)
    }
}
