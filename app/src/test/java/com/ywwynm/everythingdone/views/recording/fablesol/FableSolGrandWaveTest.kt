package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.PHYSICS_DT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FableSolGrandWaveTest {

    @Test
    fun triggerCreatesOneWideWaveOffscreenAndRejectsRetrigger() {
        val sim = FableSolSimulation(FableSolParams())

        assertTrue(sim.triggerGrandWave())
        val wave = sim.grandWave
        assertTrue(wave.active)
        assertEquals(840.0, wave.widthDp, 0.0)
        assertEquals(144.0, wave.amplitudeDp, 0.0)
        assertEquals(
            sim.geometrySpan() / 2.0 + wave.widthDp / 2.0 + 8.0,
            wave.centerUDp,
            1e-12
        )
        assertEquals(-sim.layerWaveSpeedDps(0), wave.transportDps, 1e-12)
        assertFalse(sim.triggerGrandWave(1.5))
        assertEquals(1, wave.triggerCount)
    }

    @Test
    fun compactProfileIsC2AtBothEdgesAndReusesStorage() {
        val sim = FableSolSimulation(FableSolParams())
        val wave = FableSolGrandWave(pointCount = 9)
        assertTrue(wave.trigger(sim))
        wave.centerUDp = 0.0
        val half = wave.widthDp / 2.0
        val epsilon = 0.01
        val u = doubleArrayOf(
            -half - epsilon,
            -half,
            -half + epsilon,
            -half / 2.0,
            0.0,
            half / 2.0,
            half - epsilon,
            half,
            half + epsilon
        )

        val first = wave.sample(u)!!
        val second = wave.sample(u)!!
        assertSame(first, second)
        assertEquals(0.0, first[0], 0.0)
        assertEquals(0.0, first[1], 0.0)
        assertEquals(0.0, first[7], 0.0)
        assertEquals(0.0, first[8], 0.0)
        assertEquals(wave.amplitudeDp, first[4], 1e-12)
        assertEquals(wave.amplitudeDp * 0.5, first[3], 1e-12)
        assertEquals(wave.amplitudeDp * 0.5, first[5], 1e-12)

        // quintic smootherstep 在支撑边界的一、二阶导均为 0；极小内侧样本应按 O(e^3) 出生。
        val edgeScale = wave.amplitudeDp * 10.0 * (epsilon / half) *
            (epsilon / half) * (epsilon / half)
        assertTrue(first[2] in 0.0..(edgeScale * 1.01))
        assertEquals(first[2], first[6], 1e-10)
        assertEquals(0.12, wave.backgroundKeep(wave.amplitudeDp), 1e-12)
        assertEquals(1.0, wave.backgroundKeep(0.0), 0.0)
    }

    @Test
    fun fixedStepAdvectsAtPhysicalTransportAndDeactivatesAfterPassingScreen() {
        val sim = FableSolSimulation(FableSolParams())
        sim.layers[0].flowDps = -24.0
        assertTrue(sim.triggerGrandWave())
        val wave = sim.grandWave
        val expectedTransport = -sim.layerWaveSpeedDps(0) - 24.0
        val initialCenter = wave.centerUDp

        sim.update(PHYSICS_DT)

        assertEquals(expectedTransport, wave.transportDps, 1e-9)
        assertEquals(initialCenter + expectedTransport * PHYSICS_DT, wave.centerUDp, 1e-9)
        assertEquals(abs(expectedTransport * PHYSICS_DT), wave.distanceDp, 1e-9)

        repeat(16) {
            if (wave.active) {
                sim.t += 0.5
                wave.advance(sim)
            }
        }
        assertFalse("事件浪应在完整通过画面后自然离场", wave.active)
        assertTrue(sim.triggerGrandWave(expressionGain = 2.0))
        assertEquals(0.24 * 840.0, wave.amplitudeDp, 1e-12)
    }

    @Test
    fun layerZeroCompositionSuppressesBackgroundUnderCrestWithoutTouchingOtherLayers() {
        val eventParams = quietParams()
        val controlParams = quietParams()
        val event = FableSolSimulation(eventParams)
        val control = FableSolSimulation(controlParams)
        event.layers[0].wave.u.fill(40.0)
        control.layers[0].wave.u.fill(40.0)

        assertTrue(event.triggerGrandWave())
        event.grandWave.centerUDp = 0.0
        event.update(0.0)
        control.update(0.0)

        val profile = event.grandWave.sample(event.uGrid)!!
        val centerIndex = event.uGrid.indices.minByOrNull { abs(event.uGrid[it]) }!!
        val keep = event.grandWave.backgroundKeep(profile[centerIndex])
        val controlDetail = control.heights[0][centerIndex] - 96.0
        val expected = 96.0 + controlDetail * keep + profile[centerIndex]
        assertEquals(expected, event.heights[0][centerIndex], 1e-9)

        for (layer in 1 until FableSolSpec.N_LAYERS) {
            for (point in event.uGrid.indices) {
                assertEquals(control.heights[layer][point], event.heights[layer][point], 1e-12)
            }
        }
    }

    private fun quietParams() = FableSolParams().also {
        it.setForTest("ambient_gain", 0.0)
        it.setForTest("wander_gain", 0.0)
        it.setForTest("hero_gain", 0.0)
    }
}
