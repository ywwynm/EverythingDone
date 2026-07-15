package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolPinkBreathPolicyTest {

    @Test
    fun pinkBreathIsBoundedDeterministicAndSlow() {
        val first = FableSolPinkBreathPolicy.value01(12.5, 23.7)
        assertEquals(first, FableSolPinkBreathPolicy.value01(12.5, 23.7), 0.0)
        assertTrue(first in 0.0..1.0)
        val nextFrame = FableSolPinkBreathPolicy.value01(12.5 + 1.0 / 60.0, 23.7)
        assertTrue(kotlin.math.abs(nextFrame - first) < 0.02)
    }

    @Test
    fun zeroStrengthRestoresNeutralWaveCadenceAndBirthRates() {
        assertEquals(1.0, FableSolPinkBreathPolicy.waveAmplitudeGain(7.0, 0.8, 0.0), 0.0)
        assertEquals(1.0, FableSolPinkBreathPolicy.packetCadenceRate(7.0, 0.8, 0.0), 0.0)
        assertEquals(1.0, FableSolPinkBreathPolicy.glintBirthRate(7.0, 0.8, 0.0), 0.0)
    }

    @Test
    fun stageDefaultsKeepEveryNewMaterialDetailIndependentlySwitchable() {
        val params = FableSolParams()
        assertEquals(1.0, params.get("global_pink_breath_strength"), 0.0)
        assertEquals(0.36, params.get("micro_normal_strength"), 0.0)
        assertEquals(0.16, params.get("sun_sss_strength"), 0.0)
        assertEquals(6.0, params.get("sun_sss_falloff"), 0.0)
        assertEquals(0.0, params.get("analytic_halo_strength"), 0.0)
    }
}
