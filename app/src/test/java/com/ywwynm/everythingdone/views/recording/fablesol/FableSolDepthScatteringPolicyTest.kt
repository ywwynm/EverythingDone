package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolDepthScatteringPolicyTest {

    @Test
    fun `default strength is subtle and independently switchable`() {
        assertEquals(0.21, FableSolParams().get("depth_scattering_strength"), 0.0)
    }

    @Test
    fun `derived pair gives darker deep water and lighter subsurface water`() {
        val base = intArrayOf(220, 72, 132)
        val palette = FableSolDepthScatteringPolicy.derive(base)
        val baseL = FableSolColor.rgbToOklab(base)[0]

        assertTrue(FableSolColor.rgbToOklab(palette.deep)[0] < baseL)
        assertTrue(FableSolColor.rgbToOklab(palette.subsurface)[0] > baseL)
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
            assertTrue(hueDistanceDeg(base, palette.deep) <= 2.0)
            assertTrue(hueDistanceDeg(base, palette.subsurface) <= 1.0)
            assertTrue((palette.deep + palette.subsurface).all { it in 0..255 })
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
}
