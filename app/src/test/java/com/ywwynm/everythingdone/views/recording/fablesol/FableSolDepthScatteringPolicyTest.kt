package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolDepthScatteringPolicyTest {

    @Test
    fun `derived palette only contains lighter subsurface water`() {
        val base = intArrayOf(220, 72, 132)
        val palette = FableSolDepthScatteringPolicy.derive(base)
        val baseL = FableSolColor.rgbToOklab(base)[0]

        assertTrue(FableSolColor.rgbToOklab(palette.subsurface)[0] > baseL)
    }

    @Test
    fun `subsurface follows the bounded additive lightness formula without active desaturation`() {
        val samples = arrayOf(
            intArrayOf(36, 58, 84),
            intArrayOf(112, 128, 146),
            intArrayOf(210, 196, 180),
            intArrayOf(255, 255, 255)
        )

        for (base in samples) {
            val baseLab = FableSolColor.rgbToOklab(base)
            val lightness = baseLab[0]
            val darkLift = 0.045 + 0.025 *
                (1.0 - smoothstep(0.35, 0.75, lightness))
            val delta = darkLift * (1.0 - smoothstep(0.90, 0.985, lightness))
            val targetLightness = (lightness + delta)
                .coerceAtMost(0.965)
                .coerceAtLeast(lightness)
            val expected = FableSolColor.withOklabLightness(base, targetLightness)

            assertArrayEquals(expected, FableSolDepthScatteringPolicy.derive(base).subsurface)
        }
    }

    @Test
    fun `pure white stays white and shallow chromatic colors never collapse to neutral white`() {
        val white = intArrayOf(255, 255, 255)
        assertArrayEquals(white, FableSolDepthScatteringPolicy.derive(white).subsurface)

        val shallowColors = arrayOf(
            intArrayOf(255, 220, 230),
            intArrayOf(255, 255, 200),
            intArrayOf(200, 250, 255)
        )
        for (base in shallowColors) {
            val subsurface = FableSolDepthScatteringPolicy.derive(base).subsurface
            val baseLab = FableSolColor.rgbToOklab(base)
            val subsurfaceLab = FableSolColor.rgbToOklab(subsurface)

            assertTrue(subsurfaceLab[0] + 0.0015 >= baseLab[0])
            assertTrue(subsurface.maxOrNull()!! - subsurface.minOrNull()!! > 1)
            assertTrue(hypot(subsurfaceLab[1], subsurfaceLab[2]) > 0.005)
            val hueDistance = hueDistanceDeg(base, subsurface)
            assertTrue(
                "base=${base.contentToString()}, subsurface=${subsurface.contentToString()}, " +
                    "hueDistance=$hueDistance",
                hueDistance <= 1.5
            )
        }
    }

    @Test
    fun `derived colors stay within the identity hue bound`() {
        val samples = arrayOf(
            intArrayOf(232, 52, 90),
            intArrayOf(236, 128, 42),
            intArrayOf(62, 172, 112),
            intArrayOf(55, 126, 224),
            intArrayOf(154, 76, 210)
        )
        for (base in samples) {
            val palette = FableSolDepthScatteringPolicy.derive(base)
            assertTrue(hueDistanceDeg(base, palette.subsurface) <= 1.0)
            assertTrue(palette.subsurface.all { it in 0..255 })
        }
    }

    @Test
    fun `only horizontal convergence produces a crest pinch`() {
        assertEquals(0.0, FableSolDepthScatteringPolicy.crestPinch(0.18), 0.0)
        assertEquals(0.0, FableSolDepthScatteringPolicy.crestPinch(-0.02), 0.0)
        assertTrue(FableSolDepthScatteringPolicy.crestPinch(-0.12) in 0.0..1.0)
        assertEquals(1.0, FableSolDepthScatteringPolicy.crestPinch(-0.30), 0.0)
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

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
