package com.ywwynm.everythingdone.spatial

/**
 * 只在对象移开后可能显露的窄带内，把生成背景与原图中最近的已知背景连续衔接。
 *
 * 补图模型负责深处未知内容；边界第一批显露像素优先保持原图的曝光和局部颜色，避免模型
 * 输出与已知背景的接缝在强视差下变成亮边、暗边或锯齿色带。
 */
internal object SpatialDisocclusionGuardBand {

    fun stabilize(
        source: IntArray,
        generated: IntArray,
        hiddenMask: BooleanArray,
        revealMask: BooleanArray,
        fullObjectMask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray {
        require(width > 0 && height > 0)
        require(source.size == width * height)
        require(generated.size == source.size)
        require(hiddenMask.size == source.size)
        require(revealMask.size == source.size)
        require(fullObjectMask.size == source.size)
        require(radius >= 1)
        val result = IntArray(source.size) { index ->
            if (hiddenMask[index]) generated[index] else source[index]
        }
        if (revealMask.none { it }) return result

        val distance = IntArray(source.size) { Int.MAX_VALUE }
        val nearestKnown = IntArray(source.size) { -1 }
        val queue = IntArray(source.size)
        var head = 0
        var tail = 0

        fun hasHiddenNeighbor(index: Int): Boolean {
            val x = index % width
            val y = index / width
            for (offsetY in -1..1) {
                val neighborY = y + offsetY
                if (neighborY !in 0 until height) continue
                for (offsetX in -1..1) {
                    if (offsetX == 0 && offsetY == 0) continue
                    val neighborX = x + offsetX
                    if (neighborX in 0 until width &&
                        hiddenMask[neighborY * width + neighborX]
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        // 只把完整对象以外的真实已知背景放进队列。显露带内侧虽然不需要持久化补全，
        // 仍然是前景对象，不能被当作背景回填到即将显露的区域。
        for (index in source.indices) {
            if (!hiddenMask[index] && !fullObjectMask[index] && hasHiddenNeighbor(index)) {
                distance[index] = 0
                nearestKnown[index] = index
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
                    if (!hiddenMask[target] || nextDistance >= distance[target]) continue
                    distance[target] = nextDistance
                    nearestKnown[target] = nearestKnown[index]
                    queue[tail++] = target
                }
            }
        }

        for (index in result.indices) {
            val steps = distance[index]
            val known = nearestKnown[index]
            if (!revealMask[index] || !hiddenMask[index] ||
                steps !in 1..radius || known < 0
            ) {
                continue
            }
            val normalized = ((radius + 1 - steps).toFloat() / radius)
                .coerceIn(0f, 1f)
            val sourceWeight = normalized * normalized
            result[index] = blendOpaque(
                generated = generated[index],
                source = source[known],
                sourceWeight = sourceWeight
            )
        }
        return result
    }

    private fun blendOpaque(generated: Int, source: Int, sourceWeight: Float): Int {
        fun channel(shift: Int): Int {
            val generatedValue = generated ushr shift and 0xff
            val sourceValue = source ushr shift and 0xff
            return (
                generatedValue + (sourceValue - generatedValue) * sourceWeight + 0.5f
                ).toInt().coerceIn(0, 255)
        }
        return (0xff shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }
}
