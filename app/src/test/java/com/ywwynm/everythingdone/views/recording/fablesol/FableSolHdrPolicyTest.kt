package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolHdrPolicyTest {

    @Test
    fun unavailableOrInvalidHeadroomProducesExactSdr() {
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(Float.NaN), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(0.8f), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(1.01f), 0f)
        assertEquals(1.45f, FableSolHdrPolicy.usableHeadroom(1.45f), 0f)
        assertEquals(2f, FableSolHdrPolicy.usableHeadroom(4f), 0f)
    }

    @Test
    fun peakBudgetFallsFromNearToMiddleAndBecomesSdrAtFarLayers() {
        assertEquals(2f, FableSolHdrPolicy.glintCorePeak(0), 0f)
        assertTrue(FableSolHdrPolicy.glintCorePeak(2) >= 1.6f)
        assertEquals(1.5f, FableSolHdrPolicy.glintCorePeak(3), 0f)
        assertEquals(1.2f, FableSolHdrPolicy.glintCorePeak(5), 0f)
        assertEquals(1f, FableSolHdrPolicy.glintCorePeak(6), 0f)

        assertEquals(1.4f, FableSolHdrPolicy.litCrestPeak(0), 0f)
        assertEquals(1.08f, FableSolHdrPolicy.litCrestPeak(5), 0f)
        assertEquals(1f, FableSolHdrPolicy.litCrestPeak(6), 0f)

        assertEquals(1.45f, FableSolHdrPolicy.WATER_TRANSMISSION_PEAK, 0f)
        assertEquals(1.08f, FableSolHdrPolicy.transmissionPeak(0), 0f)
        assertEquals(1.02f, FableSolHdrPolicy.transmissionPeak(3), 0f)
        assertEquals(1f, FableSolHdrPolicy.transmissionPeak(4), 0f)
    }

    @Test
    fun availableHeadroomRisesSmoothlyButDropsToTheRealLimitImmediately() {
        val halfway = FableSolHdrPolicy.advanceHeadroom(1f, 2f, 0.18f)

        assertEquals(1.5f, halfway, 1e-6f)
        assertEquals(2f, FableSolHdrPolicy.advanceHeadroom(halfway, 2f, 0.18f), 1e-6f)
        assertEquals(1.2f, FableSolHdrPolicy.advanceHeadroom(2f, 1.2f, 0.01f), 0f)
        assertEquals(1f, FableSolHdrPolicy.advanceHeadroom(1.8f, Float.NaN, 0.01f), 0f)
    }

    @Test
    fun recordingTransitionIsSmoothSymmetricAndCompletesInSharedDuration() {
        val transition = FableSolHdrTransition()

        assertEquals(0.5f, transition.update(true, 0.18f), 1e-6f)
        assertEquals(1f, transition.update(true, 0.18f), 1e-6f)
        assertEquals(0.5f, transition.update(false, 0.18f), 1e-6f)
        assertEquals(0f, transition.update(false, 0.18f), 1e-6f)
    }

    @Test
    fun interruptedTransitionContinuesFromCurrentValueWithoutJump() {
        val transition = FableSolHdrTransition()
        val rising = transition.update(true, 0.09f)
        val firstFalling = transition.update(false, 0f)

        assertTrue(rising in 0f..1f)
        assertEquals(rising, firstFalling, 0f)
        assertTrue(transition.update(false, 0.09f) < rising)
    }
}
