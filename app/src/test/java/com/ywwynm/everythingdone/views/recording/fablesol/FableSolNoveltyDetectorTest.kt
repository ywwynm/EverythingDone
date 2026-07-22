package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolNoveltyDetectorTest {

    @Test
    fun realtimeDefaultsMatchPythonCausalFallback() {
        assertEquals(3.6, FableSolNoveltyDetector.DEFAULT_FIRE_Z, 0.0)
        assertEquals(12.0, FableSolNoveltyDetector.DEFAULT_MIN_GAP_S, 0.0)
        assertEquals(2.2, FableSolNoveltyDetector.DEFAULT_MINOR_Z, 0.0)
    }
}
