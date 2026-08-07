package com.ywwynm.everythingdone.spatial

/**
 * AOT-GAN 的补图工作分辨率。数值越大，保留的局部结构越多，
 * 但激活内存和等待时间大致随像素数增长。
 */
enum class SpatialInpaintingQuality(
    val stableId: String,
    val targetLongEdge: Int,
    val minimumAvailableRamMb: Int
) {
    STANDARD("standard_512", 512, 1_536),
    HIGH("high_768", 768, 2_304),
    MAXIMUM("maximum_1024", 1024, 3_584);

    companion object {
        fun fromStableId(value: String?): SpatialInpaintingQuality? =
            entries.firstOrNull { it.stableId == value }
    }
}
