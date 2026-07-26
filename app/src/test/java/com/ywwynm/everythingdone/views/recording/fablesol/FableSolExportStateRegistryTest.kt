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
}
