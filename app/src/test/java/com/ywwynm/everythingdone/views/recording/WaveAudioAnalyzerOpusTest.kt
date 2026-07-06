package com.ywwynm.everythingdone.views.recording

import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveAudioAnalyzerOpusTest {

    @Test
    fun steadyTonalContentDoesNotCollapseBackToRestWaterLevel() {
        val analyzer = WaveAudioAnalyzerOpus(SAMPLE_RATE)
        val background = noiseFrame(amplitude = 0.001f, seed = 0x13579BDF)

        repeat(60) {
            analyzer.ingest(background, background.size)
            analyzer.analyze(FRAME_MS)
        }

        var peakWater = 0f
        var lateWater = 0f
        repeat(300) { frame ->
            val content = sineFrame(amplitude = 0.08f, hz = 220f, frameIndex = frame)
            analyzer.ingest(content, content.size)
            val drive = analyzer.analyze(FRAME_MS)
            if (frame >= 10) peakWater = max(peakWater, drive.waterLevel)
            if (frame == 299) lateWater = drive.waterLevel
        }

        assertTrue("fixture should produce a visible water response, peak=$peakWater", peakWater > 0.12f)
        assertTrue(
            "steady content should not be absorbed as floor noise, peak=$peakWater late=$lateWater",
            lateWater > 0.08f && lateWater > peakWater * 0.45f
        )
    }

    @Test
    fun steadyContentPresentAtStartupStillBuildsVisibleWaterLevel() {
        val analyzer = WaveAudioAnalyzerOpus(SAMPLE_RATE)

        var peakWater = 0f
        repeat(120) { frame ->
            val content = sineFrame(amplitude = 0.08f, hz = 220f, frameIndex = frame)
            analyzer.ingest(content, content.size)
            peakWater = max(peakWater, analyzer.analyze(FRAME_MS).waterLevel)
        }

        assertTrue("startup content should seed below the signal, peak=$peakWater", peakWater > 0.12f)
    }

    @Test
    fun steadyWidebandEnvironmentNoiseDoesNotDriveHighWaterLevel() {
        val analyzer = WaveAudioAnalyzerOpus(SAMPLE_RATE)
        val background = noiseFrame(amplitude = 0.001f, seed = 0x13579BDF)
        val outsideNoise = noiseFrame(amplitude = 0.08f, seed = 0x2468ACE)

        repeat(60) {
            analyzer.ingest(background, background.size)
            analyzer.analyze(FRAME_MS)
        }

        var peakWater = 0f
        var lateWater = 0f
        repeat(300) { frame ->
            analyzer.ingest(outsideNoise, outsideNoise.size)
            val drive = analyzer.analyze(FRAME_MS)
            if (frame >= 30) peakWater = max(peakWater, drive.waterLevel)
            if (frame == 299) lateWater = drive.waterLevel
        }

        assertTrue(
            "steady wideband environment noise should stay visually low, peak=$peakWater late=$lateWater",
            peakWater < 0.18f && lateWater < 0.10f
        )
    }

    private fun noiseFrame(amplitude: Float, seed: Int): ByteArray {
        val out = ByteArray(FRAME_SAMPLES * 2)
        var state = seed
        for (i in 0 until FRAME_SAMPLES) {
            state = state * 1664525 + 1013904223
            val unit = (((state ushr 16) and 0xffff) / 32767.5f) - 1f
            val sample = (unit * amplitude * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xff).toByte()
            out[i * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
        return out
    }

    private fun sineFrame(amplitude: Float, hz: Float, frameIndex: Int): ByteArray {
        val out = ByteArray(FRAME_SAMPLES * 2)
        val baseSample = frameIndex * FRAME_SAMPLES
        for (i in 0 until FRAME_SAMPLES) {
            val phase = 2.0 * Math.PI * hz * (baseSample + i) / SAMPLE_RATE
            val sample = (kotlin.math.sin(phase) * amplitude * 32767.0)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xff).toByte()
            out[i * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
        return out
    }

    private companion object {
        private const val SAMPLE_RATE = 44_100
        private const val FRAME_MS = 20L
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS.toInt() / 1000
    }
}
