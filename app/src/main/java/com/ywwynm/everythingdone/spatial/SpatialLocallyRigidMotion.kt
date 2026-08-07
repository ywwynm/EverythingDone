package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕空间二维视点的线性位移基。
 *
 * [horizontalX]/[horizontalY] 表示单位横向视差对 UV 的二维位移系数，
 * [verticalX]/[verticalY] 表示单位纵向视差。运行时只需按视点线性组合；因此同一
 * 视点可重复、环形轨迹可闭合，也不需要逐帧运行模型或求解器。
 */
data class SpatialScreenSpaceMotionBasis(
    val width: Int,
    val height: Int,
    val horizontalX: FloatArray,
    val horizontalY: FloatArray,
    val verticalX: FloatArray,
    val verticalY: FloatArray
) {
    init {
        require(width > 1 && height > 1)
        val size = width * height
        require(horizontalX.size == size)
        require(horizontalY.size == size)
        require(verticalX.size == size)
        require(verticalY.size == size)
        require(
            horizontalX.all(Float::isFinite) &&
                horizontalY.all(Float::isFinite) &&
                verticalX.all(Float::isFinite) &&
                verticalY.all(Float::isFinite)
        ) { "空间位移基包含无效数值" }
    }

    data class Displacement(val x: Float, val y: Float)

    internal data class Distortion(
        val nonSimilarityCoefficient: Float,
        val scaleCoefficient: Float
    )

    /**
     * 纯标量深度场候选的低成本、方向保守评分。
     *
     * 连续运动候选满足 horizontalX == verticalY 且两个交叉分量为零。候选筛选阶段
     * 无需为每个候选重复构建 16 方向完整包络：先对标量场只计算一次稳健跨度，并以
     * 每个网格单元在任意方向下可能出现的最大形变作为保守代理。入选候选仍由完整包络
     * 做最终校验，因此该代理不会放宽渲染安全边界。
     */
    internal data class ScalarSelectionProxy(
        val spanCoefficients: FloatArray,
        val nonSimilarityCoefficients: FloatArray,
        val scaleCoefficients: FloatArray
    )

    fun displacement(
        index: Int,
        viewpointX: Float,
        viewpointY: Float,
        amplitude: Float
    ): Displacement {
        require(index in 0 until width * height)
        return Displacement(
            x = amplitude * (
                viewpointX * horizontalX[index] + viewpointY * verticalX[index]
                ),
            y = amplitude * (
                viewpointX * horizontalY[index] + viewpointY * verticalY[index]
                )
        )
    }

    /** 16 个方向中 p99.5 的最大局部非相似形变；显式遮挡断边不参与统计。 */
    fun maximumNonSimilarityStrain(
        amplitude: Float,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Float {
        var maximum = 0f
        repeat(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
            val angle = direction * FULL_TURN / SpatialViewEnvelope.DIRECTION_COUNT
            val distortion = distortion(
                viewpointX = kotlin.math.cos(angle),
                viewpointY = kotlin.math.sin(angle),
                cutRight = cutRight,
                cutDown = cutDown
            )
            maximum = max(maximum, amplitude * distortion.nonSimilarityCoefficient)
        }
        return maximum
    }

    internal fun distortion(
        viewpointX: Float,
        viewpointY: Float,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Distortion {
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)
        val nonSimilarity = FloatArray((width - 1) * (height - 1))
        val scale = FloatArray(nonSimilarity.size)
        var count = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val index = y * width + x
                if (cutRight[y * (width - 1) + x] || cutDown[y * width + x]) continue
                val right = index + 1
                val down = index + width
                val centerX = coefficientX(index, viewpointX, viewpointY)
                val centerY = coefficientY(index, viewpointX, viewpointY)
                val gxx = (coefficientX(right, viewpointX, viewpointY) - centerX) *
                    (width - 1)
                val gyx = (coefficientY(right, viewpointX, viewpointY) - centerY) *
                    (height - 1)
                val gxy = (coefficientX(down, viewpointX, viewpointY) - centerX) *
                    (width - 1)
                val gyy = (coefficientY(down, viewpointX, viewpointY) - centerY) *
                    (height - 1)
                val deviatoricScale = 0.5f * (gxx - gyy)
                val symmetricShear = 0.5f * (gxy + gyx)
                nonSimilarity[count] = hypot(deviatoricScale, symmetricShear)
                scale[count] = abs(0.5f * (gxx + gyy))
                count++
            }
        }
        return Distortion(
            nonSimilarityCoefficient = percentile(nonSimilarity, count),
            scaleCoefficient = percentile(scale, count)
        )
    }

    /**
     * 单位幅度在网格长边坐标中的 p5--p95 有效视差。
     *
     * 横纵 UV 的物理像素尺度不同；先换算到网格长边，再投影到当前视点方向，避免同一张
     * 竖图横向、纵向强度不一致，也避免不同宽高比用同一个 UV 常量得到完全不同的观感。
     */
    internal fun robustProjectedSpanCoefficient(
        viewpointX: Float,
        viewpointY: Float
    ): Float {
        val radius = hypot(viewpointX, viewpointY)
        if (radius <= 1e-6f) return 0f
        val directionX = viewpointX / radius
        val directionY = viewpointY / radius
        val longEdge = max(width - 1, height - 1).toFloat()
        val xScale = (width - 1) / longEdge
        val yScale = (height - 1) / longEdge
        val projected = FloatArray(width * height) { index ->
            val x = coefficientX(index, directionX, directionY) * xScale
            val y = coefficientY(index, directionX, directionY) * yScale
            directionX * x + directionY * y
        }
        val lower = selectKth(
            projected,
            ((projected.lastIndex) * ROBUST_SPAN_LOWER).toInt(),
            projected.size
        )
        val upper = selectKth(
            projected,
            ((projected.lastIndex) * ROBUST_SPAN_UPPER).toInt(),
            projected.size
        )
        return (upper - lower).coerceAtLeast(0f)
    }

    internal fun scalarSelectionProxy(
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): ScalarSelectionProxy {
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)
        val sampleStride = max(1, max(width, height) / SELECTION_PROXY_SAMPLES_PER_LONG_EDGE)
        val scalar = FloatArray(
            ((width + sampleStride - 1) / sampleStride) *
                ((height + sampleStride - 1) / sampleStride)
        )
        var scalarCount = 0
        for (y in 0 until height step sampleStride) {
            for (x in 0 until width step sampleStride) {
                scalar[scalarCount++] = horizontalX[y * width + x]
            }
        }
        val lower = selectKth(
            scalar,
            ((scalarCount - 1) * ROBUST_SPAN_LOWER).toInt(),
            scalarCount
        )
        val upper = selectKth(
            scalar,
            ((scalarCount - 1) * ROBUST_SPAN_UPPER).toInt(),
            scalarCount
        )
        val scalarSpan = (upper - lower).coerceAtLeast(0f)
        val maximumSampleCount =
            ((width - 1 + sampleStride - 1) / sampleStride) *
                ((height - 1 + sampleStride - 1) / sampleStride)
        val nonSimilarity = FloatArray(maximumSampleCount)
        val scale = FloatArray(maximumSampleCount)
        val horizontalScale = (width - 1).toFloat()
        val verticalScale = (height - 1).toFloat()
        val longEdge = max(width - 1, height - 1).toFloat()
        val xProjectionScale = (width - 1) / longEdge
        val yProjectionScale = (height - 1) / longEdge
        val spans = FloatArray(SELECTION_PROXY_DIRECTION_COUNT)
        val nonSimilarityCoefficients = FloatArray(SELECTION_PROXY_DIRECTION_COUNT)
        val scaleCoefficients = FloatArray(SELECTION_PROXY_DIRECTION_COUNT)
        repeat(SELECTION_PROXY_DIRECTION_COUNT) { direction ->
            val angle = direction * FULL_TURN / SpatialViewEnvelope.DIRECTION_COUNT
            val viewpointX = kotlin.math.cos(angle)
            val viewpointY = kotlin.math.sin(angle)
            var count = 0
            for (y in 0 until height - 1 step sampleStride) {
                for (x in 0 until width - 1 step sampleStride) {
                    if (cutRight[y * (width - 1) + x] || cutDown[y * width + x]) continue
                    val index = y * width + x
                    val dx = horizontalX[index + 1] - horizontalX[index]
                    val dy = horizontalX[index + width] - horizontalX[index]
                    val gxx = viewpointX * dx * horizontalScale
                    val gyx = viewpointY * dx * verticalScale
                    val gxy = viewpointX * dy * horizontalScale
                    val gyy = viewpointY * dy * verticalScale
                    nonSimilarity[count] = hypot(
                        0.5f * (gxx - gyy),
                        0.5f * (gxy + gyx)
                    )
                    scale[count] = abs(0.5f * (gxx + gyy))
                    count++
                }
            }
            spans[direction] = scalarSpan * (
                viewpointX * viewpointX * xProjectionScale +
                    viewpointY * viewpointY * yProjectionScale
                )
            nonSimilarityCoefficients[direction] = percentile(nonSimilarity, count)
            scaleCoefficients[direction] = percentile(scale, count)
        }
        return ScalarSelectionProxy(
            spanCoefficients = spans,
            nonSimilarityCoefficients = nonSimilarityCoefficients,
            scaleCoefficients = scaleCoefficients
        )
    }

    private fun coefficientX(index: Int, viewpointX: Float, viewpointY: Float): Float =
        viewpointX * horizontalX[index] + viewpointY * verticalX[index]

    private fun coefficientY(index: Int, viewpointX: Float, viewpointY: Float): Float =
        viewpointX * horizontalY[index] + viewpointY * verticalY[index]

    private fun percentile(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        return selectKth(
            values,
            ((count - 1) * DISTORTION_PERCENTILE).toInt(),
            count
        )
    }

    /** 原地选择第 k 个顺序统计量，避免每个候选、每个方向都完整排序整张网格。 */
    private fun selectKth(values: FloatArray, target: Int, count: Int): Float {
        require(target in 0 until count)
        var left = 0
        var right = count - 1
        while (left < right) {
            val middle = (left + right) ushr 1
            val pivot = medianOfThree(values[left], values[middle], values[right])
            var lower = left
            var upper = right
            while (lower <= upper) {
                while (values[lower] < pivot) lower++
                while (values[upper] > pivot) upper--
                if (lower <= upper) {
                    val temporary = values[lower]
                    values[lower] = values[upper]
                    values[upper] = temporary
                    lower++
                    upper--
                }
            }
            when {
                target <= upper -> right = upper
                target >= lower -> left = lower
                else -> return values[target]
            }
        }
        return values[target]
    }

    private fun medianOfThree(first: Float, second: Float, third: Float): Float = when {
        first < second -> if (second < third) second else max(first, third)
        else -> if (first < third) first else max(second, third)
    }

    private companion object {
        private const val DISTORTION_PERCENTILE = 0.995f
        private const val ROBUST_SPAN_LOWER = 0.05f
        private const val ROBUST_SPAN_UPPER = 0.95f
        private const val SELECTION_PROXY_DIRECTION_COUNT =
            SpatialViewEnvelope.DIRECTION_COUNT / 2
        private const val SELECTION_PROXY_SAMPLES_PER_LONG_EDGE = 128
        private const val FULL_TURN = (2.0 * Math.PI).toFloat()
    }
}

/**
 * 用稀疏深度锚点和局部 similarity MLS 构造主体位移场。
 *
 * 单目深度仍决定宏观前后关系，但不再把每个像素的深度误差直接兑换成横向拉伸。
 * MLS 在每个位置拟合局部平移、旋转和等比缩放；主体可以有连续体积响应，同时脸、
 * 文字和规则轮廓不会退化成单轴橡皮形变。matting 只决定保护权重，不能创建断边。
 */
object SpatialLocallyRigidMotionBuilder {

    fun build(
        width: Int,
        height: Int,
        targetDepth: FloatArray,
        protectedMotionDepth: FloatArray? = null,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        protectedMask: BooleanArray?,
        requestedMaximumAmplitude: Float = DEFAULT_REQUESTED_MAXIMUM_AMPLITUDE,
        maximumNonSimilarityStrain: Float = DEFAULT_PROTECTED_NON_SIMILARITY_STRAIN,
        maximumScaleStrain: Float = DEFAULT_PROTECTED_SCALE_STRAIN
    ): SpatialScreenSpaceMotionBasis {
        require(width > 1 && height > 1)
        require(targetDepth.size == width * height)
        require(protectedMotionDepth == null || protectedMotionDepth.size == targetDepth.size)
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)
        require(protectedMask == null || protectedMask.size == targetDepth.size)
        require(requestedMaximumAmplitude in 0.01f..0.20f)
        require(maximumNonSimilarityStrain in 0.005f..0.10f)
        require(maximumScaleStrain in 0.005f..0.10f)

        val horizontalX = FloatArray(targetDepth.size) { targetDepth[it] - 0.5f }
        val horizontalY = FloatArray(targetDepth.size)
        val verticalX = FloatArray(targetDepth.size)
        val verticalY = FloatArray(targetDepth.size) { targetDepth[it] - 0.5f }
        if (protectedMask == null || protectedMask.none { it }) {
            return SpatialScreenSpaceMotionBasis(
                width, height, horizontalX, horizontalY, verticalX, verticalY
            )
        }

        val components = labelProtectedComponents(
            protectedMask, width, height, cutRight, cutDown
        )
        val influence = buildInfluence(
            protectedMask = protectedMask,
            componentLabels = components.labels,
            width = width,
            height = height,
            cutRight = cutRight,
            cutDown = cutDown
        )
        val smoothedDepth = smoothProtectedDepth(
            targetDepth = protectedMotionDepth ?: targetDepth,
            labels = components.labels,
            width = width,
            height = height,
            cutRight = cutRight,
            cutDown = cutDown
        )
        val rigidHorizontalX = horizontalX.copyOf()
        val rigidHorizontalY = horizontalY.copyOf()
        val rigidVerticalX = verticalX.copyOf()
        val rigidVerticalY = verticalY.copyOf()

        for (component in components.indices.indices) {
            val indices = components.indices[component]
            if (indices.size < MIN_COMPONENT_PIXELS) continue
            val referenceCoefficient = medianOf(targetDepth, indices) - 0.5f
            val anchors = buildAnchors(
                component = component,
                componentIndices = indices,
                labels = components.labels,
                smoothedDepth = smoothedDepth,
                referenceDepth = targetDepth,
                width = width,
                height = height
            )
            if (anchors.size < MIN_ANCHORS) continue
            for (index in targetDepth.indices) {
                if (influence.owner[index] != component) continue
                val similarity = similarityBasisAt(index, anchors, width, height)
                val blend = influence.weight[index]
                rigidHorizontalX[index] = lerp(
                    rigidHorizontalX[index], referenceCoefficient, blend
                )
                rigidHorizontalY[index] = lerp(rigidHorizontalY[index], 0f, blend)
                rigidVerticalX[index] = lerp(rigidVerticalX[index], 0f, blend)
                rigidVerticalY[index] = lerp(
                    rigidVerticalY[index], referenceCoefficient, blend
                )
                horizontalX[index] = lerp(horizontalX[index], similarity.horizontalX, blend)
                horizontalY[index] = lerp(horizontalY[index], similarity.horizontalY, blend)
                verticalX[index] = lerp(verticalX[index], similarity.verticalX, blend)
                verticalY[index] = lerp(verticalY[index], similarity.verticalY, blend)
            }
        }
        val rigidBasis = SpatialScreenSpaceMotionBasis(
            width,
            height,
            rigidHorizontalX,
            rigidHorizontalY,
            rigidVerticalX,
            rigidVerticalY
        )
        val candidateBasis = SpatialScreenSpaceMotionBasis(
            width, height, horizontalX, horizontalY, verticalX, verticalY
        )
        return fitProtectedResidualToBudget(
            rigidBasis = rigidBasis,
            candidateBasis = candidateBasis,
            requestedMaximumAmplitude = requestedMaximumAmplitude,
            maximumNonSimilarityStrain = maximumNonSimilarityStrain,
            maximumScaleStrain = maximumScaleStrain,
            cutRight = cutRight,
            cutDown = cutDown
        )
    }

    private fun labelProtectedComponents(
        mask: BooleanArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Components {
        val labels = IntArray(mask.size) { -1 }
        val result = ArrayList<IntArray>()
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || labels[start] >= 0) continue
            val component = result.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = component
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                fun offer(neighbour: Int, connected: Boolean) {
                    if (!connected || !mask[neighbour] || labels[neighbour] >= 0) return
                    labels[neighbour] = component
                    queue[tail++] = neighbour
                }
                if (x > 0) offer(index - 1, !cutRight[y * (width - 1) + x - 1])
                if (x + 1 < width) offer(index + 1, !cutRight[y * (width - 1) + x])
                if (y > 0) offer(index - width, !cutDown[(y - 1) * width + x])
                if (y + 1 < height) offer(index + width, !cutDown[y * width + x])
            }
            result += queue.copyOf(tail)
        }
        return Components(labels, result)
    }

    private fun buildInfluence(
        protectedMask: BooleanArray,
        componentLabels: IntArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Influence {
        val owner = IntArray(protectedMask.size) { componentLabels[it] }
        val distance = IntArray(protectedMask.size) { Int.MAX_VALUE }
        val queue: ArrayDeque<Int> = ArrayDeque()
        for (index in protectedMask.indices) {
            if (!protectedMask[index]) continue
            distance[index] = 0
            queue.addLast(index)
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val nextDistance = distance[index] + 1
            if (nextDistance > TRANSITION_CELLS) continue
            val x = index % width
            val y = index / width
            fun offer(neighbour: Int, connected: Boolean) {
                if (!connected || protectedMask[neighbour]) return
                if (nextDistance >= distance[neighbour]) return
                distance[neighbour] = nextDistance
                owner[neighbour] = owner[index]
                queue.addLast(neighbour)
            }
            if (x > 0) offer(index - 1, !cutRight[y * (width - 1) + x - 1])
            if (x + 1 < width) offer(index + 1, !cutRight[y * (width - 1) + x])
            if (y > 0) offer(index - width, !cutDown[(y - 1) * width + x])
            if (y + 1 < height) offer(index + width, !cutDown[y * width + x])
        }
        val weight = FloatArray(protectedMask.size) { index ->
            when {
                distance[index] <= FULL_INFLUENCE_CELLS -> 1f
                distance[index] > TRANSITION_CELLS -> 0f
                else -> {
                    val linear = 1f -
                        (distance[index] - FULL_INFLUENCE_CELLS).toFloat() /
                        (TRANSITION_CELLS - FULL_INFLUENCE_CELLS + 1f)
                    linear * linear * (3f - 2f * linear)
                }
            }
        }
        return Influence(owner, weight)
    }

    private fun smoothProtectedDepth(
        targetDepth: FloatArray,
        labels: IntArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): FloatArray {
        // 这里提取的是“人物整体体积”，不是保留衣服纹理或局部深度台阶。短程去噪仍会让
        // 少数头部／衣服锚点形成很大的导数，随后统一形变预算会把整个人物压回纸片。
        // vNext 网格长边固定为 256；长程、弱数据项扩散把证据摊到约十几个网格的尺度，
        // 同时严格受组件和 cut 约束，不跨人物外轮廓或真实遮挡边传播。
        var current = targetDepth.copyOf()
        repeat(SMOOTHING_PASSES) {
            val next = current.copyOf()
            for (index in labels.indices) {
                val component = labels[index]
                if (component < 0) continue
                val x = index % width
                val y = index / width
                var sum = targetDepth[index] * DEPTH_DATA_WEIGHT
                var weight = DEPTH_DATA_WEIGHT
                fun include(neighbour: Int, connected: Boolean) {
                    if (!connected || labels[neighbour] != component) return
                    sum += current[neighbour]
                    weight += 1f
                }
                if (x > 0) include(index - 1, !cutRight[y * (width - 1) + x - 1])
                if (x + 1 < width) include(index + 1, !cutRight[y * (width - 1) + x])
                if (y > 0) include(index - width, !cutDown[(y - 1) * width + x])
                if (y + 1 < height) include(index + width, !cutDown[y * width + x])
                next[index] = sum / weight
            }
            current = next
        }
        return current
    }

    private fun buildAnchors(
        component: Int,
        componentIndices: IntArray,
        labels: IntArray,
        smoothedDepth: FloatArray,
        referenceDepth: FloatArray,
        width: Int,
        height: Int
    ): List<Anchor> {
        val componentDepths = componentIndices.map { smoothedDepth[it] }.sorted()
        val median = componentDepths[componentDepths.size / 2]
        val referenceMedian = medianOf(referenceDepth, componentIndices)
        val lower = componentDepths[(componentDepths.lastIndex * ROBUST_LOWER).toInt()]
        val upper = componentDepths[(componentDepths.lastIndex * ROBUST_UPPER).toInt()]
        val result = ArrayList<Anchor>()
        var tileTop = 0
        while (tileTop < height) {
            var tileLeft = 0
            while (tileLeft < width) {
                val tileRight = min(width, tileLeft + ANCHOR_SPACING)
                val tileBottom = min(height, tileTop + ANCHOR_SPACING)
                var best = -1
                var bestDistance = Int.MAX_VALUE
                val tileDepths = ArrayList<Float>()
                val centerX = (tileLeft + tileRight - 1) / 2
                val centerY = (tileTop + tileBottom - 1) / 2
                for (y in tileTop until tileBottom) {
                    for (x in tileLeft until tileRight) {
                        val index = y * width + x
                        if (labels[index] != component) continue
                        tileDepths += smoothedDepth[index]
                        val distance = abs(x - centerX) + abs(y - centerY)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            best = index
                        }
                    }
                }
                if (best >= 0) {
                    tileDepths.sort()
                    val localMedian = tileDepths[tileDepths.size / 2].coerceIn(lower, upper)
                    val protectedDepth = referenceMedian +
                        (localMedian - median) * SUBJECT_MACRO_DEPTH_GAIN
                    result += Anchor(
                        x = best % width,
                        y = best / width,
                        depthCoefficient = protectedDepth - 0.5f
                    )
                }
                tileLeft += ANCHOR_SPACING
            }
            tileTop += ANCHOR_SPACING
        }
        return stabilizeAnchorDepths(result)
    }

    /**
     * 深度模型在暗衣、玻璃和反射处会产生小块极端值。锚点网格上的稳健中值只删除空间上
     * 孤立的深度脉冲；覆盖多个相邻锚点的头部、躯干和手臂低频起伏仍会保留。
     */
    private fun stabilizeAnchorDepths(source: List<Anchor>): List<Anchor> {
        var current = source
        repeat(ANCHOR_MEDIAN_PASSES) {
            current = current.map { anchor ->
                val neighbours = current.asSequence()
                    .filter {
                        abs(it.x - anchor.x) <= ANCHOR_NEIGHBOUR_RADIUS &&
                            abs(it.y - anchor.y) <= ANCHOR_NEIGHBOUR_RADIUS
                    }
                    .map { it.depthCoefficient }
                    .sorted()
                    .toList()
                if (neighbours.size < MIN_ANCHOR_NEIGHBOURS) {
                    anchor
                } else {
                    val median = neighbours[neighbours.size / 2]
                    anchor.copy(
                        depthCoefficient = lerp(
                            anchor.depthCoefficient,
                            median,
                            ANCHOR_MEDIAN_BLEND
                        )
                    )
                }
            }
        }
        return current
    }

    private fun medianOf(values: FloatArray, indices: IntArray): Float {
        val sorted = FloatArray(indices.size) { values[indices[it]] }
        sorted.sort()
        return sorted[sorted.size / 2]
    }

    /**
     * 只缩放受保护主体相对于近刚性基线的宏观体积残差。主体／背景均值视差不参与缩放；
     * 因此即使原始单目深度噪声很大，也不会通过降低整幅请求幅度来换取稳定。
     */
    private fun fitProtectedResidualToBudget(
        rigidBasis: SpatialScreenSpaceMotionBasis,
        candidateBasis: SpatialScreenSpaceMotionBasis,
        requestedMaximumAmplitude: Float,
        maximumNonSimilarityStrain: Float,
        maximumScaleStrain: Float,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): SpatialScreenSpaceMotionBasis {
        val baseline = Array(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
            val angle = direction * FULL_TURN / SpatialViewEnvelope.DIRECTION_COUNT
            rigidBasis.distortion(
                viewpointX = kotlin.math.cos(angle),
                viewpointY = kotlin.math.sin(angle),
                cutRight = cutRight,
                cutDown = cutDown
            )
        }
        fun withinBudget(basis: SpatialScreenSpaceMotionBasis): Boolean {
            repeat(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
                val angle = direction * FULL_TURN / SpatialViewEnvelope.DIRECTION_COUNT
                val distortion = basis.distortion(
                    viewpointX = kotlin.math.cos(angle),
                    viewpointY = kotlin.math.sin(angle),
                    cutRight = cutRight,
                    cutDown = cutDown
                )
                val allowedNonSimilarity = max(
                    baseline[direction].nonSimilarityCoefficient,
                    maximumNonSimilarityStrain / requestedMaximumAmplitude
                )
                val allowedScale = max(
                    baseline[direction].scaleCoefficient,
                    maximumScaleStrain / requestedMaximumAmplitude
                )
                if (distortion.nonSimilarityCoefficient > allowedNonSimilarity + BUDGET_EPSILON ||
                    distortion.scaleCoefficient > allowedScale + BUDGET_EPSILON
                ) {
                    return false
                }
            }
            return true
        }
        if (withinBudget(candidateBasis)) return candidateBasis
        var lower = 0f
        var upper = 1f
        repeat(BUDGET_SEARCH_STEPS) {
            val middle = (lower + upper) * 0.5f
            if (withinBudget(interpolateBasis(rigidBasis, candidateBasis, middle))) {
                lower = middle
            } else {
                upper = middle
            }
        }
        return interpolateBasis(rigidBasis, candidateBasis, lower)
    }

    private fun interpolateBasis(
        rigid: SpatialScreenSpaceMotionBasis,
        candidate: SpatialScreenSpaceMotionBasis,
        fraction: Float
    ): SpatialScreenSpaceMotionBasis {
        fun interpolate(first: FloatArray, second: FloatArray): FloatArray =
            FloatArray(first.size) { index -> lerp(first[index], second[index], fraction) }
        return SpatialScreenSpaceMotionBasis(
            width = rigid.width,
            height = rigid.height,
            horizontalX = interpolate(rigid.horizontalX, candidate.horizontalX),
            horizontalY = interpolate(rigid.horizontalY, candidate.horizontalY),
            verticalX = interpolate(rigid.verticalX, candidate.verticalX),
            verticalY = interpolate(rigid.verticalY, candidate.verticalY)
        )
    }

    private fun similarityBasisAt(
        index: Int,
        anchors: List<Anchor>,
        width: Int,
        height: Int
    ): SimilarityBasis {
        val longEdge = max(width, height) - 1f
        val xScale = (width - 1f) / longEdge
        val yScale = (height - 1f) / longEdge
        val vx = (index % width).toFloat() / longEdge
        val vy = (index / width).toFloat() / longEdge
        // 不能让查询点恰好落在锚点上时权重趋近无穷。近奇异的 1/d² 核会把
        // 平滑深度场变成锚点中心的导数尖峰，随后全局形变预算只能压低全部体积响应。
        // 以锚点间距为尺度的有限核仍保留局部性，同时让相邻锚点平滑接管运动场。
        val kernelRadius = ANCHOR_SPACING * MLS_KERNEL_RADIUS_IN_SPACINGS / longEdge
        val kernelRadiusSquared = kernelRadius * kernelRadius
        fun weight(dx: Float, dy: Float): Double {
            val dxDouble = dx.toDouble()
            val dyDouble = dy.toDouble()
            return 1.0 / (
                dxDouble * dxDouble + dyDouble * dyDouble + kernelRadiusSquared
                )
        }
        var totalWeight = 0.0
        var meanPx = 0.0
        var meanPy = 0.0
        for (anchor in anchors) {
            val px = anchor.x / longEdge
            val py = anchor.y / longEdge
            val dx = vx - px
            val dy = vy - py
            val localWeight = weight(dx, dy)
            totalWeight += localWeight
            meanPx += localWeight * px
            meanPy += localWeight * py
        }
        meanPx /= totalWeight
        meanPy /= totalWeight

        fun solve(horizontal: Boolean): Pair<Double, Double> {
            var meanQx = 0.0
            var meanQy = 0.0
            for (anchor in anchors) {
                val px = anchor.x / longEdge
                val py = anchor.y / longEdge
                val dx = vx - px
                val dy = vy - py
                val localWeight = weight(dx, dy)
                val shiftX = if (horizontal) anchor.depthCoefficient * xScale else 0f
                val shiftY = if (horizontal) 0f else anchor.depthCoefficient * yScale
                meanQx += localWeight * (px + shiftX)
                meanQy += localWeight * (py + shiftY)
            }
            meanQx /= totalWeight
            meanQy /= totalWeight
            var denominator = 0.0
            var numeratorA = 0.0
            var numeratorB = 0.0
            for (anchor in anchors) {
                val px = anchor.x / longEdge
                val py = anchor.y / longEdge
                val dx = vx - px
                val dy = vy - py
                val localWeight = weight(dx, dy)
                val pHatX = px - meanPx
                val pHatY = py - meanPy
                val shiftX = if (horizontal) anchor.depthCoefficient * xScale else 0f
                val shiftY = if (horizontal) 0f else anchor.depthCoefficient * yScale
                val qHatX = px + shiftX - meanQx
                val qHatY = py + shiftY - meanQy
                denominator += localWeight * (pHatX * pHatX + pHatY * pHatY)
                numeratorA += localWeight * (pHatX * qHatX + pHatY * qHatY)
                numeratorB += localWeight * (pHatX * qHatY - pHatY * qHatX)
            }
            if (denominator <= MLS_EPSILON) {
                return (meanQx - meanPx) to (meanQy - meanPy)
            }
            val a = numeratorA / denominator
            val b = numeratorB / denominator
            val relativeX = vx - meanPx
            val relativeY = vy - meanPy
            val mappedX = meanQx + a * relativeX - b * relativeY
            val mappedY = meanQy + b * relativeX + a * relativeY
            return mappedX - vx to mappedY - vy
        }

        val horizontal = solve(horizontal = true)
        val vertical = solve(horizontal = false)
        return SimilarityBasis(
            horizontalX = (horizontal.first / xScale).toFloat(),
            horizontalY = (horizontal.second / yScale).toFloat(),
            verticalX = (vertical.first / xScale).toFloat(),
            verticalY = (vertical.second / yScale).toFloat()
        )
    }

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private data class Components(val labels: IntArray, val indices: List<IntArray>)
    private data class Influence(val owner: IntArray, val weight: FloatArray)
    private data class Anchor(val x: Int, val y: Int, val depthCoefficient: Float)
    private data class SimilarityBasis(
        val horizontalX: Float,
        val horizontalY: Float,
        val verticalX: Float,
        val verticalY: Float
    )

    private const val ANCHOR_SPACING = 8
    private const val TRANSITION_CELLS = 4
    private const val FULL_INFLUENCE_CELLS = 2
    private const val SMOOTHING_PASSES = 256
    private const val DEPTH_DATA_WEIGHT = 0.002f
    private const val SUBJECT_MACRO_DEPTH_GAIN = 1f
    private const val ROBUST_LOWER = 0.08f
    private const val ROBUST_UPPER = 0.92f
    private const val MIN_COMPONENT_PIXELS = 24
    private const val MIN_ANCHORS = 3
    private const val ANCHOR_MEDIAN_PASSES = 2
    private const val ANCHOR_NEIGHBOUR_RADIUS = ANCHOR_SPACING + ANCHOR_SPACING / 2
    private const val MIN_ANCHOR_NEIGHBOURS = 4
    private const val ANCHOR_MEDIAN_BLEND = 0.72f
    private const val MLS_KERNEL_RADIUS_IN_SPACINGS = 8.0
    private const val MLS_EPSILON = 1e-8
    private const val DEFAULT_REQUESTED_MAXIMUM_AMPLITUDE = 0.12f
    private const val DEFAULT_PROTECTED_NON_SIMILARITY_STRAIN = 0.015f
    private const val DEFAULT_PROTECTED_SCALE_STRAIN = 0.022f
    private const val BUDGET_SEARCH_STEPS = 8
    private const val BUDGET_EPSILON = 1e-5f
    private const val FULL_TURN = (2.0 * Math.PI).toFloat()
}
