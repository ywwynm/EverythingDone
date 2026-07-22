package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class FableSolPerceptualCalibratorTest {

    @Test
    fun fixedLoudnessScaleDoesNotDependOnProgramHistory() {
        assertEquals(0.0, FableSolPerceptualCalibrator.fixedLoudness01(-30.0), 0.0)
        assertEquals(0.5, FableSolPerceptualCalibrator.fixedLoudness01(-16.0), 1e-12)
        assertEquals(1.0, FableSolPerceptualCalibrator.fixedLoudness01(-2.0), 0.0)
    }

    @Test
    fun sustainedFastPassageCannotAdaptItselfSlow() {
        val calibrator = FableSolPerceptualCalibrator(100.0)
        val input = energeticInput()
        var output = FableSolPerceptualCalibration()
        repeat(3000) { index ->
            input.t = index / 100.0
            output = calibrator.step(input)
        }

        assertTrue("speed=${output.speed01}", output.speed01 > 0.78)
        assertTrue("kinetic=${output.kineticDrive01}", output.kineticDrive01 >= output.speed01)
        assertTrue("targetDps=${output.targetDps}", output.targetDps > 120.0)
    }

    @Test
    fun stepReusesOutputObjectAndMomentaryCanOnlyAddBoundedBoost() {
        val calibrator = FableSolPerceptualCalibrator(100.0)
        calibrator.configure(0.20)
        val input = energeticInput().also {
            it.loudSDb = -18.0
            it.loudMDb = -6.0
        }

        val first = calibrator.step(input)
        input.t += 0.01
        val second = calibrator.step(input)

        assertSame(first, second)
        assertTrue(second.loudnessRaw01 >= second.loudnessAbsolute01)
        assertTrue(second.loudnessTransientBoost01 <= 0.18 + 1e-12)
    }

    @Test
    fun captureShelfReconstructsLowBandWithoutTouchingBufferShape() {
        val sr = 44100
        val conditionerLow = FableSolCaptureConditioner(FableSolCaptureProfile.PHONE_CAPTURE_V1, sr)
        val conditionerHigh = FableSolCaptureConditioner(FableSolCaptureProfile.PHONE_CAPTURE_V1, sr)
        val low = DoubleArray(sr) { sin(2.0 * PI * 100.0 * it / sr) }
        val high = DoubleArray(sr) { sin(2.0 * PI * 4000.0 * it / sr) }
        val lowOut = DoubleArray(low.size)
        val highOut = DoubleArray(high.size)
        conditionerLow.process(low, 0, low.size, lowOut)
        conditionerHigh.process(high, 0, high.size, highOut)

        val lowGain = rms(lowOut, sr / 2) / rms(low, sr / 2)
        val highGain = rms(highOut, sr / 2) / rms(high, sr / 2)
        assertTrue("lowGain=$lowGain highGain=$highGain", lowGain > highGain * 4.0)
    }

    @Test
    fun fixedDisplayProfileExpandsLoudnessMonotonicallyAndRebalancesSpectralIdentity() {
        val profile = FableSolCaptureProfile.PHONE_CAPTURE_V1
        assertEquals(-26.6, profile.displayLoudnessPivotDb, 0.0)
        assertEquals(1.76, profile.displayLoudnessRatio, 0.0)
        assertEquals(0.65, profile.displayLoudnessBlend01, 0.0)
        assertTrue(profile.displayLoudnessDb(-18.0) > profile.displayLoudnessDb(-24.0))

        val spectral = DoubleArray(4)
        profile.displaySpectralIdentity(0.20, 0.45, 0.35, 0.58, spectral)
        assertEquals(1.0, spectral[0] + spectral[1] + spectral[2], 1e-12)
        assertTrue("low=${spectral[0]}", spectral[0] > 0.20)
        assertTrue("high=${spectral[2]}", spectral[2] < 0.35)
        assertTrue("centroid=${spectral[3]}", spectral[3] < 0.58)
    }

    @Test
    fun displayCalibrationCannotChangeAuthoritativeRawCalibration() {
        val rawCalibrator = FableSolPerceptualCalibrator(100.0)
        val displayCalibrator = FableSolPerceptualCalibrator(100.0)
        val rawInput = energeticInput().also {
            it.loudMDb = -18.0
            it.loudSDb = -20.0
        }
        val displayInput = energeticInput().also {
            it.loudMDb = -18.0
            it.loudSDb = -20.0
            it.displayLoudMDb = -10.0
            it.displayLoudSDb = -12.0
            it.displayLoudnessBlend01 = 0.65
        }

        val raw = rawCalibrator.step(rawInput)
        val withDisplay = displayCalibrator.step(displayInput)

        assertEquals(raw.waterDrive01, withDisplay.waterDrive01, 0.0)
        assertEquals(raw.kineticDrive01, withDisplay.kineticDrive01, 0.0)
        assertEquals(raw.gradeDrive01, withDisplay.gradeDrive01, 0.0)
        assertEquals(raw.liftScore01, withDisplay.liftScore01, 0.0)
        assertEquals(raw.climaxScore01, withDisplay.climaxScore01, 0.0)
        assertTrue(withDisplay.displayWaterDrive01 > withDisplay.waterDrive01)
    }

    @Test
    fun displayEnvelopeAndEvidenceResetReplayExactly() {
        val calibrator = FableSolPerceptualCalibrator(100.0)
        val input = energeticInput().also {
            it.displayLoudMDb = -10.0
            it.displayLoudSDb = -12.0
            it.displayLoudnessBlend01 = 0.65
        }
        val first = ArrayList<DoubleArray>()
        repeat(160) { index ->
            input.t = index / 100.0
            val out = calibrator.step(input)
            first.add(doubleArrayOf(
                out.displayWaterDrive01,
                out.displayGradeDrive01,
                out.displayLiftScore01,
                out.displayClimaxScore01
            ))
        }

        calibrator.reset(true)
        repeat(160) { index ->
            input.t = index / 100.0
            val out = calibrator.step(input)
            assertEquals(first[index][0], out.displayWaterDrive01, 0.0)
            assertEquals(first[index][1], out.displayGradeDrive01, 0.0)
            assertEquals(first[index][2], out.displayLiftScore01, 0.0)
            assertEquals(first[index][3], out.displayClimaxScore01, 0.0)
        }
    }

    @Test
    fun analyzerDefaultsToMasterWhileExplicitCaptureRestoresDeviceLoss() {
        val sr = 44100
        val samples = DoubleArray(5 * sr) { index ->
            0.035 * sin(2.0 * PI * 1000.0 * index / sr)
        }
        val master = lastFrame(FableSolRealtimeAnalyzer(sr), samples)
        val capture = lastFrame(
            FableSolRealtimeAnalyzer(sr, FableSolCaptureProfile.PHONE_CAPTURE_V1),
            samples
        )

        assertEquals(0.0, master.inputLoudnessTrimDb, 0.0)
        assertEquals(10.5, capture.inputLoudnessTrimDb, 0.0)
        assertTrue(
            "masterW=${master.waterDrive01} captureW=${capture.waterDrive01}",
            capture.waterDrive01 > master.waterDrive01 + 0.20
        )
        assertEquals(master.waterDrive01, master.displayWaterDrive01, 0.0)
        assertEquals(master.gradeDrive01, master.displayGradeDrive01, 0.0)
        assertEquals(master.liftScore01, master.displayLiftScore01, 0.0)
        assertEquals(master.climaxScore01, master.displayClimaxScore01, 0.0)
        assertEquals(master.relLow, master.displayRelLow, 0.0)
        assertEquals(master.relMid, master.displayRelMid, 0.0)
        assertEquals(master.relHigh, master.displayRelHigh, 0.0)
        assertEquals(master.centroid01, master.displayCentroid01, 0.0)
    }

    private fun energeticInput() = FableSolCalibrationInput().also {
        it.silent = false
        it.loudMDb = -7.0
        it.loudSDb = -8.0
        it.speedAbs01 = 0.82
        it.rawRateHz = 6.0
        it.onsetEnv = 0.65
        it.flux = 0.12
        it.tempoBpm = 140.0
        it.tempoConf01 = 0.8
        it.centroid01 = 0.58
        it.bassRatio01 = 0.42
        it.percussiveMotion01 = 0.82
        it.vocalMotion01 = 0.50
        it.harmonicMotion01 = 0.72
        it.beatMotion01 = 0.78
        it.grooveMotion01 = 0.80
        it.punch01 = 0.72
        it.lowShare01 = 0.42
    }

    private fun rms(values: DoubleArray, start: Int): Double {
        var sum = 0.0
        for (i in start until values.size) sum += values[i] * values[i]
        return sqrt(sum / (values.size - start))
    }

    private fun lastFrame(
        analyzer: FableSolRealtimeAnalyzer,
        samples: DoubleArray
    ): FableSolFeatureFrame {
        var last: FableSolFeatureFrame? = null
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 1024, samples.size)
            val (frames, _) = analyzer.feed(samples.copyOfRange(offset, end))
            if (frames.isNotEmpty()) last = frames.last()
            offset = end
        }
        return requireNotNull(last)
    }
}
