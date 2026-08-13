package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 全表面局部 chart 的持久化数据。每个像素恰好属于一个 chart；重叠权重由渲染端依据
 * [labels] 和固定 renderer 契约重新构建，人物分割、matte 与 ownership 不参与运动。
 */
data class SpatialSurfaceChartData(
    val width: Int,
    val height: Int,
    val labels: IntArray,
    val chartCount: Int,
    /** 固定、等比例的取景裁边；运行时不随视角方向或半径变化。 */
    val guardFraction: Float
) {
    init {
        require(width > 1 && height > 1)
        require(labels.size == width * height)
        require(chartCount > 1)
        require(labels.all { it in 0 until chartCount })
        require(guardFraction.isFinite() && guardFraction in 0f..0.20f)
    }
}

/**
 * 从 RGB 与连续单目深度构建全表面刚性 chart。
 *
 * 每个 chart 内只有二维平移，没有仿射拉伸；chart 的位移标量只来自多尺度深度。RGB
 * 仅帮助 chart 边界贴合纹理，语义 mask 不在本类型的输入契约中，避免再次产生整个人物
 * 共用一张运动平面的“卡片人”。
 */
object SpatialSurfaceChartBuilder {

    data class Result(
        val charts: SpatialSurfaceChartData,
        val motionBasis: SpatialScreenSpaceMotionBasis,
        val backgroundMotionBasis: SpatialScreenSpaceMotionBasis,
        val viewEnvelope: SpatialViewEnvelope,
        /** 以当前 guide 像素为单位，供诊断与测试使用。 */
        val chartScalars: FloatArray
    )

    fun build(
        colorPixels: IntArray,
        width: Int,
        height: Int,
        surfaceDepth: FloatArray,
        requestedMaximumParallax: Float = REQUESTED_MAXIMUM_PARALLAX,
        depthResidualGain: Float = DEPTH_RESIDUAL_GAIN
    ): Result {
        require(width > 1 && height > 1)
        require(colorPixels.size == width * height)
        require(surfaceDepth.size == width * height)
        require(surfaceDepth.all(Float::isFinite))
        require(requestedMaximumParallax in 0.01f..0.20f)
        require(depthResidualGain.isFinite() && depthResidualGain in 0f..8f)

        val scalar = buildMotionScalar(surfaceDepth, width, height, depthResidualGain)
        val labels = buildSlicLabels(colorPixels, scalar, width, height)
        val chartCount = (labels.maxOrNull() ?: -1) + 1
        require(chartCount >= MINIMUM_CHART_COUNT)
        val chartScalars = chartMedians(scalar, labels, chartCount)

        val overlapSigma = OVERLAP_SIGMA_PX_AT_720 * max(width, height) /
            REFERENCE_LONG_EDGE
        val boundaryExpansion = ceil(overlapSigma * BOUNDARY_GAUSSIAN_RADIUS).toInt()
            .coerceAtLeast(1)
        val boundary = boundaryCharts(
            labels = labels,
            width = width,
            height = height,
            chartCount = chartCount,
            expansion = boundaryExpansion
        )
        val horizontalCenter = 0.5f * (
            maximum(chartScalars, boundary.left) + minimum(chartScalars, boundary.right)
            )
        val verticalCenter = 0.5f * (
            maximum(chartScalars, boundary.top) + minimum(chartScalars, boundary.bottom)
            )

        val horizontalX = FloatArray(labels.size)
        val horizontalY = FloatArray(labels.size)
        val verticalX = FloatArray(labels.size)
        val verticalY = FloatArray(labels.size)
        val inverseHorizontalScale = -1f /
            ((width - 1) * requestedMaximumParallax)
        val inverseVerticalScale = -1f /
            ((height - 1) * requestedMaximumParallax)
        for (index in labels.indices) {
            val chartScalar = chartScalars[labels[index]]
            horizontalX[index] = (chartScalar - horizontalCenter) * inverseHorizontalScale
            verticalY[index] = (chartScalar - verticalCenter) * inverseVerticalScale
        }
        val motionBasis = SpatialScreenSpaceMotionBasis(
            width = width,
            height = height,
            horizontalX = horizontalX,
            horizontalY = horizontalY,
            verticalX = verticalX,
            verticalY = verticalY
        )

        // 隐藏底板只跟随公共 reframe，不复制任何前景 chart 的局部运动。
        val backgroundHorizontal = horizontalCenter /
            ((width - 1) * requestedMaximumParallax)
        val backgroundVertical = verticalCenter /
            ((height - 1) * requestedMaximumParallax)
        val backgroundMotionBasis = SpatialScreenSpaceMotionBasis(
            width = width,
            height = height,
            horizontalX = FloatArray(labels.size) { backgroundHorizontal },
            horizontalY = FloatArray(labels.size),
            verticalX = FloatArray(labels.size),
            verticalY = FloatArray(labels.size) { backgroundVertical }
        )

        val maximumHorizontalInset = 0.5f * (
            maximum(chartScalars, boundary.left) - minimum(chartScalars, boundary.right)
            ).coerceAtLeast(0f)
        val maximumVerticalInset = 0.5f * (
            maximum(chartScalars, boundary.top) - minimum(chartScalars, boundary.bottom)
            ).coerceAtLeast(0f)
        val longEdge = max(width, height).toFloat()
        val extraGuidePixels = BORDER_GUARD_EXTRA_PX_AT_720 * longEdge /
            REFERENCE_LONG_EDGE
        val computedGuardFraction = max(
            (maximumHorizontalInset + extraGuidePixels) / (width - 1),
            (maximumVerticalInset + extraGuidePixels) / (height - 1)
        )
        // 部分照片的最外缘本身含有曝光窄条或不可靠像素。运动估算接近边缘共面时，
        // 仅按视差计算会得到过小的裁边，并在斜向最大视角下把窄条放大成楔形。
        // 固定下限不随方向或强度变化，也不改变 chart 之间的相对视差。
        val guardFraction = max(
            computedGuardFraction,
            MINIMUM_SOURCE_BORDER_GUARD_PX_AT_720 / REFERENCE_LONG_EDGE
        ).coerceIn(0f, MAXIMUM_GUARD_FRACTION)

        return Result(
            charts = SpatialSurfaceChartData(
                width = width,
                height = height,
                labels = labels,
                chartCount = chartCount,
                guardFraction = guardFraction
            ),
            motionBasis = motionBasis,
            backgroundMotionBasis = backgroundMotionBasis,
            viewEnvelope = SpatialViewEnvelope.uniform(
                amplitude = requestedMaximumParallax,
                maximumLocalStrain = MAXIMUM_LOCAL_STRAIN_DIAGNOSTIC
            ),
            chartScalars = chartScalars
        )
    }

    private fun buildMotionScalar(
        depth: FloatArray,
        width: Int,
        height: Int,
        depthResidualGain: Float
    ): FloatArray {
        val longEdge = max(width, height).toFloat()
        val coarse = gaussianBlur(
            depth,
            width,
            height,
            DEPTH_COARSE_SIGMA_AT_512 * longEdge / DEPTH_REFERENCE_LONG_EDGE
        )
        val medium = gaussianBlur(
            depth,
            width,
            height,
            DEPTH_MEDIUM_SIGMA_AT_512 * longEdge / DEPTH_REFERENCE_LONG_EDGE
        )
        val scalar = FloatArray(depth.size) { index ->
            coarse[index] + depthResidualGain * (medium[index] - coarse[index])
        }
        val lower = percentile(scalar, ROBUST_LOWER_PERCENTILE)
        val upper = percentile(scalar, ROBUST_UPPER_PERCENTILE)
        val spanAtReference = (upper - lower) * REFERENCE_LONG_EDGE / longEdge
        require(spanAtReference > MINIMUM_SCALAR_SPAN) {
            "深度运动场没有可用的稳健跨度"
        }
        val scale = TARGET_SPAN_PX_AT_720 / spanAtReference
        val center = percentile(scalar, 0.5f)
        for (index in scalar.indices) scalar[index] = (scalar[index] - center) * scale
        return scalar
    }

    private fun buildSlicLabels(
        colors: IntArray,
        scalar: FloatArray,
        width: Int,
        height: Int
    ): IntArray {
        val pixelCount = width * height
        val requestedCount = max(
            MINIMUM_CHART_COUNT,
            (pixelCount / CHART_AREA_GUIDE).roundToInt()
        )
        val columns = max(
            1,
            sqrt(requestedCount.toFloat() * width / height).roundToInt()
        )
        val rows = max(1, (requestedCount.toFloat() / columns).roundToInt())
        val centerCount = columns * rows
        val stepX = width.toFloat() / columns
        val stepY = height.toFloat() / rows
        val step = max(stepX, stepY)

        val scalarLow = percentile(scalar, ROBUST_LOWER_PERCENTILE)
        val scalarHigh = percentile(scalar, ROBUST_UPPER_PERCENTILE)
        val scalarRange = (scalarHigh - scalarLow).coerceAtLeast(MINIMUM_SCALAR_SPAN)
        val red = FloatArray(pixelCount)
        val green = FloatArray(pixelCount)
        val blue = FloatArray(pixelCount)
        val depthFeature = FloatArray(pixelCount)
        var globalMinimum = Float.POSITIVE_INFINITY
        var globalMaximum = Float.NEGATIVE_INFINITY
        for (index in colors.indices) {
            val color = colors[index]
            red[index] = ((color ushr 16) and 0xff) / 255f
            green[index] = ((color ushr 8) and 0xff) / 255f
            blue[index] = (color and 0xff) / 255f
            depthFeature[index] = (
                (scalar[index] - scalarLow) / scalarRange
                ).coerceIn(-0.2f, 1.2f) * DEPTH_FEATURE_GAIN
            globalMinimum = minOf(
                globalMinimum,
                red[index],
                green[index],
                blue[index],
                depthFeature[index]
            )
            globalMaximum = maxOf(
                globalMaximum,
                red[index],
                green[index],
                blue[index],
                depthFeature[index]
            )
        }
        val featureScale = 1f / (globalMaximum - globalMinimum).coerceAtLeast(1e-6f)
        for (index in colors.indices) {
            red[index] = (red[index] - globalMinimum) * featureScale
            green[index] = (green[index] - globalMinimum) * featureScale
            blue[index] = (blue[index] - globalMinimum) * featureScale
            depthFeature[index] = (depthFeature[index] - globalMinimum) * featureScale
        }

        val centerX = FloatArray(centerCount)
        val centerY = FloatArray(centerCount)
        val centerR = FloatArray(centerCount)
        val centerG = FloatArray(centerCount)
        val centerB = FloatArray(centerCount)
        val centerD = FloatArray(centerCount)
        var centerIndex = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = ((column + 0.5f) * stepX).toInt().coerceIn(0, width - 1)
                val y = ((row + 0.5f) * stepY).toInt().coerceIn(0, height - 1)
                val index = y * width + x
                centerX[centerIndex] = x.toFloat()
                centerY[centerIndex] = y.toFloat()
                centerR[centerIndex] = red[index]
                centerG[centerIndex] = green[index]
                centerB[centerIndex] = blue[index]
                centerD[centerIndex] = depthFeature[index]
                centerIndex++
            }
        }

        val labels = IntArray(pixelCount) { -1 }
        val distances = FloatArray(pixelCount)
        val spatialScale = 1f / (step * step).coerceAtLeast(1f)
        val featureDistanceScale = 1f / (SLIC_COMPACTNESS * SLIC_COMPACTNESS)
        repeat(SLIC_ITERATIONS) {
            distances.fill(Float.POSITIVE_INFINITY)
            labels.fill(-1)
            for (center in 0 until centerCount) {
                val left = floor(centerX[center] - 2f * step).toInt().coerceAtLeast(0)
                val right = ceil(centerX[center] + 2f * step).toInt().coerceAtMost(width - 1)
                val top = floor(centerY[center] - 2f * step).toInt().coerceAtLeast(0)
                val bottom = ceil(centerY[center] + 2f * step).toInt().coerceAtMost(height - 1)
                for (y in top..bottom) {
                    val dy = y - centerY[center]
                    for (x in left..right) {
                        val index = y * width + x
                        val dr = red[index] - centerR[center]
                        val dg = green[index] - centerG[center]
                        val db = blue[index] - centerB[center]
                        val dd = depthFeature[index] - centerD[center]
                        val dx = x - centerX[center]
                        val distance = (dr * dr + dg * dg + db * db + dd * dd) *
                            featureDistanceScale + (dx * dx + dy * dy) * spatialScale
                        if (distance < distances[index]) {
                            distances[index] = distance
                            labels[index] = center
                        }
                    }
                }
            }

            val count = IntArray(centerCount)
            val sumX = DoubleArray(centerCount)
            val sumY = DoubleArray(centerCount)
            val sumR = DoubleArray(centerCount)
            val sumG = DoubleArray(centerCount)
            val sumB = DoubleArray(centerCount)
            val sumD = DoubleArray(centerCount)
            for (index in labels.indices) {
                val label = labels[index]
                check(label >= 0) { "SLIC 存在未分配像素" }
                val x = index % width
                val y = index / width
                count[label]++
                sumX[label] += x
                sumY[label] += y
                sumR[label] += red[index]
                sumG[label] += green[index]
                sumB[label] += blue[index]
                sumD[label] += depthFeature[index]
            }
            for (center in 0 until centerCount) {
                if (count[center] == 0) continue
                val inverse = 1.0 / count[center]
                centerX[center] = (sumX[center] * inverse).toFloat()
                centerY[center] = (sumY[center] * inverse).toFloat()
                centerR[center] = (sumR[center] * inverse).toFloat()
                centerG[center] = (sumG[center] * inverse).toFloat()
                centerB[center] = (sumB[center] * inverse).toFloat()
                centerD[center] = (sumD[center] * inverse).toFloat()
            }
        }
        return enforceConnectivity(
            labels = labels,
            width = width,
            height = height,
            minimumComponentSize = max(4, pixelCount / centerCount / 4)
        )
    }

    private fun enforceConnectivity(
        labels: IntArray,
        width: Int,
        height: Int,
        minimumComponentSize: Int
    ): IntArray {
        val result = IntArray(labels.size) { -1 }
        val queue = ArrayDeque<Int>()
        val component = ArrayList<Int>()
        var nextLabel = 0
        for (start in labels.indices) {
            if (result[start] >= 0) continue
            val sourceLabel = labels[start]
            var adjacentLabel = -1
            queue.add(start)
            result[start] = Int.MIN_VALUE
            component.clear()
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component += index
                val x = index % width
                val y = index / width
                fun visit(neighbor: Int) {
                    when {
                        result[neighbor] >= 0 -> adjacentLabel = result[neighbor]
                        result[neighbor] == -1 && labels[neighbor] == sourceLabel -> {
                            result[neighbor] = Int.MIN_VALUE
                            queue.add(neighbor)
                        }
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            val outputLabel = if (
                component.size < minimumComponentSize && adjacentLabel >= 0
            ) {
                adjacentLabel
            } else {
                nextLabel++
            }
            component.forEach { result[it] = outputLabel }
        }
        // 小分量合并可能使编号出现空洞，压紧后再持久化。
        val remap = IntArray(nextLabel) { -1 }
        var compact = 0
        for (index in result.indices) {
            val old = result[index]
            if (remap[old] < 0) remap[old] = compact++
            result[index] = remap[old]
        }
        return result
    }

    private fun chartMedians(
        scalar: FloatArray,
        labels: IntArray,
        chartCount: Int
    ): FloatArray {
        val counts = IntArray(chartCount)
        labels.forEach { counts[it]++ }
        val values = Array(chartCount) { FloatArray(counts[it]) }
        val offsets = IntArray(chartCount)
        for (index in labels.indices) {
            val label = labels[index]
            values[label][offsets[label]++] = scalar[index]
        }
        return FloatArray(chartCount) { chart ->
            val chartValues = values[chart]
            chartValues.sort()
            if (chartValues.size % 2 == 1) {
                chartValues[chartValues.size / 2]
            } else {
                0.5f * (
                    chartValues[chartValues.size / 2 - 1] +
                        chartValues[chartValues.size / 2]
                    )
            }
        }
    }

    private data class BoundaryCharts(
        val left: BooleanArray,
        val right: BooleanArray,
        val top: BooleanArray,
        val bottom: BooleanArray
    )

    private fun boundaryCharts(
        labels: IntArray,
        width: Int,
        height: Int,
        chartCount: Int,
        expansion: Int
    ): BoundaryCharts {
        val left = BooleanArray(chartCount)
        val right = BooleanArray(chartCount)
        val top = BooleanArray(chartCount)
        val bottom = BooleanArray(chartCount)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val label = labels[y * width + x]
                if (x <= expansion) left[label] = true
                if (x >= width - 1 - expansion) right[label] = true
                if (y <= expansion) top[label] = true
                if (y >= height - 1 - expansion) bottom[label] = true
            }
        }
        require(left.any { it } && right.any { it } && top.any { it } && bottom.any { it })
        return BoundaryCharts(left, right, top, bottom)
    }

    private fun maximum(values: FloatArray, selected: BooleanArray): Float {
        var result = Float.NEGATIVE_INFINITY
        for (index in values.indices) if (selected[index]) result = max(result, values[index])
        return result
    }

    private fun minimum(values: FloatArray, selected: BooleanArray): Float {
        var result = Float.POSITIVE_INFINITY
        for (index in values.indices) if (selected[index]) result = minOf(result, values[index])
        return result
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        require(values.isNotEmpty())
        val sorted = values.copyOf()
        sorted.sort()
        val position = (sorted.lastIndex * fraction.coerceIn(0f, 1f))
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val weight = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }

    private fun gaussianBlur(
        source: FloatArray,
        width: Int,
        height: Int,
        sigma: Float
    ): FloatArray {
        require(sigma > 0f)
        var current = source.copyOf()
        for (boxWidth in gaussianBoxWidths(sigma)) {
            current = boxBlur(current, width, height, boxWidth)
        }
        return current
    }

    /** 三次滑动方框滤波近似高斯，复杂度不随大 sigma 增长。边界采用 reflect-101。 */
    private fun boxBlur(
        source: FloatArray,
        width: Int,
        height: Int,
        boxWidth: Int
    ): FloatArray {
        val radius = boxWidth / 2
        if (radius <= 0) return source.copyOf()
        val horizontal = FloatArray(source.size)
        val inverseWidth = 1f / boxWidth
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (offset in -radius..radius) {
                sum += source[row + reflectIndex(offset, width)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum * inverseWidth
                sum -= source[row + reflectIndex(x - radius, width)]
                sum += source[row + reflectIndex(x + radius + 1, width)]
            }
        }
        val result = FloatArray(source.size)
        for (x in 0 until width) {
            var sum = 0f
            for (offset in -radius..radius) {
                sum += horizontal[reflectIndex(offset, height) * width + x]
            }
            for (y in 0 until height) {
                result[y * width + x] = sum * inverseWidth
                sum -= horizontal[reflectIndex(y - radius, height) * width + x]
                sum += horizontal[reflectIndex(y + radius + 1, height) * width + x]
            }
        }
        return result
    }

    private fun gaussianBoxWidths(sigma: Float, count: Int = 3): IntArray {
        val ideal = sqrt(12f * sigma * sigma / count + 1f)
        var lower = floor(ideal).toInt().coerceAtLeast(1)
        if (lower % 2 == 0) lower--
        val upper = lower + 2
        val numerator = 12f * sigma * sigma -
            count * lower * lower - 4f * count * lower - 3f * count
        val lowerCount = (numerator / (-4f * lower - 4f))
            .roundToInt()
            .coerceIn(0, count)
        return IntArray(count) { index -> if (index < lowerCount) lower else upper }
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        var reflected = index
        while (reflected < 0 || reflected >= size) {
            reflected = if (reflected < 0) -reflected else 2 * size - 2 - reflected
        }
        return reflected
    }

    const val REQUESTED_MAXIMUM_PARALLAX = 0.16f
    const val TARGET_SPAN_PX_AT_720 = 36f
    const val CHART_AREA_GUIDE = 1100f
    const val OVERLAP_SIGMA_PX_AT_720 = 8f
    const val Z_SOFTNESS = 8f
    const val COVERAGE_ALPHA_LOW = 0.002f
    const val COVERAGE_ALPHA_HIGH = 0.08f
    private const val DEPTH_REFERENCE_LONG_EDGE = 512f
    private const val DEPTH_COARSE_SIGMA_AT_512 = 64f
    private const val DEPTH_MEDIUM_SIGMA_AT_512 = 8f
    private const val DEPTH_RESIDUAL_GAIN = 6f
    private const val DEPTH_FEATURE_GAIN = 2f
    private const val SLIC_COMPACTNESS = 8f
    private const val SLIC_ITERATIONS = 10
    private const val MINIMUM_CHART_COUNT = 4
    private const val ROBUST_LOWER_PERCENTILE = 0.05f
    private const val ROBUST_UPPER_PERCENTILE = 0.95f
    private const val MINIMUM_SCALAR_SPAN = 1e-6f
    private const val REFERENCE_LONG_EDGE = 720f
    private const val BORDER_GUARD_EXTRA_PX_AT_720 = 2f
    private const val MINIMUM_SOURCE_BORDER_GUARD_PX_AT_720 = 14f
    private const val BOUNDARY_GAUSSIAN_RADIUS = 4f
    private const val MAXIMUM_GUARD_FRACTION = 0.20f
    private const val MAXIMUM_LOCAL_STRAIN_DIAGNOSTIC = 0.08f
}
