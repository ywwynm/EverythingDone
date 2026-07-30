package com.ywwynm.everythingdone.views.recording.fablesol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ST 2094-40（HDR10+）动态元数据的构造。
 *
 * 载荷按 `user_data_registered_itu_t_t35()` 语法**逐位打包**，不是字节对齐结构——写错一位
 * 后面所有字段全部错位，而错位只会表现为"编码器不认"或"播放端色调映射离谱"，都极难反查。
 * 因此固定头、字段宽度与总长由单测钉住。
 *
 * ApplicationVersion 1 有三条特殊语义，实现与测试都必须识别（D101，已对照 ST 2094-40:2020
 * §8.5.4 逐字段验证）：`J1=5 且 J2=10` 时 `V1`、`V2` 不属于 CFD，固定写 `0.00000` 与
 * `0.00255`；`J8=99` 出现时 `V8` 必须按 **99.98%** 计算。
 */
internal object FableSolExportHdr10PlusMetadata {

    /** ST 2094-40 的九项分位定义；个数与顺序都不能改。 */
    val PERCENTAGES = intArrayOf(1, 5, 10, 25, 50, 75, 90, 95, 99)

    /** `J1 = 5` 时 `V1` 的保留固定值。 */
    const val RESERVED_V1 = 0.00000

    /** `J2 = 10` 时 `V2` 的保留固定值（以 10000 尼特归一即 25.5 尼特）。 */
    const val RESERVED_V2 = 0.00255

    /** `J8 = 99` 出现时，`V8` 实际按这个百分比计算。 */
    const val V8_PERCENT = 99.98

    /**
     * @param curve 色调映射曲线。**全片固定 Profile B**（D111、D177）：`tone_mapping_flag`
     *   始终为 1，每帧都带相同的 KneePoint 与 9 个 anchors；场景源峰值未超过参考显示峰值时
     *   写同一条 Case 3 中性曲线而不是省略曲线。横轴按场景 V8（失效时回退 MaxSCL）
     *   归一化，纵轴按 [targetedPeakNits] 归一化；MDCV 母版峰值不参与曲线坐标。
     * @param targetedPeakNits 用户选择的参考显示峰值（D94）；不是母版峰值。它写进
     *   `targeted_system_display_maximum_luminance`，只描述曲线是为哪台显示设备做的。
     */
    fun payload(
        stats: FableSolHdr10PlusStats,
        curve: FableSolExportHdr10PlusCurve.Shape,
        targetedPeakNits: Double
    ): ByteBuffer {
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
        bits.write(
            scaled(targetedPeakNits.coerceIn(0.0, MAX_TARGET_NITS), TARGET_SCALE, 27), 27
        )
        bits.write(0, 1)                          // targeted_system_display_actual_peak_flag
        for (channel in 0 until 3) {
            bits.write(normalized(stats.maxScl.getOrElse(channel) { 0.0 }), 17)
        }
        bits.write(normalized(stats.averageMaxRgb), 17)
        bits.write(PERCENTAGES.size, 4)
        for (index in PERCENTAGES.indices) {
            bits.write(PERCENTAGES[index], 7)
            bits.write(normalized(stats.distribution.getOrElse(index) { 0.0 }), 17)
        }
        bits.write(
            FableSolHdr10PlusStats.quantizeFractionBrightPixels(stats.fractionBrightPixels),
            10
        )
        bits.write(0, 1)                          // mastering_display_actual_peak_flag
        bits.write(1, 1)                          // tone_mapping_flag：全片固定为 1（D111）
        bits.write(unit(curve.kneeX, KNEE_SCALE), 12)
        bits.write(unit(curve.kneeY, KNEE_SCALE), 12)
        bits.write(curve.anchors.size, 4)
        for (anchor in curve.anchors) {
            bits.write(unit(anchor, ANCHOR_SCALE), 10)
        }
        bits.write(0, 1)                          // color_saturation_mapping_flag
        return ByteBuffer.wrap(bits.toByteArray()).order(ByteOrder.BIG_ENDIAN)
    }

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

    /** ST.2084 的 EOTF：把 `[0,1]` 的 PQ 编码值还原成线性归一化值（1.0 = 10000 尼特）。 */
    fun pqToLinear(encoded: Double): Double {
        val e = encoded.coerceIn(0.0, 1.0)
        val powered = e.pow(1.0 / M2)
        val numerator = max(powered - C1, 0.0)
        val denominator = C2 - C3 * powered
        if (denominator <= 0.0) return 1.0
        return (numerator / denominator).pow(1.0 / M1)
    }

    /** ST.2084 的反 EOTF：线性归一化值 → PQ 编码值。曲线求解器在 PQ 感知域评价（D118）。 */
    fun linearToPq(linear: Double): Double {
        val y = linear.coerceIn(0.0, 1.0)
        val ym = y.pow(M1)
        return ((C1 + C2 * ym) / (1.0 + C3 * ym)).pow(M2)
    }

    /** 膝点是 12 位（/4095），贝塞尔控制点是 10 位（/1023）。 */
    private fun unit(value: Double, scale: Int): Int =
        (value.coerceIn(0.0, 1.0) * scale).roundToInt().coerceIn(0, scale)

    /**
     * MaxSCL / AverageMaxRGB / DistributionMaxRGB 是**归一化到 `[0,1]` 的线性值**，
     * 步长 0.00001，17 位。归一化基准是 PQ 的 10000 尼特上限。
     */
    fun normalized(value: Double): Int = scaled(value, NORMALIZED_SCALE, 17)

    private fun scaled(value: Double, scale: Int, bitCount: Int): Int {
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

    /** 归一化统计字段的量化：步长 0.00001。 */
    private const val NORMALIZED_SCALE = 100_000

    private const val KNEE_SCALE = 4095
    private const val ANCHOR_SCALE = 1023

    private const val M1 = 2610.0 / 16384.0
    private const val M2 = 2523.0 / 4096.0 * 128.0
    private const val C1 = 3424.0 / 4096.0
    private const val C2 = 2413.0 / 4096.0 * 32.0
    private const val C3 = 2392.0 / 4096.0 * 32.0
}
