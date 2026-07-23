package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FableSolFrontEndTuningTest {

    @Test
    fun defaultsAndUpdatesReachAnalyzerWithCatalogClamping() {
        val tuning = FableSolFrontEndTuning()
        val analyzer = FableSolRealtimeAnalyzer(48000)

        tuning.applyTo(analyzer)
        assertEquals(24.0, analyzer.agcWindowS, 0.0)
        assertEquals(6.0, analyzer.gateDb, 0.0)
        assertEquals(0.32, analyzer.expander, 0.0)
        assertEquals(0.21, analyzer.relativeLoudnessMix, 0.0)
        assertEquals(0.0, analyzer.stateSensitivity, 0.0)
        assertEquals(3.6, analyzer.noveltyFireZ(), 0.0)

        assertTrue(tuning.set("agc_window_s", 100.0))
        assertTrue(tuning.set("silence_gate_db", -2.0))
        assertTrue(tuning.set("expander_amount", 0.75))
        assertTrue(tuning.set("relative_loudness_mix", 2.0))
        assertTrue(tuning.set("state_sensitivity", -2.0))
        assertFalse(tuning.set("unknown", 1.0))
        tuning.applyTo(analyzer)

        assertEquals(30.0, analyzer.agcWindowS, 0.0)
        assertEquals(0.0, analyzer.gateDb, 0.0)
        assertEquals(0.75, analyzer.expander, 0.0)
        assertEquals(0.60, analyzer.relativeLoudnessMix, 0.0)
        assertEquals(-1.0, analyzer.stateSensitivity, 0.0)
        assertEquals(4.0, analyzer.noveltyFireZ(), 0.0)
    }

    @Test
    fun silenceGateChangesRealAnalyzerOutput() {
        val permissive = analyzeWithGate(0.0)
        val strict = analyzeWithGate(18.0)

        assertTrue("gate=0 nonSilent=${permissive.first}", permissive.first > 100)
        assertEquals(0, strict.first)
        assertTrue("loud=${permissive.second}", permissive.second > 0.25)
        assertEquals(0.0, strict.second, 0.0)
    }

    private fun analyzeWithGate(gateDb: Double): Pair<Int, Double> {
        val sampleRate = 48000
        val analyzer = FableSolRealtimeAnalyzer(sampleRate)
        val tuning = FableSolFrontEndTuning()
        tuning.set("silence_gate_db", gateDb)
        tuning.applyTo(analyzer)
        val amplitude = Math.pow(10.0, -50.0 / 20.0)
        val samples = DoubleArray(3 * sampleRate) { index ->
            amplitude * sin(2.0 * PI * 1000.0 * index / sampleRate)
        }
        var nonSilent = 0
        var loudnessSum = 0.0
        var loudnessCount = 0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 1024, samples.size)
            val (frames, _) = analyzer.feed(samples.copyOfRange(offset, end))
            for (frame in frames) {
                if (!frame.isSilent && frame.t > 0.5) nonSilent++
                if (frame.t > 0.5) {
                    loudnessSum += frame.loudness01
                    loudnessCount++
                }
            }
            offset = end
        }
        return nonSilent to loudnessSum / loudnessCount.coerceAtLeast(1)
    }
}
