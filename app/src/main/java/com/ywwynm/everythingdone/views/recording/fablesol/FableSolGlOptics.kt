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

    fun coreAlpha(intensity: Double, layerAlpha: Double, layerWeight: Double = 1.0): Double =
        0.9129 * layerWeight.coerceAtLeast(0.0) * layerAlpha.coerceAtLeast(0.0) *
            visibility(intensity, 0.8)
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

    val vertices = FloatArray(MAX_VERTICES * COMPONENTS_PER_VERTEX)
    val layerFirstVertex = IntArray(FableSolSpec.N_LAYERS)
    val layerVertexCount = IntArray(FableSolSpec.N_LAYERS)
    internal val glintFirstVertexForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintFresnelContributionMaxForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintCandidateCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glitterBirthsByLayerForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val analyticHaloVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintMinimumSegmentsForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintMaximumSegmentsForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintPackedHaloModeMaxForTest = FloatArray(FableSolSpec.N_LAYERS)
    internal val interfaceShoulderVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)

    private val glints = Array(FableSolSpec.N_LAYERS) { ArrayList<Track>(4) }
    private val effectiveGlintCapacity = IntArray(FableSolSpec.N_LAYERS)
    private var glintsWereEnabled = false
    private val eligibleGlintLayerCount = (0 until FableSolSpec.N_LAYERS).count {
        FableSolMaterialPolicy.glintCapacity(it) > 0
    }

    /**
     * 每层一份的构面上下文。轮廓 scratch、锚点池、待生候选与本层的顶点段全部
     * 归它所有，层任务之间没有任何共享可变写；9 份常驻复用，稳态零分配。
     */
    private val layerScratch = Array(FableSolSpec.N_LAYERS) { LayerScratch(it) }

    // 待生候选的合并池：层任务各自收集，层循环之后按「层序 8→0、层内锚点序」
    // 归并进这里，与串行版逐项相同的追加顺序。顺序有意义——scheduleGlitterBirths
    // 用严格大于挑最优，并列时先入者胜。
    private val glitterCandidateLayer = IntArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateU = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateIntensity = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateSize = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidatePathWeight = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateScore = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateUsed = BooleanArray(MAX_GLITTER_CANDIDATES)
    private var glitterCandidateCount = 0
    internal val backShadeVertexCountForTest = IntArray(FableSolSpec.N_LAYERS)
    internal val glintPathCenter01ForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal val glintMaximumPathWeightForTest = DoubleArray(FableSolSpec.N_LAYERS)
    internal var glitterBirthsForTest = 0
        private set
    private var cursor = 0
    private var lastTrackTime = 0.0

    /**
     * 按层并行的串行回退开关（永久保留）。默认并行；真机若出现异常可即时切回串行。
     * 两条路径共用同一份逐层构面代码，只有投放方式不同，顶点缓冲字节级相同。
     */
    @Volatile internal var parallelLayerBuildEnabled = true

    init {
        // 顶点段按层静态划分，越界立刻失败，而不是悄悄写进邻层的段里。
        check(FableSolSpec.N_LAYERS * MAX_LAYER_VERTICES <= MAX_VERTICES) {
            "分层顶点段容量超出总预算：${FableSolSpec.N_LAYERS} × $MAX_LAYER_VERTICES > $MAX_VERTICES"
        }
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            check(FableSolMaterialPolicy.glintCapacity(layer) <= MAX_LAYER_GLINT_TRACKS) {
                "第 $layer 层闪点容量超出分层顶点段的定容假设"
            }
        }
    }

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
        java.util.Arrays.fill(glintCandidateCountForTest, 0)
        java.util.Arrays.fill(glitterBirthsByLayerForTest, 0)
        java.util.Arrays.fill(glintPathCenter01ForTest, 0.0)
        java.util.Arrays.fill(glintMaximumPathWeightForTest, 0.0)
        java.util.Arrays.fill(analyticHaloVertexCountForTest, 0)
        java.util.Arrays.fill(glintMinimumSegmentsForTest, Int.MAX_VALUE)
        java.util.Arrays.fill(glintMaximumSegmentsForTest, 0)
        java.util.Arrays.fill(glintPackedHaloModeMaxForTest, 0f)
        java.util.Arrays.fill(interfaceShoulderVertexCountForTest, 0)
        java.util.Arrays.fill(backShadeVertexCountForTest, 0)
        glitterCandidateCount = 0
        glitterBirthsForTest = 0
        if (columns < 3) return 0
        val glintDt = FableSolGlintEnvelopePolicy.trackingDeltaSeconds(
            max(sim.t - lastTrackTime, 0.0)
        )
        // 闪点数量总门默认为 0（`glint_capacity_gain`），此时 cap 恒为 0：findAnchors
        // 取不到锚点、轨迹被清空、一个顶点都不发。但整条计算链仍在跑——每帧
        // 8 层 × 196 列 × 10 模态的 sampleInto（31360 次 sin/cos）加 1568 次
        // exp/pow/sqrt，全部是死算。这里整体短路，输出顶点缓冲逐位不变。
        val capacityGain = (
            params.get("glint_capacity_gain") * sim.glintCapacity01
            ).coerceIn(0.0, 1.0)
        val glintsEnabled = capacityGain > 0.0
        // 微法线只剩闪点一个消费者（体光已随 D216 移除）；关闭时不必采样。
        val microNeeded = glintsEnabled
        if (!glintsEnabled && glintsWereEnabled) {
            // 边沿触发：关闭瞬间清一次轨迹，避免下次开启时从冻结状态复现旧闪点。
            for (index in glints.indices) glints[index].clear()
            java.util.Arrays.fill(effectiveGlintCapacity, 0)
        }
        glintsWereEnabled = glintsEnabled

        // 九层的 readContour / prepareContour / 各建带段彼此独立，只写本层的
        // scratch、本层的顶点段与按层索引的统计位；跨层状态（待生候选、轨迹排产）
        // 全部留在层循环之后串行处理。投放顺序取 0→8，最重的第 0 层先出手，
        // 缓解 9 个不等粒度任务在 4 个消费者上的收尾长尾。
        val buildBody = FableSolRowBody { startLayer, endLayer ->
            for (layer in startLayer until endLayer) {
                layerScratch[layer].buildLayer(
                    sim, params, columns, waterVertices,
                    layerStart, layerEnd, layerStop1, layerStop2,
                    gradientOrigin, gradientDirection, gradientDenominator,
                    interfaceWeightStart, interfaceWeightStop1,
                    interfaceWeightStop2, interfaceWeightEnd,
                    glintDt, microNeeded, glintsEnabled
                )
            }
        }
        if (parallelLayerBuildEnabled) {
            FableSolRowParallel.runUnits(FableSolSpec.N_LAYERS, buildBody)
        } else {
            buildBody.run(0, FableSolSpec.N_LAYERS)
        }

        // 按层序 8→0 把各层的顶点段压实成一段连续缓冲。层内顶点顺序未变，层间
        // 顺序与串行版相同，因此压实后的缓冲与串行版逐字节相同——不是浮点重排。
        // 段基址按 (N_LAYERS-1-layer) 递增分配，压实按同一顺序推进，目的地恒不
        // 晚于源地址，重叠区间的 arraycopy 安全。
        for (layer in FableSolSpec.N_LAYERS - 1 downTo 0) {
            val scratch = layerScratch[layer]
            val length = scratch.segmentFloatCount
            layerFirstVertex[layer] = cursor / COMPONENTS_PER_VERTEX
            if (scratch.glintSegmentOffset >= 0) {
                glintFirstVertexForTest[layer] =
                    (cursor + scratch.glintSegmentOffset) / COMPONENTS_PER_VERTEX
            }
            if (length > 0 && cursor != scratch.segmentStart) {
                System.arraycopy(vertices, scratch.segmentStart, vertices, cursor, length)
            }
            cursor += length
            layerVertexCount[layer] = length / COMPONENTS_PER_VERTEX
            scratch.drainGlitterCandidatesInto()
        }
        scheduleGlitterBirths()
        lastTrackTime = sim.t
        return cursor
    }

    /** 把某层收集到的待生候选按原顺序追加进合并池；全局上限判定与串行版同式。 */
    private fun appendGlitterCandidate(
        layer: Int,
        u: Double,
        intensity: Double,
        size: Double,
        pathWeight: Double,
        score: Double
    ) {
        if (glitterCandidateCount >= MAX_GLITTER_CANDIDATES) return
        glitterCandidateLayer[glitterCandidateCount] = layer
        glitterCandidateU[glitterCandidateCount] = u
        glitterCandidateIntensity[glitterCandidateCount] = intensity
        glitterCandidateSize[glitterCandidateCount] = size
        glitterCandidatePathWeight[glitterCandidateCount] = pathWeight
        glitterCandidateScore[glitterCandidateCount] = score
        glitterCandidateCount++
    }

    /** 与 [buildInterfaceShoulder] 内早退判定同式，但不分配数组。 */
    private fun interfaceShoulderNeeded(
        weightStart: Double,
        weightStop1: Double,
        weightStop2: Double,
        weightEnd: Double
    ): Boolean {
        val maximum = max(
            max(weightStart.coerceIn(0.0, 1.0), weightStop1.coerceIn(0.0, 1.0)),
            max(weightStop2.coerceIn(0.0, 1.0), weightEnd.coerceIn(0.0, 1.0))
        )
        return maximum >= 1e-3
    }

    /**
     * 所有层的未匹配受光峰先进入同一个候选池，按分数贪心兑现且不超层容量
     * （1/f 呼吸的出生预算系统已随参数移除）。
     */
    private fun scheduleGlitterBirths() {
        if (eligibleGlintLayerCount <= 0 || glitterCandidateCount <= 0) return

        java.util.Arrays.fill(glitterCandidateUsed, 0, glitterCandidateCount, false)
        var births = 0
        while (true) {
            var best = -1
            var bestScore = MIN_GLITTER_BIRTH_SCORE
            for (candidate in 0 until glitterCandidateCount) {
                if (glitterCandidateUsed[candidate]) continue
                val layer = glitterCandidateLayer[candidate]
                if (glints[layer].size >= effectiveGlintCapacity[layer]) continue
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
            glitterBirthsByLayerForTest[layer]++
            births++
        }
        glitterBirthsForTest = births
    }

    private fun highlightColor(start: IntArray, end: IntArray): IntArray =
        FableSolColor.mix(start, end, 0.3)

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

    /**
     * 单层构面的全部可变状态。
     *
     * 原实现让 9 层共享同一组 216 长度 scratch 与同一个顶点游标，逐层复写，因此
     * 只能串行。这里把它们整体下沉到层粒度：每层一份 scratch，外加 `vertices` 里
     * 一段静态划分、互不重叠的顶点段。层任务之间不存在共享可变写，输出与串行
     * 版逐字节相同。
     */
    private inner class LayerScratch(private val layer: Int) {

        /** 段基址按 (N_LAYERS-1-layer) 递增：与压实顺序一致，保证目的地不晚于源。 */
        @JvmField val segmentStart =
            (FableSolSpec.N_LAYERS - 1 - layer) * MAX_LAYER_VERTICES * COMPONENTS_PER_VERTEX
        private val segmentEnd = segmentStart + MAX_LAYER_VERTICES * COMPONENTS_PER_VERTEX
        private var cursor = segmentStart

        /** 本层实际写出的 float 数（压实用）。 */
        @JvmField var segmentFloatCount = 0
        /** 闪点段相对本层段首的 float 偏移；本帧没进闪点分支时为 -1。 */
        @JvmField var glintSegmentOffset = -1

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
        private val field = DoubleArray(FableSolSpec.N_POINTS)
        private val smooth = DoubleArray(FableSolSpec.N_POINTS)
        private val bandTop = DoubleArray(FableSolSpec.N_POINTS)
        private val bandThickness = DoubleArray(FableSolSpec.N_POINTS)
        private val backShadeColors = Array(FableSolSpec.N_POINTS) { IntArray(3) }
        private val interfaceColors = Array(FableSolSpec.N_POINTS) { IntArray(3) }
        private val depthAxisX = DoubleArray(FableSolSpec.N_POINTS)
        private val depthAxisY = DoubleArray(FableSolSpec.N_POINTS)
        private val curvedBandQ = DoubleArray(MAX_CURVED_BAND_SEGMENTS + 1)
        private val anchorU = DoubleArray(MAX_ANCHORS)
        private val anchorIntensity = DoubleArray(MAX_ANCHORS)
        private val anchorSize = DoubleArray(MAX_ANCHORS)
        private val anchorUsed = BooleanArray(MAX_ANCHORS)
        private var anchorCount = 0

        // 本层收集的待生候选；层循环之后按层序归并进共享池。
        private val candidateU = DoubleArray(MAX_ANCHORS)
        private val candidateIntensity = DoubleArray(MAX_ANCHORS)
        private val candidateSize = DoubleArray(MAX_ANCHORS)
        private val candidatePathWeight = DoubleArray(MAX_ANCHORS)
        private val candidateScore = DoubleArray(MAX_ANCHORS)
        private var candidateCount = 0

        fun buildLayer(
            sim: FableSolSimulation,
            params: FableSolParams,
            columns: Int,
            waterVertices: FloatArray,
            layerStart: Array<IntArray>,
            layerEnd: Array<IntArray>,
            layerStop1: Array<IntArray>?,
            layerStop2: Array<IntArray>?,
            gradientOrigin: FloatArray?,
            gradientDirection: FloatArray?,
            gradientDenominator: FloatArray?,
            interfaceWeightStart: FloatArray?,
            interfaceWeightStop1: FloatArray?,
            interfaceWeightStop2: FloatArray?,
            interfaceWeightEnd: FloatArray?,
            glintDt: Double,
            microNeeded: Boolean,
            glintsEnabled: Boolean
        ) {
            cursor = segmentStart
            segmentFloatCount = 0
            glintSegmentOffset = -1
            candidateCount = 0
            readContour(layer, columns, waterVertices, microNeeded, glintsEnabled)
            prepareContour(sim, layer, columns, microNeeded)

            // 界面肩属于当前轮廓的主体材质：先画近侧深肩，再画远侧亮肩，二者在轮廓处
            // 覆盖率均为 0。随后同层的阴影、透射、反射和闪点才能自然叠在其上。
            // 权重全零时旧实现要先分配一只 4 元素数组再早退（每帧 8 次）。判定移到
            // 调用点：整段不进入时 interfaceShoulderVertexCountForTest[layer] 仍是
            // build 开头 fill 的 0，与早退路径写回的 0 相同。
            if (layer < FableSolSpec.N_LAYERS - 1 &&
                interfaceWeightStart != null && interfaceWeightStop1 != null &&
                interfaceWeightStop2 != null && interfaceWeightEnd != null &&
                interfaceShoulderNeeded(
                    interfaceWeightStart[layer + 1].toDouble(),
                    interfaceWeightStop1[layer + 1].toDouble(),
                    interfaceWeightStop2[layer + 1].toDouble(),
                    interfaceWeightEnd[layer + 1].toDouble()
                )
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

            // 显式保色暗带紧随主体水层；透射、反射和闪点都在它之后叠加，
            // 阴影不能事后把这些光学分瓣压脏。
            if (FableSolMaterialPolicy.backShadeAlphaWeight(layer) > 0.0 &&
                params.get("back_shade_gain") > 1e-3
            ) {
                val startVertex = cursor
                buildBackShade(
                    params, layer, columns,
                    layerStart[layer], layerStop1?.get(layer), layerStop2?.get(layer),
                    layerEnd[layer],
                    gradientOrigin, gradientDirection, gradientDenominator
                )
                backShadeVertexCountForTest[layer] =
                    (cursor - startVertex) / COMPONENTS_PER_VERTEX
            }

            // 闪点核心最后进入同层序列，不能被透射 source-over 衰减。
            if (glintsEnabled && FableSolMaterialPolicy.glintCapacity(layer) > 0) {
                glintSegmentOffset = cursor - segmentStart
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
            segmentFloatCount = cursor - segmentStart
        }

        /** 把本层候选按收集顺序交给共享池；只在层循环之后的串行段调用。 */
        fun drainGlitterCandidatesInto() {
            for (index in 0 until candidateCount) {
                appendGlitterCandidate(
                    layer,
                    candidateU[index],
                    candidateIntensity[index],
                    candidateSize[index],
                    candidatePathWeight[index],
                    candidateScore[index]
                )
            }
        }

        private fun readContour(
            layer: Int,
            columns: Int,
            waterVertices: FloatArray,
            microNeeded: Boolean,
            glintsEnabled: Boolean
        ) {
            val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
            // 纵深轴始终取一个层区间约三分之一的跨度；97 行网格若仍固定 +1，闪点和光晕
            // 会被压成旧厚度的四分之一，并重新显露锯齿状细线。
            val depthStride = depthStrideRowsForTest()
            val depthRow = min(row + depthStride, FableSolContinuousSurface.Z_ROWS - 1)
            // uDp 的唯一消费者是 prepareContour 的 optical.sampleInto，depthAxis 的唯一
            // 消费者是 buildGlints 里的 addCurvedBand；两者的进入条件在下面原样复刻。
            // 默认色板 glint/体光全关时这两组量是死算（196 列 × 9 层的一次除法与两次差值）。
            val capacity = FableSolMaterialPolicy.glintCapacity(layer)
            val microWanted = microNeeded && capacity > 0
            val depthAxisWanted = glintsEnabled && capacity > 0
            for (column in 0 until columns) {
                val offset = (row * columns + column) * FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
                x[column] = waterVertices[offset].toDouble()
                y[column] = waterVertices[offset + 1].toDouble()
            }
            if (depthAxisWanted) {
                for (column in 0 until columns) {
                    val depthOffset = (depthRow * columns + column) *
                        FableSolGlMeshLayout.COMPONENTS_PER_VERTEX
                    depthAxisX[column] = waterVertices[depthOffset].toDouble() - x[column]
                    depthAxisY[column] = waterVertices[depthOffset + 1].toDouble() - y[column]
                }
            }
            if (microWanted) {
                for (column in 0 until columns) uDp[column] = x[column] / density
            }
        }

        private fun prepareContour(sim: FableSolSimulation, layer: Int, columns: Int,
                                   microNeeded: Boolean) {
            val dx = max(abs(x[1] - x[0]), 1e-3)
            FableSolMath.gradientInto(y, columns, dx, gradient)
            for (i in 0 until columns) slopeRaw[i] = -gradient[i]
            smoothThree(slopeRaw, slope, columns)
            FableSolMath.gradientInto(gradient, columns, dx, gradient2)
            for (i in 0 until columns) curvatureRaw[i] = -gradient2[i] * density
            smoothThree(curvatureRaw, curvature, columns)
            java.util.Arrays.fill(microSlope, 0, columns, 0.0)
            java.util.Arrays.fill(microCurvature, 0, columns, 0.0)
            // 微法线的消费者只剩 buildGlints（体光带随 D216 移除）；闪点关闭时
            // 这两个数组保持全零，与采样后再被门挡住的结果一致。
            if (microNeeded && FableSolMaterialPolicy.glintCapacity(layer) > 0) {
                val ls = sim.layers[layer]
                ls.optical.sampleInto(uDp, columns, ls.roughness01,
                    microSlope, microCurvature)
            }
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
            // D169 恢复注：原式的空气透视因子随 aerial_contrast 按 0 固化为 1。
            val alpha = (88.0 / 255.0 * FableSolMaterialPolicy.backShadeAlphaWeight(layer) *
                params.lget("alpha", layer) * params.get("back_shade_gain")).toFloat()
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
            val sigma = GLINT_SIGMA * (1.0 + 0.42 * roughness)
            val sinElevation = sin(Math.toRadians(VIEW_ELEVATION_DEG))
            val flatFresnel = WATER_F0 + (1.0 - WATER_F0) * (1.0 - sinElevation).pow(5)
            var maximumFresnelContribution = 0.0
            // 镜面反射项（2026-07-18 应用户要求恢复闪点出生）：强度固化 0.90
            //（原 crest_glint_strength 默认，参数不恢复）；数量总门仍是
            // glint_capacity_gain（默认 0），拉起即出闪点。
            for (i in 0 until columns) {
                val opticalSlope = slope[i] + microSlope[i]
                val reflection = exp(-((opticalSlope - lightSlope) / sigma).pow(2))
                val facet = (abs(microCurvature[i]) / (0.004 + 0.006 * roughness))
                    .coerceIn(0.0, 1.0).pow(0.58)
                val cosine = (sinElevation / sqrt(1.0 + opticalSlope * opticalSlope))
                    .coerceIn(0.0, 1.0)
                val fresnel = WATER_F0 + (1.0 - WATER_F0) * (1.0 - cosine).pow(5)
                val fresnelDetail = ((fresnel - flatFresnel) * 4.0).coerceIn(0.0, 1.0)
                val fresnelContribution =
                    fresnelDetail * params.get("sky_reflection_strength") * 0.24
                maximumFresnelContribution = max(maximumFresnelContribution, fresnelContribution)
                val edgeRaw = (reflection * facet * 0.90 + fresnelContribution)
                    .coerceIn(0.0, 1.0)
                field[i] = ((edgeRaw - 0.08) / 0.92).coerceIn(0.0, 1.0)
            }
            smoothHann(field, smooth, columns, 3)
            glintFresnelContributionMaxForTest[layer] = maximumFresnelContribution
            val sparkle = 0.35 + 0.65 * sim.sparkle01
            for (i in 0 until columns) {
                val edge = if (smooth[i] < 0.015) 0.0 else smooth[i]
                field[i] = (edge * 1.5).coerceIn(0.0, 1.0) * sparkle
            }
            smoothHann(field, smooth, columns, 5)
            // D156：银边评审期闪点数量门（容量表不动），与 Python gl_optics 一比一。
            val capacityGain = (
                params.get("glint_capacity_gain") * sim.glintCapacity01
                ).coerceIn(0.0, 1.0)
            val cap = Math.round(
                FableSolMaterialPolicy.glintCapacity(layer) * capacityGain
            ).toInt()
            effectiveGlintCapacity[layer] = cap
            findAnchors(
                smooth,
                columns,
                FableSolMaterialPolicy.GLINT_FIELD_FLOOR,
                FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP * density,
                cap
            )
            glintCandidateCountForTest[layer] = anchorCount
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
                // 候选先落在本层缓冲，层循环之后按层序 8→0 归并进共享池——
                // 与串行版逐项相同的追加顺序，全局上限判定移到归并处。
                if (anchorUsed[anchor] || candidateCount >= MAX_ANCHORS) continue
                candidateU[candidateCount] = anchorU[anchor]
                candidateIntensity[candidateCount] = anchorIntensity[anchor]
                candidateSize[candidateCount] = anchorSize[anchor]
                candidatePathWeight[candidateCount] = pathWeight
                candidateScore[candidateCount] = anchorIntensity[anchor] * pathWeight
                candidateCount++
            }

            val highlight = highlightColor(start, end)
            val core = FableSolColor.mixOklab(highlight, WHITE, 0.35)
            for (track in glints[layer]) {
                val intensity = track.intensity.coerceIn(0.0, 1.0)
                val alpha = FableSolGlintEnvelopePolicy.coreAlpha(
                    intensity,
                    params.lget("alpha", layer),
                    FableSolMaterialPolicy.glintCoreAlphaWeight(layer)
                ).toFloat()
                if (alpha <= 1f / 255f) continue
                val centerX = track.u
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
                                   colors: Array<IntArray>, alpha: Float, opticalMode: Float) {
            if (alpha <= 1f / 255f) return
            // 界面肩把 0.66 峰值明确留给 shader 的 mode 10 剖面；其它轮廓带继续在
            // 顶点 alpha 预乘相同峰值。两条路径都只应用一次，最终能量一致。
            val profiledAlpha = (
                if (opticalMode == OPTICAL_MODE_INTERFACE_SHOULDER) {
                    alpha
                } else {
                    alpha * CONTOUR_PROFILE_PEAK.toFloat()
                }
                ).coerceIn(0f, 1f)
            // 逐列两组颜色供 6 个顶点复用；沿列前进时右端颜色成为下一列的左端，
            // 因此整条带每列只做 3 次除法而不是 36 次。
            var red0 = 0f
            var green0 = 0f
            var blue0 = 0f
            var previousColumn = -1
            for (column in 0 until columns - 1) {
                if (thickness[column] <= 1e-4 && thickness[column + 1] <= 1e-4) continue
                requireVertexCapacity(VERTICES_PER_QUAD)
                val q0 = -1.0 + 2.0 * column / max(columns - 1, 1)
                val q1 = -1.0 + 2.0 * (column + 1) / max(columns - 1, 1)
                val bottom0 = top[column] + max(thickness[column], 0.0)
                val bottom1 = top[column + 1] + max(thickness[column + 1], 0.0)
                if (previousColumn != column) {
                    val color0 = colors[column]
                    red0 = color0[0] / 255f
                    green0 = color0[1] / 255f
                    blue0 = color0[2] / 255f
                }
                val color1 = colors[column + 1]
                val red1 = color1[0] / 255f
                val green1 = color1[1] / 255f
                val blue1 = color1[2] / 255f
                putVertexNormalized(x[column], top[column], q0, 0.0, red0, green0, blue0,
                    profiledAlpha, opticalMode, red0, green0, blue0, 0f)
                putVertexNormalized(x[column], bottom0, q0, 1.0, red0, green0, blue0,
                    profiledAlpha, opticalMode, red0, green0, blue0, 0f)
                putVertexNormalized(x[column + 1], top[column + 1], q1, 0.0, red1, green1, blue1,
                    profiledAlpha, opticalMode, red1, green1, blue1, 0f)
                putVertexNormalized(x[column + 1], top[column + 1], q1, 0.0, red1, green1, blue1,
                    profiledAlpha, opticalMode, red1, green1, blue1, 0f)
                putVertexNormalized(x[column], bottom0, q0, 1.0, red0, green0, blue0,
                    profiledAlpha, opticalMode, red0, green0, blue0, 0f)
                putVertexNormalized(x[column + 1], bottom1, q1, 1.0, red1, green1, blue1,
                    profiledAlpha, opticalMode, red1, green1, blue1, 0f)
                // 下一列的左端就是本列的右端，颜色可以直接顺延。
                red0 = red1
                green0 = green1
                blue0 = blue1
                previousColumn = column + 1
            }
        }

        /** 预算改按本层段结算：越界必须就地失败，而不是写进邻层的段里。 */
        private fun requireVertexCapacity(additionalVertices: Int) {
            check(cursor + additionalVertices * COMPONENTS_PER_VERTEX <= segmentEnd) {
                "FableSol optical vertex budget exceeded: layer=$layer " +
                    "${(cursor - segmentStart) / COMPONENTS_PER_VERTEX} + $additionalVertices " +
                    "> $MAX_LAYER_VERTICES"
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

        /**
         * 颜色已归一化的顶点写入。`255f` 不是 2 的幂，JIT 无法把 `c / 255f` 降级成乘法，
         * 因此每个顶点固定要付 6 次浮点除法。轮廓带内每列只有两组颜色却要发 6 个顶点，
         * 把归一化提到列外后除法次数降为原来的 1/6，写入的位模式完全相同。
         */
        private fun putVertexNormalized(px: Double, py: Double, u: Double, v: Double,
                                        red: Float, green: Float, blue: Float,
                                        alpha: Float, opticalMode: Float,
                                        edgeRed: Float, edgeGreen: Float, edgeBlue: Float,
                                        hdrEligibility: Float) {
            vertices[cursor++] = px.toFloat()
            vertices[cursor++] = py.toFloat()
            vertices[cursor++] = u.toFloat()
            vertices[cursor++] = v.toFloat()
            vertices[cursor++] = red
            vertices[cursor++] = green
            vertices[cursor++] = blue
            vertices[cursor++] = alpha
            vertices[cursor++] = opticalMode
            vertices[cursor++] = edgeRed
            vertices[cursor++] = edgeGreen
            vertices[cursor++] = edgeBlue
            vertices[cursor++] = hdrEligibility
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
    }

    companion object {
        const val COMPONENTS_PER_VERTEX = 13 // x、y、局部 uv、核心 rgb、alpha、模式、边缘 rgb、HDR 资格
        const val VERTICES_PER_QUAD = 6
        const val VERTICES_PER_ELLIPSE = VERTICES_PER_QUAD
        const val MAX_VERTICES = 64_000
        private const val MAX_ANCHORS = 4
        private const val MAX_GLITTER_CANDIDATES = FableSolSpec.N_LAYERS * MAX_ANCHORS
        private const val MIN_CURVED_BAND_SEGMENTS = 12
        private const val MAX_CURVED_BAND_SEGMENTS = 32

        /** 各层闪点容量的上界（`GLINT_CAPACITIES` 首项）；init 里逐层核对。 */
        private const val MAX_LAYER_GLINT_TRACKS = 4

        /**
         * 单层顶点段的定容：界面肩两条 + 波背暗带一条 + 体光一条，各至多
         * `N_POINTS - 1` 个 quad；闪点至多 [MAX_LAYER_GLINT_TRACKS] 条轨迹、
         * 每条 [MAX_CURVED_BAND_SEGMENTS] 段。9 段静态划分，互不重叠。
         */
        private const val MAX_LAYER_QUADS =
            4 * (FableSolSpec.N_POINTS - 1) +
                MAX_LAYER_GLINT_TRACKS * MAX_CURVED_BAND_SEGMENTS
        private const val MAX_LAYER_VERTICES = MAX_LAYER_QUADS * VERTICES_PER_QUAD
        private const val CURVED_BAND_TARGET_SEGMENT_DP = 3.2
        // 波峰透光的曲率尺度 (dp^-1)；波背自阴影的脊线邻近门也用它归一。
        private const val GLOW_KAPPA = 0.009
        private const val OPTICAL_MODE_GLINT = 3f
        private const val OPTICAL_MODE_TRANSMISSION = 8f
        // mode 9 复用 optical.frag 里 mode 8 的半正弦覆盖分支（剖面相同），
        // 且 HDR 透射提升带 <8.5 上界，波背暗带不会误入超白预算。
        private const val OPTICAL_MODE_BACK_SHADE = 9f
        private const val OPTICAL_MODE_INTERFACE_SHOULDER = 10f
        private const val CONTOUR_PROFILE_PEAK = 0.66
        private const val GLINT_SIGMA = 0.072
        private const val VIEW_ELEVATION_DEG = 38.0
        private const val WATER_F0 = 0.020373
        private const val MIN_GLITTER_BIRTH_SCORE = 0.03
        private val WHITE = intArrayOf(255, 255, 255)

        internal fun contourCoverageForTest(relativeDepth: Double): Double {
            val value = relativeDepth.coerceIn(0.0, 1.0)
            return CONTOUR_PROFILE_PEAK * sin(Math.PI * value)
        }

        internal fun depthStrideRowsForTest(): Int =
            max(1, FableSolContinuousSurface.ROWS_PER_LAYER / 3)
    }
}
