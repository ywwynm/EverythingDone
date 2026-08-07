package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import kotlin.math.hypot
import kotlin.math.max

/**
 * 将每个几何 chart 的深度拆成最佳仿射平面与非仿射残差。
 *
 * 仿射部分对应 chart 的低频平移／统一缩放／剪切，保留它才能维持可感知空间层次；
 * 人脸、文字等局部“变形”主要来自仿射模型解释不了的高频残差，应单独约束。
 */
internal class SpatialChartMotionModel private constructor(
    val width: Int,
    val height: Int,
    val labels: IntArray,
    val components: Array<Component>
) {

    data class Component(
        val meanX: Float,
        val meanY: Float,
        val meanDepth: Float,
        val slopeX: Float,
        val slopeY: Float
    )

    fun planeDepth(index: Int, slopeScale: Float = 1f): Float {
        val component = components[labels[index]]
        val x = (index % width).toFloat() / (width - 1)
        val y = (index / width).toFloat() / (height - 1)
        return component.meanDepth +
            component.slopeX * slopeScale * (x - component.meanX) +
            component.slopeY * slopeScale * (y - component.meanY)
    }

    fun residuals(depth: FloatArray): FloatArray {
        require(depth.size == labels.size)
        return FloatArray(depth.size) { index -> depth[index] - planeDepth(index) }
    }

    fun affineGradientNorms(): FloatArray = FloatArray(components.size) { component ->
        hypot(components[component].slopeX, components[component].slopeY)
    }

    fun residualGradientNorms(depth: FloatArray): FloatArray {
        val residual = residuals(depth)
        val result = FloatArray(components.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val component = labels[index]
                val gx = if (x + 1 < width && labels[index + 1] == component) {
                    (residual[index + 1] - residual[index]) * (width - 1)
                } else {
                    0f
                }
                val gy = if (y + 1 < height && labels[index + width] == component) {
                    (residual[index + width] - residual[index]) * (height - 1)
                } else {
                    0f
                }
                result[component] = max(result[component], hypot(gx, gy))
            }
        }
        return result
    }

    companion object {
        fun fit(
            width: Int,
            height: Int,
            depth: FloatArray,
            cutRight: BooleanArray,
            cutDown: BooleanArray
        ): SpatialChartMotionModel {
            require(width > 1 && height > 1)
            require(depth.size == width * height)
            val labels = connectedComponents(width, height, cutRight, cutDown)
            val componentCount = (labels.maxOrNull() ?: -1) + 1
            val counts = IntArray(componentCount)
            val sumX = DoubleArray(componentCount)
            val sumY = DoubleArray(componentCount)
            val sumDepth = DoubleArray(componentCount)
            for (index in depth.indices) {
                val component = labels[index]
                val x = (index % width).toDouble() / (width - 1)
                val y = (index / width).toDouble() / (height - 1)
                counts[component]++
                sumX[component] += x
                sumY[component] += y
                sumDepth[component] += depth[index]
            }
            val meanX = DoubleArray(componentCount) { component ->
                sumX[component] / counts[component].coerceAtLeast(1)
            }
            val meanY = DoubleArray(componentCount) { component ->
                sumY[component] / counts[component].coerceAtLeast(1)
            }
            val meanDepth = DoubleArray(componentCount) { component ->
                sumDepth[component] / counts[component].coerceAtLeast(1)
            }
            val covarianceXX = DoubleArray(componentCount)
            val covarianceYY = DoubleArray(componentCount)
            val covarianceXY = DoubleArray(componentCount)
            val covarianceXDepth = DoubleArray(componentCount)
            val covarianceYDepth = DoubleArray(componentCount)
            for (index in depth.indices) {
                val component = labels[index]
                val x = (index % width).toDouble() / (width - 1) - meanX[component]
                val y = (index / width).toDouble() / (height - 1) - meanY[component]
                val value = depth[index] - meanDepth[component]
                covarianceXX[component] += x * x
                covarianceYY[component] += y * y
                covarianceXY[component] += x * y
                covarianceXDepth[component] += x * value
                covarianceYDepth[component] += y * value
            }
            val components = Array(componentCount) { component ->
                val trace = covarianceXX[component] + covarianceYY[component]
                val ridge = max(trace * FIT_RIDGE_RATIO, MIN_FIT_RIDGE)
                val xx = covarianceXX[component] + ridge
                val yy = covarianceYY[component] + ridge
                val xy = covarianceXY[component]
                val determinant = xx * yy - xy * xy
                val slopeX: Double
                val slopeY: Double
                if (determinant <= MIN_DETERMINANT) {
                    slopeX = 0.0
                    slopeY = 0.0
                } else {
                    slopeX = (
                        covarianceXDepth[component] * yy -
                            covarianceYDepth[component] * xy
                        ) / determinant
                    slopeY = (
                        covarianceYDepth[component] * xx -
                            covarianceXDepth[component] * xy
                        ) / determinant
                }
                Component(
                    meanX = meanX[component].toFloat(),
                    meanY = meanY[component].toFloat(),
                    meanDepth = meanDepth[component].toFloat(),
                    slopeX = slopeX.toFloat(),
                    slopeY = slopeY.toFloat()
                )
            }
            return SpatialChartMotionModel(width, height, labels, components)
        }

        private fun connectedComponents(
            width: Int,
            height: Int,
            cutRight: BooleanArray,
            cutDown: BooleanArray
        ): IntArray {
            require(cutRight.size == height * (width - 1))
            require(cutDown.size == (height - 1) * width)
            val labels = IntArray(width * height) { -1 }
            val queue = ArrayDeque<Int>()
            var component = 0
            for (start in labels.indices) {
                if (labels[start] >= 0) continue
                labels[start] = component
                queue.addLast(start)
                while (queue.isNotEmpty()) {
                    val index = queue.removeFirst()
                    val x = index % width
                    val y = index / width
                    fun offer(neighbor: Int, connected: Boolean) {
                        if (!connected || labels[neighbor] >= 0) return
                        labels[neighbor] = component
                        queue.addLast(neighbor)
                    }
                    if (x > 0) offer(index - 1, !cutRight[y * (width - 1) + x - 1])
                    if (x + 1 < width) offer(index + 1, !cutRight[y * (width - 1) + x])
                    if (y > 0) offer(index - width, !cutDown[(y - 1) * width + x])
                    if (y + 1 < height) offer(index + width, !cutDown[y * width + x])
                }
                component++
            }
            return labels
        }

        private const val FIT_RIDGE_RATIO = 1e-7
        private const val MIN_FIT_RIDGE = 1e-10
        private const val MIN_DETERMINANT = 1e-14
    }
}
