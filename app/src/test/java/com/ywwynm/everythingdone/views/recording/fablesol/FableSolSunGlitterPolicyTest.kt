package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolSunGlitterPolicyTest {

    @Test
    fun sunPathIsContinuousAndFollowsTheFixedLightAzimuthAcrossDepth() {
        val centers = (0..8).map {
            FableSolSunGlitterPolicy.pathCenter01(it / 8.0, 27.0)
        }

        assertTrue(centers.first() < 0.5)
        assertTrue(centers.last() > 0.5)
        assertTrue(centers.zipWithNext().all { (a, b) -> b > a && b - a < 0.03 })
        assertEquals(
            0.5,
            FableSolSunGlitterPolicy.pathCenter01(0.5, 27.0),
            1e-12
        )
    }

    @Test
    fun birthsAreBiasedTowardAPathThatNarrowsIntoTheDistance() {
        val nearCenter = FableSolSunGlitterPolicy.pathCenter01(0.0, 27.0)
        val farCenter = FableSolSunGlitterPolicy.pathCenter01(1.0, 27.0)

        assertEquals(1.0, FableSolSunGlitterPolicy.birthWeight(nearCenter, 0.0, 27.0), 1e-12)
        assertEquals(1.0, FableSolSunGlitterPolicy.birthWeight(farCenter, 1.0, 27.0), 1e-12)
        assertTrue(FableSolSunGlitterPolicy.birthWeight(0.0, 0.0, 27.0) < 0.35)
        assertTrue(FableSolSunGlitterPolicy.birthWeight(0.0, 1.0, 27.0) < 0.20)
        assertTrue(FableSolSunGlitterPolicy.pathHalfWidth01(0.0) >
            FableSolSunGlitterPolicy.pathHalfWidth01(1.0))
    }

    @Test
    fun depthElongationStaysSmallAcrossEveryGlintEligibleLayer() {
        val near = FableSolSunGlitterPolicy.depthAxisLengthDp(0, 1.0)
        val third = FableSolSunGlitterPolicy.depthAxisLengthDp(2, 1.0)
        val fourth = FableSolSunGlitterPolicy.depthAxisLengthDp(3, 1.0)
        val far = FableSolSunGlitterPolicy.depthAxisLengthDp(7, 1.0)
        val disabled = FableSolSunGlitterPolicy.depthAxisLengthDp(8, 1.0)
        val outside = FableSolSunGlitterPolicy.depthAxisLengthDp(0, 0.0)

        assertEquals(2.56, near, 1e-6)
        assertEquals(2.24, third, 1e-6)
        assertEquals(1.96, fourth, 1e-6)
        assertEquals(1.29, far, 1e-6)
        assertEquals(0.0, disabled, 0.0)
        assertTrue(outside < near)
        assertTrue(outside > 1.0)
    }
}
