package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一帧原始测量或完整场景的 HDR10+ 统计（fablesol-video-export
 * D101～D103、D108～D112、D169、D177）。
 *
 * 全部在**最终可见合成、已经转换到线性 BT.2020、尚未 PQ 编码与量化**的位置计算，归一化基准
 * 是 PQ 的 10000 尼特上限。旧实现在 PQ 码值域求块平均再套一次 EOTF——`EOTF(mean(PQ))` 不等于
 * `mean(EOTF(PQ))`，那个数从定义上就不是 AverageMaxRGB。
 */
internal class FableSolHdr10PlusStats(
    /** 当前统计区间内三个通道各自的线性归一化峰值（D103）。 */
    val maxScl: DoubleArray,
    /** 当前统计区间全部像素 `max(R,G,B)` 的线性归一化平均（D102）。 */
    val averageMaxRgb: Double,
    /** 与 [FableSolExportHdr10PlusMetadata.PERCENTAGES] 一一对应的九项分位（D101）。 */
    val distribution: DoubleArray,
    /** `V8` 对应的真实 99.98% 分位；曲线横轴优先用它（D113）。 */
    val percentile9998: Double,
    /** ApplicationVersion 1 的高亮像素比例；`0` 表示未计算（D108、D109）。 */
    val fractionBrightPixels: Double,
    /**
     * 5:1 代理帧的平均亮度；单帧原始测量用它选择场景 FBP 所属的最亮代理帧（D108）。
     *
     * null 表示代理帧计算失败。它不是 ST 2094-40 载荷字段，不参与曲线或其它统计。
     */
    val proxyAverageLuminance: Double?,
    /** 完整 CFD；曲线的内容密度直接查它，不经过码流九项（D110、D117）。 */
    val histogram: FableSolExportHdr10PlusHistogram?
) {

    /** 内部任意百分位查询（D110）；没有 CFD 时退回九项里最接近的一项。 */
    fun percentile(percent: Double): Double {
        histogram?.let { return it.percentile(percent) }
        val table = FableSolExportHdr10PlusMetadata.PERCENTAGES
        var best = distribution.lastOrNull() ?: 0.0
        for (index in table.indices) {
            if (table[index] >= percent) {
                best = distribution.getOrElse(index) { best }
                break
            }
        }
        return best
    }

    /** 归一化值换算成尼特。 */
    fun nitsOf(normalized: Double): Double =
        normalized * FableSolExportTransfer.PQ_MAX_NITS

    companion object {

        /**
         * 从一份完整 CFD 生成本帧统计。
         *
         * 两个统计后端（GLES 3.1 compute 与 GLES 3.0 回读）都产出同一种 [histogram]，
         * 因此这一步是共同的：后端一致性测试比较的正是这里的每一项输出（D169）。
         */
        fun of(
            histogram: FableSolExportHdr10PlusHistogram,
            fractionBrightPixels: Double,
            proxyAverageLuminance: Double? = null
        ): FableSolHdr10PlusStats {
            val table = FableSolExportHdr10PlusMetadata.PERCENTAGES
            val distribution = DoubleArray(table.size) { index ->
                when (index) {
                    // ApplicationVersion 1：J1=5 与 J2=10 时 V1/V2 是保留标记，不是真分位
                    // （ST 2094-40 §8.5.4，D101）。
                    1 -> FableSolExportHdr10PlusMetadata.RESERVED_V1
                    2 -> FableSolExportHdr10PlusMetadata.RESERVED_V2
                    // J8 = 99 必须按 99.98% 计算 V8，不是字面 99。
                    8 -> histogram.percentile(
                        FableSolExportHdr10PlusMetadata.V8_PERCENT
                    )
                    else -> histogram.percentile(table[index].toDouble())
                }
            }
            return FableSolHdr10PlusStats(
                maxScl = histogram.maxScl.copyOf(),
                averageMaxRgb = histogram.averageMaxRgb,
                distribution = distribution,
                percentile9998 = histogram.percentile(
                    FableSolExportHdr10PlusMetadata.V8_PERCENT
                ),
                fractionBrightPixels = fractionBrightPixels,
                proxyAverageLuminance = proxyAverageLuminance,
                histogram = histogram
            )
        }

        /** 探测用的占位统计：结构合法即可，不进任何用户文件。 */
        fun placeholder(normalizedPeak: Double): FableSolHdr10PlusStats {
            val peak = normalizedPeak.coerceIn(0.0, 1.0)
            return FableSolHdr10PlusStats(
                maxScl = DoubleArray(3) { peak },
                averageMaxRgb = peak * 0.1,
                distribution = DoubleArray(
                    FableSolExportHdr10PlusMetadata.PERCENTAGES.size
                ) { index ->
                    when (index) {
                        1 -> FableSolExportHdr10PlusMetadata.RESERVED_V1
                        2 -> FableSolExportHdr10PlusMetadata.RESERVED_V2
                        else -> peak * 0.5
                    }
                },
                percentile9998 = peak,
                fractionBrightPixels = 0.0,
                proxyAverageLuminance = null,
                histogram = null
            )
        }

        /**
         * ApplicationVersion 1 的 `FractionBrightPixels`（D108，ST 2094-40 §10.4）。
         *
         * @param proxyLuminance 5:1 平滑缩小后的代理帧亮度，BT.2020/D65 加权，线性归一化。
         * @return `[0, 1]`；代理帧为空时返回 0（按 D109 解释为"未计算"）。
         */
        fun fractionBrightPixels(proxyLuminance: DoubleArray): Double {
            if (proxyLuminance.isEmpty()) return 0.0
            var peak = 0.0
            for (value in proxyLuminance) if (value > peak) peak = value
            var weighted = 0.0
            for (value in proxyLuminance) {
                weighted += brightWeight(max(peak - value, 0.0))
            }
            return weighted / proxyLuminance.size
        }

        /**
         * 规范权重函数（式 7）。
         *
         * `ε < μ1` 是**全权重区**——D108 的初版表述漏掉了它，照字面实现会把最亮 1/255 带内的
         * 像素算错。
         */
        fun brightWeight(epsilon: Double): Double = when {
            epsilon < MU1 -> 1.0
            epsilon < MU2 -> (MU2 - epsilon) / (MU2 - MU1)
            else -> 0.0
        }

        /** BT.2020 / D65 亮度权重（式 6）。 */
        const val LUMA_R = 0.2627
        const val LUMA_G = 0.6780
        const val LUMA_B = 0.0593

        /** 代理帧的缩小倍数（5:1）。 */
        const val PROXY_SCALE = 5

        private const val MU1 = 1.0 / 255.0
        private const val MU2 = 5.0 / 255.0

        /**
         * 已完成计算且结果大于 0 时至少写 0.001，不能因量化向下变成表示"未计算"的 0（D108）。
         */
        fun quantizeFractionBrightPixels(value: Double): Int {
            if (value <= 0.0) return 0
            return (value * 1000.0).roundToInt().coerceIn(1, 1023)
        }
    }
}
