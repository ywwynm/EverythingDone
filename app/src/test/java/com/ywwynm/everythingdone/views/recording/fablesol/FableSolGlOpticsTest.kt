package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class FableSolGlOpticsTest {

    @Test
    fun glintEnvelopeFadesBelowVisibilityBeforeTrackRetirement() {
        val retirementBoundaryWithMaximumBreath =
            FableSolGlintEnvelopePolicy.TRACK_RETIRE_INTENSITY * 1.12

        assertEquals(
            0.0,
            FableSolGlintEnvelopePolicy.coreAlpha(
                retirementBoundaryWithMaximumBreath,
                layerAlpha = 1.0
            ),
            0.0
        )
        assertTrue(FableSolGlintEnvelopePolicy.coreAlpha(0.04, 1.0) > 1.0 / 255.0)
        assertEquals(0.9129, FableSolGlintEnvelopePolicy.coreAlpha(1.0, 1.0), 1e-12)
        assertEquals(0.0, FableSolGlintEnvelopePolicy.hdrEligibility(0.015), 0.0)
        assertEquals(1.0, FableSolGlintEnvelopePolicy.hdrEligibility(0.30), 1e-12)
        assertTrue(
            FableSolGlintEnvelopePolicy.hdrEligibility(0.15) >
                FableSolGlintEnvelopePolicy.visibility(0.15, 0.8)
        )
        var previous = 0.0
        for (step in 0..100) {
            val alpha = FableSolGlintEnvelopePolicy.coreAlpha(step / 100.0, 1.0)
            assertTrue(alpha + 1e-12 >= previous)
            previous = alpha
        }
    }

    @Test
    fun longFrameGapCannotConsumeAWholeGlintReleaseInOneFrame() {
        val step = FableSolGlintEnvelopePolicy.trackingDeltaSeconds(2.0)
        val retained = kotlin.math.exp(-step / 0.80)

        assertEquals(1.0 / 15.0, step, 1e-12)
        assertTrue(retained > 0.90)
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
        val interfaceWeights = FloatArray(FableSolSpec.N_LAYERS) { boundary ->
            if (boundary == 0) 0f else 1f
        }
        sim.sparkle01 = 1.0
        for (layer in 0..4) {
            sim.layers[layer].roughness01 = 0.45
        }

        var floatCount = 0
        repeat(90) {
            sim.update(1.0 / 60.0)
            floatCount = optics.build(
                sim,
                params,
                COLUMNS,
                water,
                start,
                end,
                HORIZON,
                sourceIndex,
                sourceFraction,
                interfaceWeightStart = interfaceWeights,
                interfaceWeightStop1 = interfaceWeights,
                interfaceWeightStop2 = interfaceWeights,
                interfaceWeightEnd = interfaceWeights
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
        for (offset in 0 until floatCount step FableSolGlOptics.COMPONENTS_PER_VERTEX) {
            for (component in 0 until FableSolGlOptics.COMPONENTS_PER_VERTEX) {
                assertTrue(optics.vertices[offset + component].isFinite())
            }
            assertTrue(optics.vertices[offset + 7] in 0f..1f)
            val opticalMode = optics.vertices[offset + 8]
            val hdrEligibility = optics.vertices[offset + 12]
            assertTrue(hdrEligibility in 0f..1f)
            // D160：表面亮带（mode 4）已整项移除；2026-07-18：流光（2）/轻纱（6）
            // 随参数删除，不应再有这些模式的顶点；波背自阴影（9）经 D169 恢复。
            assertTrue(opticalMode != 2f)
            assertTrue(opticalMode != 4f)
            assertTrue(opticalMode != 6f)
            if (opticalMode == 7f || opticalMode == 10f) {
                assertEquals(0f, hdrEligibility, 0f)
            }
        }
    }

    @Test
    fun capacityGainActuallyBirthsGlintsOnALiveSurface() {
        // 2026-07-18 恢复闪点出生场的守护：默认 0 无出生；拉到 1 后真实
        // 动态浪面上能出生并生成 mode 3 几何（出生场曾因镜面项删除而死）。
        val params = FableSolParams()
        params.setForTest("glint_capacity_gain", 1.0)
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }
        sim.sparkle01 = 1.0
        for (layer in 0..4) sim.layers[layer].roughness01 = 0.35

        var floatCount = 0
        repeat(120) {
            sim.update(1.0 / 60.0)
            floatCount = optics.build(sim, params, COLUMNS, water, start, end, HORIZON)
        }

        assertTrue(optics.glitterOccupiedLayerCountForTest() >= 1)
        assertEquals(0, optics.glintTrackCountForTest(8))
        var glintVertices = 0
        for (offset in 0 until floatCount step FableSolGlOptics.COMPONENTS_PER_VERTEX) {
            if (optics.vertices[offset + 8] == 3f) glintVertices++
        }
        assertTrue(glintVertices > 0)

        // 默认 0 时不出生（对照）。
        val paramsOff = FableSolParams()
        val simOff = FableSolSimulation(paramsOff)
        val opticsOff = FableSolGlOptics(DENSITY)
        simOff.sparkle01 = 1.0
        for (layer in 0..4) simOff.layers[layer].roughness01 = 0.35
        repeat(60) {
            simOff.update(1.0 / 60.0)
            opticsOff.build(simOff, paramsOff, COLUMNS, water, start, end, HORIZON)
        }
        assertEquals(0, opticsOff.glitterOccupiedLayerCountForTest())
    }

    @Test
    fun backShadeIsTheOnlyDefaultGeometryAndRespectsLayerScopes() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }

        sim.update(1.0 / 60.0)
        val floatCount = optics.build(sim, params, COLUMNS, water, start, end, HORIZON)

        // 默认参数下唯一的几何光学是波背自阴影（D169 恢复，默认 0.80）；
        // 水体透光默认 0、闪点默认 0、界面肩未接线。
        assertTrue(floatCount > 0)
        assertTrue((0..8).all { optics.glintVertexCountForTest[it] == 0 })
        assertTrue((0..6).sumOf { optics.backShadeVertexCountForTest[it] } > 0)
        assertTrue((7..8).all { optics.backShadeVertexCountForTest[it] == 0 })
        for (offset in 0 until floatCount step FableSolGlOptics.COMPONENTS_PER_VERTEX) {
            assertEquals(9f, optics.vertices[offset + 8], 0f)
        }

        params.set("back_shade_gain", 0.0)
        val opticsOff = FableSolGlOptics(DENSITY)
        assertEquals(0, opticsOff.build(sim, params, COLUMNS, water, start, end, HORIZON))
    }

    @Test
    fun zeroInterfaceWeightsSuppressAllShoulderGeometry() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(12, 38, 132) }
        val stop1 = Array(FableSolSpec.N_LAYERS) { intArrayOf(210, 58, 48) }
        val stop2 = Array(FableSolSpec.N_LAYERS) { intArrayOf(236, 198, 46) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(36, 210, 224) }
        val origin = FloatArray(FableSolSpec.N_LAYERS * 2)
        val direction = FloatArray(FableSolSpec.N_LAYERS * 2)
        val denominator = FloatArray(FableSolSpec.N_LAYERS)
        val weights = FloatArray(FableSolSpec.N_LAYERS)
        val x0 = water[0]
        val x1 = water[(COLUMNS - 1) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX]
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            origin[layer * 2] = x0
            direction[layer * 2] = x1 - x0
            denominator[layer] = (x1 - x0) * (x1 - x0)
        }

        sim.update(1.0 / 60.0)
        optics.build(
            sim = sim,
            params = params,
            columns = COLUMNS,
            waterVertices = water,
            layerStart = start,
            layerEnd = end,
            environmentHorizon = HORIZON,
            layerStop1 = stop1,
            layerStop2 = stop2,
            gradientOrigin = origin,
            gradientDirection = direction,
            gradientDenominator = denominator,
            interfaceWeightStart = weights,
            interfaceWeightStop1 = weights,
            interfaceWeightStop2 = weights,
            interfaceWeightEnd = weights
        )

        assertTrue((0..8).all { optics.interfaceShoulderVertexCountForTest[it] == 0 })
    }

    @Test
    fun backShadeCarriesTheCurrentFourStopThingGradientPerVertex() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(220, 62, 48) }
        val stop1 = Array(FableSolSpec.N_LAYERS) { intArrayOf(186, 104, 62) }
        val stop2 = Array(FableSolSpec.N_LAYERS) { intArrayOf(80, 146, 155) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(42, 94, 224) }
        val origin = FloatArray(FableSolSpec.N_LAYERS * 2)
        val direction = FloatArray(FableSolSpec.N_LAYERS * 2)
        val denominator = FloatArray(FableSolSpec.N_LAYERS)
        val x0 = water[0]
        val x1 = water[(COLUMNS - 1) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX]
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            origin[layer * 2] = x0
            direction[layer * 2] = x1 - x0
            denominator[layer] = (x1 - x0) * (x1 - x0)
        }

        sim.update(1.0 / 60.0)
        val floatCount = optics.build(
            sim = sim,
            params = params,
            columns = COLUMNS,
            waterVertices = water,
            layerStart = start,
            layerEnd = end,
            environmentHorizon = HORIZON,
            layerStop1 = stop1,
            layerStop2 = stop2,
            gradientOrigin = origin,
            gradientDirection = direction,
            gradientDenominator = denominator
        )

        var minimumRed = 1f
        var maximumRed = 0f
        var minimumBlue = 1f
        var maximumBlue = 0f
        var shadeVertices = 0
        for (offset in 0 until floatCount step FableSolGlOptics.COMPONENTS_PER_VERTEX) {
            if (optics.vertices[offset + 8] != 9f) continue
            minimumRed = minOf(minimumRed, optics.vertices[offset + 4])
            maximumRed = maxOf(maximumRed, optics.vertices[offset + 4])
            minimumBlue = minOf(minimumBlue, optics.vertices[offset + 6])
            maximumBlue = maxOf(maximumBlue, optics.vertices[offset + 6])
            shadeVertices++
        }
        assertTrue(shadeVertices > 0)
        assertTrue(maximumRed - minimumRed > 0.20f)
        assertTrue(maximumBlue - minimumBlue > 0.20f)
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
    fun distantLayersDoNotCreateAnEnvironmentColoredFeatherPass() {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        val optics = FableSolGlOptics(DENSITY)
        val water = syntheticWater(COLUMNS)
        val start = Array(FableSolSpec.N_LAYERS) { intArrayOf(60, 112, 182) }
        val end = Array(FableSolSpec.N_LAYERS) { intArrayOf(94, 154, 211) }

        sim.update(1.0 / 60.0)
        val floatCount = optics.build(sim, params, COLUMNS, water, start, end, HORIZON)

        for (offset in 0 until floatCount step FableSolGlOptics.COMPONENTS_PER_VERTEX) {
            assertTrue(optics.vertices[offset + 8] != 5f)
        }
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
