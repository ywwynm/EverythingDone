package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolExportStateRegistryTest {

    @Test
    fun jobsKeepIndependentLatestStates() {
        val registry = FableSolExportStateRegistry()
        registry.initialize(1L)
        registry.initialize(2L)

        registry.accept(
            FableSolVideoExportBus.State.Running(1L, 12, 120, 900L)
        )

        assertTrue(
            registry.currentFor(1L) is FableSolVideoExportBus.State.Running
        )
        assertEquals(
            FableSolVideoExportBus.State.Queued(2L),
            registry.currentFor(2L)
        )
    }

    @Test
    fun terminalStateCannotBeOverwrittenByLateProgressOrCancellation() {
        val registry = FableSolExportStateRegistry()
        registry.initialize(9L)
        val timeout = FableSolVideoExportBus.State.Failed(9L, "timeout")
        assertTrue(registry.accept(timeout))

        assertFalse(
            registry.accept(
                FableSolVideoExportBus.State.Running(9L, 99, 100, 1L)
            )
        )
        assertFalse(
            registry.accept(FableSolVideoExportBus.State.Cancelled(9L))
        )
        assertEquals(timeout, registry.currentFor(9L))
    }

    @Test
    fun activeQueryCoversQueuedAndRunningButNotTerminalJobs() {
        val registry = FableSolExportStateRegistry()
        registry.initialize(21L)
        assertTrue(registry.hasActiveJobs())

        registry.accept(FableSolVideoExportBus.State.Cancelled(21L))
        assertFalse(registry.hasActiveJobs())
    }

    @Test
    fun awaitingSpecificationConfirmationIsActiveAndCanResume() {
        val registry = FableSolExportStateRegistry()
        val failed = FableSolExportPublicSpec(
            format = FableSolExportHdrFormat.HDR10,
            family = FableSolExportCodecFamily.AV1,
            tenBit = true,
            softwareOnly = false,
            frameRate = 120
        )
        val suggested = failed.copy(family = FableSolExportCodecFamily.HEVC)
        registry.initialize(31L)

        assertTrue(
            registry.accept(
                FableSolVideoExportBus.State.AwaitingConfirmation(
                    jobId = 31L,
                    failedSpec = failed,
                    reason = FableSolExportRetryReason.ENCODER_INITIALIZATION,
                    suggestedSpec = suggested,
                    attemptedSpecCount = 1
                )
            )
        )
        assertTrue(registry.hasActiveJobs())
        assertTrue(
            registry.accept(
                FableSolVideoExportBus.State.Running(
                    jobId = 31L,
                    done = 0,
                    total = 0,
                    etaMs = -1L
                )
            )
        )
        assertTrue(registry.currentFor(31L) is FableSolVideoExportBus.State.Running)
    }
}
