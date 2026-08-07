package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque

/**
 * Uses an alpha proposal to locate a candidate foreground, then requires component-level depth
 * evidence before that foreground is allowed to become a closed occlusion chart.
 *
 * 单条边上的单目深度经常在柔和轮廓处失真，因此不能用“每条边都再次通过深度门槛”拼接
 * 拓扑。整块候选通过稳健的内部／外环深度验证后，输出闭合轮廓提案；调用方仍决定是否
 * 合并，输入切边始终不被修改。
 */
internal object SpatialMotionGroupingPrior {

    data class Result(
        val acceptedComponents: Int,
        val acceptedPixels: Int,
        /** 已通过深度门控的完整前景；只可用于保形位移拟合与补全条件。 */
        val acceptedMask: BooleanArray? = null,
        /** 组件整体通过深度验证后生成的闭合轮廓；调用方决定是否合并。 */
        val depthSupportedCutRight: BooleanArray? = null,
        val depthSupportedCutDown: BooleanArray? = null
    ) {
        val applied: Boolean get() = acceptedComponents > 0
    }

    fun selectDepthSupportedSubjects(
        width: Int,
        height: Int,
        depth: FloatArray,
        alphaProposal: SpatialAlphaData,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Result {
        require(width > 1 && height > 1)
        require(depth.size == width * height)
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)

        val alpha = resampleAlpha(alphaProposal, width, height)
        val foreground = BooleanArray(alpha.size) { alpha[it] >= FOREGROUND_ALPHA }
        suppressIsolatedPixels(foreground, width, height)
        val labels = labelComponents(foreground, width, height)
        if (labels.sizes.isEmpty()) return Result(0, 0)

        val minimumPixels = maxOf(MIN_COMPONENT_PIXELS, (depth.size * MIN_AREA_FRACTION).toInt())
        val maximumPixels = (depth.size * MAX_AREA_FRACTION).toInt()
        val accepted = BooleanArray(labels.sizes.size)
        var acceptedComponents = 0
        for (component in labels.sizes.indices) {
            val size = labels.sizes[component]
            if (size !in minimumPixels..maximumPixels) continue
            if (!hasDepthSupport(
                    component = component,
                    labels = labels.ids,
                    foreground = foreground,
                    alpha = alpha,
                    depth = depth,
                    width = width,
                    height = height,
                    cutRight = cutRight,
                    cutDown = cutDown
                )
            ) {
                continue
            }
            accepted[component] = true
            acceptedComponents++
        }
        if (acceptedComponents == 0) return Result(0, 0)

        fun acceptedComponent(index: Int): Int {
            val component = labels.ids[index]
            return component.takeIf { it >= 0 && accepted[it] } ?: -1
        }
        val rawAcceptedMask = BooleanArray(foreground.size) { index ->
            acceptedComponent(index) >= 0
        }
        val acceptedMask = absorbUnsupportedEnclosedHoles(
            rawAcceptedMask,
            depth,
            width,
            height
        )
        val supportedRight = BooleanArray(cutRight.size)
        val supportedDown = BooleanArray(cutDown.size)
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val first = y * width + x
                val second = first + 1
                supportedRight[y * (width - 1) + x] =
                    acceptedMask[first] != acceptedMask[second]
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                val first = y * width + x
                val second = first + width
                supportedDown[y * width + x] =
                    acceptedMask[first] != acceptedMask[second]
            }
        }
        return Result(
            acceptedComponents = acceptedComponents,
            acceptedPixels = acceptedMask.count { it },
            acceptedMask = acceptedMask,
            depthSupportedCutRight = supportedRight,
            depthSupportedCutDown = supportedDown
        )
    }

    /**
     * 闭合前景内部的小洞如果与周围前景深度连续，属于 matting 漏检，必须吸收进同一运动面；
     * 洞内明显更远时则保留为真实背景开口。触达图像边缘的外部背景永远不参与填充。
     */
    private fun absorbUnsupportedEnclosedHoles(
        source: BooleanArray,
        depth: FloatArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val result = source.copyOf()
        val visited = BooleanArray(source.size)
        val queue = IntArray(source.size)
        for (start in source.indices) {
            if (source[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var touchesImageEdge = false
            queue[tail++] = start
            visited[start] = true
            val surroundingDepth = ArrayList<Float>()
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                    touchesImageEdge = true
                }
                fun inspect(neighbour: Int) {
                    if (source[neighbour]) {
                        surroundingDepth += depth[neighbour]
                    } else if (!visited[neighbour]) {
                        visited[neighbour] = true
                        queue[tail++] = neighbour
                    }
                }
                if (x > 0) inspect(index - 1)
                if (x + 1 < width) inspect(index + 1)
                if (y > 0) inspect(index - width)
                if (y + 1 < height) inspect(index + width)
            }
            if (touchesImageEdge || tail > source.size * MAX_ABSORBED_HOLE_FRACTION ||
                surroundingDepth.size < MIN_HOLE_BOUNDARY_SAMPLES
            ) {
                continue
            }
            val holeDepth = ArrayList<Float>(tail)
            repeat(tail) { offset -> holeDepth += depth[queue[offset]] }
            val hasRealDepthOpening =
                median(surroundingDepth) - median(holeDepth) >= MIN_REAL_HOLE_DEPTH_GAP
            if (!hasRealDepthOpening) {
                repeat(tail) { offset -> result[queue[offset]] = true }
            }
        }
        return result
    }

    private fun hasDepthSupport(
        component: Int,
        labels: IntArray,
        foreground: BooleanArray,
        alpha: FloatArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Boolean {
        val interior = ArrayList<Float>()
        val fallbackInterior = ArrayList<Float>()
        val ring = ArrayList<Float>()
        var boundaryEdges = 0
        var depthSupportedEdges = 0
        var existingCutEdges = 0

        for (index in labels.indices) {
            if (labels[index] != component) continue
            fallbackInterior += depth[index]
            if (alpha[index] >= CORE_ALPHA) interior += depth[index]
            val x = index % width
            val y = index / width
            fun inspect(neighbor: Int, alreadyCut: Boolean) {
                if (labels[neighbor] == component) return
                boundaryEdges++
                if (depth[index] - depth[neighbor] >= MIN_LOCAL_DEPTH_GAP) {
                    depthSupportedEdges++
                }
                if (alreadyCut) existingCutEdges++
            }
            if (x > 0) inspect(index - 1, cutRight[y * (width - 1) + x - 1])
            if (x + 1 < width) inspect(index + 1, cutRight[y * (width - 1) + x])
            if (y > 0) inspect(index - width, cutDown[(y - 1) * width + x])
            if (y + 1 < height) inspect(index + width, cutDown[y * width + x])
        }

        // Sample a ring a few mesh cells outside the proposal. This bypasses the depth model's
        // soft transition at the exact silhouette and tests whether the proposed subject is
        // actually in front of its surroundings.
        for (index in labels.indices) {
            if (foreground[index]) continue
            val x = index % width
            val y = index / width
            var nearComponent = false
            loop@ for (offsetY in -RING_RADIUS..RING_RADIUS) {
                val sampleY = y + offsetY
                if (sampleY !in 0 until height) continue
                for (offsetX in -RING_RADIUS..RING_RADIUS) {
                    val sampleX = x + offsetX
                    if (sampleX !in 0 until width) continue
                    if (labels[sampleY * width + sampleX] == component) {
                        nearComponent = true
                        break@loop
                    }
                }
            }
            if (nearComponent) ring += depth[index]
        }

        val subjectSamples = if (interior.size >= MIN_DEPTH_SAMPLES) interior else fallbackInterior
        if (subjectSamples.size < MIN_DEPTH_SAMPLES || ring.size < MIN_DEPTH_SAMPLES ||
            boundaryEdges == 0
        ) {
            return false
        }
        val medianGap = median(subjectSamples) - median(ring)
        val localSupport = depthSupportedEdges.toFloat() / boundaryEdges
        val existingCoverage = existingCutEdges.toFloat() / boundaryEdges
        return medianGap >= MIN_MEDIAN_DEPTH_GAP &&
            (
                medianGap >= MIN_STRONG_MEDIAN_DEPTH_GAP ||
                    localSupport >= MIN_LOCAL_SUPPORT ||
                    existingCoverage >= MIN_EXISTING_CUT_COVERAGE
                )
    }

    private fun resampleAlpha(
        source: SpatialAlphaData,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val result = FloatArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = ((targetY + 0.5f) * source.height / targetHeight)
                .toInt()
                .coerceIn(0, source.height - 1)
            for (targetX in 0 until targetWidth) {
                val sourceX = ((targetX + 0.5f) * source.width / targetWidth)
                    .toInt()
                    .coerceIn(0, source.width - 1)
                result[targetY * targetWidth + targetX] =
                    source.values[sourceY * source.width + sourceX].coerceIn(0f, 1f)
            }
        }
        return result
    }

    private fun suppressIsolatedPixels(mask: BooleanArray, width: Int, height: Int) {
        val source = mask.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                var foregroundNeighbors = 0
                for (offsetY in -1..1) {
                    val sampleY = y + offsetY
                    if (sampleY !in 0 until height) continue
                    for (offsetX in -1..1) {
                        val sampleX = x + offsetX
                        if (sampleX !in 0 until width || (offsetX == 0 && offsetY == 0)) continue
                        if (source[sampleY * width + sampleX]) foregroundNeighbors++
                    }
                }
                if (source[index] && foregroundNeighbors <= 1) mask[index] = false
                if (!source[index] && foregroundNeighbors >= 7) mask[index] = true
            }
        }
    }

    private fun labelComponents(mask: BooleanArray, width: Int, height: Int): Labels {
        val labels = IntArray(mask.size) { -1 }
        val sizes = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || labels[start] >= 0) continue
            val component = sizes.size
            var size = 0
            labels[start] = component
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                size++
                val x = index % width
                val y = index / width
                fun offer(neighbor: Int) {
                    if (!mask[neighbor] || labels[neighbor] >= 0) return
                    labels[neighbor] = component
                    queue.addLast(neighbor)
                }
                if (x > 0) offer(index - 1)
                if (x + 1 < width) offer(index + 1)
                if (y > 0) offer(index - width)
                if (y + 1 < height) offer(index + width)
            }
            sizes += size
        }
        return Labels(labels, sizes.toIntArray())
    }

    private fun median(values: MutableList<Float>): Float {
        values.sort()
        val middle = values.size / 2
        return if (values.size % 2 == 0) {
            (values[middle - 1] + values[middle]) * 0.5f
        } else {
            values[middle]
        }
    }

    private data class Labels(val ids: IntArray, val sizes: IntArray)

    private const val FOREGROUND_ALPHA = 0.52f
    private const val CORE_ALPHA = 0.78f
    private const val MIN_AREA_FRACTION = 0.008f
    private const val MAX_AREA_FRACTION = 0.82f
    private const val MIN_COMPONENT_PIXELS = 24
    private const val RING_RADIUS = 4
    private const val MIN_DEPTH_SAMPLES = 16
    private const val MIN_MEDIAN_DEPTH_GAP = 0.035f
    private const val MIN_STRONG_MEDIAN_DEPTH_GAP = 0.08f
    private const val MIN_LOCAL_DEPTH_GAP = 0.012f
    private const val MIN_LOCAL_SUPPORT = 0.28f
    private const val MIN_EXISTING_CUT_COVERAGE = 0.16f
    private const val MIN_REAL_HOLE_DEPTH_GAP = 0.035f
    private const val MIN_HOLE_BOUNDARY_SAMPLES = 4
    private const val MAX_ABSORBED_HOLE_FRACTION = 0.025f
}
