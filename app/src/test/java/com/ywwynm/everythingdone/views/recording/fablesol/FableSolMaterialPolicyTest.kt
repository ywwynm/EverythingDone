package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolMaterialPolicyTest {

    @Test
    fun debug0749OpticsKeepLaterBodyLightAndColorDepthControlsStable() {
        val params = FableSolParams()

        assertEquals(0.36, params.get("body_light_strength"), 0.0)
        assertEquals(0.38, params.get("thin_glow_gain"), 0.0)
        assertEquals(0.14, params.get("crest_veil_strength"), 0.0)
        assertEquals(0.10, params.get("analytic_halo_strength"), 0.0)
        assertEquals(0.0, params.get("pearl_shift_deg"), 0.0)
        assertEquals(0.0, params.get("hue_temp_deg"), 0.0)

        assertEquals(0.80, params.get("back_shade_gain"), 0.0)
        assertEquals(0.60, params.get("lighten_far"), 0.0)
        assertEquals(0.16, params.get("environment_tint"), 0.0)
    }

    @Test
    fun surfaceAndTransmissionMatchDebug0749Geometry() {
        assertEquals(1.2, FableSolMaterialPolicy.surfaceBandWidthDp(0.0, 0.0, 0.0), 1e-12)
        assertEquals(9.4, FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 1.0, 0.0), 1e-12)
        assertTrue(FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 1.0, 1.0) < 9.4)
        assertEquals(0.0, FableSolMaterialPolicy.thinGlowThicknessDp(0.0), 0.0)
        assertEquals(11.0, FableSolMaterialPolicy.thinGlowThicknessDp(1.0), 1e-12)
    }

    @Test
    fun moreNarrowGlintsAreAllowedOnlyAcrossNearAndMiddleLayers() {
        assertEquals(4, FableSolMaterialPolicy.glintCapacity(0))
        assertEquals(4, FableSolMaterialPolicy.glintCapacity(1))
        assertEquals(3, FableSolMaterialPolicy.glintCapacity(2))
        assertEquals(3, FableSolMaterialPolicy.glintCapacity(5))
        assertEquals(0, FableSolMaterialPolicy.glintCapacity(6))
        assertEquals(34.0, FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP, 0.0)
    }

    @Test
    fun analyticHaloMatchesDebug0749OuterShoulder() {
        assertEquals(1.18, FableSolMaterialPolicy.HALO_LENGTH_SCALE, 0.0)
        assertEquals(2.25, FableSolMaterialPolicy.HALO_THICKNESS_SCALE, 0.0)
        assertEquals(0.18, FableSolMaterialPolicy.HALO_ALPHA_SCALE, 0.0)
    }
}
