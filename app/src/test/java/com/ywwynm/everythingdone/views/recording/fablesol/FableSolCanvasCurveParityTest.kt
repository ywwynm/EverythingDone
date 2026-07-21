package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolCanvasCurveParityTest {

    @Test
    fun projectedRowRepairUsesOneScaleAndNeverCreatesALocalPlateau() {
        val sourceUDp = doubleArrayOf(0.0, 4.0, 8.0, 12.0, 16.0, 20.0, 24.0)
        val density = 2.0
        val perspective = 0.8
        val count = 6
        val baseline = DoubleArray(sourceUDp.size) {
            sourceUDp[it] * density * perspective
        }
        val offsets = doubleArrayOf(0.0, 1.0, 2.0, -10.0, 3.0, 2.0, 99.0)
        val projected = DoubleArray(sourceUDp.size) { baseline[it] + offsets[it] }
        val untouchedTail = projected.last()

        val scale = FableSolCanvasProjection.repairMonotoneInPlace(
            projectedX = projected,
            sourceUDp = sourceUDp,
            count = count,
            baselinePerspective = perspective,
            density = density,
            minimumSpacingRatio = 0.12
        )

        assertTrue("scale=$scale", scale >= 0.0 && scale < 1.0)
        for (index in 0 until count) {
            assertEquals(offsets[index] * scale, projected[index] - baseline[index], 1e-12)
        }
        for (index in 0 until count - 1) {
            val baselineStep = baseline[index + 1] - baseline[index]
            val repairedStep = projected[index + 1] - projected[index]
            assertTrue(
                "segment=$index step=$repairedStep",
                repairedStep >= 0.12 * baselineStep - 1e-12
            )
            assertTrue("segment=$index became a plateau", repairedStep > 0.0)
        }
        assertEquals(untouchedTail, projected.last(), 0.0)
    }

    @Test
    fun canvasContinuousGeometryUsesAnalyticHermiteWithoutASecondCubicFit() {
        val source = projectSource("WaveVisualizerFableSol.kt")

        assertTrue(source.contains(
            "private val surfaceHermiteWeights = FableSolHermiteWeightTable"
        ))
        assertTrue(source.contains("sample.orbitXSlope[r][xIndex] * surfaceHermiteWeights.h10[j]"))
        assertTrue(source.contains("sample.orbitZSlope[r][xIndex] * surfaceHermiteWeights.h10[j]"))
        assertTrue(source.contains("sample.slopeX[r][xIndex] * surfaceHermiteWeights.h10[j]"))
        assertTrue(source.contains("sample.orbitXSlope[r][xIndex] * surfaceHermiteWeights.dh10[j]"))
        assertTrue(source.contains("val layerPosition = z01 * (FableSolSpec.N_LAYERS - 1)"))
        assertTrue(source.contains("FableSolCanvasProjection.repairMonotoneInPlace("))
        assertTrue(source.contains("appendPolyline(fillPath, xsPx, ysPx, cnt, true)"))
        assertFalse(source.contains("buildSmooth("))
        assertFalse(source.contains("val layerPosition = z01.coerceIn"))
        assertFalse(source.contains("val z01 = (zEff / max(sample.depthDp, 1e-6)).coerceIn"))
    }

    @Test
    fun canvasAndGlesShareTheProjectedRowSpacingContract() {
        val canvas = projectSource("WaveVisualizerFableSol.kt")
        val gl = projectSource("FableSolGlRenderer.kt")

        assertTrue(canvas.contains("PROJECTED_MINIMUM_SPACING_RATIO = 0.12"))
        assertTrue(gl.contains("PROJECTED_MINIMUM_SPACING_RATIO = 0.12"))
        assertTrue(canvas.contains("FableSolCubicResampler.monotoneBlendBound("))
        assertTrue(gl.contains("FableSolCubicResampler.monotoneBlendBound("))
        assertTrue(canvas.contains("sample.z01[r]"))
        assertTrue(gl.contains("sample.z01[row]"))
    }

    private fun projectSource(fileName: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(
                directory,
                "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/$fileName"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 $fileName")
    }
}
