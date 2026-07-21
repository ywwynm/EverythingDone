package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolSoftDurationGradeDecoderTest {

    @Test
    fun aDroppedPresentationIntervalEqualsAllFixedDecoderTicks() {
        val caughtUp = FableSolSoftDurationGradeDecoder()
        val sequential = FableSolSoftDurationGradeDecoder()
        val oneCall = FableSolGradeDecision()
        val manyCalls = FableSolGradeDecision()

        caughtUp.step(0.99, 1.5, output = oneCall)
        repeat(15) { sequential.step(0.99, 0.1, output = manyCalls) }

        assertEquals(manyCalls.grade, oneCall.grade)
        assertEquals(manyCalls.calmProbability, oneCall.calmProbability, 0.0)
        assertEquals(manyCalls.grooveProbability, oneCall.grooveProbability, 0.0)
        assertEquals(manyCalls.peakProbability, oneCall.peakProbability, 0.0)
        val a = DoubleArray(caughtUp.posteriorSize())
        val b = DoubleArray(sequential.posteriorSize())
        caughtUp.copyPosteriorTo(a)
        sequential.copyPosteriorTo(b)
        assertArrayEquals(b, a, 0.0)
    }

    @Test
    fun overwhelmingEvidenceCanDefeatTheSoftDurationPrior() {
        val decoder = FableSolSoftDurationGradeDecoder()
        val decision = FableSolGradeDecision()
        repeat(70) { decoder.step(0.52, 0.1, output = decision) }
        assertEquals(FableSolSustainedGrade.GROOVE, decision.grade)

        repeat(6) { decoder.step(0.99, 0.1, output = decision) }
        assertEquals(FableSolSustainedGrade.PEAK, decision.grade)
    }

    @Test
    fun peakUsesDifferentFirstReentryAndHoldThresholds() {
        assertEquals(0.74, FableSolSoftDurationGradeDecoder.PEAK_FIRST_ENTER, 0.0)
        assertEquals(0.605, FableSolSoftDurationGradeDecoder.PEAK_REENTER, 0.0)
        assertEquals(0.54, FableSolSoftDurationGradeDecoder.PEAK_HOLD, 0.0)
    }

    @Test
    fun posteriorMatchesThePythonReferenceAtThePeakSwitch() {
        val decoder = FableSolSoftDurationGradeDecoder()
        val decision = FableSolGradeDecision()
        repeat(26) { index ->
            decoder.step(if (index < 25) 0.52 else 0.86, 0.1, output = decision)
        }
        assertEquals(FableSolSustainedGrade.PEAK, decision.grade)
        assertEquals(1.12698190855347e-7, decision.calmProbability, 1e-15)
        assertEquals(0.100857454894976, decision.grooveProbability, 1e-13)
        assertEquals(0.899142432406833, decision.peakProbability, 1e-13)
        assertEquals(0.899142432406833, decision.transitionProgress01, 1e-13)
    }
}
