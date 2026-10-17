package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

class FableSolDirectFrameRateTest {
    private fun mode(hz: Float, width: Int = 1320, height: Int = 2232) =
        FableSolDirectFrameRate.Mode(width, height, hz)
    private val supported = listOf(mode(120.00001f), mode(90f), mode(60.000004f), mode(45f))

    @Test fun selectsAvailable90InsteadOfUnreachable120Hint() {
        assertEquals(90f, FableSolDirectFrameRate.desiredRate(supported, 1320, 2232), 0f)
    }

    @Test fun currentDisplayDropDoesNotLowerDesiredRate() {
        val policy = FableSolDirectFrameRate(); val surface = Any()
        val votes = mutableListOf<FableSolDirectFrameRate.Vote>()
        listOf(supported[1], supported[2], supported[2], supported[1]).forEachIndexed { index, current ->
            val desired = FableSolDirectFrameRate.desiredRate(supported, current.width, current.height)
            policy.update(surface, desired, true, 36, index * 5000L) { votes += it }
        }
        assertEquals(listOf(FableSolDirectFrameRate.Vote(90f, true)), votes)
    }

    @Test fun ignores90FromOtherResolutionAndKeepsExistingFallback() {
        assertEquals(120f, FableSolDirectFrameRate.desiredRate(
            listOf(mode(120f), mode(90f, 1000, 2000)), 1320, 2232), 0f)
        assertEquals(60f, FableSolDirectFrameRate.desiredRate(listOf(mode(60f)), 1320, 2232), 0f)
        assertEquals(120f, FableSolDirectFrameRate.desiredRate(listOf(mode(144f)), 1320, 2232), 0f)
    }

    @Test fun toleratesModeRoundingAndUnavailableDisplay() {
        assertEquals(90f, FableSolDirectFrameRate.desiredRate(listOf(mode(90.00001f), mode(120f)), 1320, 2232), 0f)
        assertEquals(60f, FableSolDirectFrameRate.desiredRate(emptyList(), 0, 0), 0f)
        assertEquals(60f, FableSolDirectFrameRate.desiredRate(listOf(mode(Float.NaN), mode(0f)), 1320, 2232), 0f)
    }

    @Test fun api36SubmitsMinimumOnceEvenIfSystemStillReports60() {
        val policy = FableSolDirectFrameRate(); val surface = Any()
        val votes = mutableListOf<FableSolDirectFrameRate.Vote>()
        repeat(1000) { policy.update(surface, 90f, true, 36, it * 16L) { votes += it } }
        assertEquals(listOf(FableSolDirectFrameRate.Vote(90f, true)), votes)
    }

    @Test fun olderApiUsesDefaultCompatibility() {
        val policy = FableSolDirectFrameRate()
        val change = policy.update(Any(), 90f, true, 34, 0L) {}
        assertEquals(FableSolDirectFrameRate.Vote(90f, false), change?.applied)
    }

    @Test fun frozenOrParticleHeldSurfaceClearsVoteAndResumeRestoresIt() {
        val policy = FableSolDirectFrameRate(); val surface = Any()
        val votes = mutableListOf<FableSolDirectFrameRate.Vote>()
        fun apply(active: Boolean, at: Long) = policy.update(surface, 90f, active, 36, at) { votes += it }
        apply(true, 0); apply(false, 1); apply(false, 2); apply(true, 3)
        assertEquals(listOf(FableSolDirectFrameRate.Vote(90f, true), FableSolDirectFrameRate.Vote(0f, false),
            FableSolDirectFrameRate.Vote(90f, true)), votes)
    }

    @Test fun newSurfaceReceivesVoteDespiteUnchangedRate() {
        val policy = FableSolDirectFrameRate(); var calls = 0
        policy.update(Any(), 90f, true, 36, 0) { calls++ }
        policy.update(Any(), 90f, true, 36, 1) { calls++ }
        assertEquals(2, calls)
    }

    @Test fun failedVoteIsNotReportedAsSuccessfulAndRetriesAreBounded() {
        val policy = FableSolDirectFrameRate(); val surface = Any(); var calls = 0
        val failure = policy.update(surface, 90f, true, 36, 0) { calls++; throw IllegalStateException("released") }
        assertNull(policy.applied); assertNull(failure?.applied); assertEquals(1, policy.failures)
        repeat(999) { policy.update(surface, 90f, true, 36, it + 1L) { calls++ } }
        assertEquals(1, calls)
        policy.update(surface, 90f, true, 36, 1000) { calls++ }
        assertEquals(2, calls); assertEquals(90f, policy.applied!!.hz, 0f)
    }

    @Test fun rejectedMinimumFallsBackOncePerSurface() {
        val policy = FableSolDirectFrameRate(); val surface = Any()
        val votes = mutableListOf<FableSolDirectFrameRate.Vote>()
        val result = policy.update(surface, 90f, true, 36, 0) {
            votes += it
            if (it.minimum) throw IllegalArgumentException("unsupported")
        }
        assertTrue(result!!.fallback); assertTrue(policy.minimumRejected)
        assertEquals(listOf(FableSolDirectFrameRate.Vote(90f, true), FableSolDirectFrameRate.Vote(90f, false)), votes)
        repeat(100) { policy.update(surface, 90f, true, 36, 10L + it) { fail("duplicate vote") } }
        policy.update(Any(), 90f, true, 36, 200) { assertTrue(it.minimum) }
        assertFalse(policy.minimumRejected)
    }

    @Test fun fallbackFailureAlsoWaitsBeforeRetry() {
        val policy = FableSolDirectFrameRate(); val surface = Any(); var calls = 0
        policy.update(surface, 90f, true, 36, 0) {
            calls++
            if (it.minimum) throw IllegalArgumentException("unsupported")
            throw IllegalStateException("transient")
        }
        assertEquals(2, calls); assertNull(policy.applied)
        policy.update(surface, 90f, true, 36, 1) { fail("early retry") }
        policy.update(surface, 90f, true, 36, 1000) { assertFalse(it.minimum); calls++ }
        assertEquals(3, calls)
    }

    @Test fun lifecycleChangeDoesNotWaitForOldFailureTimeout() {
        val policy = FableSolDirectFrameRate(); val surface = Any()
        policy.update(surface, 90f, true, 36, 0) { throw IllegalStateException("transient") }
        policy.update(surface, 90f, false, 36, 1) { assertEquals(0f, it.hz, 0f) }
        policy.reset(); assertNull(policy.applied)
        assertNotNull(policy.update(surface, 90f, true, 36, 2) {})
    }

    @Test fun failedNewRatePreservesLastSuccessfulVote() {
        val policy = FableSolDirectFrameRate(); val surface = Any()
        policy.update(surface, 90f, true, 36, 0) {}
        val result = policy.update(surface, 60f, true, 36, 1) { throw IllegalStateException("transient") }
        assertEquals(90f, result!!.applied!!.hz, 0f)
        policy.update(surface, 60f, true, 36, 1001) {}
        assertEquals(60f, policy.applied!!.hz, 0f)
    }
}
