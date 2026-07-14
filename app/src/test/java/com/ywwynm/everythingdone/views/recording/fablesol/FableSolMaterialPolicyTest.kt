package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolMaterialPolicyTest {

    @Test
    fun cleanFillKeepsLocalOpticsAndAlignedDefaults() {
        val params = FableSolParams()

        assertEquals(0.0, params.get("body_light_strength"), 0.0)
        assertEquals(0.38, params.get("thin_glow_gain"), 0.0)
        assertEquals(0.14, params.get("crest_veil_strength"), 0.0)
        assertEquals(0.10, params.get("analytic_halo_strength"), 0.0)
        assertEquals(0.0, params.get("pearl_shift_deg"), 0.0)
        assertEquals(0.0, params.get("hue_temp_deg"), 0.0)

        assertEquals(0.80, params.get("back_shade_gain"), 0.0)
        assertEquals(0.864, params.get("lighten_far"), 0.0)
        assertEquals(0.16, params.get("environment_tint"), 0.0)
    }

    @Test
    fun surfaceReflectionIsNarrowAndRequiresBothFacingAndCrest() {
        assertEquals(0.0, FableSolMaterialPolicy.surfaceBandWidthDp(0.0, 1.0, 0.0), 0.0)
        assertEquals(0.0, FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 0.0, 0.0), 0.0)
        assertEquals(0.0, FableSolMaterialPolicy.surfaceBandLocality(1.0, 0.10), 0.0)
        assertEquals(1.0, FableSolMaterialPolicy.surfaceBandLocality(1.0, 1.0), 0.0)
        assertEquals(3.0, FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 1.0, 0.0), 1e-12)
        assertEquals(1.68, FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 1.0, 7.0 / 8.0), 1e-6)
        assertEquals(0.0, FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 1.0, 1.0), 0.0)
    }

    @Test
    fun thinTransmissionStillMatchesDebug0749Geometry() {
        assertEquals(0.0, FableSolMaterialPolicy.thinGlowThicknessDp(0.0), 0.0)
        assertEquals(11.0, FableSolMaterialPolicy.thinGlowThicknessDp(1.0), 1e-12)
    }

    @Test
    fun layerFamiliesUseTheConfirmedNineLayerCurves() {
        assertFloatCurve(
            doubleArrayOf(1.0, 0.927, 0.81, 0.64, 0.48, 0.30, 0.16, 0.06, 0.0),
            FableSolMaterialPolicy.COMMON_PRESENCE
        )
        assertFloatCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.75, 0.64, 0.49, 0.32, 0.16, 0.0),
            FableSolMaterialPolicy.MACRO_LIGHT_WEIGHTS
        )
        assertFloatCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.60, 0.42, 0.27, 0.12, 0.06, 0.0),
            FableSolMaterialPolicy.MACRO_SHADOW_WEIGHTS
        )
        assertFloatCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.64, 0.49, 0.32, 0.16, 0.06, 0.0),
            FableSolMaterialPolicy.MICRO_NORMAL_WEIGHTS
        )
        assertFloatCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.64, 0.49, 0.32, 0.16, 0.06, 0.0),
            FableSolMaterialPolicy.SDR_SSS_WEIGHTS
        )
        assertEquals(
            listOf(4, 4, 3, 3, 2, 2, 1, 1, 0),
            (0..8).map(FableSolMaterialPolicy::glintCapacity)
        )
        val expectedBirthWeights =
            doubleArrayOf(4.2, 3.6, 2.4, 1.92, 0.96, 0.60, 0.36, 0.16, 0.0)
        expectedBirthWeights.forEachIndexed { layer, expected ->
            assertEquals(expected, FableSolMaterialPolicy.glintBirthWeight(layer), 1e-6)
        }
        assertEquals(
            listOf(3, 2, 2, 1, 1, 0, 0, 0, 0),
            (0..8).map(FableSolMaterialPolicy::flowStreakCapacity)
        )
        assertEquals(0.45, FableSolMaterialPolicy.flowStreakWeight(3), 1e-6)
        assertEquals(0.20, FableSolMaterialPolicy.flowStreakWeight(4), 1e-6)
        assertEquals(0.06, FableSolMaterialPolicy.surfaceBandAlphaWeight(7), 1e-6)
        assertEquals(0.12, FableSolMaterialPolicy.backShadeAlphaWeight(6), 1e-6)
        assertEquals(0.0, FableSolMaterialPolicy.backShadeAlphaWeight(7), 0.0)
        assertEquals(34.0, FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP, 0.0)

        assertDoubleCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.75, 0.72, 0.64, 0.60, 0.56, 0.0)
        ) { layer -> FableSolMaterialPolicy.surfaceBandWidthDp(1.0, 1.0, layer / 8.0) / 3.0 }
        assertDoubleCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.64, 0.49, 0.32, 0.16, 0.06, 0.0),
            FableSolMaterialPolicy::surfaceBandAlphaWeight
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 1.0, 1.0, 0.84, 0.72, 0.60, 0.42, 0.0, 0.0),
            FableSolMaterialPolicy::backShadeWidthWeight
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 0.75, 0.56, 0.42, 0.24, 0.16, 0.12, 0.0, 0.0),
            FableSolMaterialPolicy::backShadeAlphaWeight
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.75, 0.64, 0.56, 0.49, 0.42, 0.0),
            FableSolMaterialPolicy::glintLengthWeight
        )
        assertDoubleCurve(
            doubleArrayOf(2.56, 2.40, 2.24, 1.96, 1.60, 1.50, 1.36, 1.29, 0.0),
            FableSolMaterialPolicy::glintDepthLengthDp
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.75, 0.64, 0.60, 0.56, 0.49, 0.0),
            FableSolMaterialPolicy::glintCoreAlphaWeight
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.75, 0.64, 0.49, 0.36, 0.24, 0.0),
            FableSolMaterialPolicy::glintHaloAlphaWeight
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 1.0, 1.0, 0.45, 0.20, 0.0, 0.0, 0.0, 0.0),
            FableSolMaterialPolicy::flowStreakWeight
        )
    }

    @Test
    fun glintGeometryAndAlphaStayHighQualityWhileReceding() {
        assertEquals(2.56, FableSolMaterialPolicy.glintDepthLengthDp(0), 1e-6)
        assertEquals(1.29, FableSolMaterialPolicy.glintDepthLengthDp(7), 1e-6)
        assertEquals(0.42, FableSolMaterialPolicy.glintLengthWeight(7), 1e-6)
        assertEquals(0.49, FableSolMaterialPolicy.glintCoreAlphaWeight(7), 1e-6)
        assertEquals(0.24, FableSolMaterialPolicy.glintHaloAlphaWeight(7), 1e-6)
    }

    @Test
    fun analyticHaloMatchesDebug0749OuterShoulder() {
        assertEquals(1.18, FableSolMaterialPolicy.HALO_LENGTH_SCALE, 0.0)
        assertEquals(2.25, FableSolMaterialPolicy.HALO_THICKNESS_SCALE, 0.0)
        assertEquals(0.18, FableSolMaterialPolicy.HALO_ALPHA_SCALE, 0.0)
    }

    private fun assertFloatCurve(expected: DoubleArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, value ->
            assertEquals("layer=$index", value, actual[index].toDouble(), 1e-6)
        }
    }

    private fun assertDoubleCurve(expected: DoubleArray, valueAt: (Int) -> Double) {
        expected.forEachIndexed { index, value ->
            assertEquals("layer=$index", value, valueAt(index), 1e-6)
        }
    }
}
