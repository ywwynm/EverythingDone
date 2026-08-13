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
    val rawInverseDepth: FloatArray? = null,
    /**
     * **米制**深度 Z（米），仅 [SpatialDepthOutputContract.MOGE_POINT_MAP] 一档提供。
     * 有它才能做真透视重投影；其余模型只给相对深度，几何只能退化成屏幕空间位移场
     * （D204）。与 [intrinsics] 同时为 null 或同时非 null。
     */
    val metricDepth: FloatArray? = null,
    /** 像素单位相机内参，来源同上。 */
    val intrinsics: Intrinsics? = null
) {
    /** fx/fy/cx/cy 均以 [width]×[height] 这个分辨率下的像素为单位。 */
    data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float)

    init {
        require(width > 0 && height > 0)
        require(values.size == width * height)
        require(rawInverseDepth == null || rawInverseDepth.size == values.size)
        require(metricDepth == null || metricDepth.size == values.size)
        require((metricDepth == null) == (intrinsics == null)) {
            "米制深度与内参必须同时存在——只有其一时下游无法做透视重投影"
        }
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
     * MoGe-2 路径的归一化：输入已经是**内容分辨率**上的逆深度，不需要方形填充与裁剪。
     * 归一化口径（p2/p98 稳健区间、闭运算、强边比、默认强度）与 [normalizeAndCrop]
     * 完全一致，两条路产出的 `values` 因此可比。
     *
     * 米制深度与内参**不参与归一化**，原样随行——归一化会丢掉尺度，而那正是这一档
     * 存在的理由（D204）。
     */
    fun normalizeFromInverseDepth(
        inverseDepth: FloatArray,
        width: Int,
        height: Int,
        closeRadius: Int = 0,
        disparityContrast: Float = 1f,
        metricDepth: FloatArray,
        intrinsics: SpatialDepthData.Intrinsics
    ): SpatialDepthData {
        require(inverseDepth.size == width * height)
        require(metricDepth.size == inverseDepth.size)

        val values = inverseDepth.copyOf()
        for (v in values) check(v.isFinite()) { "深度输出包含 NaN 或 Infinity" }
        val rawInverseDepth = values.copyOf().also {
            if (closeRadius > 0) grayscaleClose(it, width, height, closeRadius)
        }

        val sorted = values.copyOf()
        sorted.sort()
        val low = percentile(sorted, 0.02f)
        val high = percentile(sorted, 0.98f)
        val range = high - low
        check(range.isFinite() && range > MIN_ROBUST_RANGE) {
            "深度输出没有可用动态范围：$range"
        }
        for (index in values.indices) {
            values[index] = ((values[index] - low) / range).coerceIn(0f, 1f)
        }
        if (closeRadius > 0) grayscaleClose(values, width, height, closeRadius)
        if (disparityContrast < 1f) {
            for (index in values.indices) {
                values[index] =
                    (0.5f + (values[index] - 0.5f) * disparityContrast).coerceIn(0f, 1f)
            }
        }

        val edgeRatio = strongEdgeRatio(values, width, height)
        val defaultStrength = when {
            edgeRatio >= 0.16f -> 0.48f
            edgeRatio >= 0.09f -> 0.60f
            else -> 0.72f
        }
        return SpatialDepthData(
            width = width,
            height = height,
            values = values,
            robustRange = range,
            strongEdgeRatio = edgeRatio,
            defaultStrength = defaultStrength,
            sharpEdges = closeRadius > 0,
            rawInverseDepth = rawInverseDepth,
            metricDepth = metricDepth,
            intrinsics = intrinsics
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
