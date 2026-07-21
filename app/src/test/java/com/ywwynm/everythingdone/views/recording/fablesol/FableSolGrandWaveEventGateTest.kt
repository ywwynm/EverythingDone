package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolGrandWaveEventGateTest {

    @Test
    fun duplicateAudioPollDoesNotEraseAValidArrival() {
        val gate = FableSolGrandWaveEventGate()
        val frame = feature(0.0, rising = 0.60)
        val request = FableSolGrandWaveRequest()
        val reasons = ArrayList<FableSolGrandWaveReason>()
        repeat((0.4 * 94).toInt()) { index ->
            frame.t = index / 94.0
            repeat(2) {
                if (gate.step(frame, FableSolVisualState.PEAK, request)) {
                    reasons += request.reason
                    gate.resolve(request, true)
                }
            }
        }
        assertEquals(listOf(FableSolGrandWaveReason.CAUSAL_ARRIVAL), reasons)
    }

    @Test
    fun weakDropIsConsumedWithoutAnAccent() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val quiet = feature(
            1.0,
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
        quiet.t = 1.01
        assertFalse(gate.step(quiet, FableSolVisualState.GROOVE, request))
    }

    @Test
    fun sectionLiftCanBridgeTheShortPrePeakDecoderLatency() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        gate.setSectionContext(0.466)
        val frame = feature(
            0.0,
            water = 0.80,
            kinetic = 0.79,
            intensity = 0.66,
            harmonic = 0.66,
            context = 0.56
        )
        assertEquals(0, drive(gate, frame, 0.0, 0.4, FableSolVisualState.GROOVE, request))

        gate.notifySection(0.669, now = 0.4)
        val count = drive(gate, frame, 0.4, 0.4, FableSolVisualState.GROOVE, request)
        assertEquals(1, count)
        assertEquals(FableSolGrandWaveReason.SECTION_LIFT, request.reason)
    }

    @Test
    fun percussiveArrivalDoesNotRequireHarmonicDominance() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val frame = feature(
            0.0,
            water = 0.82,
            kinetic = 0.91,
            intensity = 0.66,
            percussive = 0.82,
            harmonic = 0.48,
            vocal = 0.10,
            context = 0.62,
            rising = 0.0
        )
        assertEquals(1, drive(gate, frame, 0.0, 0.4, FableSolVisualState.PEAK, request))
        assertEquals(FableSolGrandWaveReason.CAUSAL_ARRIVAL, request.reason)
    }

    @Test
    fun strongNoveltyBridgesOnlyALaggingPeakWaterEnvelope() {
        val frame = feature(
            0.0,
            water = 0.76,
            kinetic = 0.92,
            intensity = 0.65,
            percussive = 0.80,
            harmonic = 0.74,
            context = 0.61,
            rising = 0.0,
            punch = 0.70
        )
        frame.gradeDrive01 = 0.78
        val request = FableSolGrandWaveRequest()
        val peakGate = FableSolGrandWaveEventGate()
        assertEquals(1, drive(
            peakGate,
            frame,
            0.0,
            0.4,
            FableSolVisualState.PEAK,
            request
        ))
        assertEquals(FableSolGrandWaveReason.CAUSAL_ARRIVAL, request.reason)

        val grooveGate = FableSolGrandWaveEventGate()
        assertEquals(0, drive(
            grooveGate,
            frame,
            0.0,
            0.4,
            FableSolVisualState.GROOVE,
            request
        ))

        val weakNoveltyGate = FableSolGrandWaveEventGate()
        frame.motionContextBoost01 = 0.54
        assertEquals(0, drive(
            weakNoveltyGate,
            frame,
            0.0,
            0.4,
            FableSolVisualState.PEAK,
            request
        ))
    }

    @Test
    fun longClimaxCanRepeatAfterEveryFourteenSecondPhraseRelease() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val frame = feature(0.0, context = 0.55)
        gate.setSectionContext(0.40)
        gate.notifySection(0.75, now = 0.0)
        assertEquals(1, drive(gate, frame, 0.0, 0.4, FableSolVisualState.PEAK, request))
        assertEquals(FableSolGrandWaveReason.SECTION_LIFT, request.reason)

        frame.punch01 = 0.50
        frame.energyRising01 = 0.0
        assertEquals(0, drive(gate, frame, 0.4, 14.0, FableSolVisualState.CLIMAX, request))
        frame.punch01 = 0.86
        assertEquals(1, drive(gate, frame, 14.4, 0.5, FableSolVisualState.CLIMAX, request))
        assertEquals(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT, request.reason)
        assertEquals(2, gate.episodeCount())

        frame.punch01 = 0.50
        assertEquals(0, drive(gate, frame, 14.9, 13.9, FableSolVisualState.CLIMAX, request))
        frame.punch01 = 0.86
        assertEquals(1, drive(gate, frame, 28.8, 0.5, FableSolVisualState.CLIMAX, request))
        assertEquals(FableSolGrandWaveReason.PEAK_PHRASE_REPEAT, request.reason)
        assertEquals(3, gate.episodeCount())
    }

    @Test
    fun dropCannotBypassTheGlobalPhraseGap() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val frame = feature(0.0, harmonic = 0.72, rising = 0.70)
        assertEquals(1, drive(gate, frame, 0.0, 0.4, FableSolVisualState.PEAK, request))

        gate.notifyDrop(1.0)
        frame.t = 1.0
        frame.waterDrive01 = 0.90
        frame.kineticDrive01 = 0.90
        frame.intensityDrive01 = 0.90
        frame.harmonicMotion01 = 0.90
        assertFalse(gate.step(frame, FableSolVisualState.CLIMAX, request))

        gate.notifyDrop(1.0)
        frame.t = 14.4
        assertTrue(gate.step(frame, FableSolVisualState.CLIMAX, request))
        assertEquals(FableSolGrandWaveReason.DROP, request.reason)
    }

    @Test
    fun fastButQuietVocalMotionCannotTriggerABroadCrest() {
        val gate = FableSolGrandWaveEventGate()
        val request = FableSolGrandWaveRequest()
        val frame = feature(
            0.0,
            water = 0.68,
            kinetic = 0.95,
            intensity = 0.65,
            harmonic = 0.45,
            vocal = 0.90,
            rising = 0.80,
            punch = 0.90
        )
        assertEquals(0, drive(gate, frame, 0.0, 4.0, FableSolVisualState.PEAK, request))
    }

    @Test
    fun constantsDescribeARefractoryIntervalNotAnEpisodeQuota() {
        assertEquals(14.0, FableSolGrandWaveEventGate.REPEAT_MIN_GAP_S, 0.0)
        assertTrue(FableSolGrandWaveEventGate.REPEAT_CONFIRM_S > 0.0)
    }

    private fun drive(
        gate: FableSolGrandWaveEventGate,
        frame: FableSolPerceptualFrame,
        start: Double,
        seconds: Double,
        state: FableSolVisualState,
        request: FableSolGrandWaveRequest
    ): Int {
        var count = 0
        repeat((seconds * 60).toInt()) { index ->
            frame.t = start + index / 60.0
            if (gate.step(frame, state, request)) {
                gate.resolve(request, true)
                count += 1
            }
        }
        return count
    }

    private fun feature(
        t: Double,
        water: Double = 0.82,
        kinetic: Double = 0.82,
        intensity: Double = 0.70,
        rising: Double = 0.50,
        punch: Double = 0.74,
        percussive: Double = 0.65,
        harmonic: Double = 0.68,
        vocal: Double = 0.20,
        context: Double = 0.0
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
        frame.gradeDrive01 = intensity
    }
}
