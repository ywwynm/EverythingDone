package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 Python `tests/test_grand_wave_gate.py` 同构的合成契约。 */
class FableSolGrandWaveEventGateTest {

    @Test
    fun duplicateAudioPollDoesNotEraseAValidArrival() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val reasons = ArrayList<FableSolGrandWaveReason>()
        repeat((0.4 * 94).roundToInt()) { index ->
            val frame = feature(
                t = index / 94.0,
                water = 0.90,
                kinetic = 0.89,
                intensity = 0.72,
                harmonic = 0.70,
                rising = 0.60
            )
            repeat(2) {
                if (gate.step(frame, FableSolVisualState.PEAK, request)) {
                    reasons += request.reason
                    gate.resolve(request, true)
                }
            }
        }
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), reasons)
        assertTrue(gate.audibleHistorySeconds() in 0.40..0.42)
        gate.reset()
        assertEquals(0.0, gate.audibleHistorySeconds(), 0.0)
    }

    @Test
    fun duplicatePollPreservesThenConsumesAnUnqualifiedDrop() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val quiet = feature(
            t = 1.0,
            water = 0.4,
            kinetic = 0.4,
            intensity = 0.4,
            harmonic = 0.3,
            rising = 0.0,
            punch = 0.2
        )
        assertFalse(gate.step(quiet, FableSolVisualState.GROOVE, request))
        gate.notifyDrop(0.9)
        assertFalse(gate.step(quiet, FableSolVisualState.GROOVE, request))
        assertFalse(gate.step(
            feature(
                t = 1.01,
                water = 0.4,
                kinetic = 0.4,
                intensity = 0.4,
                harmonic = 0.3,
                rising = 0.0,
                punch = 0.2
            ),
            FableSolVisualState.GROOVE,
            request
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 1.02,
            seconds = 1.0,
            state = FableSolVisualState.GROOVE,
            water = 0.4,
            kinetic = 0.4,
            intensity = 0.4,
            harmonic = 0.3,
            rising = 0.0,
            punch = 0.2
        ))
    }

    @Test
    fun sectionContextIsSilentAndOnlyAuthorizesAPresentPhrase() {
        val gate = FableSolGrandWaveEventGate()
        gate.setSectionContext(0.466)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            state = FableSolVisualState.GROOVE,
            water = 0.77,
            kinetic = 0.78,
            intensity = 0.66,
            harmonic = 0.65,
            context = 0.55
        ))

        gate.notifySection(0.669, surge = true, now = 0.4)
        assertTrue(gate.isSectionWindowActive())
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 0.4,
            water = 0.83,
            kinetic = 0.78,
            intensity = 0.66,
            harmonic = 0.65,
            context = 0.55
        ))

        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = gate,
            start = 0.8,
            seconds = 0.4,
            water = 0.83,
            kinetic = 0.81,
            intensity = 0.66,
            harmonic = 0.65,
            context = 0.55,
            punch = 0.82
        ))
    }

    @Test
    fun localArrivalRecoversACaptureWithoutASectionEvent() {
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.90,
            kinetic = 0.89,
            intensity = 0.72,
            harmonic = 0.70,
            rising = 0.60
        ))
    }

    @Test
    fun highMassAttackCanArriveWithCaptureSoftenedKinetic() {
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.5,
            water = 0.93,
            kinetic = 0.82,
            intensity = 0.72,
            percussive = 0.65,
            harmonic = 0.62,
            context = 0.55,
            grade = 0.80,
            punch = 0.79,
            rising = 0.45
        ))
    }

    @Test
    fun percussiveArrivalDoesNotRequireHarmonicDominance() {
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.90,
            kinetic = 0.91,
            intensity = 0.67,
            percussive = 0.82,
            harmonic = 0.48,
            vocal = 0.10,
            context = 0.62,
            rising = 0.50
        ))
    }

    @Test
    fun denseNoveltyBridgesOnlyALaggingPeakWaterEnvelope() {
        val args = FeatureArgs(
            water = 0.76,
            kinetic = 0.92,
            intensity = 0.65,
            percussive = 0.80,
            harmonic = 0.74,
            context = 0.61,
            grade = 0.78,
            rising = 0.0,
            punch = 0.70
        )
        val peakGate = FableSolGrandWaveEventGate()
        primeNoveltyHistory(peakGate)
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = peakGate,
            start = 0.0,
            seconds = 0.4,
            args = args
        ))
        val grooveGate = FableSolGrandWaveEventGate()
        primeNoveltyHistory(grooveGate)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = grooveGate,
            start = 0.0,
            seconds = 0.4,
            state = FableSolVisualState.GROOVE,
            args = args
        ))
        val weakNoveltyGate = FableSolGrandWaveEventGate()
        primeNoveltyHistory(weakNoveltyGate)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = weakNoveltyGate,
            start = 0.0,
            seconds = 0.4,
            args = args.copy(context = 0.49)
        ))
    }

    @Test
    fun longPeakAllowsOnlyRequalifiedPhraseArrivals() {
        val gate = FableSolGrandWaveEventGate()
        primeNoveltyHistory(gate)
        gate.setSectionContext(0.40)
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            water = 0.80,
            kinetic = 0.92,
            intensity = 0.66,
            harmonic = 0.66,
            context = 0.99,
            grade = 0.78,
            punch = 0.66,
            rising = 0.0
        ))

        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 14.4,
            seconds = 0.5,
            water = 0.85,
            kinetic = 0.87,
            intensity = 0.63,
            harmonic = 0.70,
            punch = 0.86,
            context = 0.0,
            rising = 0.65
        ))
        assertEquals(2, gate.episodeCount())

        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 14.9,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 28.9,
            seconds = 0.5,
            water = 0.86,
            kinetic = 0.82,
            intensity = 0.66,
            harmonic = 0.67,
            punch = 0.86,
            context = 0.05,
            rising = 0.0
        ))

        gate.notifySection(0.35, surge = true, now = 29.4)
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 29.4,
            seconds = 0.5,
            water = 0.83,
            kinetic = 0.81,
            intensity = 0.66,
            harmonic = 0.66,
            punch = 0.82,
            context = 0.22,
            rising = 0.0
        ))

        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 29.9,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 43.9,
            seconds = 0.5,
            water = 0.86,
            kinetic = 0.84,
            intensity = 0.70,
            harmonic = 0.72,
            punch = 0.84,
            context = 0.40,
            rising = 0.0
        ))
        assertEquals(4, gate.episodeCount())
        assertEquals(3, gate.episodeRepeatCount())
    }

    @Test
    fun reviewedRealtimeLocalPatternsRejectEarlyFalseArrivals() {
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.7656,
            kinetic = 0.8862,
            intensity = 0.6327,
            percussive = 0.7075,
            harmonic = 0.65,
            context = 0.6510,
            grade = 0.7863,
            punch = 0.7345,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.8045,
            kinetic = 0.8127,
            intensity = 0.6801,
            percussive = 0.6846,
            harmonic = 0.60,
            context = 0.6796,
            grade = 0.7465,
            punch = 0.6508,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.7669,
            kinetic = 0.9026,
            intensity = 0.6619,
            percussive = 0.8378,
            harmonic = 0.5727,
            context = 0.9722,
            grade = 0.7463,
            punch = 0.9430,
            rising = 0.34
        ))
    }

    @Test
    fun kineticDenseClimaxCanBridgeASlightlyLowerWaterEnvelope() {
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.7734,
            kinetic = 0.9312,
            intensity = 0.6482,
            percussive = 0.7848,
            harmonic = 0.69,
            context = 0.5303,
            grade = 0.7906,
            punch = 0.7638,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.87,
            kinetic = 0.93,
            intensity = 0.75,
            percussive = 0.81,
            harmonic = 0.70,
            context = 0.82,
            grade = 0.88,
            punch = 0.79,
            rising = 0.0
        ))
    }

    @Test
    fun midWaterClimaxRequiresContextAndAPhysicalAttack() {
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.8552,
            kinetic = 0.8806,
            intensity = 0.7410,
            percussive = 0.7529,
            harmonic = 0.70,
            context = 0.8862,
            grade = 0.8735,
            punch = 0.80,
            rising = 0.6662
        ))

        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.8552,
            kinetic = 0.8806,
            intensity = 0.7410,
            percussive = 0.7529,
            harmonic = 0.70,
            context = 0.70,
            grade = 0.8735,
            punch = 0.80,
            rising = 0.6662
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.4,
            water = 0.8552,
            kinetic = 0.8806,
            intensity = 0.7410,
            percussive = 0.7529,
            harmonic = 0.70,
            context = 0.8862,
            grade = 0.8735,
            punch = 0.80,
            rising = 0.20
        ))
    }

    @Test
    fun relativeProgrammeHighRecoversQuietPeakAndLift() {
        fun prime(gate: FableSolGrandWaveEventGate) {
            assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
                gate = gate,
                start = 0.0,
                seconds = 12.2,
                state = FableSolVisualState.GROOVE,
                water = 0.45,
                kinetic = 0.50,
                intensity = 0.44,
                percussive = 0.42,
                harmonic = 0.44,
                rising = 0.22,
                punch = 0.35,
                grade = 0.46,
                loudSDb = -35.0,
                loudP10Db = -42.0,
                loudP95Db = -24.0
            ))
        }

        val quietPeak = FableSolGrandWaveEventGate()
        prime(quietPeak)
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = quietPeak,
            start = 12.2,
            seconds = 0.12,
            state = FableSolVisualState.PEAK,
            water = 0.77,
            kinetic = 0.84,
            intensity = 0.66,
            percussive = 0.65,
            harmonic = 0.63,
            rising = 0.22,
            punch = 0.72,
            grade = 0.74,
            arousal = 0.48,
            novelty = 0.30,
            gradeContext = 0.07,
            loudSDb = -19.0,
            loudP10Db = -40.0,
            loudP95Db = -20.0
        ))

        val quietLift = FableSolGrandWaveEventGate()
        prime(quietLift)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = quietLift,
            start = 12.2,
            seconds = 0.9,
            state = FableSolVisualState.GROOVE,
            water = 0.45,
            kinetic = 0.50,
            intensity = 0.44,
            percussive = 0.42,
            harmonic = 0.44,
            rising = 0.32,
            punch = 0.35,
            grade = 0.46,
            loudSDb = -35.0,
            loudP10Db = -42.0,
            loudP95Db = -24.0
        ))
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = quietLift,
            start = 13.1,
            seconds = 0.12,
            state = FableSolVisualState.LIFT,
            water = 0.70,
            kinetic = 0.82,
            intensity = 0.62,
            percussive = 0.72,
            harmonic = 0.70,
            rising = 0.32,
            punch = 0.72,
            grade = 0.70,
            context = 0.35,
            arousal = 0.40,
            novelty = 0.30,
            gradeContext = 0.07,
            loudSDb = -19.0,
            loudP10Db = -40.0,
            loudP95Db = -20.0
        ))
    }

    @Test
    fun relativeProgrammeHighNeedsMatureRangeAndCurrentQ95() {
        val candidate = FeatureArgs(
            water = 0.77,
            kinetic = 0.84,
            intensity = 0.66,
            percussive = 0.65,
            harmonic = 0.63,
            rising = 0.22,
            punch = 0.72,
            grade = 0.74,
            arousal = 0.48,
            novelty = 0.30,
            gradeContext = 0.07,
            loudSDb = -19.0,
            loudP10Db = -40.0,
            loudP95Db = -20.0
        )
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.3,
            args = candidate
        ))

        fun primedGate(): FableSolGrandWaveEventGate = FableSolGrandWaveEventGate().also { gate ->
            assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
                gate = gate,
                start = 0.0,
                seconds = 12.2,
                state = FableSolVisualState.GROOVE,
                water = 0.45,
                kinetic = 0.50,
                intensity = 0.44,
                percussive = 0.42,
                harmonic = 0.44,
                rising = 0.22,
                punch = 0.35,
                grade = 0.46,
                loudSDb = -35.0,
                loudP10Db = -42.0,
                loudP95Db = -24.0
            ))
        }

        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = primedGate(),
            start = 12.2,
            seconds = 0.3,
            args = candidate.copy(loudSDb = -23.0)
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = primedGate(),
            start = 12.2,
            seconds = 0.3,
            args = candidate.copy(novelty = 0.10)
        ))
    }

    @Test
    fun shortSectionDownbeatHasAnIndependentConfirmation() {
        val args = FeatureArgs(
            water = 0.85,
            kinetic = 0.83,
            intensity = 0.73,
            percussive = 0.68,
            harmonic = 0.67,
            context = 0.20,
            grade = 0.79,
            punch = 0.82,
            rising = 0.33
        )
        val gate = FableSolGrandWaveEventGate()
        gate.notifySection(0.72, surge = true, now = 0.0)
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = gate,
            start = 0.0,
            seconds = 0.10,
            args = args
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 0.10,
            args = args
        ))
    }

    @Test
    fun independentConfirmationsCannotAccumulateAcrossAlternatingBranches() {
        val gate = FableSolGrandWaveEventGate()
        primeNoveltyHistory(gate)
        gate.notifySection(0.72, surge = true, now = 0.0)
        val request = FableSolGrandWaveRequest()
        repeat(12) { index ->
            val frame = if (index % 2 == 0) {
                feature(
                    t = index / 60.0,
                    water = 0.85,
                    kinetic = 0.83,
                    intensity = 0.73,
                    percussive = 0.68,
                    harmonic = 0.67,
                    context = 0.20,
                    grade = 0.79,
                    punch = 0.82,
                    rising = 0.33
                )
            } else {
                feature(
                    t = index / 60.0,
                    water = 0.77,
                    kinetic = 0.84,
                    intensity = 0.66,
                    percussive = 0.65,
                    harmonic = 0.63,
                    grade = 0.74,
                    rising = 0.22,
                    punch = 0.72,
                    arousal = 0.48,
                    novelty = 0.30,
                    gradeContext = 0.07,
                    loudSDb = -19.0,
                    loudP10Db = -40.0,
                    loudP95Db = -20.0
                )
            }
            assertFalse(gate.step(frame, FableSolVisualState.PEAK, request))
        }
    }

    @Test
    fun firstRepeatAcceptsKineticPhraseButRejectsLowIntensityPunch() {
        val gate = FableSolGrandWaveEventGate()
        assertEquals(1, drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            water = 0.90,
            kinetic = 0.89,
            intensity = 0.76,
            harmonic = 0.72,
            context = 0.45,
            grade = 0.82,
            punch = 0.75,
            rising = 0.50
        ).size)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 14.4,
            seconds = 0.8,
            water = 0.90,
            kinetic = 0.856,
            intensity = 0.770,
            percussive = 0.770,
            harmonic = 0.70,
            context = 0.269,
            grade = 0.815,
            punch = 0.763,
            rising = 0.70
        ))

        val other = FableSolGrandWaveEventGate()
        assertEquals(1, drive(
            gate = other,
            start = 0.0,
            seconds = 0.4,
            water = 0.78,
            kinetic = 0.94,
            intensity = 0.66,
            percussive = 0.79,
            harmonic = 0.70,
            context = 0.55,
            grade = 0.80,
            punch = 0.76,
            rising = 0.0
        ).size)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = other,
            start = 0.4,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = other,
            start = 14.4,
            seconds = 0.5,
            water = 0.882,
            kinetic = 0.825,
            intensity = 0.623,
            percussive = 0.664,
            harmonic = 0.60,
            context = 0.025,
            grade = 0.799,
            punch = 0.816,
            rising = 0.0
        ))
    }

    @Test
    fun firstPunchRepeatRequiresACurrentPhysicalAttack() {
        val gate = FableSolGrandWaveEventGate()
        assertEquals(1, drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            water = 0.90,
            kinetic = 0.90,
            intensity = 0.76,
            percussive = 0.78,
            harmonic = 0.70,
            context = 0.55,
            grade = 0.84,
            punch = 0.76,
            rising = 0.60
        ).size)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 12.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 12.4,
            seconds = 2.0,
            water = 0.70,
            kinetic = 0.50,
            intensity = 0.45,
            percussive = 0.55,
            harmonic = 0.50,
            context = 0.0,
            grade = 0.50,
            punch = 0.86,
            rising = 0.12
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 14.4,
            seconds = 0.5,
            water = 0.86,
            kinetic = 0.88,
            intensity = 0.70,
            percussive = 0.79,
            harmonic = 0.60,
            context = 0.0,
            grade = 0.82,
            punch = 0.86,
            rising = 0.12
        ))
    }

    @Test
    fun unstructuredFirstRepeatExpiresAfterAPhraseScaleGap() {
        val gate = FableSolGrandWaveEventGate()
        assertEquals(1, drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            water = 0.90,
            kinetic = 0.90,
            intensity = 0.76,
            percussive = 0.78,
            harmonic = 0.70,
            context = 0.55,
            grade = 0.84,
            punch = 0.76,
            rising = 0.60
        ).size)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 34.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 34.4,
            seconds = 0.5,
            water = 0.88,
            kinetic = 0.88,
            intensity = 0.74,
            percussive = 0.78,
            harmonic = 0.68,
            context = 0.0,
            grade = 0.82,
            punch = 0.90,
            rising = 0.80
        ))
    }

    @Test
    fun captureSoftenedPhraseSequenceRequalifiesEachArrival() {
        val gate = FableSolGrandWaveEventGate()
        assertEquals(1, drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            water = 0.93,
            kinetic = 0.82,
            intensity = 0.72,
            percussive = 0.65,
            harmonic = 0.62,
            context = 0.55,
            grade = 0.80,
            punch = 0.79,
            rising = 0.45
        ).size)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 14.4,
            seconds = 0.5,
            water = 0.90,
            kinetic = 0.845,
            intensity = 0.71,
            percussive = 0.74,
            harmonic = 0.68,
            context = 0.0,
            grade = 0.81,
            punch = 0.86,
            rising = 0.65
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 14.9,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))

        gate.notifySection(0.72, surge = true, now = 28.9)
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 28.9,
            seconds = 0.5,
            water = 0.91,
            kinetic = 0.76,
            intensity = 0.71,
            percussive = 0.60,
            harmonic = 0.59,
            context = 0.18,
            grade = 0.72,
            punch = 0.64,
            rising = 0.12
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 29.4,
            seconds = 14.0,
            punch = 0.50,
            rising = 0.0
        ))
        assertEquals(listOf(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT), drive(
            gate = gate,
            start = 43.4,
            seconds = 0.5,
            water = 0.89,
            kinetic = 0.82,
            intensity = 0.72,
            percussive = 0.72,
            harmonic = 0.66,
            context = 0.04,
            grade = 0.78,
            punch = 0.82,
            rising = 0.58
        ))
    }

    @Test
    fun dropCannotBypassTheGlobalPhraseGap() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        assertEquals(1, drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            harmonic = 0.72,
            rising = 0.70
        ).size)

        gate.notifyDrop(1.0)
        assertFalse(gate.step(
            feature(1.0, water = 0.90, kinetic = 0.90, intensity = 0.90, harmonic = 0.90),
            FableSolVisualState.CLIMAX,
            request
        ))
        gate.notifyDrop(1.0)
        assertTrue(gate.step(
            feature(14.4, water = 0.90, kinetic = 0.90, intensity = 0.90, harmonic = 0.90),
            FableSolVisualState.CLIMAX,
            request
        ))
        assertEquals(FableSolGrandWaveReason.DROP, request.reason)
    }

    @Test
    fun climaxGradeAloneDoesNotManufactureAnAccent() {
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 3.0,
            state = FableSolVisualState.CLIMAX,
            harmonic = 0.45,
            rising = 0.0,
            punch = 0.60
        ))
    }

    @Test
    fun sectionWindowUsesAudioClockNotPausedSimulationClock() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        gate.setSectionContext(0.40)
        assertFalse(gate.step(feature(10.0, water = 0.70), FableSolVisualState.GROOVE, request))
        gate.notifySection(0.75, surge = true, now = 100.0)
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 20.0,
            seconds = 0.4,
            state = FableSolVisualState.GROOVE,
            water = 0.77,
            kinetic = 0.78,
            intensity = 0.66,
            harmonic = 0.66,
            context = 0.60
        ))
    }

    @Test
    fun fastButQuietVocalMotionCannotTriggerABroadCrest() {
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = FableSolGrandWaveEventGate(),
            start = 0.0,
            seconds = 4.0,
            water = 0.68,
            kinetic = 0.95,
            intensity = 0.65,
            harmonic = 0.75,
            vocal = 0.90,
            rising = 0.80,
            punch = 0.90
        ))
    }

    @Test
    fun rejectedActiveWaveCandidateIsConsumedNotDelayed() {
        val gate = FableSolGrandWaveEventGate()
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = gate,
            start = 0.0,
            seconds = 0.4,
            accept = false,
            harmonic = 0.72,
            rising = 0.70
        ))
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = 0.4,
            seconds = 2.0,
            harmonic = 0.72,
            rising = 0.70
        ))
        drive(
            gate = gate,
            start = 2.4,
            seconds = 1.1,
            water = 0.70,
            kinetic = 0.50,
            intensity = 0.45,
            harmonic = 0.40,
            rising = 0.0
        )
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), drive(
            gate = gate,
            start = 3.5,
            seconds = 0.8,
            harmonic = 0.72,
            rising = 0.70
        ))
    }

    @Test
    fun constantsMatchThePythonCausalGate() {
        assertEquals(5.5, FableSolGrandWaveEventGate.SECTION_WINDOW_S, 0.0)
        assertEquals(0.18, FableSolGrandWaveEventGate.LOCAL_CONFIRM_S, 0.0)
        assertEquals(0.05, FableSolGrandWaveEventGate.DENSE_CONFIRM_S, 0.0)
        assertEquals(0.05, FableSolGrandWaveEventGate.RELATIVE_CONFIRM_S, 0.0)
        assertEquals(0.05, FableSolGrandWaveEventGate.SECTION_PHRASE_CONFIRM_S, 0.0)
        assertEquals(0.20, FableSolGrandWaveEventGate.REPEAT_CONFIRM_S, 0.0)
        assertEquals(0.20, FableSolGrandWaveEventGate.RESURGENT_REPEAT_CONFIRM_S, 0.0)
        assertEquals(14.0, FableSolGrandWaveEventGate.REPEAT_MIN_GAP_S, 0.0)
        assertEquals(0.89, FableSolGrandWaveEventGate.LOCAL_MIN_WATER, 0.0)
        assertEquals(0.875, FableSolGrandWaveEventGate.LOCAL_MIN_KINETIC, 0.0)
        assertEquals(0.84, FableSolGrandWaveEventGate.BRIDGE_MAX_WATER, 0.0)
        assertEquals(0.90, FableSolGrandWaveEventGate.NOVELTY_CONTEXT_MIN, 0.0)
        assertEquals(0.70, FableSolGrandWaveEventGate.NOVELTY_MIN_GRADE, 0.0)
        assertEquals(12.0, FableSolGrandWaveEventGate.NOVELTY_HISTORY_S, 0.0)
        assertEquals(0.92, FableSolGrandWaveEventGate.KINETIC_BRIDGE_MIN, 0.0)
        assertEquals(0.91, FableSolGrandWaveEventGate.MASS_ARRIVAL_MIN_WATER, 0.0)
        assertEquals(0.80, FableSolGrandWaveEventGate.MASS_ARRIVAL_MIN_KINETIC, 0.0)
        assertEquals(0.30, FableSolGrandWaveEventGate.MASS_ARRIVAL_MIN_ATTACK, 0.0)
        assertEquals(0.50, FableSolGrandWaveEventGate.MASS_ARRIVAL_MIN_CONTEXT, 0.0)
        assertEquals(0.84, FableSolGrandWaveEventGate.MID_WATER_MIN, 0.0)
        assertEquals(0.89, FableSolGrandWaveEventGate.MID_WATER_MAX, 0.0)
        assertEquals(0.86, FableSolGrandWaveEventGate.MID_WATER_MIN_KINETIC, 0.0)
        assertEquals(0.70, FableSolGrandWaveEventGate.MID_WATER_MIN_INTENSITY, 0.0)
        assertEquals(0.82, FableSolGrandWaveEventGate.MID_WATER_MIN_GRADE, 0.0)
        assertEquals(0.70, FableSolGrandWaveEventGate.MID_WATER_MIN_MUSIC, 0.0)
        assertEquals(0.75, FableSolGrandWaveEventGate.MID_WATER_MIN_CONTEXT, 0.0)
        assertEquals(0.28, FableSolGrandWaveEventGate.MID_WATER_MIN_ATTACK, 0.0)
        assertEquals(12.0, FableSolGrandWaveEventGate.RELATIVE_MIN_HISTORY_S, 0.0)
        assertEquals(1.0, FableSolGrandWaveEventGate.RELATIVE_MIN_LOUDNESS, 0.0)
        assertEquals(0.20, FableSolGrandWaveEventGate.RELATIVE_MIN_NOVELTY, 0.0)
        assertEquals(0.02, FableSolGrandWaveEventGate.RELATIVE_MIN_GRADE_CONTEXT, 0.0)
        assertEquals(0.11, FableSolGrandWaveEventGate.RELATIVE_MAX_GRADE_CONTEXT, 0.0)
        assertEquals(0.30, FableSolGrandWaveEventGate.FIRST_PUNCH_MIN_ATTACK, 0.0)
        assertEquals(0.84, FableSolGrandWaveEventGate.FIRST_REPEAT_MIN_KINETIC, 0.0)
        assertEquals(28.0, FableSolGrandWaveEventGate.FIRST_REPEAT_MAX_GAP_S, 0.0)
        assertEquals(0.90, FableSolGrandWaveEventGate.STRUCTURED_MASS_MIN_WATER, 0.0)
        assertEquals(0.88, FableSolGrandWaveEventGate.ATTACKED_REPEAT_MIN_WATER, 0.0)
        assertEquals(0.38, FableSolGrandWaveEventGate.ATTACKED_REPEAT_MIN_ATTACK, 0.0)
    }

    private data class FeatureArgs(
        val water: Double = 0.90,
        val kinetic: Double = 0.89,
        val intensity: Double = 0.70,
        val rising: Double = 0.50,
        val punch: Double = 0.74,
        val percussive: Double = 0.65,
        val harmonic: Double = 0.68,
        val vocal: Double = 0.20,
        val context: Double = 0.0,
        val grade: Double? = null,
        val arousal: Double = 0.0,
        val gradeContext: Double = 0.0,
        val novelty: Double = 0.0,
        val loudSDb: Double = -30.0,
        val loudP10Db: Double = -40.0,
        val loudP95Db: Double = -20.0
    )

    private fun primeNoveltyHistory(
        gate: FableSolGrandWaveEventGate,
        end: Double = 0.0
    ) {
        val start = end - FableSolGrandWaveEventGate.NOVELTY_HISTORY_S - 0.2
        assertEquals(emptyList<FableSolGrandWaveReason>(), drive(
            gate = gate,
            start = start,
            seconds = FableSolGrandWaveEventGate.NOVELTY_HISTORY_S + 0.2,
            state = FableSolVisualState.GROOVE,
            water = 0.45,
            kinetic = 0.45,
            intensity = 0.42,
            percussive = 0.40,
            harmonic = 0.42,
            context = 0.0,
            grade = 0.44,
            punch = 0.30,
            rising = 0.0
        ))
    }

    private fun drive(
        gate: FableSolGrandWaveEventGate,
        start: Double,
        seconds: Double,
        state: FableSolVisualState = FableSolVisualState.PEAK,
        accept: Boolean = true,
        args: FeatureArgs? = null,
        water: Double = 0.90,
        kinetic: Double = 0.89,
        intensity: Double = 0.70,
        rising: Double = 0.50,
        punch: Double = 0.74,
        percussive: Double = 0.65,
        harmonic: Double = 0.68,
        vocal: Double = 0.20,
        context: Double = 0.0,
        grade: Double? = null,
        arousal: Double = 0.0,
        gradeContext: Double = 0.0,
        novelty: Double = 0.0,
        loudSDb: Double = -30.0,
        loudP10Db: Double = -40.0,
        loudP95Db: Double = -20.0
    ): List<FableSolGrandWaveReason> {
        val actual = args ?: FeatureArgs(
            water = water,
            kinetic = kinetic,
            intensity = intensity,
            rising = rising,
            punch = punch,
            percussive = percussive,
            harmonic = harmonic,
            vocal = vocal,
            context = context,
            grade = grade,
            arousal = arousal,
            gradeContext = gradeContext,
            novelty = novelty,
            loudSDb = loudSDb,
            loudP10Db = loudP10Db,
            loudP95Db = loudP95Db
        )
        val request = FableSolGrandWaveRequest()
        val reasons = ArrayList<FableSolGrandWaveReason>()
        repeat((seconds * 60).roundToInt()) { index ->
            val frame = feature(
                t = start + index / 60.0,
                water = actual.water,
                kinetic = actual.kinetic,
                intensity = actual.intensity,
                rising = actual.rising,
                punch = actual.punch,
                percussive = actual.percussive,
                harmonic = actual.harmonic,
                vocal = actual.vocal,
                context = actual.context,
                grade = actual.grade,
                arousal = actual.arousal,
                gradeContext = actual.gradeContext,
                novelty = actual.novelty,
                loudSDb = actual.loudSDb,
                loudP10Db = actual.loudP10Db,
                loudP95Db = actual.loudP95Db
            )
            if (gate.step(frame, state, request)) {
                reasons += request.reason
                gate.resolve(request, accept)
            }
        }
        return reasons
    }

    private fun feature(
        t: Double,
        water: Double = 0.90,
        kinetic: Double = 0.89,
        intensity: Double = 0.70,
        rising: Double = 0.50,
        punch: Double = 0.74,
        percussive: Double = 0.65,
        harmonic: Double = 0.68,
        vocal: Double = 0.20,
        context: Double = 0.0,
        grade: Double? = null,
        arousal: Double = 0.0,
        gradeContext: Double = 0.0,
        novelty: Double = 0.0,
        loudSDb: Double = -30.0,
        loudP10Db: Double = -40.0,
        loudP95Db: Double = -20.0
    ): FableSolPerceptualFrame = FableSolPerceptualFrame().also { frame ->
        frame.t = t
        frame.silent = false
        frame.waterDrive01 = water
        frame.kineticDrive01 = kinetic
        frame.intensityDrive01 = intensity
        frame.energyRising01 = rising
        frame.punch01 = punch
        frame.percussiveMotion01 = percussive
        frame.harmonicMotion01 = harmonic
        frame.vocalMotion01 = vocal
        frame.motionContextBoost01 = context
        frame.gradeDrive01 = grade ?: intensity
        frame.musicArousal01 = arousal
        frame.gradeContext01 = gradeContext
        frame.positiveNovelty01 = novelty
        frame.loudSDb = loudSDb
        frame.loudP10Db = loudP10Db
        frame.loudP95Db = loudP95Db
    }
}
