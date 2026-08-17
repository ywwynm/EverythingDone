package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputRowPresentationPolicyTest {

    @Test
    fun `input row is visible only before recording can start`() {
        assertEquals(
            AudioInputRowPresentation.EDITABLE,
            AudioInputRowPresentationPolicy.forPhase(AudioRecordingPhase.IDLE)
        )
        assertEquals(
            AudioInputRowPresentation.EDITABLE,
            AudioInputRowPresentationPolicy.forPhase(AudioRecordingPhase.PREPARED)
        )
        assertEquals(
            AudioInputRowPresentation.EDITABLE,
            AudioInputRowPresentationPolicy.forPhase(AudioRecordingPhase.ERROR)
        )
        assertEquals(
            AudioInputRowPresentation.HIDDEN,
            AudioInputRowPresentationPolicy.forPhase(AudioRecordingPhase.RECORDING)
        )
        assertEquals(
            AudioInputRowPresentation.HIDDEN,
            AudioInputRowPresentationPolicy.forPhase(AudioRecordingPhase.STOPPED)
        )
    }
}
