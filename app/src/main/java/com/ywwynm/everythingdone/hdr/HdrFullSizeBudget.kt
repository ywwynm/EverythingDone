package com.ywwynm.everythingdone.hdr

/** 为解码器临时内存和现有界面留出余量；估算不是内存分配成功的保证。 */
internal object HdrFullSizeBudget {
    fun decision(width: Int, height: Int, freeHeap: Long): Pair<Boolean, String> {
        val pixels = width.toLong() * height.toLong()
        val valid = width > 0 && height > 0 && pixels <= 36_000_000L
        val estimate = if (valid) pixels * 12 else Long.MAX_VALUE
        val budget = minOf(384L * 1024 * 1024, (freeHeap - 64L * 1024 * 1024).coerceAtLeast(0))
        return (valid && estimate <= budget) to "size=${width}x$height; estimatedBytes=$estimate; budgetBytes=$budget; freeHeap=$freeHeap; pixelLimit=36000000"
    }
}
