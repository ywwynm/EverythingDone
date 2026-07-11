package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class FableSolWaveShapeContinuityTest {

    @Test
    fun onsetDoesNotDirectlyReshapeExistingHeroWave() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val mapper = FableSolFeatureMapper(params)

        mapper.applyOnset(
            sim,
            FableSolEvent.Onset(
                t = 1.0,
                strength01 = 1.0,
                centroid01 = 0.75,
                low = 0.20,
                mid = 0.50,
                high = 1.0,
                flatness01 = 0.50,
                pan01 = 0.50,
                stereoWidth01 = 0.0
            )
        )

        for (layer in sim.layers) {
            assertEquals(0.0, layer.heroPunch01, 0.0)
            for (value in layer.heroPunchBand01) assertEquals(0.0, value, 0.0)
        }
    }

    @Test
    fun abruptAudioTargetCannotReplaceExistingContourInOneFrame() {
        val changed = settledSimulation()
        val control = settledSimulation()
        for (layer in changed.layers) {
            layer.heroBandTargetDp[0] = 0.0
            layer.heroBandTargetDp[1] = 0.0
            layer.heroBandTargetDp[2] = 35.0
        }

        changed.update(1.0 / 60.0)
        control.update(1.0 / 60.0)

        val visible = BooleanArray(changed.uGrid.size) { abs(changed.uGrid[it]) <= changed.geometrySpan() / 2.0 }
        var maxCenteredRms = 0.0
        for (layer in changed.heights.indices) {
            val a = changed.heights[layer]
            val b = control.heights[layer]
            var mean = 0.0
            var count = 0
            for (i in a.indices) if (visible[i]) { mean += a[i] - b[i]; count++ }
            mean /= count
            var squareSum = 0.0
            for (i in a.indices) if (visible[i]) {
                val d = a[i] - b[i] - mean
                squareSum += d * d
            }
            maxCenteredRms = maxOf(maxCenteredRms, sqrt(squareSum / count))
        }

        assertTrue("maxCenteredRms=$maxCenteredRms", maxCenteredRms < 0.75)
    }

    @Test
    fun frameLevelChangesCannotImmediatelyReshapeVisibleHeroContour() {
        val changed = settledSimulation()
        val control = settledSimulation()
        val changedMapper = FableSolFeatureMapper(FableSolParams())
        val controlMapper = FableSolFeatureMapper(FableSolParams())
        changedMapper.applyFrame(changed, audioFrame(0.0, 0.45, 0.30, 0.30, 0.30, 0.5))
        controlMapper.applyFrame(control, audioFrame(0.0, 0.45, 0.30, 0.30, 0.30, 0.5))

        repeat(12) { frame ->
            val t = (frame + 1) / 60.0
            changedMapper.applyFrame(changed, audioFrame(t, 0.95, 0.08, 0.88, 0.16, 0.95))
            controlMapper.applyFrame(control, audioFrame(t, 0.18, 0.08, 0.12, 0.08, 0.15))
            changed.update(1.0 / 60.0)
            control.update(1.0 / 60.0)
        }

        val maxCenteredRms = visibleCenteredRms(changed, control)
        assertTrue("可见主浪被逐帧音频全局改形：rms=$maxCenteredRms", maxCenteredRms < 0.10)

        repeat(90) { frame ->
            val t = (frame + 13) / 60.0
            changedMapper.applyFrame(changed, audioFrame(t, 0.95, 0.08, 0.88, 0.16, 0.95))
            controlMapper.applyFrame(control, audioFrame(t, 0.18, 0.08, 0.12, 0.08, 0.15))
            changed.update(1.0 / 60.0)
            control.update(1.0 / 60.0)
        }
        val propagatedRms = visibleCenteredRms(changed, control)
        assertTrue("上游 Hero 声音能量没有传播进可见区：rms=$propagatedRms", propagatedRms > 0.15)
    }

    private fun settledSimulation(): FableSolSimulation {
        val sim = FableSolSimulation(FableSolParams())
        for (layer in sim.layers) {
            layer.heroTargetDp = 20.0
            layer.heroBandTargetDp[0] = 10.0
            layer.heroBandTargetDp[1] = 7.0
            layer.heroBandTargetDp[2] = 3.0
        }
        repeat(180) { sim.update(1.0 / 60.0) }
        return sim
    }

    private fun visibleCenteredRms(changed: FableSolSimulation, control: FableSolSimulation): Double {
        val visible = BooleanArray(changed.uGrid.size) { abs(changed.uGrid[it]) <= changed.geometrySpan() / 2.0 }
        var maxCenteredRms = 0.0
        for (layer in 0 until FableSolSpec.DEEP_LAYER_START) {
            val a = changed.heights[layer]
            val b = control.heights[layer]
            var mean = 0.0
            var count = 0
            for (i in a.indices) if (visible[i]) { mean += a[i] - b[i]; count++ }
            mean /= count
            var squareSum = 0.0
            for (i in a.indices) if (visible[i]) {
                val d = a[i] - b[i] - mean
                squareSum += d * d
            }
            maxCenteredRms = maxOf(maxCenteredRms, sqrt(squareSum / count))
        }
        return maxCenteredRms
    }

    private fun audioFrame(t: Double, loud: Double, low: Double, mid: Double,
                           high: Double, pitch: Double) = FableSolFeatureFrame(
        t = t,
        loudness01 = loud,
        bandLow = low,
        bandMid = mid,
        bandHigh = high,
        relLow = low / (low + mid + high),
        relMid = mid / (low + mid + high),
        relHigh = high / (low + mid + high),
        centroid01 = 0.5,
        spectralTilt01 = 0.5,
        flatness01 = 0.15,
        percussive01 = 0.0,
        punch01 = 0.0,
        stereoWidth01 = 0.0,
        pan01 = 0.5,
        onsetEnv = 0.0,
        flow01 = 0.0,
        activity01 = loud,
        loudDb = -20.0,
        floorDb = -60.0,
        isSilent = false,
        tempoBpm = 0.0,
        beatPhase01 = 0.0,
        beatConf01 = 0.0,
        pitchRel01 = pitch,
        voiced01 = 1.0,
        sylRateHz = 2.0
    )
}
