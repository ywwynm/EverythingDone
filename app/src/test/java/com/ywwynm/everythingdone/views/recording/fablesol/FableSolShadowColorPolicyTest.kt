package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
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
    fun deepeningDecreasesWithLayerDepth() {
        val depths = doubleArrayOf(0.0, 0.25, 0.625, 1.0)
        for (base in noteColors) {
            for (depth in depths) {
                val depthScale = ((1.0 - depth) * (1.0 - depth)).coerceAtLeast(0.05)
                assertArrayEquals(
                    FableSolColor.darkenOklab(
                        base, FableSolShadowColorPolicy.BACK_DARKEN_L * depthScale),
                    FableSolShadowColorPolicy.backShade(base, 5.0, depth)
                )
            }
        }
    }
}
