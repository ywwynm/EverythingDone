package com.ywwynm.everythingdone.spatial

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 从单目深度构造全画面连续的二维视点位移基。
 *
 * 运动只由同一个全局深度场产生：横向视点只沿 X 位移，纵向视点只沿 Y 位移。这里
 * 不读取语义分割、matting、对象身份或遮挡 cut，也不为主体拟合独立刚性变换。
 *
 * 单目深度的像素级误差不能直接放大到可见视差。运动场因此采用两级低通：大尺度场稳定
 * 整幅画面的前后关系，中尺度残差在安全预算内尽量保留人物、物体和场景内部的体积变化。
 * 两个尺度都跨越整张图连续求值，避免重新生成沿主体边界运动的“纸片”。
 */
internal object SpatialContinuousMotionBuilder {

    fun build(
        width: Int,
        height: Int,
        depth: FloatArray
    ): SpatialScreenSpaceMotionBasis = prepare(width, height, depth)
        .motionBasis(MAXIMUM_MEDIUM_RESIDUAL_WEIGHT)

    fun prepare(
        width: Int,
        height: Int,
        depth: FloatArray
    ): Prepared {
        require(width > 1 && height > 1)
        require(depth.size == width * height)

        val longEdge = max(width, height).toFloat()
        val coarse = gaussianBlur(
            source = depth,
            width = width,
            height = height,
            sigma = longEdge * COARSE_SIGMA_FRACTION
        )
        val medium = gaussianBlur(
            source = depth,
            width = width,
            height = height,
            sigma = longEdge * MEDIUM_SIGMA_FRACTION
        )
        return Prepared(
            width = width,
            height = height,
            coarse = coarse,
            medium = medium,
            source = depth.copyOf()
        )
    }

    class Prepared internal constructor(
        val width: Int,
        val height: Int,
        private val coarse: FloatArray,
        private val medium: FloatArray,
        private val source: FloatArray
    ) {
        fun motionBasis(mediumResidualWeight: Float): SpatialScreenSpaceMotionBasis {
            require(mediumResidualWeight in 0f..MAXIMUM_MEDIUM_RESIDUAL_WEIGHT)
            val centered = FloatArray(coarse.size) { index ->
                (
                    coarse[index] + mediumResidualWeight *
                        (medium[index] - coarse[index]) - 0.5f
                    ).coerceIn(-0.5f, 0.5f)
            }
            return SpatialScreenSpaceMotionBasis(
                width = width,
                height = height,
                horizontalX = centered,
                horizontalY = FloatArray(centered.size),
                verticalX = FloatArray(centered.size),
                verticalY = centered.copyOf()
            )
        }

        /**
         * 在统一低频主场上增加按局部坡度风险连续衰减的中尺度残差。
         *
         * 权重只来自同一张深度图，不读取实例、matting、类别或断边；高风险区域只会连续
         * 降低残差，不会形成独立平移层。这样可以在平缓的大范围体积上使用更高增益，同时
         * 避免少数深度尖峰迫使整张图片一起降低视差。
         */
        fun adaptiveMotionBasis(
            mediumSigmaFraction: Float,
            maximumResidualWeight: Float,
            riskThreshold: Float
        ): SpatialScreenSpaceMotionBasis {
            require(mediumSigmaFraction in 4f / 256f..32f / 256f)
            require(maximumResidualWeight in 0f..1f)
            require(riskThreshold in 0.01f..1f)
            val longEdge = max(width, height).toFloat()
            val adaptiveMedium = if (
                kotlin.math.abs(mediumSigmaFraction - MEDIUM_SIGMA_FRACTION) <= 1e-7f
            ) {
                medium
            } else {
                gaussianBlur(
                    source = source,
                    width = width,
                    height = height,
                    sigma = longEdge * mediumSigmaFraction
                )
            }
            val residual = FloatArray(source.size) { index ->
                adaptiveMedium[index] - coarse[index]
            }
            val risk = FloatArray(source.size)
            for (y in 0 until height) {
                val previousY = (y - 1).coerceAtLeast(0)
                val nextY = (y + 1).coerceAtMost(height - 1)
                val yDenominator = (nextY - previousY).coerceAtLeast(1)
                for (x in 0 until width) {
                    val previousX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(width - 1)
                    val xDenominator = (nextX - previousX).coerceAtLeast(1)
                    val dx = (
                        residual[y * width + nextX] -
                            residual[y * width + previousX]
                        ) / xDenominator * (width - 1)
                    val dy = (
                        residual[nextY * width + x] -
                            residual[previousY * width + x]
                        ) / yDenominator * (height - 1)
                    risk[y * width + x] = hypot(dx, dy)
                }
            }
            val smoothedRisk = gaussianBlur(
                source = risk,
                width = width,
                height = height,
                sigma = longEdge * RISK_SIGMA_FRACTION
            )
            val rawWeight = FloatArray(source.size) { index ->
                maximumResidualWeight * (
                    riskThreshold / max(smoothedRisk[index], RISK_EPSILON)
                    ).coerceIn(0f, 1f)
            }
            val weight = gaussianBlur(
                source = rawWeight,
                width = width,
                height = height,
                sigma = longEdge * WEIGHT_SIGMA_FRACTION
            )
            val centered = FloatArray(source.size) { index ->
                (coarse[index] + weight[index] * residual[index] - 0.5f)
                    .coerceIn(-0.5f, 0.5f)
            }
            return scalarBasis(centered)
        }

        fun candidates(): List<Candidate> = buildList {
            for (weight in BASELINE_RESIDUAL_WEIGHTS) {
                add(
                    Candidate(
                        id = "baseline-${weight.toStableId()}",
                        basis = motionBasis(weight),
                        mediumResidualWeight = weight
                    )
                )
            }
            for (configuration in ADAPTIVE_CONFIGURATIONS) {
                add(
                    Candidate(
                        id = configuration.id,
                        basis = adaptiveMotionBasis(
                            mediumSigmaFraction = configuration.mediumSigmaFraction,
                            maximumResidualWeight = configuration.maximumResidualWeight,
                            riskThreshold = configuration.riskThreshold
                        ),
                        mediumResidualWeight = 0f
                    )
                )
            }
        }

        private fun Float.toStableId(): String =
            (this * 1000f).toInt().toString().padStart(3, '0')

        private fun scalarBasis(centered: FloatArray): SpatialScreenSpaceMotionBasis =
            SpatialScreenSpaceMotionBasis(
                width = width,
                height = height,
                horizontalX = centered,
                horizontalY = FloatArray(centered.size),
                verticalX = FloatArray(centered.size),
                verticalY = centered.copyOf()
            )
    }

    data class Candidate(
        val id: String,
        val basis: SpatialScreenSpaceMotionBasis,
        val mediumResidualWeight: Float
    )

    private data class AdaptiveConfiguration(
        val id: String,
        val mediumSigmaFraction: Float,
        val maximumResidualWeight: Float,
        val riskThreshold: Float
    )

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

    /** 三次滑动窗口方框滤波近似高斯核，复杂度不再随 sigma 增长。 */
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
            reflected = if (reflected < 0) {
                -reflected
            } else {
                2 * size - 2 - reflected
            }
        }
        return reflected
    }

    /** 以 256 长边网格为基准，分别对应 sigma 40 与 sigma 16。 */
    private const val COARSE_SIGMA_FRACTION = 40f / 256f
    private const val MEDIUM_SIGMA_FRACTION = 16f / 256f
    private const val RISK_SIGMA_FRACTION = 8f / 256f
    private const val WEIGHT_SIGMA_FRACTION = 6f / 256f
    private const val RISK_EPSILON = 1e-6f
    const val MAXIMUM_MEDIUM_RESIDUAL_WEIGHT = 0.20f
    private val BASELINE_RESIDUAL_WEIGHTS = floatArrayOf(
        0.20f,
        0.175f,
        0.15f,
        0.125f,
        0.10f,
        0.075f,
        0.05f,
        0.025f,
        0f
    )
    private val ADAPTIVE_CONFIGURATIONS = arrayOf(
        AdaptiveConfiguration(
            id = "adaptive-s08-w350-t200",
            mediumSigmaFraction = 8f / 256f,
            maximumResidualWeight = 0.35f,
            riskThreshold = 0.20f
        ),
        AdaptiveConfiguration(
            id = "adaptive-s16-w500-t200",
            mediumSigmaFraction = 16f / 256f,
            maximumResidualWeight = 0.50f,
            riskThreshold = 0.20f
        ),
        AdaptiveConfiguration(
            id = "adaptive-s32-w700-t100",
            mediumSigmaFraction = 32f / 256f,
            maximumResidualWeight = 0.70f,
            riskThreshold = 0.10f
        )
    )
}
