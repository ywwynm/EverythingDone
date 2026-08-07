package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 把 matting 的语义前景转成几何保护层。
 *
 * 受保护主体内部使用单一稳健深度并清除内部断边，因此脸、文字和物体轮廓趋于刚性平移。
 * matting 只允许移除自由度，绝不创建新断边：暗发、遮挡或低对比区域的漏分不能变成穿过
 * 面部的几何裂缝；真实层间断边仍只来自深度遮挡图。
 */
internal object SpatialSubjectLayer {


    fun buildMask(
        matte: SpatialAlphaData,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray? {
        require(targetWidth > 1 && targetHeight > 1)
        val sampled = BooleanArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = (y + 0.5f) * matte.height / targetHeight - 0.5f
            val y0 = floor(sourceY).toInt().coerceIn(0, matte.height - 1)
            val y1 = (y0 + 1).coerceAtMost(matte.height - 1)
            val fy = (sourceY - y0).coerceIn(0f, 1f)
            for (x in 0 until targetWidth) {
                val sourceX = (x + 0.5f) * matte.width / targetWidth - 0.5f
                val x0 = floor(sourceX).toInt().coerceIn(0, matte.width - 1)
                val x1 = (x0 + 1).coerceAtMost(matte.width - 1)
                val fx = (sourceX - x0).coerceIn(0f, 1f)
                val top = lerp(
                    matte.values[y0 * matte.width + x0],
                    matte.values[y0 * matte.width + x1],
                    fx
                )
                val bottom = lerp(
                    matte.values[y1 * matte.width + x0],
                    matte.values[y1 * matte.width + x1],
                    fx
                )
                sampled[y * targetWidth + x] =
                    lerp(top, bottom, fy) >= SUBJECT_ALPHA_THRESHOLD
            }
        }

        val closed = erode(dilate(sampled, targetWidth, targetHeight), targetWidth, targetHeight)
        removeSmallComponents(closed, targetWidth, targetHeight)
        fillEnclosedHoles(closed, targetWidth, targetHeight)
        val count = closed.count { it }
        val minimum = minimumComponentPixels(closed.size)
        if (count < minimum || count > closed.size * MAX_SUBJECT_RATIO) return null
        return ByteArray(closed.size) { if (closed[it]) 0xff.toByte() else 0 }
    }


    private fun removeSmallComponents(mask: BooleanArray, width: Int, height: Int) {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val minimum = minimumComponentPixels(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                fun offer(target: Int) {
                    if (!mask[target] || visited[target]) return
                    visited[target] = true
                    queue[tail++] = target
                }
                if (x > 0) offer(index - 1)
                if (x + 1 < width) offer(index + 1)
                if (y > 0) offer(index - width)
                if (y + 1 < height) offer(index + width)
            }
            if (tail < minimum) repeat(tail) { mask[queue[it]] = false }
        }
    }

    private fun fillEnclosedHoles(mask: BooleanArray, width: Int, height: Int) {
        val exterior = BooleanArray(mask.size)
        val queue: ArrayDeque<Int> = ArrayDeque()
        fun offer(index: Int) {
            if (mask[index] || exterior[index]) return
            exterior[index] = true
            queue.addLast(index)
        }
        for (x in 0 until width) {
            offer(x)
            offer((height - 1) * width + x)
        }
        for (y in 0 until height) {
            offer(y * width)
            offer(y * width + width - 1)
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            if (x > 0) offer(index - 1)
            if (x + 1 < width) offer(index + 1)
            if (y > 0) offer(index - width)
            if (y + 1 < height) offer(index + width)
        }
        for (index in mask.indices) if (!mask[index] && !exterior[index]) mask[index] = true
    }

    private fun dilate(source: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(source.size) { index ->
            val x = index % width
            val y = index / width
            var found = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val targetX = x + dx
                val targetY = y + dy
                if (targetX in 0 until width && targetY in 0 until height &&
                    source[targetY * width + targetX]
                ) {
                    found = true
                    break@loop
                }
            }
            found
        }

    private fun erode(source: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(source.size) { index ->
            val x = index % width
            val y = index / width
            var all = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val targetX = x + dx
                val targetY = y + dy
                if (targetX !in 0 until width || targetY !in 0 until height ||
                    !source[targetY * width + targetX]
                ) {
                    all = false
                    break@loop
                }
            }
            all
        }

    private fun minimumComponentPixels(total: Int): Int = maxOf(16, total / 500)

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private const val SUBJECT_ALPHA_THRESHOLD = 0.20f
    private const val MAX_SUBJECT_RATIO = 0.92f
    private const val DEPTH_BINS = 256
}
