package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FableSolRealtimeAnalyzerNoiseTest {

    @Test
    fun logBandsStopAtDeclaredUpperBoundary() {
        val spectrum = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 100.0, 200.0)
        val boundaries = intArrayOf(0, 2, 4)

        val actual = FableSolMath.sumAdjacentSegments(spectrum, boundaries)

        assertArrayEquals(doubleArrayOf(3.0, 7.0), actual, 0.0)
    }

    @Test
    fun ultrasonicAndroidNoiseStaysNearSilent() {
        val sr = 44100
        val samples = DoubleArray(12 * sr) { index ->
            val t = index.toDouble() / sr
            val baseAmp = if (t < 5.0) 0.00030 else 0.0010
            val carrierAmp = baseAmp * (1.0 + 0.38 * sin(2.0 * PI * 7.4 * t))
            carrierAmp * sin(2.0 * PI * 18300.0 * t) +
                    0.00020 * sin(2.0 * PI * 100.0 * t)
        }

        val result = analyze(samples, sr)

        assertTrue("onsets=${result.onsetCount}", result.onsetCount <= 4)
        assertTrue("meanLoudness=${result.meanLoudness}", result.meanLoudness < 0.05)
        assertTrue("meanActivity=${result.meanActivity}", result.meanActivity < 0.10)
    }

    @Test
    fun clearlyAudibleSparsePulsesRemainDetectable() {
        val sr = 44100
        val samples = DoubleArray(10 * sr)
        val pulseN = (0.05 * sr).toInt()
        for (pulse in 1 until 19) {
            val start = (0.5 * pulse * sr).toInt()
            val hz = 330.0 + 30.0 * (pulse % 3)
            for (i in 0 until pulseN) {
                val t = i.toDouble() / sr
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (pulseN - 1))
                samples[start + i] += 0.08 * sin(2.0 * PI * hz * t) * window
            }
        }

        val result = analyze(samples, sr)

        assertTrue("onsets=${result.onsetCount}", result.onsetCount >= 14)
        assertTrue("maxLoudness=${result.maxLoudness}", result.maxLoudness > 0.50)
    }

    @Test
    fun lowFrequencyCaptureStartupDoesNotRaiseWaterBeforeItSettles() {
        val sr = 44100
        val samples = DoubleArray((5.5 * sr).toInt()) { index ->
            val t = index.toDouble() / sr
            val amp = if (t < 4.2) 0.055 * kotlin.math.exp(-t / 3.0) else 0.00035
            amp * (0.78 * sin(2.0 * PI * 82.0 * t) + 0.22 * sin(2.0 * PI * 116.0 * t))
        }
        val analyzer = FableSolRealtimeAnalyzer(sr)
        var maxStartupLoudness = 0.0
        var startupOnsets = 0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val (frames, events) = analyzer.feed(samples.copyOfRange(offset, end))
            for (frame in frames) if (frame.t < 4.0) {
                maxStartupLoudness = maxOf(maxStartupLoudness, frame.loudness01)
            }
            startupOnsets += events.count { it is FableSolEvent.Onset && it.t < 4.0 }
            offset = end
        }

        assertTrue("maxStartupLoudness=$maxStartupLoudness", maxStartupLoudness < 0.01)
        assertTrue("startupOnsets=$startupOnsets", startupOnsets == 0)
    }

    @Test
    fun immediateMidbandSoundCanReleaseStartupGateEarly() {
        val sr = 44100
        val samples = DoubleArray(2 * sr) { index ->
            val t = index.toDouble() / sr
            0.045 * sin(2.0 * PI * 620.0 * t) + 0.025 * sin(2.0 * PI * 1280.0 * t)
        }
        val analyzer = FableSolRealtimeAnalyzer(sr)
        var audibleAfterWarmup = false
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val (frames, _) = analyzer.feed(samples.copyOfRange(offset, end))
            if (frames.any { it.t in 0.45..1.0 && it.loudness01 > 0.35 }) audibleAfterWarmup = true
            offset = end
        }

        assertTrue("真实中频声音不应等待完整预热超时", audibleAfterWarmup)
    }

    @Test
    fun captureTrimCannotOpenRawAudibilityGate() {
        val sr = 44100
        val amplitude = Math.pow(10.0, -68.0 / 20.0)
        val samples = DoubleArray(4 * sr) { index ->
            amplitude * sin(2.0 * PI * 1000.0 * index / sr)
        }
        val analyzer = FableSolRealtimeAnalyzer(
            sr,
            FableSolCaptureProfile(loudnessTrimDb = 12.0, lowShelfGainDb = 18.0)
        )
        var maxWater = 0.0
        var anyAudible = false
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val (frames, _) = analyzer.feed(samples.copyOfRange(offset, end))
            for (frame in frames) {
                anyAudible = anyAudible || !frame.isSilent
                maxWater = maxOf(maxWater, frame.waterDrive01)
            }
            offset = end
        }

        assertTrue("输入 trim 不得泄漏进原始 A 计权安全门", !anyAudible)
        assertTrue("maxWater=$maxWater", maxWater == 0.0)
    }

    private fun analyze(samples: DoubleArray, sr: Int): AnalysisResult {
        val analyzer = FableSolRealtimeAnalyzer(sr)
        var onsetCount = 0
        var frameCount = 0
        var loudnessSum = 0.0
        var activitySum = 0.0
        var maxLoudness = 0.0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val (frames, events) = analyzer.feed(samples.copyOfRange(offset, end))
            onsetCount += events.count { it is FableSolEvent.Onset }
            for (frame in frames) {
                frameCount++
                loudnessSum += frame.loudness01
                activitySum += frame.activity01
                maxLoudness = maxOf(maxLoudness, frame.loudness01)
            }
            offset = end
        }
        return AnalysisResult(
            onsetCount = onsetCount,
            meanLoudness = loudnessSum / frameCount,
            meanActivity = activitySum / frameCount,
            maxLoudness = maxLoudness
        )
    }

    private data class AnalysisResult(
        val onsetCount: Int,
        val meanLoudness: Double,
        val meanActivity: Double,
        val maxLoudness: Double
    )
}
