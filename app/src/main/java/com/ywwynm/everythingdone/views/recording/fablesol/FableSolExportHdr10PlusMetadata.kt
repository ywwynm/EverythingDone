package com.ywwynm.everythingdone.views.recording.fablesol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一帧画面的 HDR10+ 亮度统计。全部**实测**得来，没有一项是估的。
 *
 * 这一点很重要：在 surface 输入模式下画面已经交给编码器，任何统计量都只能猜，所以当时
 * 拒绝了"自己造元数据"。字节缓冲模式下画面在我们手里，这些数就都是量出来的。
 */
internal class FableSolHdr10PlusStats(
    /** 三个通道各自的峰值（尼特）。必须是真的最大值，不能是采样估计。 */
    val maxsclNits: DoubleArray,
    /** 全帧 max(R,G,B) 的平均（尼特）。 */
    val averageMaxRgbNits: Double,
    /** 与 [FableSolExportHdr10PlusMetadata.PERCENTAGES] 一一对应的分位点（尼特）。 */
    val percentileNits: DoubleArray
) {

    /**
     * 任意百分位处的亮度（尼特）。
     *
     * 码流里只带标准的 9 个分位点，而「高光起点」是用户连续可调的，所以落在两个标准分位点
     * 之间时按百分位轴线性插值——比强行取最近的那个更贴合滑杆的手感。
     */
    fun nitsAtPercent(percent: Int): Double {
        val table = FableSolExportHdr10PlusMetadata.PERCENTAGES
        if (percentileNits.isEmpty()) return averageMaxRgbNits
        if (percent <= table.first()) return percentileNits.first()
        if (percent >= table.last()) return percentileNits.last()
        for (index in 1 until table.size) {
            if (percent <= table[index]) {
                val lower = table[index - 1]
                val span = (table[index] - lower).toDouble()
                val ratio = if (span <= 0.0) 0.0 else (percent - lower) / span
                return percentileNits[index - 1] +
                    (percentileNits[index] - percentileNits[index - 1]) * ratio
            }
        }
        return percentileNits.last()
    }
}

/**
 * ST 2094-40（HDR10+）动态元数据的构造与测量。
 *
 * 载荷按 `user_data_registered_itu_t_t35()` 语法**逐位打包**，不是字节对齐结构——写错一位
 * 后面所有字段全部错位，而错位只会表现为"编码器不认"或"播放端色调映射离谱"，都极难反查。
 * 因此固定头与总长由单测钉住。
 */
internal object FableSolExportHdr10PlusMetadata {

    /** ST 2094-40 的分位点定义；个数与顺序都不能改。 */
    val PERCENTAGES = intArrayOf(1, 5, 10, 25, 50, 75, 90, 95, 99)

    /**
     * @param curve 色调映射曲线；null 表示不写曲线（`tone_mapping_flag = 0`）。
     *
     * `targeted_system_display_maximum_luminance` 取曲线的目标峰值而不是我们的母版峰值——
     * 曲线的用处正是"显示设备够不到母版峰值时怎么压"，两者写成同一个数等于说"不用压"。
     */
    fun payload(
        stats: FableSolHdr10PlusStats,
        curve: FableSolExportHdr10PlusCurve.Shape?
    ): ByteBuffer {
        val targetedPeakNits =
            curve?.targetNits ?: FableSolExportHdr10PlusCurve.DEFAULT_TARGET_NITS
        val bits = BitWriter()
        bits.write(0xB5, 8)                       // itu_t_t35_country_code
        bits.write(0x003C, 16)                    // terminal_provider_code
        bits.write(0x0001, 16)                    // terminal_provider_oriented_code
        bits.write(4, 8)                          // application_identifier：固定 4
        bits.write(1, 8)                          // application_version
        bits.write(1, 2)                          // num_windows：整幅一个窗口
        // **单位是 0.0001 尼特，不是 1 尼特。** 27 位这个宽度就是为此存在的：
        // 上限 10000 尼特 ÷ 0.0001 = 1e8，正好要 27 位。按 1 尼特写会让播放端把 1000 尼特
        // 读成 0.1 尼特，整条色调映射曲线的目标亮度直接崩掉。
        bits.write(nits(targetedPeakNits.coerceAtMost(MAX_TARGET_NITS), TARGET_SCALE, 27), 27)
        bits.write(0, 1)                          // targeted_system_display_actual_peak_flag
        for (channel in 0 until 3) {
            bits.write(tenths(stats.maxsclNits.getOrElse(channel) { 0.0 }), 17)
        }
        bits.write(tenths(stats.averageMaxRgbNits), 17)
        bits.write(PERCENTAGES.size, 4)
        for (index in PERCENTAGES.indices) {
            bits.write(PERCENTAGES[index], 7)
            bits.write(tenths(stats.percentileNits.getOrElse(index) { 0.0 }), 17)
        }
        bits.write(0, 10)                         // fraction_bright_pixels
        bits.write(0, 1)                          // mastering_display_actual_peak_flag
        if (curve == null) {
            bits.write(0, 1)                      // tone_mapping_flag
        } else {
            bits.write(1, 1)                      // tone_mapping_flag
            bits.write(unit(curve.kneeX, KNEE_SCALE), 12)
            bits.write(unit(curve.kneeY, KNEE_SCALE), 12)
            bits.write(curve.anchors.size, 4)
            for (anchor in curve.anchors) {
                bits.write(unit(anchor, ANCHOR_SCALE), 10)
            }
        }
        bits.write(0, 1)                          // color_saturation_mapping_flag
        return ByteBuffer.wrap(bits.toByteArray()).order(ByteOrder.BIG_ENDIAN)
    }

    /**
     * 从 GPU 归约结果（`p010_stats.frag` 输出的 `STATS_SIZE×STATS_SIZE` RGBA8）算统计量。
     *
     * 每个 texel：R/G/B 是该块内各通道的最大值，A 是该块内 `max(R,G,B)` 的平均值，全部是
     * PQ 编码后的非线性值。所以这里 CPU 侧只有一千来次迭代，逐帧成本可以忽略。
     */
    fun measure(reduced: ByteArray, texelCount: Int): FableSolHdr10PlusStats {
        val peak = DoubleArray(3)
        var sum = 0.0
        var count = 0
        val means = DoubleArray(texelCount)
        for (index in 0 until texelCount) {
            val base = index * 4
            if (base + 3 >= reduced.size) break
            for (channel in 0 until 3) {
                val value = (reduced[base + channel].toInt() and 0xFF) / 255.0
                if (value > peak[channel]) peak[channel] = value
            }
            val mean = (reduced[base + 3].toInt() and 0xFF) / 255.0
            means[count] = mean
            sum += mean
            count++
        }
        if (count == 0) return placeholder(FableSolExportTransfer.SDR_WHITE_NITS)
        val sorted = means.copyOf(count)
        sorted.sort()
        val percentiles = DoubleArray(PERCENTAGES.size) { index ->
            val position = ((count - 1) * PERCENTAGES[index] / 100).coerceIn(0, count - 1)
            pqToNits(sorted[position])
        }
        return FableSolHdr10PlusStats(
            maxsclNits = DoubleArray(3) { pqToNits(peak[it]) },
            averageMaxRgbNits = pqToNits(sum / count),
            percentileNits = percentiles
        )
    }

    /** 探测用的占位统计：只要结构合法即可，不进任何用户文件。 */
    fun placeholder(peakNits: Double): FableSolHdr10PlusStats = FableSolHdr10PlusStats(
        maxsclNits = DoubleArray(3) { peakNits },
        averageMaxRgbNits = FableSolExportTransfer.SDR_WHITE_NITS,
        percentileNits = DoubleArray(PERCENTAGES.size) {
            FableSolExportTransfer.SDR_WHITE_NITS
        }
    )

    /**
     * 编出来的码流里到底有没有那段 HDR10+ SEI。**这才是 HDR10+ 的判据。**
     *
     * 签名取 T.35 国家码 0xB5 + terminal_provider_code 0x003C + oriented_code 高两位
     * 0x00 0x01。这五个字节里没有连续两个 0x00，因此不会被防竞争字节 0x03 打断，可以直接
     * 按字节匹配，不必解析 NAL 结构。
     */
    fun containsSei(buffer: ByteBuffer, offset: Int, size: Int): Boolean {
        if (size < SEI_SIGNATURE.size) return false
        val view = buffer.duplicate()
        val bytes = ByteArray(size)
        view.position(offset)
        view.limit(offset + size)
        view.get(bytes)
        outer@ for (start in 0..bytes.size - SEI_SIGNATURE.size) {
            for (index in SEI_SIGNATURE.indices) {
                if (bytes[start + index] != SEI_SIGNATURE[index]) continue@outer
            }
            return true
        }
        return false
    }

    private val SEI_SIGNATURE = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01)

    /** ST.2084 的 EOTF：把 [0,1] 的 PQ 编码值还原成绝对亮度（尼特）。 */
    fun pqToNits(encoded: Double): Double {
        val e = encoded.coerceIn(0.0, 1.0)
        val powered = e.pow(1.0 / M2)
        val numerator = max(powered - C1, 0.0)
        val denominator = C2 - C3 * powered
        if (denominator <= 0.0) return FableSolExportTransfer.PQ_MAX_NITS
        return FableSolExportTransfer.PQ_MAX_NITS * (numerator / denominator).pow(1.0 / M1)
    }

    /** 膝点是 12 位（/4095），贝塞尔控制点是 10 位（/1023）。 */
    private fun unit(value: Double, scale: Int): Int =
        (value.coerceIn(0.0, 1.0) * scale).roundToInt().coerceIn(0, scale)

    /**
     * maxscl / average / 分位点是**归一化到 [0,1] 的线性值**，步长 0.00001，17 位。
     *
     * 归一化基准是 PQ 的 10000 尼特上限，所以 `归一化值 ÷ 0.00001 = 尼特 × 10`——数值上
     * 与"十分之一尼特"相同，但含义是归一化值，别把它和上面那个绝对亮度字段混为一谈：
     * 那一个才是真正以 0.0001 尼特为单位的绝对量。
     */
    private fun tenths(nits: Double): Int = nits(nits, 10, 17)

    private fun nits(value: Double, scale: Int, bitCount: Int): Int {
        val ceiling = (1 shl bitCount) - 1
        return min((value * scale).roundToInt().coerceAtLeast(0), ceiling)
    }

    /** 逐位写入器；ST 2094-40 的字段宽度大多不是 8 的倍数。 */
    internal class BitWriter {

        private val bytes = ArrayList<Byte>(64)
        private var current = 0
        private var filled = 0

        fun write(value: Int, bitCount: Int) {
            for (bit in bitCount - 1 downTo 0) {
                current = (current shl 1) or ((value shr bit) and 1)
                filled++
                if (filled == 8) {
                    bytes.add(current.toByte())
                    current = 0
                    filled = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (filled > 0) {
                bytes.add((current shl (8 - filled)).toByte())
                current = 0
                filled = 0
            }
            return bytes.toByteArray()
        }
    }

    /** `targeted_system_display_maximum_luminance` 以 0.0001 尼特为单位。 */
    private const val TARGET_SCALE = 10_000
    private const val MAX_TARGET_NITS = 10_000.0

    private const val KNEE_SCALE = 4095
    private const val ANCHOR_SCALE = 1023

    /** GPU 归约的边长；1024 个块足够描述一帧的亮度分布。 */
    const val STATS_SIZE = 32

    private const val M1 = 2610.0 / 16384.0
    private const val M2 = 2523.0 / 4096.0 * 128.0
    private const val C1 = 3424.0 / 4096.0
    private const val C2 = 2413.0 / 4096.0 * 32.0
    private const val C3 = 2392.0 / 4096.0 * 32.0
}
