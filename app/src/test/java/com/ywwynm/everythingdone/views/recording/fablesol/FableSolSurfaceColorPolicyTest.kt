package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolSurfaceColorPolicyTest {

    @Test
    fun reflectionSamplesTheCurrentGradientColorBeforeLightening() {
        val start = intArrayOf(238, 42, 46)
        val stop1 = intArrayOf(244, 194, 48)
        val stop2 = intArrayOf(42, 198, 184)
        val end = intArrayOf(42, 72, 232)
        val q = 0.48

        val expected = FableSolColor.lightenOklab(
            FableSolSurfaceColorPolicy.bodyAt(start, stop1, stop2, end, q),
            FableSolSurfaceColorPolicy.REFLECTION_DELTA_L
        )
        val actual = FableSolSurfaceColorPolicy.reflectionAt(
            start, stop1, stop2, end, q
        )
        val transformStopsFirst = FableSolColor.mix(
            FableSolColor.lightenOklab(stop1, FableSolSurfaceColorPolicy.REFLECTION_DELTA_L),
            FableSolColor.lightenOklab(stop2, FableSolSurfaceColorPolicy.REFLECTION_DELTA_L),
            (q - 0.24) / 0.36
        )

        assertArrayEquals(expected, actual)
        assertTrue(actual.indices.any { kotlin.math.abs(actual[it] - transformStopsFirst[it]) >= 2 })
    }

    @Test
    fun denseRampPreservesExactOriginalStopsAndCloselyTracksIntermediateSamples() {
        val start = intArrayOf(238, 42, 46)
        val stop1 = intArrayOf(244, 194, 48)
        val stop2 = intArrayOf(42, 198, 184)
        val end = intArrayOf(42, 72, 232)
        val ramp = FableSolSurfaceColorPolicy.reflectionRamp(start, stop1, stop2, end)

        for (q in doubleArrayOf(0.0, 0.24, 0.48, 0.60, 0.84, 1.0)) {
            val sampled = IntArray(3)
            FableSolSurfaceColorPolicy.sampleRampInto(ramp, q, sampled)
            val exact = FableSolSurfaceColorPolicy.reflectionAt(start, stop1, stop2, end, q)
            for (channel in 0..2) {
                assertTrue("q=$q channel=$channel sampled=${sampled.contentToString()} exact=${exact.contentToString()}",
                    kotlin.math.abs(sampled[channel] - exact[channel]) <= 3)
            }
        }
    }
}
