package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolMaterialPolicyTest {

    @Test
    fun cleanFillKeepsLocalOpticsAndAlignedDefaults() {
        val params = FableSolParams()

        assertEquals(0.0, params.get("body_light_strength"), 0.0)
        assertEquals(1.29, params.get("uplift_thick_glow"), 0.0)
        assertEquals(1.6, params.get("uplift_glow_boost"), 0.0)
        assertEquals(0.0, params.get("analytic_halo_strength"), 0.0)
        assertEquals(0.0, params.get("pearl_shift_deg"), 0.0)
        assertEquals(0.0, params.get("hue_temp_deg"), 0.0)

        assertEquals(0.864, params.get("lighten_far"), 0.0)
        assertEquals(0.16, params.get("environment_tint"), 0.0)
    }

    @Test
    fun layerFamiliesUseTheConfirmedNineLayerCurves() {
        assertFloatCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.72, 0.60, 0.49, 0.36, 0.24, 0.16),
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
        // D154：厚度透光独立权重表（4~8 层上提一档，用户裁决）。
        assertFloatCurve(
            doubleArrayOf(1.0, 0.96, 0.84, 0.64, 0.56, 0.49, 0.42, 0.36, 0.27),
            FableSolMaterialPolicy.THICKNESS_GLOW_WEIGHTS
        )
        // D156：波峰银边逐层存在度（近层重、远层近无，2026-07-17 v10 用户定值）。
        assertFloatCurve(
            doubleArrayOf(1.0, 0.90, 0.72, 0.42, 0.27, 0.16, 0.10, 0.05, 0.0129),
            FableSolMaterialPolicy.CREST_RIM_WEIGHTS
        )
        // D169：波背自阴影逐层权重恢复（与 Python material_policy 一比一）。
        assertDoubleCurve(
            doubleArrayOf(1.0, 1.0, 1.0, 0.84, 0.72, 0.60, 0.42, 0.0, 0.0),
            FableSolMaterialPolicy::backShadeWidthWeight
        )
        assertDoubleCurve(
            doubleArrayOf(1.0, 0.75, 0.56, 0.42, 0.24, 0.16, 0.12, 0.0, 0.0),
            FableSolMaterialPolicy::backShadeAlphaWeight
        )
        assertEquals(
            listOf(4, 4, 3, 3, 2, 2, 1, 1, 0),
            (0..8).map(FableSolMaterialPolicy::glintCapacity)
        )
        assertEquals(34.0, FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP, 0.0)
        assertEquals(0.085, FableSolMaterialPolicy.GLINT_FIELD_FLOOR, 0.0)

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
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            FableSolMaterialPolicy::glintHaloAlphaWeight
        )
    }

    @Test
    fun discreteGlintsRemainSparseButPresentThroughLayerSeven() {
        assertEquals(
            listOf(4, 4, 3, 3, 2, 2, 1, 1),
            (0..7).map(FableSolMaterialPolicy::glintCapacity)
        )
        assertTrue((3..7).all { FableSolMaterialPolicy.glintLengthWeight(it) > 0.0 })
        assertTrue((3..7).all { FableSolMaterialPolicy.glintDepthLengthDp(it) > 0.0 })
        assertTrue((3..7).all { FableSolMaterialPolicy.glintCoreAlphaWeight(it) > 0.0 })
        assertEquals(0, FableSolMaterialPolicy.glintCapacity(8))
        assertEquals(2.56, FableSolMaterialPolicy.glintDepthLengthDp(0), 1e-6)
        assertEquals(1.29, FableSolMaterialPolicy.glintDepthLengthDp(7), 1e-6)
        assertTrue((0..8).all { FableSolMaterialPolicy.glintHaloAlphaWeight(it) == 0.0 })
    }

    @Test
    fun analyticHaloEnergyIsDisabled() {
        assertEquals(1.18, FableSolMaterialPolicy.HALO_LENGTH_SCALE, 0.0)
        assertEquals(2.25, FableSolMaterialPolicy.HALO_THICKNESS_SCALE, 0.0)
        assertEquals(0.0, FableSolMaterialPolicy.HALO_ALPHA_SCALE, 0.0)
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
