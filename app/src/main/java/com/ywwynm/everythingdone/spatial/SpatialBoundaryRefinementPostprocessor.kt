package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 将 promptable segmentation 的结果限制在 RF-DETR 轮廓窄带内。
 *
 * RF-DETR 继续提供实例身份、可信内部和最大搜索区域；refiner 只能调整边界，不能在脸、
 * 衣物等对象内部打孔，也不能不受控地扩张到其它对象。质量门逐实例生效，失败即保留原结果。
 */
internal object SpatialBoundaryRefinementPostprocessor {

    data class Candidate(
        val label: Int,
        val predictedIou: Float,
        val maskLogits: FloatArray,
        val width: Int,
        val height: Int
    ) {
        init {
            require(label in 1..254)
            require(width > 1 && height > 1)
            require(maskLogits.size == width * height)
        }
    }

    fun refine(
        source: SpatialSegmentationData,
        candidates: List<Candidate>,
        bandRadius: Int = maxOf(
            MIN_BAND_RADIUS,
            (maxOf(source.width, source.height).toFloat() / RF_MASK_LONG_EDGE).roundToInt()
        )
    ): SpatialSegmentationData {
        require(bandRadius >= 1)
        val candidatesByLabel = candidates
            .filter { it.label in source.instances.map(SpatialSegmentationInstance::label) }
            .groupBy(Candidate::label)
            .mapValues { (_, values) -> values.maxBy(Candidate::predictedIou) }
        val outputLabels = ByteArray(source.labels.size)
        val outputAlpha = ByteArray(source.alpha.size)
        val winningStrength = FloatArray(source.labels.size)

        for (instance in source.instances) {
            val rfMask = BooleanArray(source.labels.size) { index ->
                (source.labels[index].toInt() and 0xff) == instance.label
            }
            if (rfMask.none { it }) continue
            val candidate = candidatesByLabel[instance.label]
            val refinement = candidate?.let {
                buildAcceptedRefinement(source, rfMask, it, bandRadius)
            }
            for (index in rfMask.indices) {
                val included = refinement?.mask?.get(index) ?: rfMask[index]
                if (!included) continue
                val alpha = when {
                    refinement == null -> source.alpha[index].toInt() and 0xff
                    refinement.trustedInterior[index] -> source.alpha[index].toInt() and 0xff
                    rfMask[index] -> maxOf(
                        source.alpha[index].toInt() and 0xff,
                        probabilityByte(refinement.logits[index])
                    )
                    else -> probabilityByte(refinement.logits[index])
                }
                if (alpha <= 0) continue
                val strength = alpha / 255f * instance.confidence
                if (strength <= winningStrength[index]) continue
                winningStrength[index] = strength
                outputLabels[index] = instance.label.toByte()
                outputAlpha[index] = alpha.toByte()
            }
        }

        val counts = IntArray(255)
        outputLabels.forEach { counts[it.toInt() and 0xff]++ }
        val outputInstances = source.instances.mapNotNull { instance ->
            counts[instance.label].takeIf { it > 0 }?.let { count ->
                instance.copy(pixelCount = count)
            }
        }
        return SpatialSegmentationData(
            width = source.width,
            height = source.height,
            labels = outputLabels,
            alpha = outputAlpha,
            instances = outputInstances
        )
    }

    private fun buildAcceptedRefinement(
        source: SpatialSegmentationData,
        rfMask: BooleanArray,
        candidate: Candidate,
        bandRadius: Int
    ): AcceptedRefinement? {
        if (!candidate.predictedIou.isFinite() || candidate.predictedIou < MIN_PREDICTED_IOU) {
            return null
        }
        val logits = resampleLogits(candidate, source.width, source.height) ?: return null
        val edgeMask = BooleanArray(logits.size) { logits[it] > 0f }
        var intersection = 0
        var union = 0
        var rfPixels = 0
        var edgePixels = 0
        for (index in rfMask.indices) {
            val rf = rfMask[index]
            val edge = edgeMask[index]
            if (rf) rfPixels++
            if (edge) edgePixels++
            if (rf && edge) intersection++
            if (rf || edge) union++
        }
        if (rfPixels == 0 || edgePixels == 0 || union == 0) return null
        val iou = intersection.toFloat() / union
        val areaRatio = edgePixels.toFloat() / rfPixels
        if (iou < MIN_RF_TO_REFINER_IOU || areaRatio !in MIN_AREA_RATIO..MAX_AREA_RATIO) {
            return null
        }

        val expanded = dilate(rfMask, source.width, source.height, bandRadius)
        val trustedInterior = erode(rfMask, source.width, source.height, bandRadius)
        val fused = BooleanArray(rfMask.size) { index ->
            trustedInterior[index] || (edgeMask[index] && expanded[index])
        }
        restoreNewInteriorHoles(fused, rfMask, source.width, source.height)
        removeDetachedExpansion(fused, rfMask, source.width, source.height)
        if (fused.none { it }) return null
        return AcceptedRefinement(fused, trustedInterior, logits)
    }

    private fun resampleLogits(
        candidate: Candidate,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray? {
        if (candidate.maskLogits.any { !it.isFinite() }) return null
        return FloatArray(targetWidth * targetHeight) { index ->
            val x = index % targetWidth
            val y = index / targetWidth
            val sourceX = (x + 0.5f) * candidate.width / targetWidth - 0.5f
            val sourceY = (y + 0.5f) * candidate.height / targetHeight - 0.5f
            bilinear(
                candidate.maskLogits,
                candidate.width,
                candidate.height,
                sourceX,
                sourceY
            )
        }
    }

    private fun dilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        var current = source.copyOf()
        repeat(radius) {
            val next = current.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (!current[y * width + x]) continue
                    for (dy in -1..1) {
                        val targetY = y + dy
                        if (targetY !in 0 until height) continue
                        for (dx in -1..1) {
                            val targetX = x + dx
                            if (targetX in 0 until width) next[targetY * width + targetX] = true
                        }
                    }
                }
            }
            current = next
        }
        return current
    }

    private fun erode(
        source: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        var current = source.copyOf()
        repeat(radius) {
            val next = BooleanArray(current.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (!current[y * width + x]) continue
                    var keep = true
                    loop@ for (dy in -1..1) {
                        val targetY = y + dy
                        if (targetY !in 0 until height) {
                            keep = false
                            break
                        }
                        for (dx in -1..1) {
                            val targetX = x + dx
                            if (targetX !in 0 until width || !current[targetY * width + targetX]) {
                                keep = false
                                break@loop
                            }
                        }
                    }
                    next[y * width + x] = keep
                }
            }
            current = next
        }
        return current
    }

    /** 只允许从原 RF 外轮廓连通进入的收缩，恢复 refiner 在对象内部新造的孔。 */
    private fun restoreNewInteriorHoles(
        fused: BooleanArray,
        rfMask: BooleanArray,
        width: Int,
        height: Int
    ) {
        val removable = BooleanArray(fused.size) { rfMask[it] && !fused[it] }
        val connectedToOutside = BooleanArray(fused.size)
        val queue = ArrayDeque<Int>()
        fun offer(index: Int) {
            if (removable[index] && !connectedToOutside[index]) {
                connectedToOutside[index] = true
                queue.addLast(index)
            }
        }
        for (index in removable.indices) {
            if (!removable[index]) continue
            val x = index % width
            val y = index / width
            var touchesOutside = x == 0 || y == 0 || x + 1 == width || y + 1 == height
            if (!touchesOutside) {
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!rfMask[(y + dy) * width + x + dx]) touchesOutside = true
                    }
                }
            }
            if (touchesOutside) offer(index)
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            for (dy in -1..1) {
                val nextY = y + dy
                if (nextY !in 0 until height) continue
                for (dx in -1..1) {
                    val nextX = x + dx
                    if (nextX in 0 until width) offer(nextY * width + nextX)
                }
            }
        }
        for (index in removable.indices) {
            if (removable[index] && !connectedToOutside[index]) fused[index] = true
        }
    }

    /** 删除 prompt mask 在窄带内产生、但与原实例完全不连通的小岛。 */
    private fun removeDetachedExpansion(
        fused: BooleanArray,
        rfMask: BooleanArray,
        width: Int,
        height: Int
    ) {
        val visited = BooleanArray(fused.size)
        val queue = ArrayDeque<Int>()
        val component = ArrayList<Int>()
        for (start in fused.indices) {
            if (!fused[start] || visited[start]) continue
            visited[start] = true
            queue.addLast(start)
            component.clear()
            var overlapsSource = false
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component += index
                if (rfMask[index]) overlapsSource = true
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val nextY = y + dy
                    if (nextY !in 0 until height) continue
                    for (dx in -1..1) {
                        val nextX = x + dx
                        if (nextX !in 0 until width) continue
                        val next = nextY * width + nextX
                        if (fused[next] && !visited[next]) {
                            visited[next] = true
                            queue.addLast(next)
                        }
                    }
                }
            }
            if (!overlapsSource) component.forEach { fused[it] = false }
        }
    }

    private fun bilinear(
        values: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Float {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) +
            values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun probabilityByte(logit: Float): Int =
        (sigmoid(logit) * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun sigmoid(value: Float): Float =
        (1.0 / (1.0 + exp(-value.coerceIn(-80f, 80f).toDouble()))).toFloat()

    private data class AcceptedRefinement(
        val mask: BooleanArray,
        val trustedInterior: BooleanArray,
        val logits: FloatArray
    )

    private const val RF_MASK_LONG_EDGE = 78f
    private const val MIN_BAND_RADIUS = 2
    private const val MIN_PREDICTED_IOU = 0.65f
    private const val MIN_RF_TO_REFINER_IOU = 0.50f
    private const val MIN_AREA_RATIO = 0.55f
    private const val MAX_AREA_RATIO = 1.65f
}
