package com.ywwynm.everythingdone.spatial

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 模型无关的可见对象层契约。
 *
 * ownership provider 只需给出互斥 mask/alpha；本类不关心它来自 MODNet、EdgeTAM 或未来
 * 模型。对象可见像素使用单一代表深度，因此层内投影只有整体平移，不会把脸、文字或直线
 * 按逐像素深度拉伸。原始连续表面的深度保持不变，对象区域由 [excludedFromBase] 明确让位
 * 给隐藏背景。
 */
internal object SpatialOwnershipLayer {

    /**
     * 渲染器消费的互斥对象图。0 表示连续场景表面，1..254 表示独立对象。
     * [labels] 与深度网格同分辨率；每个对象只使用一个代表深度，因此对象内部没有
     * 逐像素拉伸，不同对象仍保留各自的层间视差。
     */
    data class Graph(
        val labels: ByteArray,
        val excludedFromBase: BooleanArray,
        val layers: List<ObjectLayer>
    )

    data class ObjectLayer(
        val label: Int,
        val representativeDepth: Float,
        val pixelCount: Int
    ) {
        fun displacement(parallaxMotion: Float): Float =
            parallaxMotion * (representativeDepth - 0.5f)
    }

    data class Layer(
        val baseDepth: FloatArray,
        val excludedFromBase: BooleanArray,
        val representativeDepth: Float
    ) {
        fun displacement(parallaxMotion: Float): Float =
            parallaxMotion * (representativeDepth - 0.5f)
    }

    fun build(
        baseDepth: FloatArray,
        width: Int,
        height: Int,
        ownershipMask: ByteArray
    ): Layer? {
        require(width > 1 && height > 1)
        require(baseDepth.size == width * height)
        require(ownershipMask.size == baseDepth.size)
        val excluded = BooleanArray(baseDepth.size) {
            (ownershipMask[it].toInt() and 0xff) >= MASK_THRESHOLD
        }
        if (excluded.none { it }) return null

        val histogram = IntArray(DEPTH_BINS)
        var count = 0
        for (index in baseDepth.indices) {
            if (!excluded[index]) continue
            val bin = (baseDepth[index].coerceIn(0f, 1f) * (DEPTH_BINS - 1))
                .roundToInt()
            histogram[bin]++
            count++
        }
        val middle = (count + 1) / 2
        var accumulated = 0
        var median = 0.5f
        for (bin in histogram.indices) {
            accumulated += histogram[bin]
            if (accumulated >= middle) {
                median = bin.toFloat() / (DEPTH_BINS - 1)
                break
            }
        }
        return Layer(
            baseDepth = baseDepth.copyOf(),
            excludedFromBase = excluded,
            representativeDepth = (median + OBJECT_DEPTH_OFFSET).coerceAtMost(MAX_OBJECT_DEPTH)
        )
    }

    /**
     * 把旧 provider 的二值 union mask 拆成互斥对象层。八邻域避免把斜向相连的发丝、
     * 手指等误拆；过小碎片不会丢弃，而是并入空间上最近的主要对象，防止 base 被挖空后
     * 没有对应对象层可绘制。
     */
    fun buildGraphFromMask(
        baseDepth: FloatArray,
        width: Int,
        height: Int,
        ownershipMask: ByteArray
    ): Graph {
        require(width > 1 && height > 1)
        require(baseDepth.size == width * height)
        require(ownershipMask.size == baseDepth.size)

        val components = connectedComponents(
            ownershipMask = ownershipMask,
            width = width,
            height = height
        )
        if (components.isEmpty()) {
            return Graph(
                labels = ByteArray(baseDepth.size),
                excludedFromBase = BooleanArray(baseDepth.size),
                layers = emptyList()
            )
        }

        val minimumMajorPixels = max(
            MIN_COMPONENT_PIXELS,
            (baseDepth.size * MIN_COMPONENT_RATIO).roundToInt()
        )
        val sorted = components.sortedByDescending { it.indices.size }
        val major = sorted
            .filter { it.indices.size >= minimumMajorPixels }
            .take(MAX_OBJECT_LAYERS)
            .ifEmpty { listOf(sorted.first()) }
        val componentLabels = major.withIndex().associate { (index, component) ->
            component.id to index + 1
        }.toMutableMap()

        for (component in sorted) {
            if (componentLabels.containsKey(component.id)) continue
            val closest = major.minBy { candidate ->
                val dx = component.centerX - candidate.centerX
                val dy = component.centerY - candidate.centerY
                dx * dx + dy * dy
            }
            componentLabels[component.id] = checkNotNull(componentLabels[closest.id])
        }

        val labels = ByteArray(baseDepth.size)
        for (component in components) {
            val label = checkNotNull(componentLabels[component.id])
            for (index in component.indices) labels[index] = label.toByte()
        }
        val graph = buildGraphFromLabels(
            baseDepth = baseDepth,
            width = width,
            height = height,
            ownershipLabels = labels,
            expandBoundary = false
        )
        val expandedLabels = expandLabels(graph.labels, width, height)
        return graph.copy(
            labels = expandedLabels,
            excludedFromBase = BooleanArray(expandedLabels.size) {
                (expandedLabels[it].toInt() and 0xff) != BACKGROUND_LABEL
            }
        )
    }

    /** 接收实例分割 provider 输出的互斥标签图；同一 label 即使被遮挡成多块也保持同层运动。 */
    fun buildGraphFromLabels(
        baseDepth: FloatArray,
        width: Int,
        height: Int,
        ownershipLabels: ByteArray,
        expandBoundary: Boolean = true
    ): Graph {
        require(width > 1 && height > 1)
        require(baseDepth.size == width * height)
        require(ownershipLabels.size == baseDepth.size)

        val labels = ownershipLabels.copyOf()
        val excluded = BooleanArray(labels.size) {
            (labels[it].toInt() and 0xff) != BACKGROUND_LABEL
        }
        val independentLayers = (1..MAX_LABEL)
            .mapNotNull { label ->
                val histogram = IntArray(DEPTH_BINS)
                var count = 0
                for (index in labels.indices) {
                    if ((labels[index].toInt() and 0xff) != label) continue
                    val bin = (baseDepth[index].coerceIn(0f, 1f) * (DEPTH_BINS - 1))
                        .roundToInt()
                    histogram[bin]++
                    count++
                }
                if (count == 0) return@mapNotNull null
                ObjectLayer(
                    label = label,
                    representativeDepth = histogramMedian(histogram, count),
                    pixelCount = count
                )
            }
            .sortedBy { it.representativeDepth }
        val motionGraph = coupleContactConstrainedMotion(
            labels = labels,
            width = width,
            height = height,
            layers = independentLayers
        )
        if (!expandBoundary) {
            return Graph(
                labels = motionGraph.labels,
                excludedFromBase = excluded,
                layers = motionGraph.layers
            )
        }
        val expandedLabels = expandLabels(motionGraph.labels, width, height)
        return Graph(
            labels = expandedLabels,
            excludedFromBase = BooleanArray(expandedLabels.size) {
                (expandedLabels[it].toInt() and 0xff) != BACKGROUND_LABEL
            },
            layers = motionGraph.layers
        )
    }

    /**
     * 把任意 ownership provider 的软 alpha 变成持久化平面。低分辨率二值 mask 只负责
     * 所有权门控；轮廓覆盖率仍来自原始软 alpha。门控向外放宽一个 mask sample，避免
     * 0.2 阈值与双线性重采样截掉半透明发丝。
     */
    fun buildAlpha(
        matte: SpatialAlphaData,
        ownershipMask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray {
        require(maskWidth > 1 && maskHeight > 1)
        require(targetWidth > 0 && targetHeight > 0)
        require(ownershipMask.size == maskWidth * maskHeight)
        val expandedOwnership = dilateMask(ownershipMask, maskWidth, maskHeight)
        return ByteArray(targetWidth * targetHeight) { index ->
            val targetX = index % targetWidth
            val targetY = index / targetWidth
            val maskX = ((targetX + 0.5f) * maskWidth / targetWidth)
                .toInt()
                .coerceIn(0, maskWidth - 1)
            val maskY = ((targetY + 0.5f) * maskHeight / targetHeight)
                .toInt()
                .coerceIn(0, maskHeight - 1)
            if (!expandedOwnership[maskY * maskWidth + maskX]) {
                0
            } else {
                val sourceX = (targetX + 0.5f) * matte.width / targetWidth - 0.5f
                val sourceY = (targetY + 0.5f) * matte.height / targetHeight - 0.5f
                val x0 = floor(sourceX).toInt().coerceIn(0, matte.width - 1)
                val y0 = floor(sourceY).toInt().coerceIn(0, matte.height - 1)
                val x1 = (x0 + 1).coerceAtMost(matte.width - 1)
                val y1 = (y0 + 1).coerceAtMost(matte.height - 1)
                val fractionX = (sourceX - x0).coerceIn(0f, 1f)
                val fractionY = (sourceY - y0).coerceIn(0f, 1f)
                val top = lerp(
                    matte.values[y0 * matte.width + x0],
                    matte.values[y0 * matte.width + x1],
                    fractionX
                )
                val bottom = lerp(
                    matte.values[y1 * matte.width + x0],
                    matte.values[y1 * matte.width + x1],
                    fractionX
                )
                (
                    lerp(top, bottom, fractionY).coerceIn(0f, 1f) * 255f + 0.5f
                    ).toInt().coerceIn(0, 255).toByte()
            }
        }
    }

    private fun dilateMask(
        source: ByteArray,
        width: Int,
        height: Int
    ): BooleanArray = BooleanArray(source.size) { index ->
        val x = index % width
        val y = index / width
        var owned = false
        loop@ for (offsetY in -1..1) {
            val targetY = y + offsetY
            if (targetY !in 0 until height) continue
            for (offsetX in -1..1) {
                val targetX = x + offsetX
                if (targetX in 0 until width &&
                    (source[targetY * width + targetX].toInt() and 0xff) >= MASK_THRESHOLD
                ) {
                    owned = true
                    break@loop
                }
            }
        }
        owned
    }

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private fun connectedComponents(
        ownershipMask: ByteArray,
        width: Int,
        height: Int
    ): List<Component> {
        val visited = BooleanArray(ownershipMask.size)
        val queue = IntArray(ownershipMask.size)
        val result = mutableListOf<Component>()
        var nextId = 1
        for (start in ownershipMask.indices) {
            if (visited[start] ||
                (ownershipMask[start].toInt() and 0xff) < MASK_THRESHOLD
            ) {
                continue
            }
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            val indices = ArrayList<Int>()
            var sumX = 0L
            var sumY = 0L
            while (head < tail) {
                val index = queue[head++]
                indices += index
                val x = index % width
                val y = index / width
                sumX += x
                sumY += y
                for (offsetY in -1..1) {
                    val neighborY = y + offsetY
                    if (neighborY !in 0 until height) continue
                    for (offsetX in -1..1) {
                        if (offsetX == 0 && offsetY == 0) continue
                        val neighborX = x + offsetX
                        if (neighborX !in 0 until width) continue
                        val neighbor = neighborY * width + neighborX
                        if (!visited[neighbor] &&
                            (ownershipMask[neighbor].toInt() and 0xff) >= MASK_THRESHOLD
                        ) {
                            visited[neighbor] = true
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
            result += Component(
                id = nextId++,
                indices = indices.toIntArray(),
                centerX = sumX.toFloat() / indices.size,
                centerY = sumY.toFloat() / indices.size
            )
        }
        return result
    }

    private fun histogramMedian(histogram: IntArray, count: Int): Float {
        val middle = (count + 1) / 2
        var accumulated = 0
        for (bin in histogram.indices) {
            accumulated += histogram[bin]
            if (accumulated >= middle) {
                return bin.toFloat() / (DEPTH_BINS - 1)
            }
        }
        return 0.5f
    }

    /**
     * 当前 LDI-lite 只有可见对象层与一张全局隐藏背景，无法表达“前景对象后面仍是另一个
     * 前景对象”的遮挡链。两个近深度对象若在原图中有较长接触边，独立平移会凭空拉开一条
     * 无来源缝隙。持久化实例标签保持不变；渲染图只把同一运动组映射为一次联合覆盖，从而避免
     * 低分辨率实例标签在组内产生锯齿切缝。分离对象、偶然单点接触和深度差明确的对象不会被耦合。
     */
    private fun coupleContactConstrainedMotion(
        labels: ByteArray,
        width: Int,
        height: Int,
        layers: List<ObjectLayer>
    ): MotionGraph {
        if (layers.size < 2) return MotionGraph(labels, layers)
        val byLabel = arrayOfNulls<ObjectLayer>(MAX_LABEL + 1)
        for (layer in layers) byLabel[layer.label] = layer
        val contacts = IntArray((MAX_LABEL + 1) * (MAX_LABEL + 1))

        fun registerContact(first: Int, second: Int) {
            if (first == BACKGROUND_LABEL || second == BACKGROUND_LABEL || first == second) {
                return
            }
            val low = minOf(first, second)
            val high = maxOf(first, second)
            contacts[low * (MAX_LABEL + 1) + high]++
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val label = labels[index].toInt() and 0xff
                if (x + 1 < width) {
                    registerContact(label, labels[index + 1].toInt() and 0xff)
                }
                if (y + 1 < height) {
                    registerContact(label, labels[index + width].toInt() and 0xff)
                }
            }
        }

        val candidates = ArrayList<Contact>()
        for (first in 1 until MAX_LABEL) {
            val firstLayer = byLabel[first] ?: continue
            for (second in first + 1..MAX_LABEL) {
                val secondLayer = byLabel[second] ?: continue
                val count = contacts[first * (MAX_LABEL + 1) + second]
                val minimumContact = max(
                    MIN_CONTACT_EDGES,
                    (sqrt(minOf(firstLayer.pixelCount, secondLayer.pixelCount).toFloat()) *
                        MIN_CONTACT_SCALE).roundToInt()
                )
                if (count >= minimumContact) {
                    candidates += Contact(first, second, count)
                }
            }
        }
        if (candidates.isEmpty()) return MotionGraph(labels, layers)

        val parent = IntArray(MAX_LABEL + 1) { it }
        val minimumDepth = FloatArray(MAX_LABEL + 1)
        val maximumDepth = FloatArray(MAX_LABEL + 1)
        for (layer in layers) {
            minimumDepth[layer.label] = layer.representativeDepth
            maximumDepth[layer.label] = layer.representativeDepth
        }

        fun find(label: Int): Int {
            var root = label
            while (parent[root] != root) root = parent[root]
            var current = label
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        for (candidate in candidates.sortedByDescending { it.edgeCount }) {
            val firstRoot = find(candidate.first)
            val secondRoot = find(candidate.second)
            if (firstRoot == secondRoot) continue
            val combinedMinimum = minOf(minimumDepth[firstRoot], minimumDepth[secondRoot])
            val combinedMaximum = maxOf(maximumDepth[firstRoot], maximumDepth[secondRoot])
            if (combinedMaximum - combinedMinimum > MAX_CONTACT_DEPTH_SPAN) continue
            parent[secondRoot] = firstRoot
            minimumDepth[firstRoot] = combinedMinimum
            maximumDepth[firstRoot] = combinedMaximum
        }

        val weightedDepth = FloatArray(MAX_LABEL + 1)
        val weightedPixels = IntArray(MAX_LABEL + 1)
        for (layer in layers) {
            val root = find(layer.label)
            weightedDepth[root] += layer.representativeDepth * layer.pixelCount
            weightedPixels[root] += layer.pixelCount
        }
        val motionLabels = ByteArray(labels.size) { index ->
            val label = labels[index].toInt() and 0xff
            if (label == BACKGROUND_LABEL) {
                BACKGROUND_LABEL.toByte()
            } else {
                find(label).toByte()
            }
        }
        val motionLayers = layers
            .groupBy { find(it.label) }
            .map { (root, members) ->
                ObjectLayer(
                    label = root,
                    representativeDepth = weightedDepth[root] / weightedPixels[root],
                    pixelCount = weightedPixels[root]
                )
            }
            .sortedWith(compareBy<ObjectLayer> { it.representativeDepth }.thenBy { it.label })
        return MotionGraph(motionLabels, motionLayers)
    }

    /** 与 [buildAlpha] 的一采样 ownership 放宽保持一致，避免软发丝已有 alpha 却无 label。 */
    private fun expandLabels(source: ByteArray, width: Int, height: Int): ByteArray {
        val expanded = source.copyOf()
        val counts = IntArray(MAX_LABEL + 1)
        for (index in source.indices) {
            if ((source[index].toInt() and 0xff) != BACKGROUND_LABEL) continue
            counts.fill(0)
            val x = index % width
            val y = index / width
            for (offsetY in -1..1) {
                val neighborY = y + offsetY
                if (neighborY !in 0 until height) continue
                for (offsetX in -1..1) {
                    val neighborX = x + offsetX
                    if (neighborX !in 0 until width) continue
                    val label = source[neighborY * width + neighborX].toInt() and 0xff
                    if (label != BACKGROUND_LABEL) counts[label]++
                }
            }
            var selected = BACKGROUND_LABEL
            var bestCount = 0
            for (label in 1..MAX_LABEL) {
                if (counts[label] > bestCount) {
                    selected = label
                    bestCount = counts[label]
                }
            }
            if (selected != BACKGROUND_LABEL) expanded[index] = selected.toByte()
        }
        return expanded
    }

    private data class Component(
        val id: Int,
        val indices: IntArray,
        val centerX: Float,
        val centerY: Float
    )

    private data class Contact(
        val first: Int,
        val second: Int,
        val edgeCount: Int
    )

    private data class MotionGraph(
        val labels: ByteArray,
        val layers: List<ObjectLayer>
    )

    private const val BACKGROUND_LABEL = 0
    private const val MAX_LABEL = 254
    private const val MAX_OBJECT_LAYERS = 12
    private const val MIN_COMPONENT_PIXELS = 4
    private const val MIN_COMPONENT_RATIO = 0.0002f
    private const val MASK_THRESHOLD = 128
    private const val DEPTH_BINS = 256
    private const val OBJECT_DEPTH_OFFSET = 0.16f
    private const val MAX_OBJECT_DEPTH = 0.92f
    private const val MIN_CONTACT_EDGES = 4
    private const val MIN_CONTACT_SCALE = 0.25f
    private const val MAX_CONTACT_DEPTH_SPAN = 0.10f
}
