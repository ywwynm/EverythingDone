package com.ywwynm.everythingdone.spatial

import kotlin.math.abs

/**
 * 清理深度噪声形成的微小封闭断层岛。
 *
 * 小于 3×3 网格的独立层无法在 9% 最大视差下稳定显示，只会成为飞散纹理块；将它并回
 * 共享边最多、深度最接近的相邻表面。大区域之间的真实断边不受影响。
 */
internal object SpatialCutGraphCleaner {

    fun healSmallIslands(
        width: Int,
        height: Int,
        depth: FloatArray,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ) {
        require(width > 1 && height > 1)
        require(depth.size == width * height)
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)

        repeat(MAX_PASSES) {
            val labels = labelComponents(width, height, cutRight, cutDown)
            if (labels.sizes.size <= 1) return
            val means = FloatArray(labels.sizes.size)
            for (index in depth.indices) means[labels.ids[index]] += depth[index]
            for (id in means.indices) means[id] /= labels.sizes[id]

            val neighbours = arrayOfNulls<MutableMap<Int, Int>>(labels.sizes.size)
            fun record(first: Int, second: Int) {
                if (first == second || labels.sizes[first] > MAX_COMPONENT_PIXELS) return
                val map = neighbours[first] ?: HashMap<Int, Int>().also {
                    neighbours[first] = it
                }
                map[second] = (map[second] ?: 0) + 1
            }
            for (y in 0 until height) {
                for (x in 0 until width - 1) {
                    if (!cutRight[y * (width - 1) + x]) continue
                    val first = labels.ids[y * width + x]
                    val second = labels.ids[y * width + x + 1]
                    record(first, second)
                    record(second, first)
                }
            }
            for (y in 0 until height - 1) {
                for (x in 0 until width) {
                    if (!cutDown[y * width + x]) continue
                    val first = labels.ids[y * width + x]
                    val second = labels.ids[(y + 1) * width + x]
                    record(first, second)
                    record(second, first)
                }
            }

            val target = IntArray(labels.sizes.size) { -1 }
            for (id in target.indices) {
                val candidates = neighbours[id] ?: continue
                var best = -1
                var bestEdges = -1
                var bestSize = -1
                var bestDepthDifference = Float.POSITIVE_INFINITY
                for ((candidate, edges) in candidates) {
                    val candidateSize = labels.sizes[candidate]
                    val difference = abs(means[id] - means[candidate])
                    if (edges > bestEdges ||
                        (edges == bestEdges && candidateSize > bestSize) ||
                        (edges == bestEdges && candidateSize == bestSize &&
                            difference < bestDepthDifference)
                    ) {
                        best = candidate
                        bestEdges = edges
                        bestSize = candidateSize
                        bestDepthDifference = difference
                    }
                }
                target[id] = best
            }

            var changed = false
            for (y in 0 until height) {
                for (x in 0 until width - 1) {
                    val edge = y * (width - 1) + x
                    if (!cutRight[edge]) continue
                    val first = labels.ids[y * width + x]
                    val second = labels.ids[y * width + x + 1]
                    if (target[first] == second || target[second] == first) {
                        cutRight[edge] = false
                        changed = true
                    }
                }
            }
            for (y in 0 until height - 1) {
                for (x in 0 until width) {
                    val edge = y * width + x
                    if (!cutDown[edge]) continue
                    val first = labels.ids[y * width + x]
                    val second = labels.ids[(y + 1) * width + x]
                    if (target[first] == second || target[second] == first) {
                        cutDown[edge] = false
                        changed = true
                    }
                }
            }
            if (!changed) return
        }
    }

    private fun labelComponents(
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray
    ): Labels {
        val ids = IntArray(width * height) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntArray(ids.size)
        for (start in ids.indices) {
            if (ids[start] >= 0) continue
            val id = sizes.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            ids[start] = id
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                fun offer(target: Int, connected: Boolean) {
                    if (!connected || ids[target] >= 0) return
                    ids[target] = id
                    queue[tail++] = target
                }
                if (x > 0) {
                    offer(index - 1, !cutRight[y * (width - 1) + x - 1])
                }
                if (x + 1 < width) {
                    offer(index + 1, !cutRight[y * (width - 1) + x])
                }
                if (y > 0) offer(index - width, !cutDown[(y - 1) * width + x])
                if (y + 1 < height) offer(index + width, !cutDown[y * width + x])
            }
            sizes += tail
        }
        return Labels(ids, sizes.toIntArray())
    }

    private data class Labels(val ids: IntArray, val sizes: IntArray)

    private const val MAX_COMPONENT_PIXELS = 8
    private const val MAX_PASSES = 4
}
