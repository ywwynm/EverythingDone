package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * vNext 的纯几何 tracer bullet。
 *
 * 深度值和主运动场只由单目深度产生。实例连续性可以删除同一物体内部的伪遮挡 cut，
 * 但不能改写深度或位移基；所有像素共用同一个经过有限尺度稳健化的连续深度场。最终
 * motion basis 决定实际显露宽度，避免把原始深度差直接扩张成大块补图区。
 */
object SpatialVNextGeometryBuilder {

    /** 中等视角下连续场允许的高分位局部形变；不再用近刚性 chart 换取低数值。 */
    const val MAX_TOTAL_STRAIN = 0.08f
    const val MAX_LOCAL_STRAIN = MAX_TOTAL_STRAIN
    const val MAX_AFFINE_STRAIN = MAX_TOTAL_STRAIN
    const val REQUESTED_MAXIMUM_PARALLAX = 0.16f
    internal const val GEOMETRY_REGULARIZATION_REFERENCE_PARALLAX = 0.12f

    data class Result(
        val geometry: SpatialLdiLiteGeometry,
        val viewEnvelope: SpatialViewEnvelope,
        val motionCandidateId: String,
        val mediumResidualWeight: Float,
        val motionGroupingApplied: Boolean,
        val motionGroupCount: Int,
        val inpaintingOccluderMask: BooleanArray?
    )

    fun build(
        width: Int,
        height: Int,
        sourceDepth: SpatialDepthData,
        continuityMask: BooleanArray? = null,
        continuityLabels: ByteArray? = null,
        requestedMaximumParallax: Float = REQUESTED_MAXIMUM_PARALLAX,
        maximumLocalStrain: Float = MAX_LOCAL_STRAIN
    ): Result {
        require(width > 1 && height > 1) { "vNext 几何尺寸必须大于 1" }
        require(requestedMaximumParallax in 0.01f..0.20f)
        require(maximumLocalStrain in 0.01f..0.10f)
        require(continuityMask == null || continuityMask.size == width * height)
        require(continuityLabels == null || continuityLabels.size == width * height)

        val upsampled = upsampleGrid(
            sourceDepth.values,
            sourceDepth.width,
            sourceDepth.height,
            width,
            height
        )
        val rawInverse = sourceDepth.rawInverseDepth?.let {
            upsampleGrid(it, sourceDepth.width, sourceDepth.height, width, height)
        }
        val denoised = denoiseIsotropically(upsampled, width, height)
        val denoisedRaw = rawInverse?.let { medianCardinal(it, width, height) }
        val geometricCuts = buildOcclusionGraph(denoised, denoisedRaw, width, height)
        SpatialCutGraphCleaner.healSmallIslands(
            width = width,
            height = height,
            depth = denoised,
            cutRight = geometricCuts.first,
            cutDown = geometricCuts.second
        )
        // 候选运动场的选择只读取几何证据。语义连续性可以删除最终渲染中的伪断边，
        // 但不得通过改变候选选择间接改写主运动场。
        val selectionCutRight = geometricCuts.first.copyOf()
        val selectionCutDown = geometricCuts.second.copyOf()
        val cuts = geometricCuts.first.copyOf() to geometricCuts.second.copyOf()
        suppressTrustedInteriorCuts(
            width = width,
            height = height,
            acceptedMask = continuityMask,
            continuityLabels = continuityLabels,
            cutRight = cuts.first,
            cutDown = cuts.second
        )
        val surface = denoised
        val motionScales = SpatialContinuousMotionBuilder.prepare(
            width = width,
            height = height,
            depth = surface
        )
        val baseGeometry = SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = surface,
            backgroundDepth = surface.copyOf(),
            cutRight = cuts.first,
            cutDown = cuts.second,
            hiddenBackgroundMask = BooleanArray(surface.size)
        )
        data class EvaluatedCandidate(
            val candidate: SpatialContinuousMotionBuilder.Candidate,
            val selectionSpan: Float,
            val proxyAmplitude: Float
        )
        data class Finalist(
            val evaluated: EvaluatedCandidate,
            val localResponseScore: Float
        )
        val evaluated = motionScales.candidates().map { candidate ->
            val proxy = candidate.basis.scalarSelectionProxy(
                cutRight = selectionCutRight,
                cutDown = selectionCutDown
            )
            var minimumSpan = Float.POSITIVE_INFINITY
            var minimumAmplitude = requestedMaximumParallax
            for (direction in proxy.spanCoefficients.indices) {
                val spanCoefficient = proxy.spanCoefficients[direction]
                var safeAmplitude = requestedMaximumParallax
                val nonSimilarity = proxy.nonSimilarityCoefficients[direction]
                if (nonSimilarity > MIN_PROXY_GRADIENT) {
                    safeAmplitude = min(
                        safeAmplitude,
                        maximumLocalStrain / nonSimilarity
                    )
                }
                val scale = proxy.scaleCoefficients[direction]
                if (scale > MIN_PROXY_GRADIENT) {
                    safeAmplitude = min(
                        safeAmplitude,
                        SpatialViewEnvelopeBuilder.MAX_LOCAL_SCALE_STRAIN / scale
                    )
                }
                if (spanCoefficient > MIN_PROXY_SPAN) {
                    safeAmplitude = min(
                        safeAmplitude,
                        SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE /
                            SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE / spanCoefficient
                    )
                }
                safeAmplitude = safeAmplitude.coerceAtLeast(
                    SpatialViewEnvelope.MINIMUM_AMPLITUDE
                )
                minimumAmplitude = min(minimumAmplitude, safeAmplitude)
                minimumSpan = min(
                    minimumSpan,
                    spanCoefficient * safeAmplitude *
                        SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE
                )
            }
            EvaluatedCandidate(
                candidate = candidate,
                selectionSpan = minimumSpan.takeIf(Float::isFinite) ?: 0f,
                proxyAmplitude = minimumAmplitude
            )
        }
        val bestSpan = evaluated.maxOf { it.selectionSpan }
        val finalists = evaluated.filter {
            it.selectionSpan >= bestSpan - PARALLAX_TIE_TOLERANCE_PX
        }.map {
            Finalist(
                evaluated = it,
                localResponseScore = localResponseScore(
                    basis = it.candidate.basis,
                    amplitude = it.proxyAmplitude
                )
            )
        }
        val selectedFinalist = selectNearBestCandidateIndex(
            selectionSpans = FloatArray(finalists.size) {
                finalists[it].evaluated.selectionSpan
            },
            localResponseScores = FloatArray(finalists.size) {
                finalists[it].localResponseScore
            }
        )
        val selectedCandidate = finalists[selectedFinalist].evaluated
        val selected = baseGeometry.copy(
            motionBasis = selectedCandidate.candidate.basis
        )
        val envelope = SpatialViewEnvelopeBuilder.build(
            selected,
            requestedMaximumAmplitude = requestedMaximumParallax,
            maximumLocalStrain = maximumLocalStrain,
            targetParallaxSpanAtReferenceLongEdge =
                SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE
        )
        val visibility = SpatialVNextVisibilityBuilder.build(
            surfaceDepth = selected.surfaceDepth,
            width = width,
            height = height,
            cutRight = selected.cutRight,
            cutDown = selected.cutDown,
            motionBasis = checkNotNull(selected.motionBasis),
            viewEnvelope = envelope,
            continuityLabels = continuityLabels
        )
        return Result(
            geometry = selected.copy(
                backgroundDepth = visibility.backgroundDepth,
                hiddenBackgroundMask = visibility.hiddenBackgroundMask,
                backgroundMotionBasis = visibility.backgroundMotionBasis
            ),
            viewEnvelope = envelope,
            motionCandidateId = selectedCandidate.candidate.id,
            mediumResidualWeight = selectedCandidate.candidate.mediumResidualWeight,
            motionGroupingApplied = false,
            motionGroupCount = 0,
            inpaintingOccluderMask = visibility.inpaintingOccluderMask
        )
    }

    /**
     * 在相同全局视差附近，优先保留跨多个局部块持续存在的深度变化，而不是由少量尖峰
     * 抬高全图分位跨度。该指标只读取连续深度运动场，不包含语义 ROI。
     */
    private fun localResponseScore(
        basis: SpatialScreenSpaceMotionBasis,
        amplitude: Float
    ): Float {
        val width = basis.width
        val height = basis.height
        val tileSize = max(8, max(width, height) / LOCAL_RESPONSE_TILES_PER_LONG_EDGE)
        val spans = ArrayList<Float>()
        val referenceAxisPixels = SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE *
            min(width, height).toFloat() / max(width, height)
        var top = 0
        while (top < height) {
            var left = 0
            while (left < width) {
                val right = min(width, left + tileSize)
                val bottom = min(height, top + tileSize)
                val sampleCount = (right - left) * (bottom - top)
                if (sampleCount >= 16) {
                    val values = FloatArray(sampleCount)
                    var target = 0
                    for (y in top until bottom) {
                        for (x in left until right) {
                            values[target++] = basis.horizontalX[y * width + x]
                        }
                    }
                    values.sort()
                    val lower = values[(values.lastIndex * 0.05f).toInt()]
                    val upper = values[(values.lastIndex * 0.95f).toInt()]
                    spans += (upper - lower) * amplitude * referenceAxisPixels
                }
                left += tileSize
            }
            top += tileSize
        }
        if (spans.isEmpty()) return 0f
        spans.sort()
        val median = spans[(spans.lastIndex * 0.50f).toInt()]
        val upperQuartile = spans[(spans.lastIndex * 0.75f).toInt()]
        return median + LOCAL_RESPONSE_UPPER_QUARTILE_WEIGHT * upperQuartile
    }

    /** 局部响应相差不足 1% 时不把离散分位抖动误当成真实质量差异。 */
    internal fun selectNearBestCandidateIndex(
        selectionSpans: FloatArray,
        localResponseScores: FloatArray
    ): Int {
        require(selectionSpans.isNotEmpty())
        require(selectionSpans.size == localResponseScores.size)
        val bestLocalResponse = localResponseScores.maxOrNull() ?: 0f
        val minimumEquivalentLocal = bestLocalResponse *
            (1f - LOCAL_RESPONSE_TIE_FRACTION)
        return selectionSpans.indices
            .filter { localResponseScores[it] >= minimumEquivalentLocal }
            .maxByOrNull { selectionSpans[it] }
            ?: error("没有局部响应候选")
    }

    /**
     * 深度模型在暗衣和反射处可能把同一人物内部误判成远背景。只有两端都落在同一可信
     * 人物实例时才删除该内部断边；外轮廓、人物之间的边界、孔洞和非人物区域不受影响。
     */
    private fun suppressTrustedInteriorCuts(
        width: Int,
        height: Int,
        acceptedMask: BooleanArray?,
        continuityLabels: ByteArray?,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ) {
        if (acceptedMask == null || continuityLabels == null) return
        fun sameTrustedSurface(first: Int, second: Int): Boolean {
            if (!acceptedMask[first] || !acceptedMask[second]) return false
            val label = continuityLabels[first].toInt() and 0xff
            return label != 0 && label == (continuityLabels[second].toInt() and 0xff)
        }
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val first = y * width + x
                if (sameTrustedSurface(first, first + 1)) {
                    cutRight[y * (width - 1) + x] = false
                }
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                val first = y * width + x
                if (sameTrustedSurface(first, first + width)) {
                    cutDown[y * width + x] = false
                }
            }
        }
    }

    /**
     * matting 漏掉的手指、透明物体边缘或小孔只在深度连续且没有遮挡 cut 时并入保护区。
     * 扩展半径有硬上限，不能沿平缓背景无限泛洪。
     */
    private fun expandDepthAffineProtection(
        sourceMask: BooleanArray?,
        depth: FloatArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): BooleanArray? {
        if (sourceMask == null) return null
        var current = sourceMask.copyOf()
        repeat(PROTECTION_EXPANSION_PASSES) {
            val next = current.copyOf()
            for (index in current.indices) {
                if (!current[index]) continue
                val x = index % width
                val y = index / width
                fun include(neighbour: Int, connected: Boolean) {
                    if (!connected || current[neighbour]) return
                    if (abs(depth[neighbour] - depth[index]) <=
                        PROTECTION_DEPTH_AFFINITY
                    ) {
                        next[neighbour] = true
                    }
                }
                if (x > 0) include(index - 1, !cutRight[y * (width - 1) + x - 1])
                if (x + 1 < width) include(index + 1, !cutRight[y * (width - 1) + x])
                if (y > 0) include(index - width, !cutDown[(y - 1) * width + x])
                if (y + 1 < height) include(index + width, !cutDown[y * width + x])
            }
            current = next
        }
        return current
    }

    private fun buildOcclusionGraph(
        depth: FloatArray,
        rawInverse: FloatArray?,
        width: Int,
        height: Int
    ): Pair<BooleanArray, BooleanArray> {
        val candidateRight = BooleanArray(height * (width - 1))
        val candidateDown = BooleanArray((height - 1) * width)
        val rightStrength = FloatArray(candidateRight.size)
        val downStrength = FloatArray(candidateDown.size)
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val first = y * width + x
                val second = first + 1
                val difference = abs(depth[first] - depth[second])
                val edge = y * (width - 1) + x
                rightStrength[edge] = difference
                candidateRight[edge] =
                    (rawInverse != null && rawOcclusion(
                        rawInverse[first], rawInverse[second]
                    )) ||
                    difference >= HARD_DEPTH_EDGE ||
                    (
                        difference >= MIN_TRANSITION_STEP &&
                            horizontalTwoSidedContrast(depth, width, y, x) >=
                            WIDE_TRANSITION_CONTRAST
                        )
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                val first = y * width + x
                val second = first + width
                val difference = abs(depth[first] - depth[second])
                val edge = y * width + x
                downStrength[edge] = difference
                candidateDown[edge] =
                    (rawInverse != null && rawOcclusion(
                        rawInverse[first], rawInverse[second]
                    )) ||
                    difference >= HARD_DEPTH_EDGE ||
                    (
                        difference >= MIN_TRANSITION_STEP &&
                            verticalTwoSidedContrast(depth, width, height, y, x) >=
                            WIDE_TRANSITION_CONTRAST
                        )
            }
        }
        return thinHorizontalRuns(candidateRight, rightStrength, width, height) to
            thinVerticalRuns(candidateDown, downStrength, width, height)
    }

    private fun horizontalTwoSidedContrast(
        depth: FloatArray,
        width: Int,
        y: Int,
        leftX: Int
    ): Float {
        var leftSum = 0f
        var leftCount = 0
        for (x in max(0, leftX - CONTRAST_RADIUS + 1)..leftX) {
            leftSum += depth[y * width + x]
            leftCount++
        }
        var rightSum = 0f
        var rightCount = 0
        for (x in leftX + 1..min(width - 1, leftX + CONTRAST_RADIUS)) {
            rightSum += depth[y * width + x]
            rightCount++
        }
        return abs(leftSum / leftCount - rightSum / rightCount)
    }

    private fun verticalTwoSidedContrast(
        depth: FloatArray,
        width: Int,
        height: Int,
        topY: Int,
        x: Int
    ): Float {
        var topSum = 0f
        var topCount = 0
        for (y in max(0, topY - CONTRAST_RADIUS + 1)..topY) {
            topSum += depth[y * width + x]
            topCount++
        }
        var bottomSum = 0f
        var bottomCount = 0
        for (y in topY + 1..min(height - 1, topY + CONTRAST_RADIUS)) {
            bottomSum += depth[y * width + x]
            bottomCount++
        }
        return abs(topSum / topCount - bottomSum / bottomCount)
    }

    /** 把宽过渡带的连续候选压成一条 ridge；横纵独立计算，不按处理顺序改写深度。 */
    private fun thinHorizontalRuns(
        candidates: BooleanArray,
        strength: FloatArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val result = BooleanArray(candidates.size)
        for (y in 0 until height) {
            var x = 0
            while (x < width - 1) {
                val edge = y * (width - 1) + x
                if (!candidates[edge]) {
                    x++
                    continue
                }
                val start = x
                while (x + 1 < width - 1 && candidates[y * (width - 1) + x + 1]) x++
                val end = x
                var best = (start + end) / 2
                var bestStrength = strength[y * (width - 1) + best]
                for (candidate in start..end) {
                    val value = strength[y * (width - 1) + candidate]
                    if (value > bestStrength + STRENGTH_TIE_EPSILON) {
                        best = candidate
                        bestStrength = value
                    }
                }
                result[y * (width - 1) + best] = true
                x++
            }
        }
        return result
    }

    private fun thinVerticalRuns(
        candidates: BooleanArray,
        strength: FloatArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val result = BooleanArray(candidates.size)
        for (x in 0 until width) {
            var y = 0
            while (y < height - 1) {
                val edge = y * width + x
                if (!candidates[edge]) {
                    y++
                    continue
                }
                val start = y
                while (y + 1 < height - 1 && candidates[(y + 1) * width + x]) y++
                val end = y
                var best = (start + end) / 2
                var bestStrength = strength[best * width + x]
                for (candidate in start..end) {
                    val value = strength[candidate * width + x]
                    if (value > bestStrength + STRENGTH_TIE_EPSILON) {
                        best = candidate
                        bestStrength = value
                    }
                }
                result[best * width + x] = true
                y++
            }
        }
        return result
    }

    /**
     * 每个 chart 先拟合最佳低频仿射深度平面，再让仿射与非仿射残差共享 1.5% 总预算。
     * chart 的平均深度不变，因此前景／背景等不同 chart 仍保留完整层间视差；单个 chart 内部
     * 不能再靠拉长或压扁可见内容制造空间感。
     */
    private fun limitChartResiduals(
        source: FloatArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        requestedMaximumParallax: Float,
        maximumLocalStrain: Float
    ): FloatArray {
        val model = SpatialChartMotionModel.fit(
            width,
            height,
            source,
            cutRight,
            cutDown
        )
        val affineGradients = model.affineGradientNorms()
        val residualGradients = model.residualGradientNorms(source)
        val affineScales = FloatArray(model.components.size)
        val residualScales = FloatArray(model.components.size)
        for (component in model.components.indices) {
            val affineRisk = requestedMaximumParallax * affineGradients[component]
            val limitedAffineRisk = min(affineRisk, MAX_AFFINE_STRAIN)
            affineScales[component] = if (affineRisk <= limitedAffineRisk) {
                1f
            } else {
                limitedAffineRisk / affineRisk
            }
            val residualBudget = (
                min(maximumLocalStrain, MAX_TOTAL_STRAIN) - limitedAffineRisk
                ).coerceAtLeast(0f)
            val residualRisk = requestedMaximumParallax * residualGradients[component]
            residualScales[component] = if (residualRisk <= residualBudget) {
                1f
            } else if (residualRisk > 0f) {
                residualBudget / residualRisk
            } else {
                1f
            }
        }
        return FloatArray(source.size) { index ->
            val component = model.labels[index]
            val originalPlane = model.planeDepth(index)
            val limitedPlane = model.planeDepth(index, affineScales[component])
            (
                limitedPlane +
                    (source[index] - originalPlane) * residualScales[component]
                ).coerceIn(0f, 1f)
        }
    }

    /** 4 邻域、双缓冲、同权重，保持 90° 旋转和转置等变；硬深度边不跨越。 */
    private fun denoiseIsotropically(
        source: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        // 先用十字 5 点中位数移除孤立坏点；真实阶跃两侧各有纵向同侧样本，不会被
        // 跨边抹掉。随后再做一次有深度范围门控的旋转等变低通。
        var current = medianCardinal(source, width, height)
        repeat(DENOISE_PASSES) {
            val next = FloatArray(current.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val center = current[index]
                    var sum = center * DENOISE_CENTER_WEIGHT
                    var weight = DENOISE_CENTER_WEIGHT
                    fun include(neighbor: Int) {
                        val value = current[neighbor]
                        if (abs(value - center) <= DENOISE_RANGE) {
                            sum += value
                            weight += 1f
                        }
                    }
                    if (x > 0) include(index - 1)
                    if (x + 1 < width) include(index + 1)
                    if (y > 0) include(index - width)
                    if (y + 1 < height) include(index + width)
                    next[index] = sum / weight
                }
            }
            current = next
        }
        return current
    }

    private fun medianCardinal(
        source: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val result = FloatArray(source.size)
        val samples = FloatArray(5)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = source[index]
                samples[0] = center
                samples[1] = if (x > 0) source[index - 1] else center
                samples[2] = if (x + 1 < width) source[index + 1] else center
                samples[3] = if (y > 0) source[index - width] else center
                samples[4] = if (y + 1 < height) source[index + width] else center
                samples.sort()
                result[index] = samples[2]
            }
        }
        return result
    }

    private fun rawOcclusion(first: Float, second: Float): Boolean {
        val near = max(first, second)
        val far = min(first, second)
        if (near <= RAW_INFINITY_EPSILON) return false
        if (far <= RAW_INFINITY_EPSILON) return true
        return near / far >= OCCLUSION_DEPTH_RATIO
    }

    private fun upsampleGrid(
        values: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val result = FloatArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = (targetY + 0.5f) * sourceHeight / targetHeight - 0.5f
            val y0 = kotlin.math.floor(sourceY).toInt().coerceIn(0, sourceHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val fy = (sourceY - y0).coerceIn(0f, 1f)
            for (targetX in 0 until targetWidth) {
                val sourceX = (targetX + 0.5f) * sourceWidth / targetWidth - 0.5f
                val x0 = kotlin.math.floor(sourceX).toInt().coerceIn(0, sourceWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val fx = (sourceX - x0).coerceIn(0f, 1f)
                val top = lerp(values[y0 * sourceWidth + x0], values[y0 * sourceWidth + x1], fx)
                val bottom = lerp(
                    values[y1 * sourceWidth + x0],
                    values[y1 * sourceWidth + x1],
                    fx
                )
                result[targetY * targetWidth + targetX] = lerp(top, bottom, fy)
            }
        }
        return result
    }

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private const val HARD_DEPTH_EDGE = 0.045f
    private const val WIDE_TRANSITION_CONTRAST = 0.055f
    private const val MIN_TRANSITION_STEP = 0.0025f
    private const val CONTRAST_RADIUS = 3
    private const val STRENGTH_TIE_EPSILON = 1e-6f
    private const val OCCLUSION_DEPTH_RATIO = 1.2f
    private const val RAW_INFINITY_EPSILON = 1e-6f
    private const val DENOISE_PASSES = 2
    private const val DENOISE_CENTER_WEIGHT = 4f
    private const val DENOISE_RANGE = 0.08f
    private const val PROTECTION_EXPANSION_PASSES = 4
    private const val PROTECTION_DEPTH_AFFINITY = 0.055f
    private const val PARALLAX_TIE_TOLERANCE_PX = 1f
    private const val MIN_PROXY_GRADIENT = 1e-6f
    private const val MIN_PROXY_SPAN = 1e-5f
    private const val LOCAL_RESPONSE_TILES_PER_LONG_EDGE = 8
    private const val LOCAL_RESPONSE_UPPER_QUARTILE_WEIGHT = 0.5f
    private const val LOCAL_RESPONSE_TIE_FRACTION = 0.01f
}
