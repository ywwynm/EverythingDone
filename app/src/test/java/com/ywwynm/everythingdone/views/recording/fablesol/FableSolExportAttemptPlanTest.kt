package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolExportAttemptPlanTest {

    @Test
    fun hdrRequestTriesSixtyFpsHdrBeforeAnySdrFallback() {
        assertEquals(
            listOf(
                FableSolExportModeAttempt(hdr = true, frameRate = 120),
                FableSolExportModeAttempt(hdr = true, frameRate = 60),
                FableSolExportModeAttempt(hdr = false, frameRate = 120),
                FableSolExportModeAttempt(hdr = false, frameRate = 60)
            ),
            FableSolExportAttemptPlan.ordered(
                wantHdr = true,
                requestedFrameRate = 120
            )
        )
    }

    @Test
    fun sdrRequestNeverCreatesAnHdrAttempt() {
        assertEquals(
            listOf(
                FableSolExportModeAttempt(hdr = false, frameRate = 60)
            ),
            FableSolExportAttemptPlan.ordered(
                wantHdr = false,
                requestedFrameRate = 60
            )
        )
    }
}
