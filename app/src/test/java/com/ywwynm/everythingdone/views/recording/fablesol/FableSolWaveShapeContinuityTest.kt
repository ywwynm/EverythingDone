package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

        var maxCenteredRms = 0.0
        for (layer in changed.heights.indices) {
            val a = changed.heights[layer]
            val b = control.heights[layer]
            var mean = 0.0
            for (i in a.indices) mean += a[i] - b[i]
            mean /= a.size
            var squareSum = 0.0
            for (i in a.indices) {
                val d = a[i] - b[i] - mean
                squareSum += d * d
            }
            maxCenteredRms = maxOf(maxCenteredRms, sqrt(squareSum / a.size))
        }

        assertTrue("maxCenteredRms=$maxCenteredRms", maxCenteredRms < 0.75)
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
}
