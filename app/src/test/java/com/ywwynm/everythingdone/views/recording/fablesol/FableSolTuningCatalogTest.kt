package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolTuningCatalogTest {

    @Test
    fun waveShapeGroupFollowsSurfaceAndOwnsGeometryControls() {
        val surfaceIndex = FableSolTuning.GROUPS.indexOfFirst {
            it.titleRes == R.string.fablesol_group_surface
        }
        val waveShapeIndex = FableSolTuning.GROUPS.indexOfFirst { group ->
            group.specs.any { it.key == "ambient_shape_stability" }
        }
        val waveShape = FableSolTuning.GROUPS[waveShapeIndex]
        val ambientFlow = FableSolTuning.GROUPS.single {
            it.titleRes == R.string.fablesol_group_ambient_flow
        }
        val soundAnalysis = FableSolTuning.GROUPS.single {
            it.titleRes == R.string.fablesol_group_sound_analysis_sensitivity
        }
        val allSpecs = FableSolTuning.GROUPS.flatMap { it.specs }
        val allKeys = allSpecs.map { it.key }.toSet()

        assertEquals(surfaceIndex + 1, waveShapeIndex)
        assertEquals(R.string.fablesol_group_wave_shape, waveShape.titleRes)
        assertEquals(
            setOf(
                "hero_gain",
                "hero_len_dp",
                "hero_attack_s",
                "hero_release_s",
                "hero_breath",
                "ambient_gain",
                "ambient_breath",
                "ambient_shape_stability",
                "surface_heading_deg",
                "surface_spread_deg",
                "surface_spectrum_gain",
                "surface_spectrum_audio_response",
                "surface_shape_stability",
                "surface_decay_dp",
                "wall_soft"
            ),
            waveShape.specs.map { it.key }.toSet()
        )
        assertTrue(ambientFlow.specs.any { it.key == "beat_gain" })
        assertFalse(ambientFlow.specs.any { it.key in waveShape.specs.map { spec -> spec.key } })
        for (key in waveShape.specs.map { it.key }) {
            assertEquals(1, allSpecs.count { it.key == key })
        }
        assertEquals(
            setOf(
                "agc_window_s",
                "silence_gate_db",
                "expander_amount",
                "relative_loudness_mix"
            ),
            soundAnalysis.specs.map { it.key }.toSet()
        )
        assertTrue(soundAnalysis.specs.all {
            it.target == FableSolTuning.Target.AUDIO_FRONT_END
        })
        assertFalse("hero_punch" in allKeys)
        assertFalse("hero_punch_decay_s" in allKeys)
    }

    @Test
    fun selectedDefaultsMatchPythonPanel() {
        val params = FableSolParams()

        assertEquals(36.0, params.get("surface_view_elev_deg"), 0.0)
        assertEquals(0.84, params.get("back_shade_gain"), 0.0)
        assertEquals(0.84, params.get("hero_attack_s"), 0.0)
        assertEquals(0.42, params.get("ambient_gain"), 0.0)
        assertEquals(0.54, params.get("swell_presmooth_s"), 0.0)
        assertEquals(0.36, params.get("swell_attack_s"), 0.0)
        assertEquals(0.21, params.get("relative_loudness_mix"), 0.0)
        assertEquals(0.27, params.get("ambient_breath"), 0.0)
        assertEquals(0.0, params.get("ambient_shape_stability"), 0.0)
        assertEquals(0.96, params.get("surface_spectrum_gain"), 0.0)
        assertEquals(0.25, params.get("surface_spectrum_audio_response"), 0.0)
        assertEquals(0.0, params.get("surface_shape_stability"), 0.0)
    }
}
