package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class FableSolGlOpticsTest {

    @Test
    fun analyticSpecularAaFeedsUnresolvedVarianceIntoTheGlintLobe() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS, 7.5)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }
        for (layer in 0..4) {
            sim.layers[layer].capillary01 = 1.0
            sim.layers[layer].roughness01 = 0.5
        }

        optics.build(sim, params, COLUMNS, water, start, end, HORIZON)

        assertTrue((0..4).any { optics.glintUnresolvedVarianceForTest[it] > 0.0 })
        assertTrue((0..4).any {
            optics.glintUnresolvedCurvatureVarianceForTest[it] > 0.0
        })
        for (layer in 0..4) {
            assertTrue(optics.glintEffectiveSigmaForTest[layer] >=
                optics.glintBaseSigmaForTest[layer])
            assertTrue(optics.glintPeakNormalizationForTest[layer] in 0.0..1.0)
        }
    }

    @Test
    fun generatedOpticalMeshesStayInsideFixedCapacityAndLayerRanges() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(48, 102, 176) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(88, 146, 205) }
        val sourceIndex = IntArray(COLUMNS) { it.coerceAtMost(FableSolSpec.N_POINTS - 2) }
        val sourceFraction = DoubleArray(COLUMNS)
        sim.sparkle01 = 1.0
        for (layer in 0..4) {
            sim.layers[layer].capillary01 = 1.0
            sim.layers[layer].roughness01 = 0.45
        }

        var floatCount = 0
        repeat(90) {
            sim.update(1.0 / 60.0)
            for (layer in 0..2) java.util.Arrays.fill(sim.layers[layer].crestVeil, 0.8)
            floatCount = optics.build(
                sim,
                params,
                COLUMNS,
                water,
                start,
                end,
                HORIZON,
                sourceIndex,
                sourceFraction
            )
        }

        assertTrue(floatCount > 0)
        assertTrue(floatCount <= optics.vertices.size)
        assertEquals(0, floatCount % FableSolGlOptics.COMPONENTS_PER_VERTEX)
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            assertEquals(0, optics.layerVertexCount[layer] % FableSolGlOptics.VERTICES_PER_ELLIPSE)
            assertTrue(optics.layerFirstVertex[layer] + optics.layerVertexCount[layer] <=
                floatCount / FableSolGlOptics.COMPONENTS_PER_VERTEX)
        }
        val fullContourVertices = (COLUMNS - 1) * FableSolGlOptics.VERTICES_PER_QUAD
        assertTrue((0..8).all {
            optics.bodyLightVertexCountForTest[it] == fullContourVertices
        })
        assertTrue((0..2).all { optics.crestVeilVertexCountForTest[it] == fullContourVertices })
        var hasHdrEligibleSurfaceSegment = false
        for (offset in 0 until floatCount step FableSolGlOptics.COMPONENTS_PER_VERTEX) {
            for (component in 0 until FableSolGlOptics.COMPONENTS_PER_VERTEX) {
                assertTrue(optics.vertices[offset + component].isFinite())
            }
            assertTrue(optics.vertices[offset + 7] in 0f..1f)
            val opticalMode = optics.vertices[offset + 8]
            val hdrEligibility = optics.vertices[offset + 12]
            assertTrue(hdrEligibility in 0f..1f)
            if (opticalMode == 4f && hdrEligibility > 0f) {
                hasHdrEligibleSurfaceSegment = true
            }
            if (opticalMode == 2f || opticalMode == 7f || opticalMode == 9f) {
                assertEquals(0f, hdrEligibility, 0f)
            }
        }
        assertTrue(hasHdrEligibleSurfaceSegment)
    }

    @Test
    fun glintsAndStreaksKeepBoundedCrossFrameIdentity() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }
        sim.sparkle01 = 1.0
        for (layer in 0..4) {
            sim.layers[layer].capillary01 = 1.0
            sim.layers[layer].roughness01 = 0.35
        }

        repeat(120) {
            sim.update(1.0 / 60.0)
            optics.build(sim, params, COLUMNS, water, start, end, HORIZON)
        }

        assertTrue((0..4).sumOf { optics.glintTrackCountForTest(it) } > 0)
        assertTrue((0..2).sumOf { optics.streakTrackCountForTest(it) } > 0)
        assertTrue((0..5).all {
            optics.glintTrackCountForTest(it) <= FableSolMaterialPolicy.glintCapacity(it)
        })
        assertTrue((0..2).all { optics.streakTrackCountForTest(it) <= if (it == 0) 3 else 2 })
        assertTrue((0..4).all { optics.glintPinkGainForTest[it] in 0.904..1.096 })
        assertTrue((0..2).all { optics.streakPinkGainForTest[it] in 0.88..1.12 })
        assertTrue((0..4).any { optics.glintFresnelContributionMaxForTest[it] > 0.0 })
        assertTrue((0..4).all { optics.glintBirthRateForTest[it] in 0.72..1.28 })
        assertTrue((0..4).sumOf { optics.analyticHaloVertexCountForTest[it] } > 0)

        val layer = (0..4).first { optics.glintVertexCountForTest[it] > 0 }
        val first = optics.glintFirstVertexForTest[layer]
        val glintEnd = first + optics.glintVertexCountForTest[layer]
        val coreVertex = (first until glintEnd).first { vertex ->
            optics.vertices[vertex * FableSolGlOptics.COMPONENTS_PER_VERTEX + 8] == 3f
        }
        val offset = coreVertex * FableSolGlOptics.COMPONENTS_PER_VERTEX
        val coreToEdgeDistance = kotlin.math.abs(optics.vertices[offset + 4] - optics.vertices[offset + 9]) +
            kotlin.math.abs(optics.vertices[offset + 5] - optics.vertices[offset + 10]) +
            kotlin.math.abs(optics.vertices[offset + 6] - optics.vertices[offset + 11])
        assertTrue(coreToEdgeDistance > 1e-4f)
    }

    @Test
    fun movingStreakGeometryStaysOnTheWaterSideOfItsTangent() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }

        repeat(120) {
            sim.update(1.0 / 60.0)
            optics.build(sim, params, COLUMNS, water, start, end, HORIZON)
        }

        val streakVertices = optics.streakVertexCountForTest[0]
        assertTrue("streakVertices=$streakVertices", streakVertices > 0)
        val streakFirstVertex = optics.streakFirstVertexForTest[0]
        val streakEndVertex = streakFirstVertex + streakVertices
        for (vertex in streakFirstVertex until streakEndVertex) {
            val vertexOffset = vertex * FableSolGlOptics.COMPONENTS_PER_VERTEX
            val normalCoordinate = optics.vertices[
                vertexOffset + 3
            ]
            assertTrue("vertex=$vertex normal=$normalCoordinate", normalCoordinate >= 0f)
            val vertexX = optics.vertices[vertexOffset].toDouble()
            val vertexY = optics.vertices[vertexOffset + 1].toDouble()
            val contourY = contourYAt(water, 0, COLUMNS, vertexX)
            assertTrue(
                "vertex=$vertex point=($vertexX,$vertexY) contour=$contourY",
                vertexY + 1e-3 >= contourY
            )
        }
    }

    @Test
    fun mirrorGlintGeometryNeverCrossesOutsideItsWaterContour() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }
        sim.sparkle01 = 1.0
        for (layer in 0..4) {
            sim.layers[layer].capillary01 = 1.0
            sim.layers[layer].roughness01 = 0.35
        }

        repeat(120) {
            sim.update(1.0 / 60.0)
            optics.build(sim, params, COLUMNS, water, start, end, HORIZON)
        }

        var checked = 0
        for (layer in 0..4) {
            val first = optics.glintFirstVertexForTest[layer]
            val endVertex = first + optics.glintVertexCountForTest[layer]
            val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
            for (vertex in first until endVertex) {
                val offset = vertex * FableSolGlOptics.COMPONENTS_PER_VERTEX
                val vertexX = optics.vertices[offset].toDouble()
                val vertexY = optics.vertices[offset + 1].toDouble()
                val normalCoordinate = optics.vertices[offset + 3]
                val contourY = contourYAt(water, row, COLUMNS, vertexX)
                assertTrue("layer=$layer vertex=$vertex normal=$normalCoordinate", normalCoordinate >= 0f)
                assertTrue(
                    "layer=$layer vertex=$vertex point=($vertexX,$vertexY) contour=$contourY",
                    vertexY + 1e-3 >= contourY
                )
                checked++
            }
        }
        assertTrue("checked=$checked", checked > 0)
    }

    @Test
    fun contourEffectsRespectTheirLayerScopes() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }

        sim.update(1.0 / 60.0)
        optics.build(sim, params, COLUMNS, water, start, end, HORIZON)

        assertTrue((0..6).all { optics.surfaceBandVertexCountForTest[it] > 0 })
        assertTrue((7..8).all { optics.surfaceBandVertexCountForTest[it] == 0 })
        assertTrue((0..4).sumOf { optics.thinGlowVertexCountForTest[it] } > 0)
        assertTrue((5..8).all { optics.thinGlowVertexCountForTest[it] == 0 })
        assertTrue((0..5).sumOf { optics.backShadeVertexCountForTest[it] } > 0)
        assertTrue((6..8).all { optics.backShadeVertexCountForTest[it] == 0 })
        assertTrue((0..6).all { optics.featherVertexCountForTest[it] == 0 })
        assertTrue((7..8).all { optics.featherVertexCountForTest[it] > 0 })
    }

    @Test
    fun restoredBodyLightCoversEveryLayerWhileCrestVeilKeepsItsNearLayerScope() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }
        val sourceIndex = IntArray(COLUMNS) { it.coerceAtMost(FableSolSpec.N_POINTS - 2) }
        val sourceFraction = DoubleArray(COLUMNS)
        for (layer in 0..2) java.util.Arrays.fill(sim.layers[layer].crestVeil, 0.8)

        sim.update(1.0 / 60.0)
        optics.build(
            sim,
            params,
            COLUMNS,
            water,
            start,
            end,
            HORIZON,
            sourceIndex,
            sourceFraction
        )

        val fullContourVertices = (COLUMNS - 1) * FableSolGlOptics.VERTICES_PER_QUAD
        assertTrue((0..8).all {
            optics.bodyLightVertexCountForTest[it] == fullContourVertices
        })
        assertTrue((0..2).all { optics.crestVeilVertexCountForTest[it] == fullContourVertices })
        assertTrue((3..8).all { optics.crestVeilVertexCountForTest[it] == 0 })
    }

    @Test
    fun contourBandProfileMatchesCanvasPeakAndIntegratedLight() {
        val samples = 10_000
        var integral = 0.0
        for (index in 0 until samples) {
            val relativeDepth = (index + 0.5) / samples
            integral += FableSolGlOptics.contourCoverageForTest(relativeDepth) / samples
        }

        assertEquals(0.66, FableSolGlOptics.contourCoverageForTest(0.5), 1e-12)
        assertEquals(0.66 * 2.0 / Math.PI, integral, 1e-4)
    }

    @Test
    fun glesSurfaceBandUsesCanvasWidthAndAlphaWithoutCompensation() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }

        sim.update(1.0 / 60.0)
        optics.build(sim, params, COLUMNS, water, start, end, HORIZON)

        for (layer in 0..6) {
            assertEquals(
                optics.canvasSurfacePeakAlphaForTest[layer],
                optics.actualSurfacePeakAlphaForTest[layer],
                1e-6f
            )
            assertEquals(
                optics.canvasSurfaceMaxThicknessForTest[layer],
                optics.actualSurfaceMaxThicknessForTest[layer],
                1e-9
            )
        }
    }

    @Test
    fun distantFeatherUsesCanvasAlphaWithoutCompensation() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }

        sim.update(1.0 / 60.0)
        optics.build(sim, params, COLUMNS, water, start, end, HORIZON)

        for (layer in 7..8) {
            assertTrue(optics.featherVertexCountForTest[layer] > 0)
            val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
            val amount = sim.calm01 * ((depth - 0.55) / 0.45).coerceIn(0.0, 1.0)
            val expectedPeakAlpha = 105.0 / 255.0 * amount * 0.66
            val alphaOffset = optics.featherFirstVertexForTest[layer] *
                FableSolGlOptics.COMPONENTS_PER_VERTEX + 7
            assertEquals(expectedPeakAlpha, optics.vertices[alphaOffset].toDouble(), 1e-6)
        }
    }

    private fun contourYAt(water: FloatArray, row: Int, columns: Int, queryX: Double): Double {
        fun value(column: Int, component: Int): Double = water[
            (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX + component
        ].toDouble()
        if (queryX <= value(0, 0)) return value(0, 1)
        if (queryX >= value(columns - 1, 0)) return value(columns - 1, 1)
        var low = 0
        var high = columns - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (value(middle, 0) <= queryX) low = middle else high = middle
        }
        val x0 = value(low, 0)
        val x1 = value(high, 0)
        val fraction = (queryX - x0) / (x1 - x0)
        return value(low, 1) + (value(high, 1) - value(low, 1)) * fraction
    }

    private fun syntheticWater(columns: Int, spacingPx: Double = 3.0): FloatArray {
        val values = FloatArray(
            FableSolContinuousSurface.Z_ROWS * columns * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
        )
        for (row in 0 until FableSolContinuousSurface.Z_ROWS) {
            for (column in 0 until columns) {
                val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
                values[offset] = ((column - (columns - 1) / 2.0) * spacingPx).toFloat()
                values[offset + 1] = (row * 4.0 +
                    16.0 * sin(column * 0.05 + row * 0.11) +
                    2.0 * sin(column * 0.13 + row * 0.07)).toFloat()
                values[offset + 2] = 0f
                values[offset + 3] = 0f
            }
        }
        return values
    }

    private companion object {
        const val COLUMNS = 120
        const val DENSITY = 2.5
        val HORIZON = intArrayOf(218, 205, 226)
    }
}
