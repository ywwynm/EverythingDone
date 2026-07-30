package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 编码器输入缓冲里 P010 的**实际排布**。
 *
 * 不能假定紧密排布：行距、平面高度由编码器说了算，crop 原点也可能不是 (0,0)，而 0 与缺失
 * 必须当成"没告诉我"而不是真值——Android 明确允许厂商回报 0，那会让色度平面的起始偏移和
 * 入队长度一起变成 0，画面直接废掉且不报错。
 *
 * 这个类只做算术，不碰 `MediaFormat` 或 `Image`：调用方把整数取出来传进来，JVM 单测因此
 * 能覆盖每一种回报组合。
 */
internal data class FableSolExportP010Layout(
    /** Y 平面的行距（字节）。 */
    val lumaRowStride: Int,
    /** 交错 CbCr 平面的行距（字节）。P010 通常与亮度行距相同，但不保证。 */
    val chromaRowStride: Int,
    /** 有效画面在缓冲里的原点；crop 不为 (0,0) 时非零。垂直原点恒为偶数。 */
    val originXPx: Int,
    val originYPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    /** 编码器回报的平面高度；亮度平面按它计长，色度平面紧随其后。 */
    val sliceHeight: Int
) {

    /** Y 平面第一个有效样本的字节偏移。 */
    val lumaOffset: Int get() = originYPx * lumaRowStride + originXPx * BYTES_PER_SAMPLE

    /** 交错 CbCr 平面第一个有效样本的字节偏移。 */
    val chromaOffset: Int
        get() = lumaRowStride * sliceHeight +
            originYPx / 2 * chromaRowStride +
            originXPx * BYTES_PER_SAMPLE

    /** 规范整帧长度。入队长度用这个数，而不是"我实际写到哪儿"——有实现会按它校验。 */
    val frameBytes: Int get() = lumaRowStride * sliceHeight + chromaRowStride * (sliceHeight / 2)

    /** 写出最后一个有效样本所需的最小容量。 */
    val requiredBytes: Int
        get() = maxOf(
            frameBytes,
            lumaOffset + (heightPx - 1) * lumaRowStride + widthPx * BYTES_PER_SAMPLE,
            chromaOffset + (heightPx / 2 - 1) * chromaRowStride + widthPx * BYTES_PER_SAMPLE
        )

    /**
     * 用 `MediaCodec.getInputImage()` 报出的平面行距修正本排布。
     *
     * `Image` 的平面行距比 `KEY_STRIDE` 更权威：后者是整帧的一个数，而 P010 的色度平面行距
     * **不保证**等于亮度行距。只在回报值确实容得下一行有效样本时才采纳，其余保留原值——
     * 半截可信的排布比完全不可信的更危险。
     */
    fun withPlaneRowStrides(luma: Int?, chroma: Int?): FableSolExportP010Layout {
        val minimum = (originXPx + widthPx) * BYTES_PER_SAMPLE
        return copy(
            lumaRowStride = luma?.takeIf { it >= minimum } ?: lumaRowStride,
            chromaRowStride = chroma?.takeIf { it >= minimum } ?: chromaRowStride
        )
    }

    companion object {

        /** P010 每个样本 2 字节；色度按 2×2 交错，一组 (Cb, Cr) 共 4 字节。 */
        const val BYTES_PER_SAMPLE = 2

        /** `Image` 的 P010 平面像素步长：Y 每样本 2 字节，Cb/Cr 交错因而各 4 字节。 */
        const val LUMA_PIXEL_STRIDE = 2
        const val CHROMA_PIXEL_STRIDE = 4

        /**
         * 由编码器回报解析排布。
         *
         * @param reportedStride `MediaFormat.KEY_STRIDE`；null 或非正表示未回报。
         * @param reportedSliceHeight `MediaFormat.KEY_SLICE_HEIGHT`；同上。
         * @param cropLeft/cropTop 输入格式里的 crop 原点。回报的行距或平面高度容不下该原点
         *   时整体按无 crop 处理：几个数互相矛盾时，按矛盾的值写出去比忽略 crop 更危险。
         */
        fun of(
            widthPx: Int,
            heightPx: Int,
            reportedStride: Int? = null,
            reportedSliceHeight: Int? = null,
            cropLeft: Int = 0,
            cropTop: Int = 0
        ): FableSolExportP010Layout {
            val rawStride = reportedStride?.takeIf { it > 0 } ?: 0
            val rawSlice = reportedSliceHeight?.takeIf { it > 0 } ?: 0
            var originX = cropLeft.coerceAtLeast(0)
            // 色度按 2×2 分组，垂直原点必须落在偶数行，否则整帧色度相位偏半个亮度样本。
            var originY = cropTop.coerceAtLeast(0).let { it - it % 2 }
            val fits = rawStride >= (widthPx + originX) * BYTES_PER_SAMPLE &&
                rawSlice >= heightPx + originY
            if (!fits) {
                originX = 0
                originY = 0
            }
            return FableSolExportP010Layout(
                lumaRowStride = maxOf(rawStride, (widthPx + originX) * BYTES_PER_SAMPLE),
                chromaRowStride = maxOf(rawStride, (widthPx + originX) * BYTES_PER_SAMPLE),
                originXPx = originX,
                originYPx = originY,
                widthPx = widthPx,
                heightPx = heightPx,
                sliceHeight = maxOf(rawSlice, heightPx + originY)
            )
        }
    }
}
