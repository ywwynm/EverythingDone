package com.ywwynm.everythingdone.spatial

import kotlin.math.ceil

/**
 * 只保留前景遮挡区内，在渲染允许的最大相对视差下确实可能显露的窄带。
 *
 * 深埋在物体内部、任何允许视角都不会被采样到的像素不应交给补全模型。这样既降低
 * 大面积幻觉的概率，也让模型把容量集中在轮廓外沿和内部孔洞周围的真实显露区域。
 */
internal object SpatialDisocclusionBand {

    fun inside(
        objectMask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        require(width > 0 && height > 0)
        require(objectMask.size == width * height)
        require(radius >= 1)

        val result = BooleanArray(objectMask.size)
        val distance = IntArray(objectMask.size) { Int.MAX_VALUE }
        val queue = IntArray(objectMask.size)
        var head = 0
        var tail = 0

        fun touchesKnownBackground(index: Int): Boolean {
            val x = index % width
            val y = index / width
            for (offsetY in -1..1) {
                val targetY = y + offsetY
                if (targetY !in 0 until height) continue
                for (offsetX in -1..1) {
                    if (offsetX == 0 && offsetY == 0) continue
                    val targetX = x + offsetX
                    if (targetX in 0 until width &&
                        !objectMask[targetY * width + targetX]
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        for (index in objectMask.indices) {
            if (objectMask[index] && touchesKnownBackground(index)) {
                distance[index] = 1
                result[index] = true
                queue[tail++] = index
            }
        }

        while (head < tail) {
            val index = queue[head++]
            val nextDistance = distance[index] + 1
            if (nextDistance > radius) continue
            val x = index % width
            val y = index / width
            for (offsetY in -1..1) {
                val targetY = y + offsetY
                if (targetY !in 0 until height) continue
                for (offsetX in -1..1) {
                    if (offsetX == 0 && offsetY == 0) continue
                    val targetX = x + offsetX
                    if (targetX !in 0 until width) continue
                    val target = targetY * width + targetX
                    if (!objectMask[target] || nextDistance >= distance[target]) continue
                    distance[target] = nextDistance
                    result[target] = true
                    queue[tail++] = target
                }
            }
        }
        return result
    }

    fun requiredRadius(width: Int, height: Int): Int {
        return requiredRadius(
            width = width,
            height = height,
            maximumRelativeDepth = 1f
        )
    }

    fun requiredRadius(
        width: Int,
        height: Int,
        maximumRelativeDepth: Float
    ): Int {
        require(width > 0 && height > 0)
        require(maximumRelativeDepth.isFinite())
        val maximumAxisPixels = maxOf(width, height) *
            SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE *
            maximumRelativeDepth.coerceIn(0f, 1f)
        return ceil(maximumAxisPixels).toInt() + SAMPLING_SAFETY_PIXELS
    }

    fun maximumRelativeDepth(
        labels: ByteArray,
        backgroundDepth: FloatArray,
        layers: List<SpatialOwnershipLayer.ObjectLayer>
    ): Float {
        require(labels.size == backgroundDepth.size)
        val layerDepth = FloatArray(255) { Float.NaN }
        for (layer in layers) {
            if (layer.label in 1..254) {
                layerDepth[layer.label] = layer.representativeDepth.coerceIn(0f, 1f)
            }
        }
        var maximum = 0f
        for (index in labels.indices) {
            val label = labels[index].toInt() and 0xff
            if (label == 0) continue
            val foregroundDepth = layerDepth[label]
            if (!foregroundDepth.isFinite()) return 1f
            maximum = maxOf(
                maximum,
                kotlin.math.abs(
                    foregroundDepth - backgroundDepth[index].coerceIn(0f, 1f)
                )
            )
        }
        return maximum.coerceIn(0f, 1f)
    }

    private const val SAMPLING_SAFETY_PIXELS = 6
}
