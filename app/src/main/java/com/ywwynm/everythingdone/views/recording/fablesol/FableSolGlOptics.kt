package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Stage 1 光学实体的 CPU 跟踪与小网格生成器。
 *
 * 闪点、珍珠和流光保留跨帧身份，只在受光峰之间平滑跟随；猫爪直接消费 Simulation 中已经
 * 持久化的阵风。输出为少量带局部 UV 的椭圆三角形，由 GLES 做软边光栅化。
 */
internal class FableSolGlOptics(private val density: Double) {

    private class Track(var u: Double, var intensity: Double, var size: Double, val seed: Double)
    private class Streak(var u: Double, var age: Double, val life: Double,
                         val length: Double, val seed: Double)

    val vertices = FloatArray(MAX_VERTICES * COMPONENTS_PER_VERTEX)
    val layerFirstVertex = IntArray(FableSolSpec.N_LAYERS)
    val layerVertexCount = IntArray(FableSolSpec.N_LAYERS)
    internal val glintFirstVertexForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintFresnelContributionMaxForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintPinkGainForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintUnresolvedVarianceForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintUnresolvedCurvatureVarianceForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintBaseSigmaForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintEffectiveSigmaForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintPeakNormalizationForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintBirthRateForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val analyticHaloVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val streakPinkGainForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val streakFirstVertexForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val streakVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val surfaceBandVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val bodyLightVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val canvasSurfacePeakAlphaForTest = FloatArray(FableSolSpec.N_LAYERS)
    internal val actualSurfacePeakAlphaForTest = FloatArray(FableSolSpec.N_LAYERS)
    internal val canvasSurfaceMaxThicknessForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val actualSurfaceMaxThicknessForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val thinGlowVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val backShadeVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val featherFirstVertexForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val featherVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val crestVeilVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)

    private val glints = Array(FableSolSpec.N_LAYERS) { ArrayList<Track>(4) }
    private val glintBirthCredit = DoubleArray(FableSolSpec.N_LAYERS) { 1.0 }
    private val streaks = Array(FableSolSpec.N_LAYERS) { ArrayList<Streak>(3) }
    private val streakSequence = IntArray(FableSolSpec.N_LAYERS)
    private val nextStreakTime = DoubleArray(FableSolSpec.N_LAYERS)

    private val x = DoubleArray(FableSolSpec.N_POINTS)
    private val y = DoubleArray(FableSolSpec.N_POINTS)
    private val gradient = DoubleArray(FableSolSpec.N_POINTS)
    private val gradient2 = DoubleArray(FableSolSpec.N_POINTS)
    private val slopeRaw = DoubleArray(FableSolSpec.N_POINTS)
    private val slope = DoubleArray(FableSolSpec.N_POINTS)
    private val curvatureRaw = DoubleArray(FableSolSpec.N_POINTS)
    private val curvature = DoubleArray(FableSolSpec.N_POINTS)
    private val uDp = DoubleArray(FableSolSpec.N_POINTS)
    private val microSlope = DoubleArray(FableSolSpec.N_POINTS)
    private val microCurvature = DoubleArray(FableSolSpec.N_POINTS)
    private val specularSlope = DoubleArray(FableSolSpec.N_POINTS)
    private val specularCurvature = DoubleArray(FableSolSpec.N_POINTS)
    private val field = DoubleArray(FableSolSpec.N_POINTS)
    private val smooth = DoubleArray(FableSolSpec.N_POINTS)
    private val hdrEligibility = DoubleArray(FableSolSpec.N_POINTS)
    private val sway = DoubleArray(FableSolSpec.N_POINTS)
    private val bandTop = DoubleArray(FableSolSpec.N_POINTS)
    private val bandThickness = DoubleArray(FableSolSpec.N_POINTS)
    private val bandUpperX = DoubleArray(FableSolSpec.N_POINTS)
    private val bandUpperY = DoubleArray(FableSolSpec.N_POINTS)
    private val bandLowerX = DoubleArray(FableSolSpec.N_POINTS)
    private val bandLowerY = DoubleArray(FableSolSpec.N_POINTS)
    private var unresolvedSpecularSlopeVariance = 0.0
    private var unresolvedSpecularCurvatureVariance = 0.0
    private val anchorU = DoubleArray(MAX_ANCHORS)
    private val anchorIntensity = DoubleArray(MAX_ANCHORS)
    private val anchorSize = DoubleArray(MAX_ANCHORS)
    private val anchorUsed = BooleanArray(MAX_ANCHORS)
    private var anchorCount = 0
    private var cursor = 0
    private var lastTrackTime = 0.0

    fun build(
        sim: FableSolSimulation,
        params: FableSolParams,
        columns: Int,
        waterVertices: FloatArray,
        layerStart: Array<IntArray>,
        layerEnd: Array<IntArray>,
        environmentHorizon: IntArray,
        sourceIndex: IntArray? = null,
        sourceFraction: DoubleArray? = null
    ): Int {
        cursor = 0
        java.util.Arrays.fill(layerVertexCount, 0)
        java.util.Arrays.fill(glintVertexCountForTest, 0)
        java.util.Arrays.fill(glintFresnelContributionMaxForTest, 0.0)
        java.util.Arrays.fill(glintPinkGainForTest, 0.0)
        java.util.Arrays.fill(glintUnresolvedVarianceForTest, 0.0)
        java.util.Arrays.fill(glintUnresolvedCurvatureVarianceForTest, 0.0)
        java.util.Arrays.fill(glintBaseSigmaForTest, 0.0)
        java.util.Arrays.fill(glintEffectiveSigmaForTest, 0.0)
        java.util.Arrays.fill(glintPeakNormalizationForTest, 0.0)
        java.util.Arrays.fill(glintBirthRateForTest, 0.0)
        java.util.Arrays.fill(analyticHaloVertexCountForTest, 0)
        java.util.Arrays.fill(streakPinkGainForTest, 0.0)
        java.util.Arrays.fill(streakVertexCountForTest, 0)
        java.util.Arrays.fill(surfaceBandVertexCountForTest, 0)
        java.util.Arrays.fill(bodyLightVertexCountForTest, 0)
        java.util.Arrays.fill(canvasSurfacePeakAlphaForTest, 0f)
        java.util.Arrays.fill(actualSurfacePeakAlphaForTest, 0f)
        java.util.Arrays.fill(canvasSurfaceMaxThicknessForTest, 0.0)
        java.util.Arrays.fill(actualSurfaceMaxThicknessForTest, 0.0)
        java.util.Arrays.fill(thinGlowVertexCountForTest, 0)
        java.util.Arrays.fill(backShadeVertexCountForTest, 0)
        java.util.Arrays.fill(featherVertexCountForTest, 0)
        java.util.Arrays.fill(crestVeilVertexCountForTest, 0)
        if (columns < 3) return 0
        val dt = max(sim.t - lastTrackTime, 0.0)

        for (layer in FableSolSpec.N_LAYERS - 1 downTo 0) {
            layerFirstVertex[layer] = cursor / COMPONENTS_PER_VERTEX
            readContour(layer, columns, waterVertices)
            prepareContour(sim, params, layer, columns)

            if (layer <= 6 && params.get("surface_strip_gain") > 1e-3) {
                val startVertex = cursor
                buildSurfaceBand(
                    sim, params, layer, columns, layerStart[layer], layerEnd[layer],
                    environmentHorizon
                )
                surfaceBandVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }
            if (layer <= 2) {
                streakFirstVertexForTest[layer] = cursor / COMPONENTS_PER_VERTEX
                val streakStart = cursor
                buildStreaks(sim, params, layer, columns, dt, layerStart[layer], layerEnd[layer])
                streakVertexCountForTest[layer] =
                    (cursor - streakStart) / COMPONENTS_PER_VERTEX
            }
            if (params.get("body_light_strength") > 1e-3) {
                val startVertex = cursor
                buildBodyLight(sim, params, layer, columns, layerStart[layer], layerEnd[layer])
                bodyLightVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }
            if (layer <= 4 && params.get("thin_glow_gain") > 1e-3) {
                val startVertex = cursor
                buildThinGlow(sim, params, layer, columns, layerStart[layer], layerEnd[layer])
                thinGlowVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }
            if (layer <= 5 && params.get("back_shade_gain") > 1e-3) {
                val startVertex = cursor
                buildBackShade(params, layer, columns, layerStart[layer])
                backShadeVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }

            if (FableSolMaterialPolicy.glintCapacity(layer) > 0) {
                glintFirstVertexForTest[layer] = cursor / COMPONENTS_PER_VERTEX
                val glintStart = cursor
                buildGlints(sim, params, layer, columns, dt, layerStart[layer], layerEnd[layer])
                glintVertexCountForTest[layer] = (cursor - glintStart) / COMPONENTS_PER_VERTEX
            }
            if (layer <= 2 && sourceIndex != null && sourceFraction != null &&
                params.get("crest_veil_strength") > 1e-3
            ) {
                val startVertex = cursor
                buildCrestVeil(
                    sim,
                    params,
                    layer,
                    columns,
                    layerStart[layer],
                    layerEnd[layer],
                    sourceIndex,
                    sourceFraction
                )
                crestVeilVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }
            if (layer >= 7) {
                featherFirstVertexForTest[layer] = cursor / COMPONENTS_PER_VERTEX
                val startVertex = cursor
                buildEdgeFeather(sim, layer, columns, environmentHorizon)
                featherVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }
            layerVertexCount[layer] = cursor / COMPONENTS_PER_VERTEX - layerFirstVertex[layer]
        }
        lastTrackTime = sim.t
        return cursor
    }

    private fun readContour(layer: Int, columns: Int, waterVertices: FloatArray) {
        val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
        for (column in 0 until columns) {
            val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            x[column] = waterVertices[offset].toDouble()
            y[column] = waterVertices[offset + 1].toDouble()
            uDp[column] = x[column] / density
        }
    }

    private fun prepareContour(sim: FableSolSimulation, params: FableSolParams,
                               layer: Int, columns: Int) {
        val dx = max(abs(x[1] - x[0]), 1e-3)
        FableSolMath.gradientInto(y, columns, dx, gradient)
        for (i in 0 until columns) slopeRaw[i] = -gradient[i]
        smoothThree(slopeRaw, slope, columns)
        FableSolMath.gradientInto(gradient, columns, dx, gradient2)
        for (i in 0 until columns) curvatureRaw[i] = -gradient2[i] * density
        smoothThree(curvatureRaw, curvature, columns)
        java.util.Arrays.fill(microSlope, 0, columns, 0.0)
        java.util.Arrays.fill(microCurvature, 0, columns, 0.0)
        java.util.Arrays.fill(specularSlope, 0, columns, 0.0)
        java.util.Arrays.fill(specularCurvature, 0, columns, 0.0)
        unresolvedSpecularSlopeVariance = 0.0
        unresolvedSpecularCurvatureVariance = 0.0
        if (FableSolMaterialPolicy.glintCapacity(layer) > 0) {
            val ls = sim.layers[layer]
            unresolvedSpecularSlopeVariance = ls.optical.sampleInto(
                uDp,
                columns,
                ls.capillary01 * params.get("capillary_glint_gain"),
                ls.roughness01,
                specularSlope,
                specularCurvature,
                params.get("specular_aa_strength"),
                microSlope,
                microCurvature
            )
            unresolvedSpecularCurvatureVariance =
                ls.optical.lastUnresolvedCurvatureVariance
        }
        val swayGain = params.get("orbital_sway_dp") * density
        for (i in 0 until columns) sway[i] = (slope[i] * swayGain)
            .coerceIn(-8.0 * density, 8.0 * density)
    }

    private fun buildSurfaceBand(sim: FableSolSimulation, params: FableSolParams,
                                 layer: Int, columns: Int, start: IntArray, end: IntArray,
                                 environmentHorizon: IntArray) {
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        for (i in 0 until columns) {
            val q = ((slope[i] + 0.05) / 0.50).coerceIn(0.0, 1.0)
            val facing = q * q * (3.0 - 2.0 * q)
            val crest = (curvature[i] / -GLOW_KAPPA).coerceIn(0.0, 1.0)
            field[i] = FableSolMaterialPolicy.surfaceBandWidthDp(facing, crest, depth)
            hdrEligibility[i] = facing * (0.65 + 0.35 * crest)
        }
        smoothHann(field, smooth, columns, 4)
        var maximumThickness = 0.0
        for (i in 0 until columns) {
            bandTop[i] = y[i] + 0.2 * density
            val canvasThickness = smooth[i] * density
            maximumThickness = max(maximumThickness, canvasThickness)
            bandThickness[i] = canvasThickness * GLES_SURFACE_WIDTH_SCALE
        }
        val highlight = FableSolOpticalColorPolicy.highlight(
            FableSolColor.mix(start, end, 0.3),
            params.get("crest_lighten")
        )
        val color = FableSolColor.mixOklab(environmentHorizon, highlight, 0.42)
        val air = 1.0 - params.get("aerial_contrast") * depth
        val breath = 1.0 + 0.10 * params.get("pink_mod") *
            (2.0 * pink01(sim.t, 9.7) - 1.0)
        val alpha = ((92.0 - 34.0 * depth) / 255.0 * params.lget("alpha", layer) * air *
            breath * params.get("surface_strip_gain")).toFloat()
        canvasSurfacePeakAlphaForTest[layer] = alpha * CONTOUR_PROFILE_PEAK.toFloat()
        actualSurfacePeakAlphaForTest[layer] =
            alpha * GLES_SURFACE_ALPHA_SCALE.toFloat() * CONTOUR_PROFILE_PEAK.toFloat()
        canvasSurfaceMaxThicknessForTest[layer] = maximumThickness
        actualSurfaceMaxThicknessForTest[layer] = maximumThickness * GLES_SURFACE_WIDTH_SCALE
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            color,
            alpha * GLES_SURFACE_ALPHA_SCALE.toFloat(),
            OPTICAL_MODE_SURFACE_REFLECTION,
            hdrEligibility
        )
    }

    private fun buildBodyLight(sim: FableSolSimulation, params: FableSolParams,
                               layer: Int, columns: Int, start: IntArray, end: IntArray) {
        val strength = params.get("body_light_strength")
        val glowStrength = params.get("crest_glow_strength")
        for (i in 0 until columns) {
            field[i] = (curvature[i] / -GLOW_KAPPA).coerceIn(0.0, 1.0) * glowStrength
        }
        smoothBox(field, smooth, columns, 2)
        val sinElevation = sin(Math.toRadians(VIEW_ELEVATION_DEG))
        val depthPx = params.get("crest_glow_depth_dp") * density
        for (i in 0 until columns) {
            val opticalSlope = slope[i] + microSlope[i]
            val cosine = (sinElevation / sqrt(1.0 + opticalSlope * opticalSlope))
                .coerceIn(0.0, 1.0)
            val fresnel = WATER_F0 + (1.0 - WATER_F0) * (1.0 - cosine).pow(5)
            val volume = ((0.16 + 0.84 * smooth[i]) * (1.0 - fresnel) * strength)
                .coerceIn(0.0, 1.0)
            bandTop[i] = y[i] + 0.35 * density
            bandThickness[i] = depthPx * (0.34 + 0.66 * volume)
        }
        val highlight = highlightColor(start, end, params)
        val color = FableSolColor.mixOklab(start, highlight, 0.46)
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val air = 1.0 - params.get("aerial_contrast") * depth
        val alpha = (72.0 / 255.0 * params.lget("alpha", layer) * air * strength).toFloat()
        addContourBand(columns, bandTop, bandThickness, color, alpha, OPTICAL_MODE_TRANSMISSION)
    }

    private fun buildCrestVeil(sim: FableSolSimulation, params: FableSolParams,
                               layer: Int, columns: Int, start: IntArray, end: IntArray,
                               sourceIndex: IntArray, sourceFraction: DoubleArray) {
        val strength = params.get("crest_veil_strength")
        val values = sim.layers[layer].crestVeil
        for (i in 0 until columns) {
            val index = sourceIndex[i].coerceIn(0, values.size - 2)
            val fraction = sourceFraction[i].coerceIn(0.0, 1.0)
            field[i] = values[index] * (1.0 - fraction) + values[index + 1] * fraction
        }
        smoothHann(field, smooth, columns, 4)
        var maximum = 0.0
        val layerAlpha = params.lget("alpha", layer)
        for (i in 0 until columns) {
            smooth[i] = (smooth[i] * strength).coerceIn(0.0, 1.0)
            bandThickness[i] = smooth[i] * layerAlpha
            maximum = max(maximum, smooth[i])
        }
        if (maximum <= 1e-3) return
        val highlight = highlightColor(start, end, params)
        val color = FableSolOpticalColorPolicy.crestVeil(highlight)
        val alpha = (96.0 / 255.0 * layerAlpha * strength).toFloat()
        addVariableCenteredBand(columns, bandThickness, color, alpha)
    }

    private fun buildThinGlow(sim: FableSolSimulation, params: FableSolParams,
                              layer: Int, columns: Int, start: IntArray, end: IntArray) {
        var meanY = 0.0
        for (i in 0 until columns) meanY += y[i]
        meanY /= columns
        for (i in 0 until columns) {
            var gate = (((meanY - y[i]) / density - 4.0) / 10.0).coerceIn(0.0, 1.0)
            gate = gate * gate * (3.0 - 2.0 * gate)
            val thin = (curvature[i] / -GLOW_KAPPA).coerceIn(0.0, 1.0)
            field[i] = gate * (0.15 + 0.85 * thin)
        }
        smoothHann(field, smooth, columns, 5)
        var maximum = 0.0
        for (i in 0 until columns) {
            maximum = max(maximum, smooth[i])
            bandTop[i] = y[i] + 0.4 * density
            bandThickness[i] = FableSolMaterialPolicy.thinGlowThicknessDp(smooth[i]) * density
            hdrEligibility[i] = smooth[i]
        }
        if (maximum <= 0.03) return
        val highlight = highlightColor(start, end, params)
        val color = FableSolOpticalColorPolicy.thinTransmission(highlight)
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val air = 1.0 - params.get("aerial_contrast") * depth
        val alpha = (140.0 / 255.0 * params.lget("alpha", layer) *
            params.get("thin_glow_gain") * air).toFloat()
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            color,
            alpha,
            OPTICAL_MODE_TRANSMISSION,
            hdrEligibility
        )
    }

    private fun buildBackShade(params: FableSolParams, layer: Int,
                               columns: Int, start: IntArray) {
        val litSign = if (params.get("light_azimuth_deg") >= 0.0) 1.0 else -1.0
        for (i in 0 until columns) {
            var back = ((-slope[i] * litSign - 0.05) / 0.40).coerceIn(0.0, 1.0)
            back = back * back * (3.0 - 2.0 * back)
            val crest = (curvature[i] / -GLOW_KAPPA).coerceIn(0.0, 1.0)
            field[i] = back * (0.30 + 0.70 * crest)
        }
        smoothHann(field, smooth, columns, 4)
        var maximum = 0.0
        for (i in 0 until columns) {
            maximum = max(maximum, smooth[i])
            bandTop[i] = y[i] + 0.3 * density
            bandThickness[i] = (2.0 + 13.0 * smooth[i]) * density * sqrt(max(smooth[i], 0.0))
        }
        if (maximum <= 0.04) return
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val color = FableSolShadowColorPolicy.backShade(
            start, params.get("hue_temp_deg"), depth
        )
        val air = 1.0 - params.get("aerial_contrast") * depth
        val alpha = (88.0 / 255.0 * params.lget("alpha", layer) *
            params.get("back_shade_gain") * air).toFloat()
        addContourBand(columns, bandTop, bandThickness, color, alpha, OPTICAL_MODE_BACK_SHADE)
    }

    private fun buildEdgeFeather(sim: FableSolSimulation, layer: Int, columns: Int,
                                 environmentHorizon: IntArray) {
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val amount = sim.calm01 * ((depth - 0.55) / 0.45).coerceIn(0.0, 1.0)
        if (amount < 0.06) return
        val thickness = (3.0 + 8.0 * amount) * density
        for (i in 0 until columns) {
            bandTop[i] = y[i] - thickness * 0.55
            bandThickness[i] = thickness
        }
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            environmentHorizon,
            (105.0 / 255.0 * amount * GLES_FEATHER_ALPHA_SCALE).toFloat(),
            OPTICAL_MODE_FEATHER
        )
    }

    private fun buildGlints(sim: FableSolSimulation, params: FableSolParams, layer: Int,
                            columns: Int, dt: Double, start: IntArray, end: IntArray) {
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val roughness = sim.layers[layer].roughness01
        val lightSlope = tan(Math.toRadians(params.get("light_azimuth_deg")) / 2.0)
        val baseSigma = GLINT_SIGMA * (1.0 + 0.42 * roughness)
        val sigma = FableSolSpecularAaPolicy.effectiveSigma(
            baseSigma,
            unresolvedSpecularSlopeVariance
        )
        val peakNormalization = FableSolSpecularAaPolicy.peakNormalization(baseSigma, sigma)
        val depthGain = max(0.0, 1.0 - depth / 0.42)
        val sinElevation = sin(Math.toRadians(VIEW_ELEVATION_DEG))
        val flatFresnel = WATER_F0 + (1.0 - WATER_F0) * (1.0 - sinElevation).pow(5)
        var maximumFresnelContribution = 0.0
        for (i in 0 until columns) {
            val opticalSlope = slope[i] + specularSlope[i]
            val reflection = peakNormalization * exp(-((opticalSlope - lightSlope) / sigma).pow(2))
            val filteredFacetSignal = sqrt(
                specularCurvature[i] * specularCurvature[i] +
                    unresolvedSpecularCurvatureVariance
            )
            val facet = (filteredFacetSignal / (0.004 + 0.006 * roughness))
                .coerceIn(0.0, 1.0).pow(0.58)
            val cosine = (sinElevation / sqrt(1.0 + opticalSlope * opticalSlope))
                .coerceIn(0.0, 1.0)
            val fresnel = WATER_F0 + (1.0 - WATER_F0) * (1.0 - cosine).pow(5)
            val fresnelDetail = ((fresnel - flatFresnel) * 4.0).coerceIn(0.0, 1.0)
            val fresnelContribution =
                fresnelDetail * params.get("sky_reflection_strength") * 0.24
            maximumFresnelContribution = max(maximumFresnelContribution, fresnelContribution)
            val edgeRaw = (
                reflection * facet * params.get("crest_glint_strength") * depthGain +
                    fresnelContribution
                ).coerceIn(0.0, 1.0)
            field[i] = ((edgeRaw - 0.08) / 0.92).coerceIn(0.0, 1.0)
        }
        smoothHann(field, smooth, columns, 3)
        val pink = 1.0 + 0.12 * params.get("pink_mod") *
            (2.0 * pink01(sim.t, 3.1) - 1.0)
        glintFresnelContributionMaxForTest[layer] = maximumFresnelContribution
        glintPinkGainForTest[layer] = pink
        glintUnresolvedVarianceForTest[layer] = unresolvedSpecularSlopeVariance
        glintUnresolvedCurvatureVarianceForTest[layer] =
            unresolvedSpecularCurvatureVariance
        glintBaseSigmaForTest[layer] = baseSigma
        glintEffectiveSigmaForTest[layer] = sigma
        glintPeakNormalizationForTest[layer] = peakNormalization
        val sparkle = (0.35 + 0.65 * sim.sparkle01) * pink
        val air = 1.0 - params.get("aerial_contrast") * depth
        for (i in 0 until columns) {
            val edge = if (smooth[i] < 0.015) 0.0 else smooth[i]
            field[i] = (edge * 1.5).coerceIn(0.0, 1.0) * sparkle * air
        }
        smoothHann(field, smooth, columns, 5)
        val cap = FableSolMaterialPolicy.glintCapacity(layer)
        findAnchors(
            smooth,
            columns,
            FableSolMaterialPolicy.GLINT_FIELD_FLOOR,
            FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP * density,
            cap
        )
        val globalBreathStrength = params.get("global_pink_breath_strength")
        val birthRate = FableSolPinkBreathPolicy.glintBirthRate(
            sim.t,
            params.get("pink_mod"),
            globalBreathStrength
        )
        glintBirthRateForTest[layer] = birthRate
        val maxBirths = if (globalBreathStrength <= 1e-6) {
            cap
        } else {
            glintBirthCredit[layer] = min(
                1.0,
                glintBirthCredit[layer] + dt * birthRate / GLINT_BIRTH_INTERVAL_SECONDS
            )
            if (glintBirthCredit[layer] >= 1.0) 1 else 0
        }
        val births = updateTracks(
            glints[layer],
            dt,
            34.0 * density,
            0.30,
            0.80,
            0.10,
            cap,
            maxBirths
        )
        if (globalBreathStrength > 1e-6 && births > 0) {
            glintBirthCredit[layer] = max(0.0, glintBirthCredit[layer] - births)
        }

        val highlight = highlightColor(start, end, params)
        val core = FableSolColor.mixOklab(highlight, WHITE, 0.35)
        for (track in glints[layer]) {
            val breath = 1.0 + 0.12 * sin(2.0 * PI * sim.t /
                (2.6 + 1.4 * track.seed) + track.seed * 2.0 * PI)
            val intensity = (track.intensity * breath).coerceIn(0.0, 1.0)
            if (intensity < 0.04) continue
            val centerX = track.u + interpolate(sway, columns, track.u)
            val halfLength = (track.size * 0.62).coerceIn(6.0 * density, 34.0 * density) *
                (0.8 + 0.4 * intensity)
            val halfThickness = (1.1 + 0.8 * track.seed) * density
            val alpha = (0.92 * params.lget("alpha", layer) * intensity.pow(0.8)).toFloat()
            val haloStrength = params.get("analytic_halo_strength")
            if (haloStrength > 1e-3) {
                val haloStart = cursor
                val haloColor = FableSolColor.mixOklab(highlight, WHITE, 0.18)
                val haloAlpha = (FableSolMaterialPolicy.HALO_ALPHA_SCALE * haloStrength *
                    params.lget("alpha", layer) * intensity.pow(0.72)).toFloat()
                addCurvedBand(
                    centerX,
                    halfLength * FableSolMaterialPolicy.HALO_LENGTH_SCALE,
                    halfThickness * FableSolMaterialPolicy.HALO_THICKNESS_SCALE,
                    haloColor,
                    haloColor,
                    haloAlpha,
                    columns,
                    OPTICAL_MODE_ANALYTIC_HALO
                )
                analyticHaloVertexCountForTest[layer] +=
                    (cursor - haloStart) / COMPONENTS_PER_VERTEX
            }
            addCurvedBand(
                centerX,
                halfLength,
                halfThickness,
                core,
                highlight,
                alpha,
                columns,
                OPTICAL_MODE_GLINT
            )
        }
    }

    private fun buildStreaks(sim: FableSolSimulation, params: FableSolParams, layer: Int,
                             columns: Int, dt: Double, start: IntArray, end: IntArray) {
        val tracks = streaks[layer]
        val flowPxPerSecond = sim.layers[layer].flowDps * density
        for (track in tracks) {
            track.age += dt
            track.u += flowPxPerSecond * dt
        }
        val margin = 60.0 * density
        tracks.removeAll {
            it.age >= it.life || it.u <= x[0] - margin || it.u >= x[columns - 1] + margin
        }
        val cap = if (layer == 0) 3 else 2
        if (tracks.size < cap && sim.t >= nextStreakTime[layer]) {
            val sequence = streakSequence[layer]
            val seed = hash01(sequence * 1.7 + 0.37, layer * 2.9)
            val seed2 = hash01(sequence * 3.1 + 1.11, layer * 5.3)
            tracks.add(Streak(
                x[0] + (0.08 + 0.84 * seed) * (x[columns - 1] - x[0]),
                0.0,
                5.0 + 4.0 * seed2,
                (26.0 + 38.0 * hash01(sequence + 9.1, layer.toDouble())) * density,
                seed2
            ))
            streakSequence[layer]++
            nextStreakTime[layer] = sim.t + 0.8 + 1.6 * seed
        }
        if (tracks.isEmpty()) return

        val base = highlightColor(start, end, params)
        val color = FableSolColor.mixOklab(base, WHITE, 0.45)
        val gain = params.get("flow_streak_gain")
        val pink = 1.0 + 0.15 * params.get("pink_mod") *
            (2.0 * pink01(sim.t, 17.3) - 1.0)
        streakPinkGainForTest[layer] = pink
        for (track in tracks) {
            val envelope = sin(PI * min(track.age / track.life, 1.0)).pow(0.8) * pink
            val centerX = track.u + interpolate(sway, columns, track.u)
            val facing = ((interpolate(slope, columns, centerX) + 0.05) / 0.50)
                .coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
            val visibility = envelope * facing.pow(1.1)
            if (visibility < 0.05) continue
            val halfLength = track.length * 0.5 * (0.85 + 0.30 * facing)
            val halfThickness = (1.1 + 0.9 * track.seed) * density
            val alpha = (0.36 * params.lget("alpha", layer) * gain * visibility).toFloat()
            addCurvedBand(
                centerX,
                halfLength,
                halfThickness,
                color,
                color,
                alpha,
                columns,
                OPTICAL_MODE_STREAK
            )
        }
    }

    private fun highlightColor(start: IntArray, end: IntArray,
                               params: FableSolParams): IntArray =
        FableSolOpticalColorPolicy.highlight(
            FableSolColor.mix(start, end, 0.3),
            params.get("crest_lighten")
        )

    private fun findAnchors(values: DoubleArray, count: Int, floor: Double,
                            minSeparation: Double, maxAnchors: Int) {
        anchorCount = 0
        while (anchorCount < maxAnchors) {
            var best = -1
            var bestValue = floor
            for (i in 2 until count - 2) {
                val value = values[i]
                if (value < values[i - 1] || value <= values[i + 1] || value <= bestValue) continue
                var separated = true
                for (anchor in 0 until anchorCount) {
                    if (abs(x[i] - anchorU[anchor]) < minSeparation) {
                        separated = false
                        break
                    }
                }
                if (separated) {
                    best = i
                    bestValue = value
                }
            }
            if (best < 0) break
            val half = bestValue * 0.5
            var left = best
            while (left > 0 && values[left] > half) left--
            var right = best
            while (right < count - 1 && values[right] > half) right++
            anchorU[anchorCount] = x[best]
            anchorIntensity[anchorCount] = bestValue
            anchorSize[anchorCount] = max(x[right] - x[left], 6.0)
            anchorCount++
        }
    }

    private fun updateTracks(tracks: ArrayList<Track>, dt: Double, matchDistance: Double,
                             attackSeconds: Double, releaseSeconds: Double,
                             positionSeconds: Double, cap: Int, maxBirths: Int): Int {
        java.util.Arrays.fill(anchorUsed, false)
        val positionGain = 1.0 - exp(-dt / max(positionSeconds, 1e-3))
        val attackGain = 1.0 - exp(-dt / max(attackSeconds, 1e-3))
        val releaseGain = 1.0 - exp(-dt / max(releaseSeconds, 1e-3))
        for (track in tracks) {
            var best = -1
            var bestDistance = matchDistance
            for (anchor in 0 until anchorCount) {
                if (anchorUsed[anchor]) continue
                val distance = abs(anchorU[anchor] - track.u)
                if (distance < bestDistance) {
                    best = anchor
                    bestDistance = distance
                }
            }
            if (best >= 0) {
                anchorUsed[best] = true
                track.u += (anchorU[best] - track.u) * positionGain
                val gain = if (anchorIntensity[best] > track.intensity) attackGain else releaseGain
                track.intensity += (anchorIntensity[best] - track.intensity) * gain
                track.size += (anchorSize[best] - track.size) * attackGain
            } else {
                track.intensity -= track.intensity * releaseGain
            }
        }
        var births = 0
        for (anchor in 0 until anchorCount) {
            if (!anchorUsed[anchor] &&
                anchorIntensity[anchor] > FableSolMaterialPolicy.GLINT_FIELD_FLOOR &&
                tracks.size < cap && births < maxBirths
            ) {
                val seed = fract(sin(anchorU[anchor] * 12.9898) * 43758.5453)
                tracks.add(Track(
                    anchorU[anchor],
                    anchorIntensity[anchor] * 0.12,
                    anchorSize[anchor],
                    seed
                ))
                births++
            }
        }
        tracks.removeAll { it.intensity <= 0.015 }
        tracks.sortByDescending { it.intensity }
        while (tracks.size > cap) tracks.removeAt(tracks.lastIndex)
        return births
    }

    private fun addContourBand(columns: Int, top: DoubleArray, thickness: DoubleArray,
                               color: IntArray, alpha: Float, opticalMode: Float,
                               hdrEligibility: DoubleArray? = null) {
        if (alpha <= 1f / 255f) return
        val profiledAlpha = alpha * CONTOUR_PROFILE_PEAK.toFloat()
        for (column in 0 until columns - 1) {
            if (thickness[column] <= 1e-4 && thickness[column + 1] <= 1e-4) continue
            if (cursor + VERTICES_PER_QUAD * COMPONENTS_PER_VERTEX > vertices.size) return
            val q0 = -1.0 + 2.0 * column / max(columns - 1, 1)
            val q1 = -1.0 + 2.0 * (column + 1) / max(columns - 1, 1)
            val bottom0 = top[column] + max(thickness[column], 0.0)
            val bottom1 = top[column + 1] + max(thickness[column + 1], 0.0)
            val eligibility0 = hdrEligibility?.get(column)?.toFloat() ?: 0f
            val eligibility1 = hdrEligibility?.get(column + 1)?.toFloat() ?: 0f
            putVertex(x[column], top[column], q0, 0.0, color, profiledAlpha, opticalMode,
                hdrEligibility = eligibility0)
            putVertex(x[column], bottom0, q0, 1.0, color, profiledAlpha, opticalMode,
                hdrEligibility = eligibility0)
            putVertex(x[column + 1], top[column + 1], q1, 0.0, color, profiledAlpha, opticalMode,
                hdrEligibility = eligibility1)
            putVertex(x[column + 1], top[column + 1], q1, 0.0, color, profiledAlpha, opticalMode,
                hdrEligibility = eligibility1)
            putVertex(x[column], bottom0, q0, 1.0, color, profiledAlpha, opticalMode,
                hdrEligibility = eligibility0)
            putVertex(x[column + 1], bottom1, q1, 1.0, color, profiledAlpha, opticalMode,
                hdrEligibility = eligibility1)
        }
    }

    private fun addVariableCenteredBand(columns: Int, amount: DoubleArray,
                                        color: IntArray, alpha: Float) {
        if (alpha <= 1f / 255f) return
        for (i in 0 until columns) {
            val value = if (i < 2 || i >= columns - 2) 0.0 else
                amount[i].coerceIn(0.0, 1.0).pow(0.72)
            val left = max(i - 1, 0)
            val right = min(i + 1, columns - 1)
            val tangentX = x[right] - x[left]
            val tangentY = y[right] - y[left]
            val inverse = 1.0 / max(sqrt(tangentX * tangentX + tangentY * tangentY), 1e-6)
            val normalX = -tangentY * inverse
            val normalY = tangentX * inverse
            val halfWidth = 0.5 * 3.2 * density * value
            val centerY = y[i] - 0.20 * density
            bandUpperX[i] = x[i] + normalX * halfWidth
            bandUpperY[i] = centerY + normalY * halfWidth
            bandLowerX[i] = x[i] - normalX * halfWidth
            bandLowerY[i] = centerY - normalY * halfWidth
        }
        for (column in 0 until columns - 1) {
            if (cursor + VERTICES_PER_QUAD * COMPONENTS_PER_VERTEX > vertices.size) return
            val q0 = -1.0 + 2.0 * column / max(columns - 1, 1)
            val q1 = -1.0 + 2.0 * (column + 1) / max(columns - 1, 1)
            putVertex(bandUpperX[column], bandUpperY[column], q0, 0.0,
                color, alpha, OPTICAL_MODE_VEIL)
            putVertex(bandLowerX[column], bandLowerY[column], q0, 1.0,
                color, alpha, OPTICAL_MODE_VEIL)
            putVertex(bandUpperX[column + 1], bandUpperY[column + 1], q1, 0.0,
                color, alpha, OPTICAL_MODE_VEIL)
            putVertex(bandUpperX[column + 1], bandUpperY[column + 1], q1, 0.0,
                color, alpha, OPTICAL_MODE_VEIL)
            putVertex(bandLowerX[column], bandLowerY[column], q0, 1.0,
                color, alpha, OPTICAL_MODE_VEIL)
            putVertex(bandLowerX[column + 1], bandLowerY[column + 1], q1, 1.0,
                color, alpha, OPTICAL_MODE_VEIL)
        }
    }

    /** 长流光必须沿整段轮廓弯曲，不能只取中心点切线后画一条直椭圆。 */
    private fun addCurvedBand(centerX: Double, halfLength: Double, thickness: Double,
                              color: IntArray, edgeColor: IntArray, alpha: Float, columns: Int,
                              opticalMode: Float) {
        if (alpha <= 1f / 255f) return
        val visibleStart = x[0]
        val visibleEnd = x[columns - 1]
        for (segment in 0 until CURVED_BAND_SEGMENTS) {
            val rawQ0 = -1.0 + 2.0 * segment / CURVED_BAND_SEGMENTS
            val rawQ1 = -1.0 + 2.0 * (segment + 1) / CURVED_BAND_SEGMENTS
            val x0 = (centerX + rawQ0 * halfLength).coerceIn(visibleStart, visibleEnd)
            val x1 = (centerX + rawQ1 * halfLength).coerceIn(visibleStart, visibleEnd)
            if (x1 - x0 <= 1e-4) continue
            if (cursor + VERTICES_PER_QUAD * COMPONENTS_PER_VERTEX > vertices.size) return
            val q0 = ((x0 - centerX) / max(halfLength, 1e-3)).coerceIn(-1.0, 1.0)
            val q1 = ((x1 - centerX) / max(halfLength, 1e-3)).coerceIn(-1.0, 1.0)
            val top0 = interpolate(y, columns, x0)
            val top1 = interpolate(y, columns, x1)
            val depth0 = thickness * (0.30 + 0.70 * sqrt(max(1.0 - q0 * q0, 0.0)))
            val depth1 = thickness * (0.30 + 0.70 * sqrt(max(1.0 - q1 * q1, 0.0)))

            putVertex(x0, top0, q0, 0.0, color, alpha, opticalMode, edgeColor)
            putVertex(x0, top0 + depth0, q0, 1.0, color, alpha, opticalMode, edgeColor)
            putVertex(x1, top1, q1, 0.0, color, alpha, opticalMode, edgeColor)
            putVertex(x1, top1, q1, 0.0, color, alpha, opticalMode, edgeColor)
            putVertex(x0, top0 + depth0, q0, 1.0, color, alpha, opticalMode, edgeColor)
            putVertex(x1, top1 + depth1, q1, 1.0, color, alpha, opticalMode, edgeColor)
        }
    }

    private fun putVertex(px: Double, py: Double, u: Double, v: Double,
                          color: IntArray, alpha: Float, opticalMode: Float,
                          edgeColor: IntArray = color, hdrEligibility: Float = 0f) {
        vertices[cursor++] = px.toFloat()
        vertices[cursor++] = py.toFloat()
        vertices[cursor++] = u.toFloat()
        vertices[cursor++] = v.toFloat()
        vertices[cursor++] = color[0] / 255f
        vertices[cursor++] = color[1] / 255f
        vertices[cursor++] = color[2] / 255f
        vertices[cursor++] = alpha.coerceIn(0f, 1f)
        vertices[cursor++] = opticalMode
        vertices[cursor++] = edgeColor[0] / 255f
        vertices[cursor++] = edgeColor[1] / 255f
        vertices[cursor++] = edgeColor[2] / 255f
        vertices[cursor++] = hdrEligibility.coerceIn(0f, 1f)
    }

    private fun interpolate(values: DoubleArray, count: Int, queryX: Double): Double {
        if (queryX <= x[0]) return values[0]
        if (queryX >= x[count - 1]) return values[count - 1]
        var low = 0
        var high = count - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (x[middle] <= queryX) low = middle else high = middle
        }
        val fraction = (queryX - x[low]) / max(x[high] - x[low], 1e-6)
        return values[low] + (values[high] - values[low]) * fraction
    }

    private fun smoothThree(input: DoubleArray, output: DoubleArray, count: Int) {
        for (i in 0 until count) {
            val left = if (i > 0) input[i - 1] else 0.0
            val right = if (i + 1 < count) input[i + 1] else 0.0
            output[i] = 0.25 * left + 0.50 * input[i] + 0.25 * right
        }
    }

    private fun smoothHann(input: DoubleArray, output: DoubleArray, count: Int, radius: Int) {
        FableSolMath.smoothHannInto(input, count, radius, output)
    }

    private fun smoothBox(input: DoubleArray, output: DoubleArray, count: Int, radius: Int) {
        for (i in 0 until count) {
            var sum = 0.0
            var samples = 0
            for (offset in -radius..radius) {
                val index = i + offset
                if (index in 0 until count) sum += input[index]
                samples++
            }
            output[i] = sum / samples
        }
    }

    private fun hash01(a: Double, b: Double): Double =
        fract(sin(a * 12.9898 + b * 78.233) * 43758.5453)

    private fun pink01(time: Double, seed: Double): Double {
        var total = 0.0
        var weightSum = 0.0
        for (index in PINK_TAU.indices) {
            val weight = 1.0 / (index + 1.0)
            val phase = time / PINK_TAU[index] + seed * (7.31 + index)
            val base = Math.floor(phase)
            var fraction = phase - base
            fraction = fraction * fraction * (3.0 - 2.0 * fraction)
            val a = pinkHash01(base, seed + index * 3.7)
            val b = pinkHash01(base + 1.0, seed + index * 3.7)
            total += weight * (a + (b - a) * fraction)
            weightSum += weight
        }
        return total / weightSum
    }

    private fun pinkHash01(a: Double, b: Double): Double =
        fract(sin(a * 127.1 + b * 311.7) * 43758.5453)

    private fun fract(value: Double): Double = value - Math.floor(value)

    internal fun glintTrackCountForTest(layer: Int): Int = glints[layer].size

    internal fun streakTrackCountForTest(layer: Int): Int = streaks[layer].size

    companion object {
        const val COMPONENTS_PER_VERTEX = 13 // x、y、局部 uv、核心 rgb、alpha、模式、边缘 rgb、HDR 资格
        const val VERTICES_PER_QUAD = 6
        const val VERTICES_PER_ELLIPSE = VERTICES_PER_QUAD
        const val MAX_VERTICES = 32_000
        private const val MAX_ANCHORS = 4
        private const val CURVED_BAND_SEGMENTS = 10
        private const val OPTICAL_MODE_STREAK = 2f
        private const val OPTICAL_MODE_GLINT = 3f
        private const val OPTICAL_MODE_SURFACE_REFLECTION = 4f
        private const val OPTICAL_MODE_FEATHER = 5f
        private const val OPTICAL_MODE_VEIL = 6f
        private const val OPTICAL_MODE_ANALYTIC_HALO = 7f
        private const val OPTICAL_MODE_TRANSMISSION = 8f
        private const val OPTICAL_MODE_BACK_SHADE = 9f
        private const val CONTOUR_PROFILE_PEAK = 0.66
        private const val GLES_SURFACE_ALPHA_SCALE = 1.0
        private const val GLES_SURFACE_WIDTH_SCALE = 1.0
        private const val GLES_FEATHER_ALPHA_SCALE = 1.0
        private const val GLINT_SIGMA = 0.072
        private const val GLOW_KAPPA = 0.009
        private const val VIEW_ELEVATION_DEG = 38.0
        private const val WATER_F0 = 0.020373
        private const val GLINT_BIRTH_INTERVAL_SECONDS = 0.42
        private val WHITE = intArrayOf(255, 255, 255)
        private val PINK_TAU = doubleArrayOf(0.9, 3.7, 14.0, 55.0)

        internal fun contourCoverageForTest(relativeDepth: Double): Double {
            val value = relativeDepth.coerceIn(0.0, 1.0)
            return CONTOUR_PROFILE_PEAK * sin(Math.PI * value)
        }
    }
}
