package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class FableSolExpressionUpgradeTest {

    @Test
    fun kWeightingKeepsExpectedResponseAtAndroidSampleRate() {
        val response = FableSolAudioFrontEnd.kWeightPower(
            doubleArrayOf(60.0, 1000.0, 10000.0), 44100.0)
        val db = DoubleArray(response.size) { 10.0 * log10(response[it]) }

        assertTrue("60Hz=${db[0]}", db[0] < -2.0)
        assertTrue("1kHz=${db[1]}", abs(db[1] - 0.7) < 0.5)
        assertTrue("10kHz=${db[2]}", abs(db[2] - 4.0) < 0.6)
    }

    @Test
    fun voicedAmplitudeModulationProducesProsodyAndFiniteExpressionFeatures() {
        val sr = 44100
        val seconds = 6.0
        val samples = DoubleArray((seconds * sr).toInt()) { index ->
            val t = index.toDouble() / sr
            val carrier = sin(2.0 * PI * 150.0 * t) + 0.4 * sin(2.0 * PI * 300.0 * t)
            val envelope = 0.1 + 0.9 * 0.5 * (1.0 - kotlin.math.cos(2.0 * PI * 4.0 * t))
            0.25 * carrier * envelope
        }

        val result = analyze(samples, sr)
        val voicedTail = result.frames.filter { it.t > 1.0 && it.voiced01 > 0.5 }
        val medianF0 = voicedTail.map { it.f0Hz }.sorted().let { it[it.size / 2] }
        val rateTail = result.frames.filter { it.t > 3.0 }.map { it.sylRateHz }.sorted()
        val medianRate = rateTail[rateTail.size / 2]

        assertTrue("voicedFrames=${voicedTail.size}", voicedTail.size > 100)
        assertTrue("medianF0=$medianF0", abs(medianF0 - 150.0) < 6.0)
        assertTrue("medianRate=$medianRate", medianRate in 2.4..5.6)
        assertTrue("hnr=${voicedTail.takeLast(60).map { it.hnr01 }.average()}",
            voicedTail.takeLast(60).map { it.hnr01 }.average() > 0.45)
        result.frames.forEach { frame ->
            assertFalse(frame.loudMDb.isNaN())
            assertFalse(frame.loudSDb.isNaN())
            assertFalse(frame.fluct4hz01.isNaN())
            assertFalse(frame.arousal01.isNaN())
            assertFalse(frame.loom01.isNaN())
            assertFalse(frame.impulse01.isNaN())
        }
    }

    @Test
    fun hnrSeparatesCleanToneFromNoise() {
        val sr = 44100
        val tone = DoubleArray(5 * sr) { index -> 0.20 * sin(2.0 * PI * 220.0 * index / sr) }
        val random = Random(3)
        val noise = DoubleArray(5 * sr) { 0.20 * random.nextGaussian() }

        val toneHnr = analyze(tone, sr).frames.takeLast(60).map { it.hnr01 }.average()
        val noiseHnr = analyze(noise, sr).frames.takeLast(60).map { it.hnr01 }.average()

        assertTrue("tone=$toneHnr noise=$noiseHnr", toneHnr > noiseHnr + 0.30)
        assertTrue("noise=$noiseHnr", noiseHnr < 0.20)
    }

    @Test
    fun mapperTensionRisesWithSustainedLoomAndReturnsDuringSilence() {
        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val simulation = FableSolSimulation(params)
        var t = 0.0
        repeat(180) {
            t += 1.0 / 60.0
            mapper.applyFrame(simulation, expressionFrame(t, loom01 = 0.9, voiced01 = 1.0))
        }
        assertTrue("tension=${simulation.tension01}", simulation.tension01 > 0.40)

        repeat(240) { mapper.applySilence(simulation) }
        assertTrue("tension=${simulation.tension01}", simulation.tension01 < 0.15)
    }

    private fun analyze(samples: DoubleArray, sr: Int): Analysis {
        val analyzer = FableSolRealtimeAnalyzer(sr)
        val frames = ArrayList<FableSolFeatureFrame>()
        val events = ArrayList<FableSolEvent>()
        // 先以可信中频内容开放 Android 专属的采集启动保护；本组只验证其后的 A1/A3/A6 前端。
        val warmup = DoubleArray((0.45 * sr).toInt()) { index ->
            val t = index.toDouble() / sr
            0.04 * sin(2.0 * PI * 620.0 * t) + 0.02 * sin(2.0 * PI * 1240.0 * t)
        }
        var warmupOffset = 0
        while (warmupOffset < warmup.size) {
            val end = minOf(warmupOffset + 512, warmup.size)
            val (newFrames, newEvents) = analyzer.feed(warmup.copyOfRange(warmupOffset, end))
            frames.addAll(newFrames)
            events.addAll(newEvents)
            warmupOffset = end
        }
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val (newFrames, newEvents) = analyzer.feed(samples.copyOfRange(offset, end))
            frames.addAll(newFrames)
            events.addAll(newEvents)
            offset = end
        }
        return Analysis(frames, events)
    }

    private fun expressionFrame(t: Double, loom01: Double, voiced01: Double) =
        FableSolFeatureFrame(
            t = t,
            loudness01 = 0.5,
            bandLow = 0.3,
            bandMid = 0.3,
            bandHigh = 0.3,
            relLow = 1.0 / 3.0,
            relMid = 1.0 / 3.0,
            relHigh = 1.0 / 3.0,
            centroid01 = 0.5,
            spectralTilt01 = 0.5,
            flatness01 = 0.2,
            percussive01 = 0.0,
            punch01 = 0.0,
            stereoWidth01 = 0.0,
            pan01 = 0.5,
            onsetEnv = 0.0,
            flow01 = 0.3,
            activity01 = 0.3,
            loudDb = -20.0,
            floorDb = -60.0,
            isSilent = false,
            tempoBpm = 0.0,
            beatPhase01 = 0.0,
            beatConf01 = 0.0,
            voiced01 = voiced01,
            loom01 = loom01
        )

    private data class Analysis(
        val frames: List<FableSolFeatureFrame>,
        val events: List<FableSolEvent>
    )
}
