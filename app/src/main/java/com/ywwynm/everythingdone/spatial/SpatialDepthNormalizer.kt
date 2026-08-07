package com.ywwynm.everythingdone.spatial

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class SpatialDepthData(
    val width: Int,
    val height: Int,
    /** 远处为 0、近处为 1。 */
    val values: FloatArray,
    val robustRange: Float,
    val strongEdgeRatio: Float,
    val defaultStrength: Float,
    /**
     * 来自锐边模型（过渡 1–2 px、已做闭运算）时为 true。几何构建据此跳过引导滤波：
     * 引导滤波是给糊边深度做图像对齐的补偿，对锐边深度反而按亮度重新糊开边缘、
     * 吸附回偏移位置（暗发对暗背景时亮度引导即噪声）。仅生成期使用，不落盘。
     */
    val sharpEdges: Boolean = false,
    /**
     * depth 型模型的未归一化逆深度裁剪副本（0 = 无穷远）。它保留模型输出的比例结构，
     * 但不代表米制尺度；几何构建只把尺度不变的比值作为遮挡证据。仅生成期使用，不落盘。
     */
    val rawInverseDepth: FloatArray? = null
) {
    init {
        require(width > 0 && height > 0)
        require(values.size == width * height)
        require(rawInverseDepth == null || rawInverseDepth.size == values.size)
    }
}

/**
 * 把模型相对逆深度转换成稳定的 0～1 渲染契约，并去掉正方形输入的补边区域。
 */
object SpatialDepthNormalizer {

    fun normalizeAndCrop(
        raw: FloatArray,
        inputSize: Int,
        contentLeft: Int,
        contentTop: Int,
        contentWidth: Int,
        contentHeight: Int,
        closeRadius: Int = 0,
        disparityContrast: Float = 1f,
        keepRawInverseDepth: Boolean = false
    ): SpatialDepthData {
        require(raw.size == inputSize * inputSize)
        require(contentWidth > 0 && contentHeight > 0)
        require(contentLeft >= 0 && contentTop >= 0)
        require(contentLeft + contentWidth <= inputSize)
        require(contentTop + contentHeight <= inputSize)

        val cropped = FloatArray(contentWidth * contentHeight)
        var outputIndex = 0
        for (y in 0 until contentHeight) {
            val inputOffset = (contentTop + y) * inputSize + contentLeft
            for (x in 0 until contentWidth) {
                val value = raw[inputOffset + x]
                check(value.isFinite()) { "深度输出包含 NaN 或 Infinity" }
                cropped[outputIndex++] = value
            }
        }
        val rawInverseDepth = if (keepRawInverseDepth) {
            cropped.copyOf().also {
                // 与归一化场同拓扑：闭运算把发丝间隙并入前景团块。不闭合的话，
                // 原始 depth 比值判据会在每条发丝间隙两侧重新造出条缕级断边。
                if (closeRadius > 0) grayscaleClose(it, contentWidth, contentHeight, closeRadius)
            }
        } else {
            null
        }

        val sorted = cropped.copyOf()
        sorted.sort()
        val low = percentile(sorted, 0.02f)
        val high = percentile(sorted, 0.98f)
        val range = high - low
        check(range.isFinite() && range > MIN_ROBUST_RANGE) {
            "深度输出没有可用动态范围：$range"
        }

        for (index in cropped.indices) {
            cropped[index] = ((cropped[index] - low) / range).coerceIn(0f, 1f)
        }
        if (closeRadius > 0) {
            grayscaleClose(cropped, contentWidth, contentHeight, closeRadius)
        }
        if (disparityContrast < 1f) {
            // depth 型输出的分布整形（D55）：绕 0.5 线性压缩，间隔对齐 DAV2 类形态。
            for (index in cropped.indices) {
                cropped[index] =
                    (0.5f + (cropped[index] - 0.5f) * disparityContrast).coerceIn(0f, 1f)
            }
        }

        val edgeRatio = strongEdgeRatio(cropped, contentWidth, contentHeight)
        val defaultStrength = when {
            edgeRatio >= 0.16f -> 0.48f
            edgeRatio >= 0.09f -> 0.60f
            else -> 0.72f
        }
        return SpatialDepthData(
            width = contentWidth,
            height = contentHeight,
            values = cropped,
            robustRange = range,
            strongEdgeRatio = edgeRatio,
            defaultStrength = defaultStrength,
            sharpEdges = closeRadius > 0,
            rawInverseDepth = rawInverseDepth
        )
    }

    /**
     * 灰度闭运算（先膨胀后腐蚀，方形结构元，水平/竖直可分离）：把窄于约 2×radius 的
     * 远深度缝隙（发丝间隙等亚网格结构）并入近景团块，外轮廓位置与锐度不变。
     * 仅用于锐边深度模型（[SpatialDepthModel.sharpDepthEdges]）。
     */
    private fun grayscaleClose(
        values: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val buffer = FloatArray(values.size)
        extremeHorizontal(values, buffer, width, height, radius, dilate = true)
        extremeVertical(buffer, values, width, height, radius, dilate = true)
        extremeHorizontal(values, buffer, width, height, radius, dilate = false)
        extremeVertical(buffer, values, width, height, radius, dilate = false)
    }

    private fun extremeHorizontal(
        source: FloatArray,
        target: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        dilate: Boolean
    ) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var extreme = source[row + x]
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                for (i in left..right) {
                    val value = source[row + i]
                    extreme = if (dilate) max(extreme, value) else min(extreme, value)
                }
                target[row + x] = extreme
            }
        }
    }

    private fun extremeVertical(
        source: FloatArray,
        target: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        dilate: Boolean
    ) {
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                var extreme = source[y * width + x]
                for (i in top..bottom) {
                    val value = source[i * width + x]
                    extreme = if (dilate) max(extreme, value) else min(extreme, value)
                }
                target[y * width + x] = extreme
            }
        }
    }

    private fun percentile(sorted: FloatArray, fraction: Float): Float {
        if (sorted.size == 1) return sorted[0]
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val lowIndex = floor(position).toInt()
        val highIndex = ceil(position).toInt()
        if (lowIndex == highIndex) return sorted[lowIndex]
        val mix = position - lowIndex
        return sorted[lowIndex] * (1f - mix) + sorted[highIndex] * mix
    }

    private fun strongEdgeRatio(values: FloatArray, width: Int, height: Int): Float {
        if (width < 2 || height < 2) return 0f
        var strong = 0
        var compared = 0
        for (y in 0 until height - 1) {
            val row = y * width
            for (x in 0 until width - 1) {
                val value = values[row + x]
                if (kotlin.math.abs(value - values[row + x + 1]) >= STRONG_EDGE_DELTA) strong++
                if (kotlin.math.abs(value - values[row + width + x]) >= STRONG_EDGE_DELTA) strong++
                compared += 2
            }
        }
        return if (compared == 0) 0f else strong.toFloat() / compared
    }

    private const val MIN_ROBUST_RANGE = 1e-6f
    private const val STRONG_EDGE_DELTA = 0.16f
}
