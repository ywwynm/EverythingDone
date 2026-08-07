package com.ywwynm.everythingdone.spatial

/**
 * 根据源图与设备内存选择人像 matting 推理分辨率。
 *
 * MODNet 是全卷积模型；较高分辨率主要用于保留发丝、衣物轮廓与孔洞边界，
 * 不改变模型文件。低内存设备继续使用模型的官方参考分辨率。
 */
internal object SpatialMattingResolutionPolicy {

    fun selectLongEdge(
        sourceLongEdge: Int,
        baseLongEdge: Int,
        totalRamMb: Long,
        availableRamMb: Long
    ): Int {
        require(sourceLongEdge > 0)
        require(baseLongEdge > 0)
        val target = when {
            totalRamMb >= HIGH_TOTAL_RAM_MB &&
                availableRamMb >= HIGH_AVAILABLE_RAM_MB -> HIGH_LONG_EDGE
            totalRamMb >= MEDIUM_TOTAL_RAM_MB &&
                availableRamMb >= MEDIUM_AVAILABLE_RAM_MB -> MEDIUM_LONG_EDGE
            else -> baseLongEdge
        }
        return minOf(sourceLongEdge, maxOf(baseLongEdge, target))
    }

    private const val HIGH_LONG_EDGE = 1440
    private const val MEDIUM_LONG_EDGE = 1024
    private const val HIGH_TOTAL_RAM_MB = 8_192L
    private const val HIGH_AVAILABLE_RAM_MB = 1_536L
    private const val MEDIUM_TOTAL_RAM_MB = 6_144L
    private const val MEDIUM_AVAILABLE_RAM_MB = 1_024L
}
