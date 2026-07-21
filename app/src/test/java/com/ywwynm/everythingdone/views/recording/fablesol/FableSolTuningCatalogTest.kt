package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolTuningCatalogTest {

    @Test
    fun mainWaveAndBeatParametersFollowRuntimeRoles() {
        val hero = FableSolTuning.GROUPS.single {
            it.titleRes == R.string.fablesol_group_hero
        }
        val ambientFlow = FableSolTuning.GROUPS.single {
            it.titleRes == R.string.fablesol_group_ambient_flow
        }
        val soundAnalysis = FableSolTuning.GROUPS.single {
            it.titleRes == R.string.fablesol_group_sound_analysis_sensitivity
        }
        val heroKeys = hero.specs.map { it.key }.toSet()
        val allKeys = FableSolTuning.GROUPS.flatMap { it.specs }.map { it.key }.toSet()

        assertEquals(
            setOf(
                "hero_gain",
                "hero_len_dp",
                "hero_attack_s",
                "hero_release_s",
                "hero_breath"
            ),
            heroKeys
        )
        assertTrue(ambientFlow.specs.any { it.key == "beat_gain" })
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
}
