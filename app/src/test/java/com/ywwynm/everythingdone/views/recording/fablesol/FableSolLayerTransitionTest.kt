package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

class FableSolLayerTransitionTest {
    private val pq = FableSolLayerTransition.Spec(FableSolScreenOutput.PQ, 100, 200)
    private val sdr = pq.copy(output = FableSolScreenOutput.SDR)

    @Test fun oldFrameRemainsUntilNewTransactionCommits() {
        val state = FableSolLayerTransition()
        state.request(pq); val first = state.begin()!!; state.committed(first)
        state.request(sdr); val second = state.begin()!!
        assertEquals(first, state.shown)
        assertTrue(state.canPublish(second))
        state.committed(second)
        assertEquals(second, state.shown)
    }
    @Test fun rapidOffOnDoesNotPublishStaleSdr() {
        val state = FableSolLayerTransition()
        state.request(pq); val first = state.begin()!!; state.committed(first)
        state.request(sdr); val second = state.begin()!!
        state.request(pq)
        assertFalse(state.canPublish(second))
        state.discard(second)
        assertNull(state.begin())
        assertEquals(first, state.shown)
    }
    @Test fun requestDuringCommitContinuesFromActuallyShownLayer() {
        val state = FableSolLayerTransition()
        state.request(sdr); val first = state.begin()!!
        state.request(pq)
        assertNull(state.begin())
        assertTrue(state.committed(first))
        assertEquals(pq, state.begin()!!.spec)
    }
    @Test fun detachedCallbacksCannotReplaceReattachedLayer() {
        val state = FableSolLayerTransition()
        state.request(pq); val old = state.begin()!!
        state.reset(); state.request(pq); val fresh = state.begin()!!
        assertFalse(state.canPublish(old))
        assertFalse(state.committed(old))
        state.discard(old)
        assertEquals(fresh, state.preparing)
    }
    @Test fun resizeIsCoalescedToLatestGeometry() {
        val state = FableSolLayerTransition()
        state.request(pq); val first = state.begin()!!
        val resized = pq.copy(width = 240)
        state.request(resized)
        assertFalse(state.canPublish(first))
        state.discard(first)
        assertEquals(resized, state.begin()!!.spec)
    }

    @Test fun discardedCandidateCanRestoreProducerWithoutDroppingShownBuffer() {
        val state = FableSolLayerTransition()
        state.request(pq); val first = state.begin()!!; state.committed(first)
        state.request(sdr); val abandoned = state.begin()!!
        state.request(pq); state.discard(abandoned)
        val restore = state.begin(force = true)!!
        assertEquals(first, state.shown)
        assertEquals(pq, restore.spec)
        assertNotEquals(first.id, restore.id)
        assertFalse(state.committed(abandoned))
        assertTrue(state.canPublish(restore))
    }

    @Test fun burstsOnlyPrepareLatestRequestAndDetachInvalidatesIt() {
        val state = FableSolLayerTransition()
        state.request(pq); val first = state.begin()!!; state.committed(first)
        state.request(sdr); val pending = state.begin()!!
        repeat(100) { state.request(if (it % 2 == 0) sdr else pq); assertNull(state.begin()) }
        assertFalse(state.canPublish(pending))
        state.reset()
        assertNull(state.shown)
        assertFalse(state.committed(pending))
    }
}
