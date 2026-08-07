package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把 RF-DETR 的“语义实例”收敛为渲染需要的“运动实例”。
 *
 * 语义分割会合理地把人、背包、手机、球拍等拆开，但有限视点的空间照片不能让这些接触
 * 部件因单目深度噪声各自漂移。这里只合并具备明确人身依附语义、实际边界接触且深度没有
 * 强反证的实例；两个人、普通相邻物体以及有间隙的配件始终保持独立。
 */
internal object SpatialOwnershipRelationResolver {

    fun resolve(
        labels: ByteArray,
        width: Int,
        height: Int,
        instances: List<SpatialSegmentationInstance>,
        depth: FloatArray
    ): ByteArray {
        require(width > 1 && height > 1)
        require(labels.size == width * height)
        require(depth.size == labels.size)
        if (instances.size < 2) return labels.copyOf()

        val instanceByLabel = instances.associateBy { it.label }
        val stats = buildStats(labels, depth, width, instanceByLabel.keys)
        val people = instances.filter {
            it.classId == SpatialSegmentationPostprocessor.PERSON_CLASS_ID &&
                stats[it.label]?.pixelCount != null
        }
        if (people.isEmpty()) return labels.copyOf()

        val remap = IntArray(MAX_LABEL + 1) { it }
        for (child in instances) {
            val childStats = stats[child.label] ?: continue
            val policy = attachmentPolicy(child.classId) ?: continue
            val parent = people
                .asSequence()
                .filter { it.label != child.label }
                .mapNotNull { person ->
                    val parentStats = stats[person.label] ?: return@mapNotNull null
                    val sizeRatio = childStats.pixelCount.toFloat() / parentStats.pixelCount
                    if (sizeRatio > policy.maximumChildToPersonRatio) return@mapNotNull null
                    val depthDelta = abs(childStats.medianDepth - parentStats.medianDepth)
                    if (depthDelta > policy.maximumDepthDelta) return@mapNotNull null
                    if (!hasAttachmentLayout(childStats, parentStats, policy.layout)) {
                        return@mapNotNull null
                    }
                    val contact = contactPixels(
                        labels = labels,
                        width = width,
                        height = height,
                        childLabel = child.label,
                        parentLabel = person.label
                    )
                    val minimumContact = max(
                        1,
                        (sqrt(childStats.pixelCount.toDouble()) * MIN_CONTACT_LINEAR_RATIO)
                            .roundToInt()
                    )
                    if (contact < minimumContact) return@mapNotNull null
                    ParentCandidate(
                        label = person.label,
                        contact = contact,
                        depthDelta = depthDelta,
                        confidence = person.confidence
                    )
                }
                .sortedWith(
                    compareByDescending<ParentCandidate> { it.contact }
                        .thenBy { it.depthDelta }
                        .thenByDescending { it.confidence }
                        .thenBy { it.label }
                )
                .firstOrNull()
            if (parent != null) remap[child.label] = parent.label
        }

        return ByteArray(labels.size) { index ->
            val label = labels[index].toInt() and 0xff
            if (label in 1..MAX_LABEL) remap[label].toByte() else 0
        }
    }

    private fun buildStats(
        labels: ByteArray,
        depth: FloatArray,
        width: Int,
        acceptedLabels: Set<Int>
    ): Map<Int, RegionStats> {
        val counts = IntArray(MAX_LABEL + 1)
        val histograms = Array(MAX_LABEL + 1) { IntArray(DEPTH_BINS) }
        val sumX = LongArray(MAX_LABEL + 1)
        val sumY = LongArray(MAX_LABEL + 1)
        val minimumX = IntArray(MAX_LABEL + 1) { Int.MAX_VALUE }
        val minimumY = IntArray(MAX_LABEL + 1) { Int.MAX_VALUE }
        val maximumX = IntArray(MAX_LABEL + 1) { Int.MIN_VALUE }
        val maximumY = IntArray(MAX_LABEL + 1) { Int.MIN_VALUE }
        for (index in labels.indices) {
            val label = labels[index].toInt() and 0xff
            if (label !in acceptedLabels || label !in 1..MAX_LABEL) continue
            counts[label]++
            val x = index % width
            val y = index / width
            sumX[label] += x
            sumY[label] += y
            minimumX[label] = minOf(minimumX[label], x)
            minimumY[label] = minOf(minimumY[label], y)
            maximumX[label] = maxOf(maximumX[label], x)
            maximumY[label] = maxOf(maximumY[label], y)
            val bin = (depth[index].coerceIn(0f, 1f) * (DEPTH_BINS - 1))
                .roundToInt()
            histograms[label][bin]++
        }
        return buildMap {
            for (label in acceptedLabels) {
                val count = counts.getOrElse(label) { 0 }
                if (count == 0) continue
                put(
                    label,
                    RegionStats(
                        pixelCount = count,
                        medianDepth = histogramMedian(histograms[label], count),
                        centerX = sumX[label].toFloat() / count,
                        centerY = sumY[label].toFloat() / count,
                        minimumX = minimumX[label],
                        minimumY = minimumY[label],
                        maximumX = maximumX[label],
                        maximumY = maximumY[label]
                    )
                )
            }
        }
    }

    /**
     * 边界相触也可能只是前景物体遮住人物。随身小物的中心必须仍位于人物占据范围内；骑乘物则允许
     * “人中心位于载体范围内”。这个几何证据只负责拒绝误绑定，不会凭空间接近主动创建关系。
     */
    private fun hasAttachmentLayout(
        child: RegionStats,
        parent: RegionStats,
        layout: AttachmentLayout
    ): Boolean = when (layout) {
        AttachmentLayout.BODY -> containsCenter(
            bounds = parent,
            target = child,
            marginRatio = BODY_BOUNDS_MARGIN_RATIO
        )
        AttachmentLayout.RIDEABLE -> containsCenter(
            bounds = parent,
            target = child,
            marginRatio = BODY_BOUNDS_MARGIN_RATIO
        ) || containsCenter(
            bounds = child,
            target = parent,
            marginRatio = RIDEABLE_BOUNDS_MARGIN_RATIO
        )
    }

    private fun containsCenter(
        bounds: RegionStats,
        target: RegionStats,
        marginRatio: Float
    ): Boolean {
        val marginX = (bounds.maximumX - bounds.minimumX + 1) * marginRatio
        val marginY = (bounds.maximumY - bounds.minimumY + 1) * marginRatio
        return target.centerX >= bounds.minimumX - marginX &&
            target.centerX <= bounds.maximumX + marginX &&
            target.centerY >= bounds.minimumY - marginY &&
            target.centerY <= bounds.maximumY + marginY
    }

    private fun contactPixels(
        labels: ByteArray,
        width: Int,
        height: Int,
        childLabel: Int,
        parentLabel: Int
    ): Int {
        var contact = 0
        for (index in labels.indices) {
            if ((labels[index].toInt() and 0xff) != childLabel) continue
            val x = index % width
            val y = index / width
            var touches = false
            loop@ for (offsetY in -1..1) {
                val targetY = y + offsetY
                if (targetY !in 0 until height) continue
                for (offsetX in -1..1) {
                    if (offsetX == 0 && offsetY == 0) continue
                    val targetX = x + offsetX
                    if (targetX in 0 until width &&
                        (labels[targetY * width + targetX].toInt() and 0xff) == parentLabel
                    ) {
                        touches = true
                        break@loop
                    }
                }
            }
            if (touches) contact++
        }
        return contact
    }

    private fun histogramMedian(histogram: IntArray, count: Int): Float {
        val middle = (count + 1) / 2
        var accumulated = 0
        for (bin in histogram.indices) {
            accumulated += histogram[bin]
            if (accumulated >= middle) return bin.toFloat() / (DEPTH_BINS - 1)
        }
        return 0.5f
    }

    private fun attachmentPolicy(classId: Int): AttachmentPolicy? = when (classId) {
        in BODY_ATTACHMENT_CLASSES -> AttachmentPolicy(
            maximumChildToPersonRatio = 0.65f,
            maximumDepthDelta = 0.28f,
            layout = AttachmentLayout.BODY
        )
        in RIDEABLE_ATTACHMENT_CLASSES -> AttachmentPolicy(
            maximumChildToPersonRatio = 3.0f,
            maximumDepthDelta = 0.22f,
            layout = AttachmentLayout.RIDEABLE
        )
        else -> null
    }

    private data class RegionStats(
        val pixelCount: Int,
        val medianDepth: Float,
        val centerX: Float,
        val centerY: Float,
        val minimumX: Int,
        val minimumY: Int,
        val maximumX: Int,
        val maximumY: Int
    )

    private data class AttachmentPolicy(
        val maximumChildToPersonRatio: Float,
        val maximumDepthDelta: Float,
        val layout: AttachmentLayout
    )

    private enum class AttachmentLayout {
        BODY,
        RIDEABLE
    }

    private data class ParentCandidate(
        val label: Int,
        val contact: Int,
        val depthDelta: Float,
        val confidence: Float
    )

    private const val MAX_LABEL = 254
    private const val DEPTH_BINS = 256
    private const val MIN_CONTACT_LINEAR_RATIO = 0.18
    private const val BODY_BOUNDS_MARGIN_RATIO = 0.04f
    private const val RIDEABLE_BOUNDS_MARGIN_RATIO = 0.15f

    // COCO 稀疏 category ID：穿戴、携带或通常由手直接控制的小物体。
    private val BODY_ATTACHMENT_CLASSES = setOf(
        27, 28, 31, 32, 33, // backpack, umbrella, handbag, tie, suitcase
        34, 37, 38, 39, 40, 43, // frisbee, ball, kite, bat, glove, racket
        44, 46, 47, 48, 49, 50, 51, // bottle, glass, cup, cutlery, bowl
        75, 77, 84, 87, 89, 90 // remote, phone, book, scissors, drier, toothbrush
    )

    // 人与这些对象接触时通常构成同一运动装配；允许子对象大于人物。
    private val RIDEABLE_ATTACHMENT_CLASSES = setOf(
        2, 4, 19, 35, 36, 41, 42 // bicycle, motorcycle, horse, skis, board, skateboard, surfboard
    )
}
