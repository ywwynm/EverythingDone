package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.ceil
import kotlin.math.min

/**
 * HDR10+ 逐像素累计频率分布（fablesol-video-export D112、D169）。
 *
 * 桶宽固定 `0.00001`、共 100001 个桶，与 `DistributionMaxRGB` 的载荷量化网格完全对齐——
 * 归一化基准是 PQ 的 10000 尼特上限，因此一个桶正好是 0.1 尼特。桶粒度必须显式固定，否则
 * D104 那两个统计后端的"逐项相等"没有判定标准。
 *
 * 分位一律是 **nearest-rank**：`r = max(1, ceil(n × p / 100))`，返回升序序列第 `r` 个样本
 * 所在的桶值。不在相邻样本、桶或码流九项 V 向量之间插值——三处各用一种 percentile 约定，
 * 是同一份统计给出三个不同答案的经典来源。
 *
 * [maxScl] 与 [sum] **不经过桶**：前者按 D103 保留源线性缓冲的有效精度，后者按 D102 由线性
 * 总和与真实像素数计算，只在写入载荷时量化。
 */
internal class FableSolExportHdr10PlusHistogram private constructor(
    /** 单帧统计使用 32 位计数，避免 GPU 回读后再复制 100001 个桶。 */
    private val intCounts: IntArray?,
    /** 整个场景跨帧累计时使用 64 位计数，长视频的热门桶可能超过 Int 上限。 */
    private val longCounts: LongArray?,
    /** 样本总数（真实像素数，不是桶计数之和的近似）。 */
    val pixelCount: Long,
    /** 三个通道各自的线性归一化峰值。 */
    val maxScl: DoubleArray,
    /** 全部像素 `max(R,G,B)` 的线性归一化总和。 */
    val sum: Double
) {

    /** 单帧直方图；每个桶的计数不超过一帧的像素数。 */
    constructor(
        counts: IntArray,
        pixelCount: Long,
        maxScl: DoubleArray,
        sum: Double
    ) : this(
        intCounts = counts,
        longCounts = null,
        pixelCount = pixelCount,
        maxScl = maxScl,
        sum = sum
    )

    /** 跨帧场景直方图；不能把累计计数压回 32 位。 */
    constructor(
        counts: LongArray,
        pixelCount: Long,
        maxScl: DoubleArray,
        sum: Double
    ) : this(
        intCounts = null,
        longCounts = counts,
        pixelCount = pixelCount,
        maxScl = maxScl,
        sum = sum
    )

    init {
        require((intCounts != null) xor (longCounts != null))
        require((intCounts?.size ?: longCounts?.size) == BUCKET_COUNT)
    }

    /** `AverageMaxRGB`：线性总和除以真实像素数（D102）。 */
    val averageMaxRgb: Double get() = if (pixelCount > 0L) sum / pixelCount else 0.0

    /**
     * nearest-rank 分位（D112）。
     *
     * @param percent 百分比，允许小数——`J8 = 99` 必须按 **99.98** 解释，而不是字面 99。
     */
    fun percentile(percent: Double): Double {
        if (pixelCount <= 0L) return 0.0
        val clamped = percent.coerceIn(0.0, 100.0)
        val rank = ceil(pixelCount.toDouble() * clamped / 100.0).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        longCounts?.let { counts ->
            for (bucket in counts.indices) {
                cumulative += counts[bucket]
                if (cumulative >= rank) return bucketValue(bucket)
            }
            return bucketValue(counts.size - 1)
        }
        val counts = checkNotNull(intCounts)
        for (bucket in counts.indices) {
            cumulative += counts[bucket].toLong()
            if (cumulative >= rank) return bucketValue(bucket)
        }
        return bucketValue(counts.size - 1)
    }

    /** 桶内累计质量，用于曲线的内容密度（D120）。 */
    fun massBetween(lowValue: Double, highValue: Double): Long {
        if (pixelCount <= 0L) return 0L
        val from = bucketOf(lowValue)
        val to = bucketOf(highValue)
        var total = 0L
        longCounts?.let { counts ->
            for (bucket in from..min(to, counts.size - 1)) total += counts[bucket]
            return total
        }
        val counts = checkNotNull(intCounts)
        for (bucket in from..min(to, counts.size - 1)) total += counts[bucket].toLong()
        return total
    }

    /** 把本表累计进场景级 64 位计数；调用方保证按时间顺序逐帧调用。 */
    fun addCountsTo(destination: LongArray) {
        require(destination.size == BUCKET_COUNT)
        longCounts?.let { counts ->
            for (bucket in counts.indices) destination[bucket] += counts[bucket]
            return
        }
        val counts = checkNotNull(intCounts)
        for (bucket in counts.indices) destination[bucket] += counts[bucket].toLong()
    }

    companion object {

        /** 桶宽：归一化 `0.00001`，即以 10000 尼特为上限时的 0.1 尼特（D169）。 */
        const val BUCKET_WIDTH = 0.00001

        /** 桶数：`[0, 1]` 闭区间在 0.00001 网格上的取值个数。 */
        const val BUCKET_COUNT = 100001

        /** 每个单位宽度里的桶数；与 [BUCKET_WIDTH] 互为倒数，但乘法没有除法那点舍入。 */
        const val BUCKETS_PER_UNIT = 100_000.0

        /**
         * 归一化值 → 桶下标。
         *
         * **用乘法而不是除以 [BUCKET_WIDTH]**：`1.0 / 0.00001` 在双精度里是
         * `99999.99999999999`，取整之后 1.0 会落进 99999 号桶，最高那一档从此永远空着。
         */
        fun bucketOf(normalized: Double): Int =
            (normalized * BUCKETS_PER_UNIT).toInt().coerceIn(0, BUCKET_COUNT - 1)

        /** 桶下标 → 该桶代表的网格值。 */
        fun bucketValue(bucket: Int): Double =
            bucket.coerceIn(0, BUCKET_COUNT - 1) / BUCKETS_PER_UNIT

        /** 供测试与 CPU 参考实现使用：从一组归一化样本直接建表。 */
        fun of(samples: DoubleArray, channels: Array<DoubleArray>? = null):
            FableSolExportHdr10PlusHistogram {
            val counts = IntArray(BUCKET_COUNT)
            var sum = 0.0
            for (value in samples) {
                counts[bucketOf(value)]++
                sum += value
            }
            val maxScl = DoubleArray(3)
            if (channels != null) {
                for (index in 0 until 3) {
                    maxScl[index] = channels[index].maxOrNull() ?: 0.0
                }
            } else {
                val peak = samples.maxOrNull() ?: 0.0
                for (index in 0 until 3) maxScl[index] = peak
            }
            return FableSolExportHdr10PlusHistogram(
                counts = counts,
                pixelCount = samples.size.toLong(),
                maxScl = maxScl,
                sum = sum
            )
        }
    }
}
