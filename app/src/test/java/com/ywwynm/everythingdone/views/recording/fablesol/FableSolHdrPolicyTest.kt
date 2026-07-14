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
        assertEquals(3.6f, FableSolHdrPolicy.usableHeadroom(4f), 0f)
    }

    @Test
    fun peakBudgetFallsFromNearToMiddleAndBecomesSdrAtFarLayers() {
        assertPeakCurve(
            floatArrayOf(3.6f, 2.8f, 2.4f, 2f, 1.6f, 1.36f, 1.29f, 1.16f, 1f),
            FableSolHdrPolicy::glintCorePeak
        )
        assertPeakCurve(
            floatArrayOf(3.2f, 2.7f, 2.24f, 1.96f, 1.6f, 1.29f, 1.18f, 1.08f, 1f),
            FableSolHdrPolicy::surfaceReflectionPeak
        )
        assertPeakCurve(
            floatArrayOf(1.08f, 1.06f, 1.04f, 1.02f, 1f, 1f, 1f, 1f, 1f),
            FableSolHdrPolicy::transmissionPeak
        )
        assertPeakArray(
            floatArrayOf(2.7f, 2.4f, 2.1f, 1.8f, 1.5f, 1.29f, 1.08f, 1f, 1f),
            FableSolHdrPolicy.CONTINUOUS_SHEEN_PEAKS
        )
        assertPeakArray(
            floatArrayOf(1.6f, 1.5f, 1.36f, 1.29f, 1.21f, 1.14f, 1.08f, 1f, 1f),
            FableSolHdrPolicy.CONTINUOUS_TRANSMISSION_PEAKS
        )
    }

    @Test
    fun availableHeadroomRisesSmoothlyButDropsToTheRealLimitImmediately() {
        val halfway = FableSolHdrPolicy.advanceHeadroom(1f, 3.6f, 0.18f)

        assertEquals(2.3f, halfway, 1e-6f)
        assertEquals(3.6f, FableSolHdrPolicy.advanceHeadroom(halfway, 3.6f, 0.18f), 1e-6f)
        assertEquals(1.2f, FableSolHdrPolicy.advanceHeadroom(3.6f, 1.2f, 0.01f), 0f)
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

    private fun assertPeakCurve(expected: FloatArray, valueAt: (Int) -> Float) {
        expected.forEachIndexed { index, value ->
            assertEquals("layer=$index", value, valueAt(index), 0f)
        }
    }

    private fun assertPeakArray(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, value ->
            assertEquals("layer=$index", value, actual[index], 0f)
        }
    }
}
