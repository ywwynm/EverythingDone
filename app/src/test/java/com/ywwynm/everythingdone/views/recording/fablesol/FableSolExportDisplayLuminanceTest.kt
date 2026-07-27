package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolExportDisplayLuminanceTest {

    @Test
    fun peakAndStrengthProduceAdaptiveWhiteInsteadOfFixedValue() {
        val result = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = 2000f,
            panelMaxAverageNits = 600f,
            hdrStrength = 9.6f
        )

        // 2000 × 1.75 ÷ 9.6 = 364.58；为保证不突破约束，落到下一个较低的 25 尼特档。
        assertEquals(350f, result.whiteNits, 0f)
        assertEquals(3360f, result.authoredPeakNits, 1e-3f)
        assertEquals(364.583f, result.peakConstraintWhiteNits!!, 1e-3f)
        assertEquals(364.583f, result.rawConstraintWhiteNits, 1e-3f)
        assertEquals(
            "min(2000 × 1.75 ÷ 9.60, 600, 400)",
            FableSolExportDisplayLuminance.constraintFormula(result)
        )
        assertFalse(result.fallbackUsed)
    }

    @Test
    fun lowerHdrStrengthAllowsBrighterBodyButAutoTierStopsAtFourHundred() {
        val result = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = 2000f,
            panelMaxAverageNits = 600f,
            hdrStrength = 5f
        )

        // 峰值约束允许 700，平均亮度允许 600，但自动档仍封顶 400；更亮必须由用户手动选。
        assertEquals(400f, result.whiteNits, 0f)
        assertEquals(2000f, result.authoredPeakNits, 0f)
    }

    @Test
    fun maximumFrameAverageLuminanceCanBeTheTightestConstraint() {
        val result = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = 2000f,
            panelMaxAverageNits = 325f,
            hdrStrength = 9.6f
        )

        assertEquals(325f, result.whiteNits, 0f)
        assertEquals(325f, result.panelMaxAverageNits!!, 0f)
    }

    @Test
    fun automaticValueAlwaysRoundsDownToExistingSliderStep() {
        val result = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = 1700f,
            panelMaxAverageNits = 900f,
            hdrStrength = 9.6f
        )

        // 峰值约束约 309.90，不得四舍五入到会突破约束的 325。
        assertEquals(300f, result.whiteNits, 0f)
    }

    @Test
    fun minimumUiWhiteRemainsTheLastSafetyFloor() {
        val result = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = 1000f,
            panelMaxAverageNits = 800f,
            hdrStrength = 9.6f
        )

        assertEquals(FableSolExportOptions.MIN_PQ_WHITE_NITS, result.whiteNits, 0f)
    }

    @Test
    fun missingPeakStillUsesAverageAndMissingBothUsesExplicitFallback() {
        val averageOnly = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = null,
            panelMaxAverageNits = 325f,
            hdrStrength = 9.6f
        )
        val missingBoth = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = Float.NaN,
            panelMaxAverageNits = -1f,
            hdrStrength = 9.6f
        )

        assertEquals(325f, averageOnly.whiteNits, 0f)
        assertNull(averageOnly.panelPeakNits)
        assertFalse(averageOnly.fallbackUsed)
        assertEquals(
            "min(325, 400)",
            FableSolExportDisplayLuminance.constraintFormula(averageOnly)
        )
        assertEquals(FableSolExportOptions.DEFAULT_PQ_WHITE_NITS, missingBoth.whiteNits, 0f)
        assertTrue(missingBoth.fallbackUsed)
        assertEquals(
            "min(400)",
            FableSolExportDisplayLuminance.constraintFormula(missingBoth)
        )
    }

    @Test
    fun invalidStrengthAndPlaceholderAverageAreSanitized() {
        val result = FableSolExportDisplayLuminance.recommend(
            panelPeakNits = 2000f,
            panelMaxAverageNits = 100f,
            hdrStrength = Float.NaN
        )

        assertEquals(FableSolHdrPolicy.DEFAULT_STRENGTH, result.hdrStrength, 0f)
        assertNull(result.panelMaxAverageNits)
        assertEquals(350f, result.whiteNits, 0f)
    }
}
