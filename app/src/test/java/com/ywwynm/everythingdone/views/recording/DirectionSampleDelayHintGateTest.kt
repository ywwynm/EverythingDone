package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionSampleDelayHintGateTest {

    @Test
    fun `successful projection return without a new sample reaches the one-time hint`() {
        val gate = DirectionSampleDelayHintGate(alreadyShown = false)

        gate.onProjectionRequestStarted(currentSampleSequence = 10L)
        gate.onHostPausedForProjection(currentSampleSequence = 11L)

        assertTrue(
            gate.onProjectionResult(
                granted = true,
                monitoringEnabled = true,
                currentSampleSequence = 11L
            )
        )
        assertTrue(gate.onWaitExpired())
        assertFalse(gate.onWaitExpired())
    }

    @Test
    fun `a sample after the result silently cancels the hint`() {
        val gate = DirectionSampleDelayHintGate(alreadyShown = false)

        gate.onProjectionRequestStarted(currentSampleSequence = 3L)
        gate.onHostPausedForProjection(currentSampleSequence = 4L)
        assertTrue(
            gate.onProjectionResult(
                granted = true,
                monitoringEnabled = true,
                currentSampleSequence = 4L
            )
        )

        gate.onDirectionSample()

        assertFalse(gate.onWaitExpired())
    }

    @Test
    fun `a healthy first authorization does not consume the later first delayed occurrence`() {
        val gate = DirectionSampleDelayHintGate(alreadyShown = false)

        gate.onProjectionRequestStarted(currentSampleSequence = 1L)
        gate.onHostPausedForProjection(currentSampleSequence = 2L)
        assertTrue(
            gate.onProjectionResult(
                granted = true,
                monitoringEnabled = true,
                currentSampleSequence = 2L
            )
        )
        gate.onDirectionSample()
        assertFalse(gate.onWaitExpired())

        gate.onProjectionRequestStarted(currentSampleSequence = 3L)
        gate.onHostPausedForProjection(currentSampleSequence = 4L)
        assertTrue(
            gate.onProjectionResult(
                granted = true,
                monitoringEnabled = true,
                currentSampleSequence = 4L
            )
        )
        assertTrue(gate.onWaitExpired())
    }

    @Test
    fun `a sample delivered between resume and result needs no timer`() {
        val gate = DirectionSampleDelayHintGate(alreadyShown = false)

        gate.onProjectionRequestStarted(currentSampleSequence = 20L)
        gate.onHostPausedForProjection(currentSampleSequence = 21L)

        assertFalse(
            gate.onProjectionResult(
                granted = true,
                monitoringEnabled = true,
                currentSampleSequence = 22L
            )
        )
        assertFalse(gate.onWaitExpired())
    }

    @Test
    fun `cancelled authorization disabled monitoring and ordinary samples never arm a hint`() {
        val cancelled = DirectionSampleDelayHintGate(alreadyShown = false)
        cancelled.onProjectionRequestStarted(currentSampleSequence = 0L)
        assertFalse(
            cancelled.onProjectionResult(
                granted = false,
                monitoringEnabled = true,
                currentSampleSequence = 0L
            )
        )

        val disabled = DirectionSampleDelayHintGate(alreadyShown = false)
        disabled.onProjectionRequestStarted(currentSampleSequence = 0L)
        assertFalse(
            disabled.onProjectionResult(
                granted = true,
                monitoringEnabled = false,
                currentSampleSequence = 0L
            )
        )

        val unrelated = DirectionSampleDelayHintGate(alreadyShown = false)
        unrelated.onDirectionSample()

        assertFalse(cancelled.onWaitExpired())
        assertFalse(disabled.onWaitExpired())
        assertFalse(unrelated.onWaitExpired())
    }

    @Test
    fun `persisted shown state prevents every future request from arming`() {
        val gate = DirectionSampleDelayHintGate(alreadyShown = true)

        gate.onProjectionRequestStarted(currentSampleSequence = 0L)

        assertFalse(
            gate.onProjectionResult(
                granted = true,
                monitoringEnabled = true,
                currentSampleSequence = 0L
            )
        )
        assertFalse(gate.onWaitExpired())
    }
}
