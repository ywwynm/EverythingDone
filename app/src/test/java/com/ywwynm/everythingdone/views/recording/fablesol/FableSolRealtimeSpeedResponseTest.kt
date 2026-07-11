package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FableSolRealtimeSpeedResponseTest {

    @Test
    fun denseAudibleEventsRaiseFlowDuringTheFirstTwoSeconds() {
        val sr = 44100
        val samples = DoubleArray(6 * sr)
        val pulseN = (0.045 * sr).toInt()
        repeat(24) { pulse ->
            val start = ((1.0 + pulse / 6.0) * sr).toInt()
            val hz = 330.0 + 70.0 * (pulse % 3)
            repeat(pulseN) { index ->
                val t = index.toDouble() / sr
                val window = 0.5 - 0.5 * cos(2.0 * PI * index / (pulseN - 1))
                samples[start + index] += 0.08 * sin(2.0 * PI * hz * t) * window
            }
        }

        val analyzer = FableSolRealtimeAnalyzer(sr)
        var onsetCount = 0
        var flowAt2_5s = 0.0
        var maxFlow = 0.0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val (frames, events) = analyzer.feed(samples.copyOfRange(offset, end))
            onsetCount += events.count { it is FableSolEvent.Onset }
            for (frame in frames) {
                if (frame.t in 2.45..2.55) flowAt2_5s = maxOf(flowAt2_5s, frame.flow01)
                maxFlow = maxOf(maxFlow, frame.flow01)
            }
            offset = end
        }

        assertTrue("onsetCount=$onsetCount", onsetCount >= 20)
        assertTrue("flowAt2_5s=$flowAt2_5s", flowAt2_5s > 0.52)
        assertTrue("maxFlow=$maxFlow", maxFlow > 0.65)
    }
}
