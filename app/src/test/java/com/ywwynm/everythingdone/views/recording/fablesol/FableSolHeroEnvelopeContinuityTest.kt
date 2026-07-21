package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FableSolHeroEnvelopeContinuityTest {

    @Test
    fun sourceBirthUsesMultiCellC2BlendOnEveryLayer() {
        val sim = FableSolSimulation(FableSolParams())
        for (layer in sim.layers) {
            layer.heroBandTargetDp[0] = 18.0
            layer.heroBandTargetDp[1] = 11.0
            layer.heroBandTargetDp[2] = 6.0
        }

        sim.update(1.0 / 60.0)

        for (layer in sim.layers) {
            for (band in 0 until 3) {
                val source = layer.heroBandDp[band]
                val field = layer.heroBandFieldDp[band]
                assertTrue("layer=${layer.i}, band=$band", source > 0.0)

                var transitionCells = 0
                var maxNormalizedStep = 0.0
                var previous = field[0] / source
                for (point in 1 until field.size) {
                    val normalized = field[point] / source
                    if (normalized > 1e-4 && normalized < 1.0 - 1e-4) transitionCells++
                    assertTrue(
                        "上游出生包络必须单调且连续: layer=${layer.i}, band=$band, point=$point",
                        normalized + 1e-12 >= previous
                    )
                    maxNormalizedStep = maxOf(maxNormalizedStep, abs(normalized - previous))
                    previous = normalized
                }
                assertTrue("C2 注入应跨越多个网格: cells=$transitionCells", transitionCells >= 4)
                assertTrue("不得重新形成单格台阶: step=$maxNormalizedStep", maxNormalizedStep < 0.35)
                assertEquals(1.0, field.last() / source, 1e-12)
            }
        }
    }

    @Test
    fun simulationConsumesLayerSpreadAndMoodEnergyCannotMoveWater() {
        val compact = quietSimulation()
        val authored = quietSimulation()
        compact.layerSpread = 0.0
        authored.layerSpread = 1.0
        compact.update(0.0)
        authored.update(0.0)

        val center = FableSolSpec.N_POINTS / 2
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            assertEquals(96.0, compact.heights[layer][center], 1e-9)
        }
        assertTrue(authored.heights.last()[center] > compact.heights.last()[center] + 50.0)

        val lowEnergy = quietSimulation()
        val highEnergy = quietSimulation()
        lowEnergy.setMood(0.0, 0.8)
        highEnergy.setMood(1.0, 0.8)
        repeat(30) {
            lowEnergy.update(1.0 / 60.0)
            highEnergy.update(1.0 / 60.0)
        }
        assertEquals(lowEnergy.moodBright, highEnergy.moodBright, 0.0)
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            for (point in 0 until FableSolSpec.N_POINTS) {
                assertEquals(
                    lowEnergy.heights[layer][point],
                    highEnergy.heights[layer][point],
                    0.0
                )
            }
        }
    }

    @Test
    fun layerZeroFlowTargetUsesSharedPerceptualActuator() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        sim.flow01 = 0.70
        val target = -FableSolFlowPolicy.targetFlowDps(
            speed01 = sim.flow01,
            baseDps = params.lget("flow_base_dps", 0),
            gain = params.get("flow_gain"),
            curve = params.get("flow_curve"),
            idleRatio = params.get("idle_flow_ratio")
        )

        sim.update(FableSolSpec.PHYSICS_DT)

        val expected = target *
            (1.0 - kotlin.math.exp(-FableSolSpec.PHYSICS_DT / params.get("flow_smooth_s")))
        assertEquals(expected, sim.layers[0].flowDps, 1e-12)
    }

    private fun quietSimulation(): FableSolSimulation {
        val params = FableSolParams().also {
            it.setForTest("ambient_gain", 0.0)
            it.setForTest("wander_gain", 0.0)
            it.setForTest("hero_gain", 0.0)
        }
        return FableSolSimulation(params)
    }
}
