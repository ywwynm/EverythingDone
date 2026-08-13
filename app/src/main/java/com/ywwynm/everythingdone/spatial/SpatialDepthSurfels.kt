package com.ywwynm.everythingdone.spatial

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 连续深度微表面渲染所需的最小数据。
 *
 * [motionScalars] 以当前 guide 像素为单位；每个样本独立平移并通过目标空间深度测试
 * 决定可见性。它不是人物／物体的刚性平面，也不读取语义标签来决定运动。
 */
data class SpatialDepthSurfelData(
    val width: Int,
    val height: Int,
    val motionScalars: FloatArray,
    val guardFraction: Float,
    val backgroundScalar: Float,
    val requestedMaximumParallax: Float = 0.16f
) {
    init {
        require(width > 1 && height > 1) { "微表面尺寸必须大于 1" }
        require(motionScalars.size == width * height) { "微表面标量尺寸不匹配" }
        require(motionScalars.all(Float::isFinite)) { "微表面标量包含无效数值" }
        require(guardFraction.isFinite() && guardFraction in 0f..0.20f) {
            "微表面取景保护比例无效"
        }
        require(backgroundScalar.isFinite()) { "隐藏底板位移无效" }
        require(requestedMaximumParallax.isFinite() && requestedMaximumParallax in 0.01f..0.20f) {
            "微表面最大视点幅度无效"
        }
    }
}

/**
 * vNext13 连续深度微表面运动场。
 *
 * 低频场保存稳定的前后层次；中频场只在局部位移梯度安全时注入。最终以 p5-p95
 * 标定可见行程，不采用全局仿射、整图 backward warp 或语义刚性人物层。
 */
object SpatialDepthSurfelBuilder {

    data class Result(
        val surfels: SpatialDepthSurfelData,
        val motionBasis: SpatialScreenSpaceMotionBasis,
        val backgroundMotionBasis: SpatialScreenSpaceMotionBasis,
        val viewEnvelope: SpatialViewEnvelope
    )

    fun build(
        sourceDepth: SpatialDepthData,
        width: Int,
        height: Int,
        requestedMaximumParallax: Float = REQUESTED_MAXIMUM_PARALLAX,
        targetSpanPxAt720: Float = TARGET_SPAN_PX_AT_720,
        guardFraction: Float = GUARD_FRACTION
    ): Result {
        require(width > 1 && height > 1)
        require(requestedMaximumParallax in 0.01f..0.20f)
        require(targetSpanPxAt720.isFinite() && targetSpanPxAt720 in 4f..72f)
        require(guardFraction.isFinite() && guardFraction in 0f..0.20f)

        val depth = resampleBilinear(
            source = sourceDepth.values,
            sourceWidth = sourceDepth.width,
            sourceHeight = sourceDepth.height,
            targetWidth = width,
            targetHeight = height
        )
        val longEdge = max(width, height).toFloat()
        val coarse = gaussianBlur(
            depth,
            width,
            height,
            longEdge * COARSE_SIGMA_FRACTION
        )
        val medium = gaussianBlur(
            depth,
            width,
            height,
            longEdge * MEDIUM_SIGMA_FRACTION
        )
        val residual = FloatArray(depth.size) { index -> medium[index] - coarse[index] }
        val risk = residualRisk(residual, width, height)
        val smoothedRisk = gaussianBlur(
            risk,
            width,
            height,
            longEdge * RISK_SIGMA_FRACTION
        )
        val rawWeight = FloatArray(depth.size) { index ->
            (RESIDUAL_RISK_THRESHOLD / smoothedRisk[index].coerceAtLeast(MINIMUM_SPAN))
                .coerceIn(0f, 1f)
        }
        val weight = gaussianBlur(
            rawWeight,
            width,
            height,
            longEdge * WEIGHT_SIGMA_FRACTION
        )
        val scalar = FloatArray(depth.size) { index ->
            coarse[index] + weight[index].coerceIn(0f, 1f) * residual[index]
        }
        val low = percentile(scalar, ROBUST_LOW_PERCENTILE)
        val center = percentile(scalar, ROBUST_CENTER_PERCENTILE)
        val high = percentile(scalar, ROBUST_HIGH_PERCENTILE)
        val span = high - low
        require(span > MINIMUM_SPAN) { "深度运动场没有可用的稳健跨度" }
        val targetSpan = targetSpanPxAt720 * longEdge / REFERENCE_LONG_EDGE
        val scale = targetSpan / span
        for (index in scalar.indices) scalar[index] = (scalar[index] - center) * scale

        val backgroundScalar = percentile(scalar, BACKGROUND_PERCENTILE)
        val surfels = SpatialDepthSurfelData(
            width = width,
            height = height,
            motionScalars = scalar,
            guardFraction = guardFraction,
            backgroundScalar = backgroundScalar,
            requestedMaximumParallax = requestedMaximumParallax
        )
        // 点元位于像素中心，标量单位对应 width/height 个像素单元，而不是端点网格的
        // width-1/height-1 条边。
        val inverseHorizontal = 1f / (width * requestedMaximumParallax)
        val inverseVertical = 1f / (height * requestedMaximumParallax)
        val motionBasis = SpatialScreenSpaceMotionBasis(
            width = width,
            height = height,
            horizontalX = FloatArray(scalar.size) { scalar[it] * inverseHorizontal },
            horizontalY = FloatArray(scalar.size),
            verticalX = FloatArray(scalar.size),
            verticalY = FloatArray(scalar.size) { scalar[it] * inverseVertical }
        )
        val backgroundMotionBasis = SpatialScreenSpaceMotionBasis(
            width = width,
            height = height,
            horizontalX = FloatArray(scalar.size) {
                backgroundScalar * inverseHorizontal
            },
            horizontalY = FloatArray(scalar.size),
            verticalX = FloatArray(scalar.size),
            verticalY = FloatArray(scalar.size) {
                backgroundScalar * inverseVertical
            }
        )
        return Result(
            surfels = surfels,
            motionBasis = motionBasis,
            backgroundMotionBasis = backgroundMotionBasis,
            viewEnvelope = SpatialViewEnvelope.uniform(
                amplitude = requestedMaximumParallax,
                maximumLocalStrain = MAXIMUM_LOCAL_STRAIN_DIAGNOSTIC
            )
        )
    }

    private fun residualRisk(
        residual: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val result = FloatArray(residual.size)
        val horizontalScale = (width - 1).toFloat()
        val verticalScale = (height - 1).toFloat()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val dx = when (x) {
                    0 -> residual[index + 1] - residual[index]
                    width - 1 -> residual[index] - residual[index - 1]
                    else -> 0.5f * (residual[index + 1] - residual[index - 1])
                }
                val dy = when (y) {
                    0 -> residual[index + width] - residual[index]
                    height - 1 -> residual[index] - residual[index - width]
                    else -> 0.5f * (residual[index + width] - residual[index - width])
                }
                result[index] = hypot(dx * horizontalScale, dy * verticalScale)
            }
        }
        return result
    }

    private fun resampleBilinear(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(source.size == sourceWidth * sourceHeight)
        val result = FloatArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = ((y + 0.5f) * sourceHeight / targetHeight - 0.5f)
                .coerceIn(0f, (sourceHeight - 1).toFloat())
            val y0 = floor(sourceY).toInt()
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val wy = sourceY - y0
            for (x in 0 until targetWidth) {
                val sourceX = ((x + 0.5f) * sourceWidth / targetWidth - 0.5f)
                    .coerceIn(0f, (sourceWidth - 1).toFloat())
                val x0 = floor(sourceX).toInt()
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val wx = sourceX - x0
                val top = source[y0 * sourceWidth + x0] * (1f - wx) +
                    source[y0 * sourceWidth + x1] * wx
                val bottom = source[y1 * sourceWidth + x0] * (1f - wx) +
                    source[y1 * sourceWidth + x1] * wx
                result[y * targetWidth + x] = top * (1f - wy) + bottom * wy
            }
        }
        return result
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        require(values.isNotEmpty())
        val sorted = values.copyOf()
        sorted.sort()
        val position = sorted.lastIndex * fraction.coerceIn(0f, 1f)
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

    /** 三次滑动方框滤波近似高斯，复杂度不随 sigma 增长；边界使用 reflect-101。 */
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
    const val GUARD_FRACTION = 0.05f
    private const val REFERENCE_LONG_EDGE = 720f
    private const val COARSE_SIGMA_FRACTION = 40f / 256f
    private const val MEDIUM_SIGMA_FRACTION = 4f / 256f
    private const val RISK_SIGMA_FRACTION = 2f / 256f
    private const val WEIGHT_SIGMA_FRACTION = 2f / 256f
    private const val RESIDUAL_RISK_THRESHOLD = 0.10f
    private const val ROBUST_LOW_PERCENTILE = 0.05f
    private const val ROBUST_CENTER_PERCENTILE = 0.50f
    private const val ROBUST_HIGH_PERCENTILE = 0.95f
    private const val BACKGROUND_PERCENTILE = 0.08f
    private const val MAXIMUM_LOCAL_STRAIN_DIAGNOSTIC = 0.10f
    private const val MINIMUM_SPAN = 1e-6f
}
