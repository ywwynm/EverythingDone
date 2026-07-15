package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 闪点只在已经不可感知时才退出，并把暂停/卡顿后的生命周期追赶拆到多个显示帧。 */
internal object FableSolGlintEnvelopePolicy {
    const val TRACK_RETIRE_INTENSITY = 0.015
    const val HDR_FULL_INTENSITY = 0.30
    private const val FADE_START = 0.018
    private const val FADE_END = 0.060
    private const val MAX_TRACKING_DELTA_SECONDS = 1.0 / 15.0

    fun trackingDeltaSeconds(rawDeltaSeconds: Double): Double =
        rawDeltaSeconds.coerceIn(0.0, MAX_TRACKING_DELTA_SECONDS)

    fun visibility(intensity: Double, exponent: Double): Double {
        val value = intensity.coerceIn(0.0, 1.0)
        return value.pow(exponent) * birthGate(value)
    }

    /** HDR 资格与核心覆盖分责，但共享同一出生/退场门；0.30 是真实 track 的满额标定。 */
    fun hdrEligibility(intensity: Double): Double {
        val value = intensity.coerceIn(0.0, 1.0)
        val reach = (value / HDR_FULL_INTENSITY).coerceAtMost(1.0)
        return birthGate(value) * reach.pow(0.8)
    }

    private fun birthGate(value: Double): Double {
        val t = ((value - FADE_START) / (FADE_END - FADE_START)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun coreAlpha(intensity: Double, layerAlpha: Double, layerWeight: Double = 1.0,
                  aerialContrast: Double = 1.0): Double =
        0.9129 * layerWeight.coerceAtLeast(0.0) * layerAlpha.coerceAtLeast(0.0) *
            aerialContrast.coerceIn(0.0, 1.0) * visibility(intensity, 0.8)
}

/**
 * Stage 1 光学实体的 CPU 跟踪与小网格生成器。
 *
 * 闪点、珍珠和流光保留跨帧身份，只在受光峰之间平滑跟随；猫爪直接消费 Simulation 中已经
 * 持久化的阵风。输出为少量带局部 UV 的单侧短带三角形，由 GLES 做软边光栅化。
 */
internal class FableSolGlOptics(private val density: Double) {

    private class Track(
        var u: Double,
        var intensity: Double,
        val birthSize: Double,
        val seed: Double,
        val birthPathWeight: Double
    )
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
    internal val glintCandidateCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glitterBirthsByLayerForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val analyticHaloVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintMinimumSegmentsForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintMaximumSegmentsForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintPackedHaloModeMaxForTest = FloatArray(FableSolSpec.N_LAYERS)
    internal val streakPinkGainForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val streakFirstVertexForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val streakVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val surfaceBandFirstVertexForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val surfaceBandVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val bodyLightVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val canvasSurfacePeakAlphaForTest = FloatArray(FableSolSpec.N_LAYERS)
    internal val actualSurfacePeakAlphaForTest = FloatArray(FableSolSpec.N_LAYERS)
    internal val canvasSurfaceMaxThicknessForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val actualSurfaceMaxThicknessForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val thinGlowVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val backShadeVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val crestVeilVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val interfaceShoulderVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)

    private val glints = Array(FableSolSpec.N_LAYERS) { ArrayList<Track>(4) }
    private val eligibleGlintLayerCount = (0 until FableSolSpec.N_LAYERS).count {
        FableSolMaterialPolicy.glintCapacity(it) > 0
    }
    private var glitterBirthCredit = eligibleGlintLayerCount.toDouble()
    private val glitterLayerBirthCredit = DoubleArray(FableSolSpec.N_LAYERS) { layer ->
        eligibleGlintLayerCount * FableSolMaterialPolicy.glintBirthWeight(layer) /
            FableSolMaterialPolicy.GLINT_BIRTH_WEIGHT_TOTAL
    }
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
    private val backShadeColors = Array(FableSolSpec.N_POINTS) { IntArray(3) }
    private val interfaceColors = Array(FableSolSpec.N_POINTS) { IntArray(3) }
    private val surfaceBandColors = Array(FableSolSpec.N_POINTS) { IntArray(3) }
    private val depthAxisX = DoubleArray(FableSolSpec.N_POINTS)
    private val depthAxisY = DoubleArray(FableSolSpec.N_POINTS)
    private val curvedBandQ = DoubleArray(MAX_CURVED_BAND_SEGMENTS + 1)
    private var unresolvedSpecularSlopeVariance = 0.0
    private var unresolvedSpecularCurvatureVariance = 0.0
    private val anchorU = DoubleArray(MAX_ANCHORS)
    private val anchorIntensity = DoubleArray(MAX_ANCHORS)
    private val anchorSize = DoubleArray(MAX_ANCHORS)
    private val anchorUsed = BooleanArray(MAX_ANCHORS)
    private var anchorCount = 0
    private val glitterCandidateLayer = IntArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateU = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateIntensity = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateSize = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidatePathWeight = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateScore = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateUsed = BooleanArray(MAX_GLITTER_CANDIDATES)
    private var glitterCandidateCount = 0
    internal val glintPathCenter01ForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintMaximumPathWeightForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal var glitterBirthsForTest = 0
        private set
    private var cursor = 0
    private var lastTrackTime = 0.0

    fun build(
        sim: FableSolSimulation,
        params: FableSolParams,
        columns: Int,
        waterVertices: FloatArray,
        layerStart: Array<IntArray>,
        layerEnd: Array<IntArray>,
        @Suppress("UNUSED_PARAMETER") environmentHorizon: IntArray,
        sourceIndex: IntArray? = null,
        sourceFraction: DoubleArray? = null,
        layerStop1: Array<IntArray>? = null,
        layerStop2: Array<IntArray>? = null,
        gradientOrigin: FloatArray? = null,
        gradientDirection: FloatArray? = null,
        gradientDenominator: FloatArray? = null,
        interfaceWeightStart: FloatArray? = null,
        interfaceWeightStop1: FloatArray? = null,
        interfaceWeightStop2: FloatArray? = null,
        interfaceWeightEnd: FloatArray? = null
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
        java.util.Arrays.fill(glintCandidateCountForTest, 0)
        java.util.Arrays.fill(glitterBirthsByLayerForTest, 0)
        java.util.Arrays.fill(glintPathCenter01ForTest, 0.0)
        java.util.Arrays.fill(glintMaximumPathWeightForTest, 0.0)
        java.util.Arrays.fill(analyticHaloVertexCountForTest, 0)
        java.util.Arrays.fill(glintMinimumSegmentsForTest, Int.MAX_VALUE)
        java.util.Arrays.fill(glintMaximumSegmentsForTest, 0)
        java.util.Arrays.fill(glintPackedHaloModeMaxForTest, 0f)
        java.util.Arrays.fill(streakPinkGainForTest, 0.0)
        java.util.Arrays.fill(streakVertexCountForTest, 0)
        java.util.Arrays.fill(surfaceBandFirstVertexForTest, 0)
        java.util.Arrays.fill(surfaceBandVertexCountForTest, 0)
        java.util.Arrays.fill(bodyLightVertexCountForTest, 0)
        java.util.Arrays.fill(canvasSurfacePeakAlphaForTest, 0f)
        java.util.Arrays.fill(actualSurfacePeakAlphaForTest, 0f)
        java.util.Arrays.fill(canvasSurfaceMaxThicknessForTest, 0.0)
        java.util.Arrays.fill(actualSurfaceMaxThicknessForTest, 0.0)
        java.util.Arrays.fill(thinGlowVertexCountForTest, 0)
        java.util.Arrays.fill(backShadeVertexCountForTest, 0)
        java.util.Arrays.fill(crestVeilVertexCountForTest, 0)
        java.util.Arrays.fill(interfaceShoulderVertexCountForTest, 0)
        glitterCandidateCount = 0
        glitterBirthsForTest = 0
        if (columns < 3) return 0
        val dt = max(sim.t - lastTrackTime, 0.0)
        val glintDt = FableSolGlintEnvelopePolicy.trackingDeltaSeconds(dt)

        for (layer in FableSolSpec.N_LAYERS - 1 downTo 0) {
            layerFirstVertex[layer] = cursor / COMPONENTS_PER_VERTEX
            readContour(layer, columns, waterVertices)
            prepareContour(sim, params, layer, columns)

            // 界面肩属于当前轮廓的主体材质：先画近侧深肩，再画远侧亮肩，二者在轮廓处
            // 覆盖率均为 0。随后同层的阴影、透射、反射和闪点才能自然叠在其上。
            if (layer < FableSolSpec.N_LAYERS - 1 &&
                interfaceWeightStart != null && interfaceWeightStop1 != null &&
                interfaceWeightStop2 != null && interfaceWeightEnd != null
            ) {
                val startVertex = cursor
                buildInterfaceShoulder(
                    layer = layer,
                    columns = columns,
                    start = layerStart[layer],
                    stop1 = layerStop1?.get(layer),
                    stop2 = layerStop2?.get(layer),
                    end = layerEnd[layer],
                    gradientOrigin = gradientOrigin,
                    gradientDirection = gradientDirection,
                    gradientDenominator = gradientDenominator,
                    weightStart = interfaceWeightStart[layer + 1].toDouble(),
                    weightStop1 = interfaceWeightStop1[layer + 1].toDouble(),
                    weightStop2 = interfaceWeightStop2[layer + 1].toDouble(),
                    weightEnd = interfaceWeightEnd[layer + 1].toDouble()
                )
                interfaceShoulderVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }

            // 阴影带先进入顶点数组，后续反射、流光、透射与闪点始终绘制在它上方。
            if (FableSolMaterialPolicy.backShadeAlphaWeight(layer) > 0.0 &&
                params.get("back_shade_gain") > 1e-3
            ) {
                val startVertex = cursor
                buildBackShade(
                    params = params,
                    layer = layer,
                    columns = columns,
                    start = layerStart[layer],
                    stop1 = layerStop1?.get(layer),
                    stop2 = layerStop2?.get(layer),
                    end = layerEnd[layer],
                    gradientOrigin = gradientOrigin,
                    gradientDirection = gradientDirection,
                    gradientDenominator = gradientDenominator
                )
                backShadeVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
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
            if (FableSolMaterialPolicy.crestVeilSourceWeight(layer) > 0.0 &&
                sourceIndex != null && sourceFraction != null &&
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
            // source-over 下先画内部体光/透射/轻纱，再画表面反射与流光；否则后画的
            // 半透明介质会衰减已经存在的表面响应并形成乳白覆盖。
            if (FableSolMaterialPolicy.surfaceBandAlphaWeight(layer) > 0.0 &&
                params.get("surface_strip_gain") > 1e-3
            ) {
                surfaceBandFirstVertexForTest[layer] = cursor / COMPONENTS_PER_VERTEX
                val startVertex = cursor
                buildSurfaceBand(
                    sim = sim,
                    params = params,
                    layer = layer,
                    columns = columns,
                    start = layerStart[layer],
                    stop1 = layerStop1?.get(layer),
                    stop2 = layerStop2?.get(layer),
                    end = layerEnd[layer],
                    gradientOrigin = gradientOrigin,
                    gradientDirection = gradientDirection,
                    gradientDenominator = gradientDenominator
                )
                surfaceBandVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }
            if (FableSolMaterialPolicy.flowStreakCapacity(layer) > 0) {
                streakFirstVertexForTest[layer] = cursor / COMPONENTS_PER_VERTEX
                val streakStart = cursor
                buildStreaks(sim, params, layer, columns, dt, layerStart[layer], layerEnd[layer])
                streakVertexCountForTest[layer] =
                    (cursor - streakStart) / COMPONENTS_PER_VERTEX
            }
            // 闪点核心最后进入同层序列，不能再被轻纱或透射 source-over 衰减。
            if (FableSolMaterialPolicy.glintCapacity(layer) > 0) {
                glintFirstVertexForTest[layer] = cursor / COMPONENTS_PER_VERTEX
                val glintStart = cursor
                buildGlints(
                    sim,
                    params,
                    layer,
                    columns,
                    glintDt,
                    layerStart[layer],
                    layerEnd[layer]
                )
                glintVertexCountForTest[layer] = (cursor - glintStart) / COMPONENTS_PER_VERTEX
            }
            layerVertexCount[layer] = cursor / COMPONENTS_PER_VERTEX - layerFirstVertex[layer]
        }
        scheduleGlitterBirths(sim, params, glintDt)
        lastTrackTime = sim.t
        return cursor
    }

    private fun readContour(layer: Int, columns: Int, waterVertices: FloatArray) {
        val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
        // 纵深轴始终取一个层区间约三分之一的跨度；97 行网格若仍固定 +1，闪点和光晕
        // 会被压成旧厚度的四分之一，并重新显露锯齿状细线。
        val depthStride = depthStrideRowsForTest()
        val depthRow = min(row + depthStride, FableSolContinuousSurface.Z_ROWS - 1)
        for (column in 0 until columns) {
            val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            val depthOffset = (depthRow * columns + column) *
                FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
            x[column] = waterVertices[offset].toDouble()
            y[column] = waterVertices[offset + 1].toDouble()
            depthAxisX[column] = waterVertices[depthOffset].toDouble() - x[column]
            depthAxisY[column] = waterVertices[depthOffset + 1].toDouble() - y[column]
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

    /**
     * D129：以真实屏幕 dp 构造宽软界面肩，而不是在相邻水层的窄 ribbon 内按比例着色。
     * 目标色沿当前层四停靠点渐变逐列采样；轮廓处与带外覆盖率均为 0，避免形成描边。
     */
    private fun buildInterfaceShoulder(
        layer: Int,
        columns: Int,
        start: IntArray,
        stop1: IntArray?,
        stop2: IntArray?,
        end: IntArray,
        gradientOrigin: FloatArray?,
        gradientDirection: FloatArray?,
        gradientDenominator: FloatArray?,
        weightStart: Double,
        weightStop1: Double,
        weightStop2: Double,
        weightEnd: Double
    ) {
        val weights = doubleArrayOf(
            weightStart.coerceIn(0.0, 1.0),
            weightStop1.coerceIn(0.0, 1.0),
            weightStop2.coerceIn(0.0, 1.0),
            weightEnd.coerceIn(0.0, 1.0)
        )
        if (weights.maxOrNull()!! < 1e-3) return

        val resolvedStop1 = stop1 ?: FableSolColor.mixOklab(start, end, 0.21)
        val resolvedStop2 = stop2 ?: FableSolColor.mixOklab(start, end, 0.56)
        val baseStops = arrayOf(start, resolvedStop1, resolvedStop2, end)
        val brightStops = Array(4) { stop ->
            FableSolInterfaceShoulderPolicy.bright(baseStops[stop], weights[stop])
        }
        val deepStops = Array(4) { stop ->
            FableSolInterfaceShoulderPolicy.deep(baseStops[stop], weights[stop])
        }

        for (column in 0 until columns) {
            val q = layerGradientT(
                layer,
                column,
                columns,
                gradientOrigin,
                gradientDirection,
                gradientDenominator
            )
            val weight = interpolateFourStopValue(weights, q).coerceIn(0.0, 1.0)
            val envelope = sqrt(weight)
            val width = (FableSolInterfaceShoulderPolicy.MIN_WIDTH_DP +
                (FableSolInterfaceShoulderPolicy.MAX_WIDTH_DP -
                    FableSolInterfaceShoulderPolicy.MIN_WIDTH_DP) * weight) *
                density * envelope
            bandTop[column] = y[column]
            bandThickness[column] = width * 0.72
            interpolateFourStopColor(
                deepStops[0], deepStops[1], deepStops[2], deepStops[3], q,
                interfaceColors[column]
            )
        }
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            interfaceColors,
            1f,
            OPTICAL_MODE_INTERFACE_SHOULDER
        )

        for (column in 0 until columns) {
            val q = layerGradientT(
                layer,
                column,
                columns,
                gradientOrigin,
                gradientDirection,
                gradientDenominator
            )
            val weight = interpolateFourStopValue(weights, q).coerceIn(0.0, 1.0)
            val envelope = sqrt(weight)
            val width = (FableSolInterfaceShoulderPolicy.MIN_WIDTH_DP +
                (FableSolInterfaceShoulderPolicy.MAX_WIDTH_DP -
                    FableSolInterfaceShoulderPolicy.MIN_WIDTH_DP) * weight) *
                density * envelope
            bandTop[column] = y[column] - width
            bandThickness[column] = width
            interpolateFourStopColor(
                brightStops[0], brightStops[1], brightStops[2], brightStops[3], q,
                interfaceColors[column]
            )
        }
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            interfaceColors,
            1f,
            OPTICAL_MODE_INTERFACE_SHOULDER
        )
    }

    private fun buildSurfaceBand(
        sim: FableSolSimulation,
        params: FableSolParams,
        layer: Int,
        columns: Int,
        start: IntArray,
        stop1: IntArray?,
        stop2: IntArray?,
        end: IntArray,
        gradientOrigin: FloatArray?,
        gradientDirection: FloatArray?,
        gradientDenominator: FloatArray?
    ) {
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        for (i in 0 until columns) {
            val q = ((slope[i] + 0.05) / 0.50).coerceIn(0.0, 1.0)
            val facing = q * q * (3.0 - 2.0 * q)
            val crest = (curvature[i] / -GLOW_KAPPA).coerceIn(0.0, 1.0)
            field[i] = FableSolMaterialPolicy.surfaceBandWidthDp(facing, crest, depth)
            hdrEligibility[i] = FableSolMaterialPolicy.surfaceBandLocality(facing, crest)
        }
        smoothHann(field, smooth, columns, 4)
        var maximumThickness = 0.0
        for (i in 0 until columns) {
            bandTop[i] = y[i] + 0.2 * density
            val canvasThickness = smooth[i] * density
            maximumThickness = max(maximumThickness, canvasThickness)
            bandThickness[i] = canvasThickness * GLES_SURFACE_WIDTH_SCALE
        }
        // 低频表面带沿当前位置的四停靠点层色固定 hue 提亮；不能用整层单一中间色
        // 覆盖任意双色 Thing 渐变，否则互补色会形成灰浊的常量带。
        val resolvedStop1 = stop1 ?: FableSolColor.mixOklab(start, end, 0.21)
        val resolvedStop2 = stop2 ?: FableSolColor.mixOklab(start, end, 0.56)
        val reflectionRamp = FableSolSurfaceColorPolicy.reflectionRamp(
            start, resolvedStop1, resolvedStop2, end
        )
        for (column in 0 until columns) {
            val gradientQ = layerGradientT(
                layer,
                column,
                columns,
                gradientOrigin,
                gradientDirection,
                gradientDenominator
            )
            FableSolSurfaceColorPolicy.sampleRampInto(
                reflectionRamp, gradientQ, surfaceBandColors[column]
            )
        }
        val air = 1.0 - params.get("aerial_contrast") * depth
        val breath = 1.0 + 0.10 * params.get("pink_mod") *
            (2.0 * pink01(sim.t, 9.7) - 1.0)
        val alpha = (92.0 / 255.0 * FableSolMaterialPolicy.surfaceBandAlphaWeight(layer) *
            params.lget("alpha", layer) * air * breath *
            params.get("surface_strip_gain")).toFloat()
        canvasSurfacePeakAlphaForTest[layer] = alpha * CONTOUR_PROFILE_PEAK.toFloat()
        actualSurfacePeakAlphaForTest[layer] =
            alpha * GLES_SURFACE_ALPHA_SCALE.toFloat() * CONTOUR_PROFILE_PEAK.toFloat()
        canvasSurfaceMaxThicknessForTest[layer] = maximumThickness
        actualSurfaceMaxThicknessForTest[layer] = maximumThickness * GLES_SURFACE_WIDTH_SCALE
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            surfaceBandColors,
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
            hdrEligibility[i] = volume
            bandTop[i] = y[i] + 0.35 * density
            bandThickness[i] = depthPx * (0.34 + 0.66 * volume)
        }
        val highlight = highlightColor(start, end, params)
        val color = FableSolColor.mixOklab(start, highlight, 0.46)
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val air = 1.0 - params.get("aerial_contrast") * depth
        val alpha = (72.0 / 255.0 * params.lget("alpha", layer) * air * strength).toFloat()
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

    private fun buildBackShade(params: FableSolParams, layer: Int, columns: Int,
                               start: IntArray, stop1: IntArray?, stop2: IntArray?,
                               end: IntArray, gradientOrigin: FloatArray?,
                               gradientDirection: FloatArray?,
                               gradientDenominator: FloatArray?) {
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
            bandThickness[i] = (2.0 + 13.0 * smooth[i]) * density *
                sqrt(max(smooth[i], 0.0)) * FableSolMaterialPolicy.backShadeWidthWeight(layer)
        }
        if (maximum <= 0.04) return
        val depth = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val resolvedStop1 = stop1 ?: FableSolColor.mixOklab(start, end, 0.21)
        val resolvedStop2 = stop2 ?: FableSolColor.mixOklab(start, end, 0.56)
        val shadowStart = FableSolShadowColorPolicy.backShade(
            start, params.get("hue_temp_deg"), depth
        )
        val shadowStop1 = FableSolShadowColorPolicy.backShade(
            resolvedStop1, params.get("hue_temp_deg"), depth
        )
        val shadowStop2 = FableSolShadowColorPolicy.backShade(
            resolvedStop2, params.get("hue_temp_deg"), depth
        )
        val shadowEnd = FableSolShadowColorPolicy.backShade(
            end, params.get("hue_temp_deg"), depth
        )
        for (column in 0 until columns) {
            val q = layerGradientT(
                layer,
                column,
                columns,
                gradientOrigin,
                gradientDirection,
                gradientDenominator
            )
            interpolateFourStopColor(
                shadowStart,
                shadowStop1,
                shadowStop2,
                shadowEnd,
                q,
                backShadeColors[column]
            )
        }
        val air = 1.0 - params.get("aerial_contrast") * depth
        val alpha = (88.0 / 255.0 * FableSolMaterialPolicy.backShadeAlphaWeight(layer) *
            params.lget("alpha", layer) * params.get("back_shade_gain") * air).toFloat()
        addContourBand(
            columns,
            bandTop,
            bandThickness,
            backShadeColors,
            alpha,
            OPTICAL_MODE_BACK_SHADE
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
                reflection * facet * params.get("crest_glint_strength") +
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
            field[i] = (edge * 1.5).coerceIn(0.0, 1.0) * sparkle
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
        glintCandidateCountForTest[layer] = anchorCount
        val globalBreathStrength = params.get("global_pink_breath_strength")
        val birthRate = FableSolPinkBreathPolicy.glintBirthRate(
            sim.t,
            params.get("pink_mod"),
            globalBreathStrength
        )
        glintBirthRateForTest[layer] = birthRate
        updateTracks(
            glints[layer],
            dt,
            34.0 * density,
            0.30,
            0.80,
            0.10,
            cap
        )

        val visibleSpan = max(x[columns - 1] - x[0], 1e-6)
        val lightAzimuth = params.get("light_azimuth_deg")
        glintPathCenter01ForTest[layer] = FableSolSunGlitterPolicy.pathCenter01(
            depth,
            lightAzimuth
        )
        for (anchor in 0 until anchorCount) {
            val x01 = ((anchorU[anchor] - x[0]) / visibleSpan).coerceIn(0.0, 1.0)
            val pathWeight = FableSolSunGlitterPolicy.birthWeight(x01, depth, lightAzimuth)
            glintMaximumPathWeightForTest[layer] = max(
                glintMaximumPathWeightForTest[layer],
                pathWeight
            )
            if (anchorUsed[anchor] || glitterCandidateCount >= MAX_GLITTER_CANDIDATES) continue
            glitterCandidateLayer[glitterCandidateCount] = layer
            glitterCandidateU[glitterCandidateCount] = anchorU[anchor]
            glitterCandidateIntensity[glitterCandidateCount] = anchorIntensity[anchor]
            glitterCandidateSize[glitterCandidateCount] = anchorSize[anchor]
            glitterCandidatePathWeight[glitterCandidateCount] = pathWeight
            glitterCandidateScore[glitterCandidateCount] = anchorIntensity[anchor] * pathWeight
            glitterCandidateCount++
        }

        val highlight = highlightColor(start, end, params)
        val core = FableSolColor.mixOklab(highlight, WHITE, 0.35)
        for (track in glints[layer]) {
            val intensity = track.intensity.coerceIn(0.0, 1.0)
            val alpha = FableSolGlintEnvelopePolicy.coreAlpha(
                intensity,
                params.lget("alpha", layer),
                FableSolMaterialPolicy.glintCoreAlphaWeight(layer),
                air
            ).toFloat()
            if (alpha <= 1f / 255f) continue
            val centerX = track.u + interpolate(sway, columns, track.u)
            // 出生时固定长度，只允许位置和强度追随新的坡面峰。几何直接描述实际核心，
            // 不再借用已停用 halo 的放大外框，否则 shader 内的核心剖面会再次变成长光带。
            val halfLength = (
                track.birthSize * 0.42 * FableSolMaterialPolicy.glintLengthWeight(layer)
                ).coerceIn(2.4 * density, 12.0 * density)
            val halfThickness = (1.1 + 0.8 * track.seed) * density
            val depthAxisLength = FableSolSunGlitterPolicy.depthAxisLengthDp(
                layer,
                track.birthPathWeight
            ) * density
            val curvedSegments = prepareCurvedBandSegments(
                centerX,
                halfLength,
                columns
            )
            glintMinimumSegmentsForTest[layer] = min(
                glintMinimumSegmentsForTest[layer], curvedSegments
            )
            glintMaximumSegmentsForTest[layer] = max(
                glintMaximumSegmentsForTest[layer], curvedSegments
            )
            val packedMode = OPTICAL_MODE_GLINT
            glintPackedHaloModeMaxForTest[layer] = max(
                glintPackedHaloModeMaxForTest[layer], packedMode
            )
            addCurvedBand(
                centerX,
                halfLength,
                halfThickness,
                core,
                highlight,
                alpha,
                columns,
                packedMode,
                depthAxisLength,
                curvedSegments,
                1f / 255f,
                FableSolGlintEnvelopePolicy.hdrEligibility(intensity).toFloat()
            )
        }
    }

    /**
     * 所有层的未匹配受光峰先进入同一个候选池，再由一份出生额度全局选择。这样闪点仍贴在各层
     * 轮廓上绘制，却不再由每层各自独立决定出生，太阳路径在连续深度上成为一个整体。
     */
    private fun scheduleGlitterBirths(
        sim: FableSolSimulation,
        params: FableSolParams,
        dt: Double
    ) {
        if (eligibleGlintLayerCount <= 0) return
        val strength = params.get("global_pink_breath_strength")
        val birthRate = FableSolPinkBreathPolicy.glintBirthRate(
            sim.t,
            params.get("pink_mod"),
            strength
        )
        var allowance = glitterCandidateCount
        if (strength > 1e-6) {
            val earned = dt * birthRate * eligibleGlintLayerCount /
                GLINT_BIRTH_INTERVAL_SECONDS
            glitterBirthCredit = min(
                eligibleGlintLayerCount.toDouble(),
                glitterBirthCredit + earned
            )
            for (layer in 0 until FableSolSpec.N_LAYERS) {
                val capacity = FableSolMaterialPolicy.glintCapacity(layer)
                if (capacity <= 0) continue
                glitterLayerBirthCredit[layer] = min(
                    capacity.toDouble(),
                    glitterLayerBirthCredit[layer] + earned *
                        FableSolMaterialPolicy.glintBirthWeight(layer) /
                        FableSolMaterialPolicy.GLINT_BIRTH_WEIGHT_TOTAL
                )
            }
            allowance = min(allowance, glitterBirthCredit.toInt())
        }
        if (allowance <= 0 || glitterCandidateCount <= 0) return

        java.util.Arrays.fill(glitterCandidateUsed, 0, glitterCandidateCount, false)
        var births = 0
        while (births < allowance) {
            var hasQuotaCandidate = false
            for (candidate in 0 until glitterCandidateCount) {
                if (glitterCandidateUsed[candidate]) continue
                val layer = glitterCandidateLayer[candidate]
                if (glints[layer].size < FableSolMaterialPolicy.glintCapacity(layer) &&
                    glitterLayerBirthCredit[layer] >= 1.0
                ) {
                    hasQuotaCandidate = true
                    break
                }
            }
            var best = -1
            var bestScore = MIN_GLITTER_BIRTH_SCORE
            for (candidate in 0 until glitterCandidateCount) {
                if (glitterCandidateUsed[candidate]) continue
                val layer = glitterCandidateLayer[candidate]
                if (glints[layer].size >= FableSolMaterialPolicy.glintCapacity(layer)) continue
                if (hasQuotaCandidate && glitterLayerBirthCredit[layer] < 1.0) continue
                val occupancy = glints[layer].size
                val distributedScore = glitterCandidateScore[candidate] /
                    (1.0 + 0.28 * occupancy)
                if (distributedScore > bestScore) {
                    best = candidate
                    bestScore = distributedScore
                }
            }
            if (best < 0) break
            glitterCandidateUsed[best] = true
            val layer = glitterCandidateLayer[best]
            val u = glitterCandidateU[best]
            val seed = fract(sin(u * 12.9898 + layer * 78.233) * 43758.5453)
            glints[layer].add(
                Track(
                    u,
                    glitterCandidateIntensity[best] * 0.12,
                    glitterCandidateSize[best],
                    seed,
                    glitterCandidatePathWeight[best]
                )
            )
            glitterLayerBirthCredit[layer] = max(0.0, glitterLayerBirthCredit[layer] - 1.0)
            glitterBirthsByLayerForTest[layer]++
            births++
        }
        if (strength > 1e-6) glitterBirthCredit = max(0.0, glitterBirthCredit - births)
        glitterBirthsForTest = births
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
        val cap = FableSolMaterialPolicy.flowStreakCapacity(layer)
        val layerWeight = FableSolMaterialPolicy.flowStreakWeight(layer)
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
            nextStreakTime[layer] = sim.t + (0.8 + 1.6 * seed) /
                max(layerWeight, 1e-3)
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
            val alpha = (0.36 * params.lget("alpha", layer) * gain * visibility *
                layerWeight).toFloat()
            val curvedSegments = prepareCurvedBandSegments(centerX, halfLength, columns)
            addCurvedBand(
                centerX,
                halfLength,
                halfThickness,
                color,
                color,
                alpha,
                columns,
                OPTICAL_MODE_STREAK,
                segmentCount = curvedSegments
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
                             positionSeconds: Double, cap: Int) {
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
            } else {
                track.intensity -= track.intensity * releaseGain
            }
        }
        tracks.removeAll {
            it.intensity <= FableSolGlintEnvelopePolicy.TRACK_RETIRE_INTENSITY
        }
        tracks.sortByDescending { it.intensity }
        while (tracks.size > cap) tracks.removeAt(tracks.lastIndex)
    }

    private fun layerGradientT(layer: Int, column: Int, columns: Int,
                               origin: FloatArray?, direction: FloatArray?,
                               denominator: FloatArray?): Double {
        val offset = layer * 2
        if (origin == null || direction == null || denominator == null ||
            offset + 1 >= origin.size || offset + 1 >= direction.size ||
            layer >= denominator.size
        ) {
            return column.toDouble() / max(columns - 1, 1)
        }
        val deltaX = x[column] - origin[offset]
        val deltaY = y[column] - origin[offset + 1]
        return ((deltaX * direction[offset] + deltaY * direction[offset + 1]) /
            max(denominator[layer].toDouble(), 1e-6)).coerceIn(0.0, 1.0)
    }

    private fun interpolateFourStopColor(start: IntArray, stop1: IntArray,
                                         stop2: IntArray, end: IntArray,
                                         q: Double, target: IntArray) {
        val clamped = q.coerceIn(0.0, 1.0)
        val first: IntArray
        val second: IntArray
        val fraction: Double
        if (clamped <= 0.24) {
            first = start
            second = stop1
            fraction = clamped / 0.24
        } else if (clamped <= 0.60) {
            first = stop1
            second = stop2
            fraction = (clamped - 0.24) / 0.36
        } else {
            first = stop2
            second = end
            fraction = (clamped - 0.60) / 0.40
        }
        for (channel in 0 until 3) {
            target[channel] = (first[channel] +
                (second[channel] - first[channel]) * fraction).roundToInt()
        }
    }

    private fun interpolateFourStopValue(stops: DoubleArray, q: Double): Double {
        val clamped = q.coerceIn(0.0, 1.0)
        return when {
            clamped <= 0.24 -> stops[0] +
                (stops[1] - stops[0]) * (clamped / 0.24)
            clamped <= 0.60 -> stops[1] +
                (stops[2] - stops[1]) * ((clamped - 0.24) / 0.36)
            else -> stops[2] +
                (stops[3] - stops[2]) * ((clamped - 0.60) / 0.40)
        }
    }

    private fun addContourBand(columns: Int, top: DoubleArray, thickness: DoubleArray,
                               color: IntArray, alpha: Float, opticalMode: Float,
                               hdrEligibility: DoubleArray? = null) {
        if (alpha <= 1f / 255f) return
        val profiledAlpha = alpha * CONTOUR_PROFILE_PEAK.toFloat()
        for (column in 0 until columns - 1) {
            if (thickness[column] <= 1e-4 && thickness[column + 1] <= 1e-4) continue
            requireVertexCapacity(VERTICES_PER_QUAD)
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
            requireVertexCapacity(VERTICES_PER_QUAD)
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

    /** 按可见弧的 3.2dp 目标段长一次确定 12～32 段，避免短光迹退化成折线。 */
    @Suppress("UNUSED_PARAMETER")
    private fun prepareCurvedBandSegments(centerX: Double, halfLength: Double,
                                          columns: Int): Int {
        val targetLength = CURVED_BAND_TARGET_SEGMENT_DP * density
        val segments = kotlin.math.ceil(
            2.0 * max(halfLength, 0.0) / max(targetLength, 1e-4)
        ).toInt().coerceIn(MIN_CURVED_BAND_SEGMENTS, MAX_CURVED_BAND_SEGMENTS)
        for (index in 0..segments) {
            curvedBandQ[index] = -1.0 + 2.0 * index / segments
        }
        return segments
    }

    /** 长流光必须沿整段轮廓弯曲，不能只取中心点切线后画一条直椭圆。 */
    private fun addCurvedBand(centerX: Double, halfLength: Double, thickness: Double,
                              color: IntArray, edgeColor: IntArray, alpha: Float, columns: Int,
                              opticalMode: Float, depthAxisLengthPx: Double = 0.0,
                              segmentCount: Int = 0, minimumAlpha: Float = 1f / 255f,
                              hdrEligibility: Float = 0f) {
        if (alpha <= minimumAlpha) return
        val visibleStart = x[0]
        val visibleEnd = x[columns - 1]
        val actualSegments = if (segmentCount > 0) segmentCount else
            prepareCurvedBandSegments(centerX, halfLength, columns)
        for (segment in 0 until actualSegments) {
            val rawQ0 = curvedBandQ[segment]
            val rawQ1 = curvedBandQ[segment + 1]
            val x0 = (centerX + rawQ0 * halfLength).coerceIn(visibleStart, visibleEnd)
            val x1 = (centerX + rawQ1 * halfLength).coerceIn(visibleStart, visibleEnd)
            if (x1 - x0 <= 1e-4) continue
            requireVertexCapacity(VERTICES_PER_QUAD)
            val q0 = ((x0 - centerX) / max(halfLength, 1e-3)).coerceIn(-1.0, 1.0)
            val q1 = ((x1 - centerX) / max(halfLength, 1e-3)).coerceIn(-1.0, 1.0)
            val top0 = interpolate(y, columns, x0)
            val top1 = interpolate(y, columns, x1)
            val depth0 = thickness * (0.30 + 0.70 * sqrt(max(1.0 - q0 * q0, 0.0)))
            val depth1 = thickness * (0.30 + 0.70 * sqrt(max(1.0 - q1 * q1, 0.0)))
            var bottomX0 = x0
            var bottomY0 = top0 + depth0
            var bottomX1 = x1
            var bottomY1 = top1 + depth1
            if (depthAxisLengthPx > 1e-4) {
                val axisX0 = interpolate(depthAxisX, columns, x0)
                val axisY0 = interpolate(depthAxisY, columns, x0)
                val available0 = sqrt(axisX0 * axisX0 + axisY0 * axisY0)
                if (available0 > 1e-4) {
                    val requested0 = max(depth0, depthAxisLengthPx *
                        (0.30 + 0.70 * sqrt(max(1.0 - q0 * q0, 0.0))))
                    val scale0 = min(requested0, available0) / available0
                    bottomX0 = x0 + axisX0 * scale0
                    bottomY0 = top0 + axisY0 * scale0
                }
                val axisX1 = interpolate(depthAxisX, columns, x1)
                val axisY1 = interpolate(depthAxisY, columns, x1)
                val available1 = sqrt(axisX1 * axisX1 + axisY1 * axisY1)
                if (available1 > 1e-4) {
                    val requested1 = max(depth1, depthAxisLengthPx *
                        (0.30 + 0.70 * sqrt(max(1.0 - q1 * q1, 0.0))))
                    val scale1 = min(requested1, available1) / available1
                    bottomX1 = x1 + axisX1 * scale1
                    bottomY1 = top1 + axisY1 * scale1
                }
            }

            putVertex(x0, top0, q0, 0.0, color, alpha, opticalMode, edgeColor, hdrEligibility)
            putVertex(bottomX0, bottomY0, q0, 1.0, color, alpha, opticalMode, edgeColor,
                hdrEligibility)
            putVertex(x1, top1, q1, 0.0, color, alpha, opticalMode, edgeColor, hdrEligibility)
            putVertex(x1, top1, q1, 0.0, color, alpha, opticalMode, edgeColor, hdrEligibility)
            putVertex(bottomX0, bottomY0, q0, 1.0, color, alpha, opticalMode, edgeColor,
                hdrEligibility)
            putVertex(bottomX1, bottomY1, q1, 1.0, color, alpha, opticalMode, edgeColor,
                hdrEligibility)
        }
    }

    /** 显式波背带逐列携带当前 Thing 渐变色，避免整条带固定使用起点身份色。 */
    private fun addContourBand(columns: Int, top: DoubleArray, thickness: DoubleArray,
                               colors: Array<IntArray>, alpha: Float, opticalMode: Float,
                               hdrEligibility: DoubleArray? = null) {
        if (alpha <= 1f / 255f) return
        // 界面肩把 0.66 峰值明确留给 shader 的 mode 10 剖面；其它轮廓带继续在
        // 顶点 alpha 预乘相同峰值。两条路径都只应用一次，最终能量一致。
        val profiledAlpha = if (opticalMode == OPTICAL_MODE_INTERFACE_SHOULDER) {
            alpha
        } else {
            alpha * CONTOUR_PROFILE_PEAK.toFloat()
        }
        for (column in 0 until columns - 1) {
            if (thickness[column] <= 1e-4 && thickness[column + 1] <= 1e-4) continue
            requireVertexCapacity(VERTICES_PER_QUAD)
            val q0 = -1.0 + 2.0 * column / max(columns - 1, 1)
            val q1 = -1.0 + 2.0 * (column + 1) / max(columns - 1, 1)
            val bottom0 = top[column] + max(thickness[column], 0.0)
            val bottom1 = top[column + 1] + max(thickness[column + 1], 0.0)
            val color0 = colors[column]
            val color1 = colors[column + 1]
            val eligibility0 = hdrEligibility?.get(column)?.toFloat() ?: 0f
            val eligibility1 = hdrEligibility?.get(column + 1)?.toFloat() ?: 0f
            putVertex(x[column], top[column], q0, 0.0, color0, profiledAlpha, opticalMode,
                hdrEligibility = eligibility0)
            putVertex(x[column], bottom0, q0, 1.0, color0, profiledAlpha, opticalMode,
                hdrEligibility = eligibility0)
            putVertex(x[column + 1], top[column + 1], q1, 0.0,
                color1, profiledAlpha, opticalMode, hdrEligibility = eligibility1)
            putVertex(x[column + 1], top[column + 1], q1, 0.0,
                color1, profiledAlpha, opticalMode, hdrEligibility = eligibility1)
            putVertex(x[column], bottom0, q0, 1.0, color0, profiledAlpha, opticalMode,
                hdrEligibility = eligibility0)
            putVertex(x[column + 1], bottom1, q1, 1.0,
                color1, profiledAlpha, opticalMode, hdrEligibility = eligibility1)
        }
    }

    private fun requireVertexCapacity(additionalVertices: Int) {
        check(cursor + additionalVertices * COMPONENTS_PER_VERTEX <= vertices.size) {
            "FableSol optical vertex budget exceeded: " +
                "${cursor / COMPONENTS_PER_VERTEX} + $additionalVertices > $MAX_VERTICES"
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

    internal fun glitterOccupiedLayerCountForTest(): Int = glints.count { it.isNotEmpty() }

    internal fun glitterBirthPathWeightAverageForTest(): Double {
        var total = 0.0
        var count = 0
        for (tracks in glints) for (track in tracks) {
            total += track.birthPathWeight
            count++
        }
        return if (count == 0) 0.0 else total / count
    }

    internal fun streakTrackCountForTest(layer: Int): Int = streaks[layer].size

    companion object {
        const val COMPONENTS_PER_VERTEX = 13 // x、y、局部 uv、核心 rgb、alpha、模式、边缘 rgb、HDR 资格
        const val VERTICES_PER_QUAD = 6
        const val VERTICES_PER_ELLIPSE = VERTICES_PER_QUAD
        const val MAX_VERTICES = 64_000
        private const val MAX_ANCHORS = 4
        private const val MAX_GLITTER_CANDIDATES = FableSolSpec.N_LAYERS * MAX_ANCHORS
        private const val MIN_CURVED_BAND_SEGMENTS = 12
        private const val MAX_CURVED_BAND_SEGMENTS = 32
        private const val CURVED_BAND_TARGET_SEGMENT_DP = 3.2
        private const val OPTICAL_MODE_STREAK = 2f
        private const val OPTICAL_MODE_GLINT = 3f
        private const val OPTICAL_MODE_SURFACE_REFLECTION = 4f
        private const val OPTICAL_MODE_VEIL = 6f
        private const val OPTICAL_MODE_TRANSMISSION = 8f
        private const val OPTICAL_MODE_BACK_SHADE = 9f
        private const val OPTICAL_MODE_INTERFACE_SHOULDER = 10f
        private const val CONTOUR_PROFILE_PEAK = 0.66
        private const val GLES_SURFACE_ALPHA_SCALE = 1.0
        private const val GLES_SURFACE_WIDTH_SCALE = 1.0
        private const val GLINT_SIGMA = 0.072
        private const val GLOW_KAPPA = 0.009
        private const val VIEW_ELEVATION_DEG = 38.0
        private const val WATER_F0 = 0.020373
        private const val GLINT_BIRTH_INTERVAL_SECONDS = 0.42
        private const val MIN_GLITTER_BIRTH_SCORE = 0.03
        private val WHITE = intArrayOf(255, 255, 255)
        private val PINK_TAU = doubleArrayOf(0.9, 3.7, 14.0, 55.0)

        internal fun contourCoverageForTest(relativeDepth: Double): Double {
            val value = relativeDepth.coerceIn(0.0, 1.0)
            return CONTOUR_PROFILE_PEAK * sin(Math.PI * value)
        }

        internal fun depthStrideRowsForTest(): Int =
            max(1, FableSolContinuousSurface.ROWS_PER_LAYER / 3)
    }
}
