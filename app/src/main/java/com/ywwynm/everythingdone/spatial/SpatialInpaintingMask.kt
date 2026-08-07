package com.ywwynm.everythingdone.spatial

/**
 * 补图 mask 缩放必须是保守的：目标像素覆盖的任一源像素需要补全时，目标像素就必须标记。
 * 最近邻下采样会漏掉头发、栏杆等一像素显露带，使模型把原前景残留到背景层。
 */
internal object SpatialInpaintingMask {

    /**
     * 补全模型需要看不到完整遮挡物，但持久背景只应写入实际可能显露的区域。
     * 返回二者并集作为模型条件 mask，不改写调用方持有的 write mask。
     */
    fun withOccluder(
        writeMask: BooleanArray,
        occluderMask: BooleanArray?
    ): BooleanArray {
        if (occluderMask == null) return writeMask.copyOf()
        require(writeMask.size == occluderMask.size) { "补全条件 mask 尺寸不符" }
        return BooleanArray(writeMask.size) { index ->
            writeMask[index] || occluderMask[index]
        }
    }

    fun conservativeResize(
        source: BooleanArray,
        sourceWidth: Int,
        sourceHeight: Int,
        regionLeft: Int,
        regionTop: Int,
        regionWidth: Int,
        regionHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): BooleanArray {
        require(source.size == sourceWidth * sourceHeight)
        require(regionLeft >= 0 && regionTop >= 0)
        require(regionWidth > 0 && regionHeight > 0)
        require(regionLeft + regionWidth <= sourceWidth)
        require(regionTop + regionHeight <= sourceHeight)
        require(targetWidth > 0 && targetHeight > 0)

        val result = BooleanArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val localTop = floorScale(targetY, regionHeight, targetHeight)
            val localBottom = ceilScale(targetY + 1, regionHeight, targetHeight)
                .coerceAtLeast(localTop + 1)
            for (targetX in 0 until targetWidth) {
                val localLeft = floorScale(targetX, regionWidth, targetWidth)
                val localRight = ceilScale(targetX + 1, regionWidth, targetWidth)
                    .coerceAtLeast(localLeft + 1)
                var hidden = false
                for (sourceY in localTop until localBottom.coerceAtMost(regionHeight)) {
                    val row = (regionTop + sourceY) * sourceWidth + regionLeft
                    for (sourceX in localLeft until localRight.coerceAtMost(regionWidth)) {
                        if (source[row + sourceX]) {
                            hidden = true
                            break
                        }
                    }
                    if (hidden) break
                }
                result[targetY * targetWidth + targetX] = hidden
            }
        }
        return result
    }

    private fun floorScale(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value.toLong() * sourceSize / targetSize).toInt()

    private fun ceilScale(value: Int, sourceSize: Int, targetSize: Int): Int =
        (
            (value.toLong() * sourceSize + targetSize - 1L) /
                targetSize
            ).toInt()
}
