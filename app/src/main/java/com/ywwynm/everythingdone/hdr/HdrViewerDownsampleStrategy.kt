package com.ywwynm.everythingdone.hdr

import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy

/** 保留原查看器对大图的采样，仅阻止解码器把小图预先插值放大；缩放仍由 PhotoView 负责。 */
internal object HdrViewerDownsampleStrategy : DownsampleStrategy() {
    override fun getScaleFactor(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): Float =
        minOf(1f, CENTER_OUTSIDE.getScaleFactor(sourceWidth, sourceHeight, requestedWidth, requestedHeight))

    override fun getSampleSizeRounding(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): SampleSizeRounding =
        SampleSizeRounding.QUALITY
}
