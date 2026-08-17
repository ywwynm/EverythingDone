package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecordingControlPolicyTest {

    @Test
    fun `idle session after projection result keeps source selector recoverable`() {
        val controls = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(phase = AudioRecordingPhase.IDLE),
            binderConnected = true,
            projectionRequestInFlight = false
        )

        assertFalse(controls.mainActionEnabled)
        assertTrue(controls.sourceSelectorEnabled)
    }

    @Test
    fun `unconfigured and failed sessions keep source selector recoverable`() {
        val unconfigured = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(
                phase = AudioRecordingPhase.PREPARED,
                configured = false
            ),
            binderConnected = true,
            projectionRequestInFlight = false
        )
        val failed = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(phase = AudioRecordingPhase.ERROR),
            binderConnected = true,
            projectionRequestInFlight = false
        )

        assertFalse(unconfigured.mainActionEnabled)
        assertTrue(unconfigured.sourceSelectorEnabled)
        assertFalse(failed.mainActionEnabled)
        assertTrue(failed.sourceSelectorEnabled)
    }

    @Test
    fun `source selector stays locked while projection or configuration is in flight`() {
        val requestingProjection = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(phase = AudioRecordingPhase.IDLE),
            binderConnected = true,
            projectionRequestInFlight = true
        )
        val configuring = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(
                phase = AudioRecordingPhase.PREPARED,
                busy = true,
                configured = false
            ),
            binderConnected = true,
            projectionRequestInFlight = false
        )

        assertFalse(requestingProjection.sourceSelectorEnabled)
        assertFalse(configuring.sourceSelectorEnabled)
    }

    @Test
    fun `stopped without saved file disables the save action`() {
        val failedStop = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(
                phase = AudioRecordingPhase.STOPPED,
                savedFile = null
            ),
            binderConnected = true,
            projectionRequestInFlight = false
        )
        val keptStop = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(
                phase = AudioRecordingPhase.STOPPED,
                savedFile = java.io.File("recorded.wav")
            ),
            binderConnected = true,
            projectionRequestInFlight = false
        )

        assertFalse(failedStop.mainActionEnabled)
        assertTrue(failedStop.stoppedActionsEnabled)
        assertTrue(failedStop.cancelEnabled)
        assertTrue(keptStop.mainActionEnabled)
        assertTrue(keptStop.stoppedActionsEnabled)
        assertTrue(keptStop.cancelEnabled)
    }

    @Test
    fun `finalizing stop keeps only the cancel action available`() {
        val finalizing = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(
                phase = AudioRecordingPhase.STOPPED,
                busy = true,
                savedFile = java.io.File("recording-in-progress.wav")
            ),
            binderConnected = true,
            projectionRequestInFlight = false
        )

        assertFalse(finalizing.mainActionEnabled)
        assertFalse(finalizing.stoppedActionsEnabled)
        assertTrue(finalizing.cancelEnabled)
    }

    @Test
    fun `prepared configured session enables recording and source selection`() {
        val controls = AudioRecordingControlPolicy.resolve(
            snapshot = AudioRecordingSnapshot(
                phase = AudioRecordingPhase.PREPARED,
                configured = true
            ),
            binderConnected = true,
            projectionRequestInFlight = false
        )

        assertTrue(controls.mainActionEnabled)
        assertTrue(controls.sourceSelectorEnabled)
    }
}
