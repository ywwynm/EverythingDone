package com.ywwynm.everythingdone.spatial

/**
 * 对落盘的局部显示 alpha 做一次保守的亚像素边缘重建。
 *
 * 旧平面在 matting 活性格边界处可能从接近透明直接跳到 255。边界 splat 放大后，
 * 这个硬跳变会把原图中的背景混色像素显示成亮边，并暴露格级锯齿。这里先用 3×3
 * 最小值把透明覆盖向外扩一像素，再用可分离的 1-2-1 核生成连续覆盖率；远离边界的
 * 不透明内容严格保持 255，处理不依赖图片内容或人物位置。
 */
internal object SpatialAlphaEdgeRefiner {

    internal const val MAX_NEIGHBOR_ALPHA_JUMP_FOR_TEST = 128
    internal const val NEAR_CUT_PADDING_SAMPLES = 0.5f

    fun refine(source: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0)
        require(source.size == width * height)
        if (source.all { (it.toInt() and 0xff) == 255 }) return source.copyOf()

        val horizontalMin = ByteArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var minimum = source[row + x].toInt() and 0xff
                if (x > 0) minimum = minOf(minimum, source[row + x - 1].toInt() and 0xff)
                if (x + 1 < width) {
                    minimum = minOf(minimum, source[row + x + 1].toInt() and 0xff)
                }
                horizontalMin[row + x] = minimum.toByte()
            }
        }
        val expanded = ByteArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var minimum = horizontalMin[row + x].toInt() and 0xff
                if (y > 0) {
                    minimum = minOf(minimum, horizontalMin[row - width + x].toInt() and 0xff)
                }
                if (y + 1 < height) {
                    minimum = minOf(minimum, horizontalMin[row + width + x].toInt() and 0xff)
                }
                expanded[row + x] = minimum.toByte()
            }
        }

        val horizontalBlur = IntArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val center = expanded[row + x].toInt() and 0xff
                val left = if (x > 0) expanded[row + x - 1].toInt() and 0xff else 255
                val right = if (x + 1 < width) {
                    expanded[row + x + 1].toInt() and 0xff
                } else {
                    255
                }
                horizontalBlur[row + x] = left + center * 2 + right
            }
        }
        return ByteArray(source.size) { index ->
            val y = index / width
            val center = horizontalBlur[index]
            val top = if (y > 0) horizontalBlur[index - width] else 255 * 4
            val bottom = if (y + 1 < height) horizontalBlur[index + width] else 255 * 4
            ((top + center * 2 + bottom + 8) / 16)
                .coerceIn(0, 255)
                .toByte()
        }
    }
}
