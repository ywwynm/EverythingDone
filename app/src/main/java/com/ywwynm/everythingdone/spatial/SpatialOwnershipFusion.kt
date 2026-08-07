package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor

/** 把实例身份与人像软边缘融合成渲染器可直接消费的互斥 ownership。 */
internal object SpatialOwnershipFusion {

    data class Result(
        val labels: ByteArray,
        val subjectMask: ByteArray,
        val alpha: ByteArray,
        /**
         * 只标记可信人物实例内部的连续性。它可以否决人物内部的伪深度断边，但不会把
         * 酒杯等非人物实例或真实背景孔洞并入人物表面。
         */
        val continuityLabels: ByteArray
    )

    fun build(
        segmentation: SpatialSegmentationData,
        matte: SpatialAlphaData?,
        meshWidth: Int,
        meshHeight: Int,
        alphaWidth: Int,
        alphaHeight: Int
    ): Result? {
        require(meshWidth > 1 && meshHeight > 1)
        require(alphaWidth > 0 && alphaHeight > 0)
        val labels = resampleLabels(
            segmentation.labels,
            segmentation.width,
            segmentation.height,
            meshWidth,
            meshHeight
        )
        val personLabels = segmentation.personLabels.toMutableSet()
        val matteMask = matte?.let { SpatialSubjectLayer.buildMask(it, meshWidth, meshHeight) }
        if (matteMask != null) {
            fuseMatteIntoPersonLabels(labels, matteMask, meshWidth, meshHeight, personLabels)
        }
        if (labels.none { (it.toInt() and 0xff) != 0 }) return null

        val subjectMask = ByteArray(labels.size) {
            if ((labels[it].toInt() and 0xff) == 0) 0 else 0xff.toByte()
        }
        val segmentationAlpha = resampleAlpha(
            segmentation.alpha,
            segmentation.width,
            segmentation.height,
            alphaWidth,
            alphaHeight
        )
        val targetLabels = resampleLabels(
            labels,
            meshWidth,
            meshHeight,
            alphaWidth,
            alphaHeight
        )
        val matteAlpha = matte?.let { sampleMatte(it, alphaWidth, alphaHeight) }
        val personSupportLabels = if (matteAlpha != null && personLabels.isNotEmpty()) {
            expandPersonLabelSupport(
                labels = targetLabels,
                width = alphaWidth,
                height = alphaHeight,
                personLabels = personLabels,
                maximumDistance = ceil(
                    maxOf(
                        alphaWidth.toFloat() / meshWidth,
                        alphaHeight.toFloat() / meshHeight
                    )
                ).toInt().coerceAtLeast(1)
            )
        } else {
            targetLabels
        }
        val alpha = ByteArray(segmentationAlpha.size) { index ->
            val categoricalLabel = targetLabels[index].toInt() and 0xff
            val label = if (categoricalLabel != 0) {
                categoricalLabel
            } else {
                personSupportLabels[index].toInt() and 0xff
            }
            if (label == 0) {
                0
            } else if (matteAlpha != null && label in personLabels) {
                val matteValue = matteAlpha[index].toInt() and 0xff
                if (
                    categoricalLabel == label &&
                    isTrustedPersonInterior(
                        labels = targetLabels,
                        width = alphaWidth,
                        height = alphaHeight,
                        index = index,
                        label = label
                    )
                ) {
                    // MODNet 偶尔会在暗衣内部塌零；可信内部继续由实例分割兜底。
                    maxOf(
                        segmentationAlpha[index].toInt() and 0xff,
                        matteValue
                    ).toByte()
                } else {
                    // 轮廓必须由高分辨率 matte 决定，不能让粗 RF mask 的高 alpha 重新
                    // 覆盖发丝/衣缘的软覆盖率并形成阶梯。
                    matteValue.toByte()
                }
            } else {
                if (categoricalLabel == 0) 0 else segmentationAlpha[index]
            }
        }
        val continuityLabels = ByteArray(labels.size) { index ->
            val label = labels[index].toInt() and 0xff
            if (label in personLabels) labels[index] else 0
        }
        return Result(labels, subjectMask, alpha, continuityLabels)
    }

    /**
     * categorical label 只负责实例身份，不能把更高分辨率的 matting 轮廓裁回网格边界。
     * 这里仅把人物身份向外传播一个网格采样宽度；最终覆盖率仍完全由 matte 决定。
     */
    private fun expandPersonLabelSupport(
        labels: ByteArray,
        width: Int,
        height: Int,
        personLabels: Set<Int>,
        maximumDistance: Int
    ): ByteArray {
        val result = labels.copyOf()
        val distance = IntArray(labels.size) { Int.MAX_VALUE }
        val queue: ArrayDeque<Int> = ArrayDeque()
        for (index in labels.indices) {
            if ((labels[index].toInt() and 0xff) in personLabels) {
                distance[index] = 0
                queue.addLast(index)
            }
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val nextDistance = distance[index] + 1
            if (nextDistance > maximumDistance) continue
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
                    val existing = labels[target].toInt() and 0xff
                    if (existing != 0 && existing !in personLabels) continue
                    if (nextDistance >= distance[target]) continue
                    result[target] = result[index]
                    distance[target] = nextDistance
                    queue.addLast(target)
                }
            }
        }
        return result
    }

    private fun isTrustedPersonInterior(
        labels: ByteArray,
        width: Int,
        height: Int,
        index: Int,
        label: Int
    ): Boolean {
        val centerX = index % width
        val centerY = index / width
        for (offsetY in -PERSON_BOUNDARY_RADIUS..PERSON_BOUNDARY_RADIUS) {
            val y = centerY + offsetY
            if (y !in 0 until height) return false
            for (offsetX in -PERSON_BOUNDARY_RADIUS..PERSON_BOUNDARY_RADIUS) {
                val x = centerX + offsetX
                if (x !in 0 until width ||
                    (labels[y * width + x].toInt() and 0xff) != label
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun fuseMatteIntoPersonLabels(
        labels: ByteArray,
        matteMask: ByteArray,
        width: Int,
        height: Int,
        personLabels: MutableSet<Int>
    ) {
        val queue: ArrayDeque<Int> = ArrayDeque()
        val owner = IntArray(labels.size)
        val distance = IntArray(labels.size) { Int.MAX_VALUE }
        // Matting 只细化实例分割的窄边界。传播过宽会把与人物相接的桌面、椅背等
        // false positive 变成人物刚性层；高分辨率亚像素轮廓由后续 alpha 支持带保留。
        val maximumExpansionSteps = 2
        for (index in labels.indices) {
            val label = labels[index].toInt() and 0xff
            if (label in personLabels && (matteMask[index].toInt() and 0xff) != 0) {
                owner[index] = label
                distance[index] = 0
                queue.addLast(index)
            }
        }
        if (queue.isEmpty()) {
            val nextLabel = ((labels.maxOfOrNull { it.toInt() and 0xff } ?: 0) + 1)
                .coerceAtMost(254)
            if (nextLabel == 0 || nextLabel in personLabels) return
            for (index in matteMask.indices) {
                if ((matteMask[index].toInt() and 0xff) != 0 &&
                    (labels[index].toInt() and 0xff) == 0
                ) {
                    labels[index] = nextLabel.toByte()
                }
            }
            personLabels += nextLabel
            return
        }

        fun offer(from: Int, target: Int) {
            if ((matteMask[target].toInt() and 0xff) == 0) return
            val targetDistance = distance[from] + 1
            if (targetDistance > maximumExpansionSteps ||
                (owner[target] != 0 && targetDistance >= distance[target])
            ) {
                return
            }
            // 已识别的非人物对象保持自己的实例身份，不能被人像 matte 吞掉。
            val existing = labels[target].toInt() and 0xff
            if (existing != 0 && existing !in personLabels) return
            owner[target] = owner[from]
            distance[target] = targetDistance
            queue.addLast(target)
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            if (x > 0) offer(index, index - 1)
            if (x + 1 < width) offer(index, index + 1)
            if (y > 0) offer(index, index - width)
            if (y + 1 < height) offer(index, index + width)
        }
        for (index in labels.indices) {
            if (owner[index] != 0 && (labels[index].toInt() and 0xff) == 0) {
                labels[index] = owner[index].toByte()
            }
        }
    }

    private fun resampleLabels(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray = ByteArray(targetWidth * targetHeight) { index ->
        val x = index % targetWidth
        val y = index / targetWidth
        val sourceX = ((x + 0.5f) * sourceWidth / targetWidth)
            .toInt().coerceIn(0, sourceWidth - 1)
        val sourceY = ((y + 0.5f) * sourceHeight / targetHeight)
            .toInt().coerceIn(0, sourceHeight - 1)
        source[sourceY * sourceWidth + sourceX]
    }

    private fun resampleAlpha(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray = ByteArray(targetWidth * targetHeight) { index ->
        val x = index % targetWidth
        val y = index / targetWidth
        val sourceX = (x + 0.5f) * sourceWidth / targetWidth - 0.5f
        val sourceY = (y + 0.5f) * sourceHeight / targetHeight - 0.5f
        bilinearByte(source, sourceWidth, sourceHeight, sourceX, sourceY)
    }

    private fun sampleMatte(
        matte: SpatialAlphaData,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray = ByteArray(targetWidth * targetHeight) { index ->
        val x = index % targetWidth
        val y = index / targetWidth
        val sourceX = (x + 0.5f) * matte.width / targetWidth - 0.5f
        val sourceY = (y + 0.5f) * matte.height / targetHeight - 0.5f
        val value = bilinearFloat(matte.values, matte.width, matte.height, sourceX, sourceY)
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
    }

    private fun bilinearByte(
        values: ByteArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Byte {
        fun value(index: Int) = (values[index].toInt() and 0xff).toFloat()
        return bilinear(width, height, x, y) { value(it) }
            .toInt().coerceIn(0, 255).toByte()
    }

    private fun bilinearFloat(
        values: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Float = bilinear(width, height, x, y) { values[it] }

    private inline fun bilinear(
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        value: (Int) -> Float
    ): Float {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = value(y0 * width + x0) * (1f - fx) + value(y0 * width + x1) * fx
        val bottom = value(y1 * width + x0) * (1f - fx) + value(y1 * width + x1) * fx
        return top * (1f - fy) + bottom * fy
    }

    private const val PERSON_BOUNDARY_RADIUS = 2
}
