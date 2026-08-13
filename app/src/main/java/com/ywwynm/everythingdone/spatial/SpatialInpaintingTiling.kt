package com.ywwynm.everythingdone.spatial

import kotlin.math.cos
import kotlin.math.max

/**
 * Big-LaMa 的 1:1 分块推理几何。**与桌面 `inpaint_onnx_tiled` 逐条对齐**，这样端上
 * 产出的第二层与网页端验收过的那一版是同一个东西。
 *
 * 为什么必须分块而不是像 AOT-GAN 那样缩到工作分辨率：Big-LaMa 的 ONNX 空间维**写死
 * 512**，整幅 540×720 送不进去；而把带所在的区域缩到 512 会直接吃掉显露带——实测遮挡带
 * 局部宽度中位只有 4–17px，压缩 1.4× 之后一条 6px 的带只剩 4.3px，模型看不见它
 * （D160/D188）。按原生像素切 512 的块逐块推理，块内不缩放，这 1.4× 就拿回来了。
 *
 * 三处容易写错、必须与桌面一致的细节：
 * 1. **反射填充只补右侧与下侧**（numpy `pad(mode="reflect")` 的镜像语义：不重复边缘像素）；
 * 2. 窗函数是 `np.hanning(overlap*2)[:overlap]` 铺在两端、中间填 1，再取外积；
 * 3. 累加后按权重归一；**权重为 0 的像素退回原图**（最外圈窗值恰为 0，且只被一个块覆盖）。
 */
internal object SpatialInpaintingTiling {

    const val TILE = 512
    const val OVERLAP = 128

    data class Plan(
        val paddedWidth: Int,
        val paddedHeight: Int,
        val tile: Int,
        val originsX: IntArray,
        val originsY: IntArray
    ) {
        val tileCount: Int get() = originsX.size * originsY.size

        override fun equals(other: Any?): Boolean =
            other is Plan && paddedWidth == other.paddedWidth &&
                paddedHeight == other.paddedHeight && tile == other.tile &&
                originsX.contentEquals(other.originsX) &&
                originsY.contentEquals(other.originsY)

        override fun hashCode(): Int =
            ((paddedWidth * 31 + paddedHeight) * 31 + tile) * 31 +
                originsX.contentHashCode() * 31 + originsY.contentHashCode()
    }

    fun plan(width: Int, height: Int, tile: Int = TILE, overlap: Int = OVERLAP): Plan {
        require(width > 0 && height > 0) { "尺寸必须为正" }
        require(tile > overlap && overlap >= 0) { "重叠必须小于块边长" }
        val stride = tile - overlap
        val paddedWidth = paddedExtent(width, tile, stride)
        val paddedHeight = paddedExtent(height, tile, stride)
        return Plan(
            paddedWidth = paddedWidth,
            paddedHeight = paddedHeight,
            tile = tile,
            originsX = origins(paddedWidth, tile, stride),
            originsY = origins(paddedHeight, tile, stride)
        )
    }

    /** 与桌面同式：不足一块时补到一块；否则补到刚好能被 stride 步进覆盖。 */
    private fun paddedExtent(extent: Int, tile: Int, stride: Int): Int {
        if (extent <= tile) return tile
        val steps = ceilDiv(extent - tile, stride)
        return tile + steps * stride
    }

    private fun origins(paddedExtent: Int, tile: Int, stride: Int): IntArray {
        val last = max(paddedExtent - tile, 0)
        val count = last / stride + 1
        return IntArray(count) { it * stride }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    /**
     * numpy `mode="reflect"` 的镜像下标：不重复边缘像素。extent==1 时恒为 0。
     * 只会被右／下的填充用到，因此下标非负。
     */
    fun reflectIndex(index: Int, extent: Int): Int {
        if (extent <= 1) return 0
        var value = index
        val period = 2 * extent - 2
        value %= period
        if (value < 0) value += period
        return if (value < extent) value else period - value
    }

    /**
     * 余弦窗：两端各 `overlap` 个 `np.hanning(2*overlap)` 的前半段，中间为 1，再取外积。
     * 返回 tile×tile 的行主序权重。
     */
    fun window(tile: Int = TILE, overlap: Int = OVERLAP): FloatArray {
        require(tile > overlap && overlap >= 0)
        val line = FloatArray(tile) { 1f }
        if (overlap > 0) {
            val span = (2 * overlap - 1).toDouble()
            for (i in 0 until overlap) {
                val value = (0.5 - 0.5 * cos(2.0 * Math.PI * i / span)).toFloat()
                line[i] = value
                line[tile - 1 - i] = value
            }
        }
        val result = FloatArray(tile * tile)
        for (y in 0 until tile) {
            val rowWeight = line[y]
            val row = y * tile
            for (x in 0 until tile) result[row + x] = rowWeight * line[x]
        }
        return result
    }
}
