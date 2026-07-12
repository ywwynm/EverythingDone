package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FableSolShadowColorPolicyTest {

    private val black = intArrayOf(0, 0, 0)
    private val noteColors = arrayOf(
        intArrayOf(240, 42, 75),
        intArrayOf(46, 139, 87),
        intArrayOf(61, 111, 224),
        intArrayOf(229, 185, 61),
        intArrayOf(170, 104, 205)
    )

    @Test
    fun backShadeIsOnlyTheLayerColorMixedWithBlack() {
        for (base in noteColors) {
            val expected = FableSolColor.mixOklab(
                base, black, FableSolShadowColorPolicy.BACK_BLACK_MIX)
            assertArrayEquals(expected,
                FableSolShadowColorPolicy.backShade(base, hueTemperatureDeg = 5.0, depth01 = 0.0))
        }
    }

    @Test
    fun gustShadeIsOnlyTheLayerColorMixedWithBlack() {
        for (base in noteColors) {
            val expected = FableSolColor.mixOklab(
                base, black, FableSolShadowColorPolicy.GUST_BLACK_MIX)
            assertArrayEquals(expected, FableSolShadowColorPolicy.gustShade(base, depth01 = 0.0))
        }
    }

    @Test
    fun blackMixDecreasesWithLayerDepth() {
        val depths = doubleArrayOf(0.0, 0.25, 0.625, 1.0)
        for (base in noteColors) {
            for (depth in depths) {
                val depthScale = ((1.0 - depth) * (1.0 - depth)).coerceAtLeast(0.05)
                assertArrayEquals(
                    FableSolColor.mixOklab(
                        base, black, FableSolShadowColorPolicy.BACK_BLACK_MIX * depthScale),
                    FableSolShadowColorPolicy.backShade(base, 5.0, depth)
                )
                assertArrayEquals(
                    FableSolColor.mixOklab(
                        base, black, FableSolShadowColorPolicy.GUST_BLACK_MIX * depthScale),
                    FableSolShadowColorPolicy.gustShade(base, depth)
                )
            }
        }
    }
}
