package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * HDR Vivid 1.0 统计信息模式的四项场景统计。
 *
 * 四个字段都是 12-bit PQ 码值。生成过程严格对应 T/UWA 005.1 附录 A：
 *
 * - minimum / maximum：场景内 `max(R', G', B')` 的最小值与最大值；
 * - average：先在绝对线性光域求 `max(R, G, B)` 的平均，再转回 PQ；
 * - variance：线性域 90% 分位减 10% 分位后再整体转 PQ，并非统计学方差。
 */
internal data class FableSolHdrVividStatistics(
    val minimumMaxRgbPq: Int,
    val averageMaxRgbPq: Int,
    val varianceMaxRgbPq: Int,
    val maximumMaxRgbPq: Int
) {

    init {
        for (value in listOf(
            minimumMaxRgbPq,
            averageMaxRgbPq,
            varianceMaxRgbPq,
            maximumMaxRgbPq
        )) {
            require(value in 0..MAX_PQ_CODE) { "HDR Vivid statistic exceeds 12 bits: $value" }
        }
        require(minimumMaxRgbPq <= maximumMaxRgbPq) {
            "HDR Vivid minimum exceeds maximum"
        }
    }

    companion object {

        const val MAX_PQ_CODE = 4095

        /**
         * 复用 FableSol 已有的完整场景直方图，不从 HDR10+ 的九项码流分位反推。
         *
         * 直方图存的是以 10000 尼特归一的线性 `maxRGB`；PQ 是单调函数，因此最小值、最大值
         * 和分位位置保持不变，只需在最终量化前转回 PQ。
         */
        fun from(stats: FableSolHdr10PlusStats): FableSolHdrVividStatistics {
            val histogram = checkNotNull(stats.histogram) {
                "HDR Vivid requires the complete scene histogram"
            }
            val minimumPq = FableSolExportHdr10PlusMetadata.linearToPq(
                histogram.percentile(0.0)
            )
            val averagePq = FableSolExportHdr10PlusMetadata.linearToPq(
                histogram.averageMaxRgb
            )
            val percentile10Linear = histogram.percentile(10.0)
            val percentile90Linear = histogram.percentile(90.0)
            val maximumLinear = stats.maxScl.maxOrNull()
                ?: histogram.percentile(100.0)
            val maximumPq = FableSolExportHdr10PlusMetadata.linearToPq(maximumLinear)
            return FableSolHdrVividStatistics(
                minimumMaxRgbPq = quantize(minimumPq),
                averageMaxRgbPq = quantize(averagePq),
                varianceMaxRgbPq = quantize(
                    FableSolExportHdr10PlusMetadata.linearToPq(
                        (percentile90Linear - percentile10Linear).coerceAtLeast(0.0)
                    )
                ),
                maximumMaxRgbPq = quantize(maximumPq)
            )
        }

        /** T/UWA 005.1 明确规定使用 Floor，不得改成四舍五入。 */
        internal fun quantize(pq: Double): Int =
            floor(pq.coerceIn(0.0, 1.0) * MAX_PQ_CODE).toInt()
    }
}

/** T/UWA 005.1 表 10 中一组已经量化的 Base Parameters。 */
internal data class FableSolHdrVividBaseCurve(
    val mP: Int,
    val mM: Int,
    val mA: Int,
    val mB: Int,
    val mN: Int,
    val k1: Int = 1,
    val k2: Int = 1,
    val k3: Int = 1,
    val deltaMode: Int = 3,
    val delta: Int = 0
) {

    init {
        require(mP in 0..0x3FFF)
        require(mM in 0..0x3F)
        require(mA in 0..0x3FF)
        require(mB in 0..0x3FF)
        require(mN in 0..0x3F)
        require(k1 in 0..1)
        require(k2 in 0..1)
        require(k3 in 1..2)
        require(deltaMode in 0..7)
        require(delta in 0..0x7F)
    }

    /**
     * 对本项目固定使用的 `K1=K2=K3=1` Base 曲线求值。
     *
     * 公式对应 T/UWA 005.1 式（16）；输入与输出均为 `[0,1]` PQ 码值。发送端门禁对量化后
     * 参数求值，避免浮点拟合通过而最终位流发生反转或峰值漂移。
     */
    fun evaluate(inputPq: Double): Double {
        check(k1 == 1 && k2 == 1 && k3 == 1) {
            "FableSol only evaluates the K1=K2=K3=1 HDR Vivid base family"
        }
        val x = inputPq.coerceIn(0.0, 1.0)
        val mp = mP * 10.0 / 0x3FFF
        val mm = mM / 10.0
        val ma = mA / 1023.0
        val mb = mB * 0.25 / 1023.0
        val mn = mN / 10.0
        if (mp <= 0.0 || mm <= 0.0 || mn <= 0.0) return mb
        val xn = x.pow(mn)
        val denominator = (mp - 1.0) * xn + 1.0
        if (denominator <= 0.0) return mb
        val shaped = (mp * xn / denominator).coerceAtLeast(0.0).pow(mm)
        return (ma * shaped + mb).coerceIn(0.0, 1.0)
    }

    fun blend(other: FableSolHdrVividBaseCurve, amount: Double):
        FableSolHdrVividBaseCurve {
        require(k1 == other.k1 && k2 == other.k2 && k3 == other.k3)
        require(deltaMode == other.deltaMode)
        return FableSolHdrVividBaseCurve(
            mP = blendCode(mP, other.mP, amount),
            mM = blendCode(mM, other.mM, amount),
            mA = blendCode(mA, other.mA, amount),
            mB = blendCode(mB, other.mB, amount),
            mN = blendCode(mN, other.mN, amount),
            k1 = k1,
            k2 = k2,
            k3 = k3,
            deltaMode = deltaMode,
            delta = blendCode(delta, other.delta, amount)
        )
    }
}

/** 一段已经量化的 3Spline 参数。 */
internal data class FableSolHdrVividSpline(
    val mode: Int,
    val mb: Int = 0,
    val threshold: Int,
    val delta1: Int,
    val delta2: Int,
    val strength: Int
) {

    init {
        require(mode in 0..3)
        require(mb in 0..0xFF)
        require(threshold in 0..0xFFF)
        require(delta1 in 0..0x3FF)
        require(delta2 in 0..0x3FF)
        require(strength in 0..0xFF)
    }

    fun blend(other: FableSolHdrVividSpline, amount: Double): FableSolHdrVividSpline {
        require(mode == other.mode)
        return FableSolHdrVividSpline(
            mode = mode,
            mb = blendCode(mb, other.mb, amount),
            threshold = blendCode(threshold, other.threshold, amount),
            delta1 = blendCode(delta1, other.delta1, amount),
            delta2 = blendCode(delta2, other.delta2, amount),
            strength = blendCode(strength, other.strength, amount)
        )
    }
}

/** 一个参考显示峰值对应的完整 Tone Mapping 参数组。 */
internal data class FableSolHdrVividToneMapping(
    val targetedSystemDisplayMaximumLuminancePq: Int,
    val base: FableSolHdrVividBaseCurve,
    val splines: List<FableSolHdrVividSpline>
) {

    init {
        require(targetedSystemDisplayMaximumLuminancePq in 0..0xFFF)
        require(splines.size in 0..2)
    }

    fun blend(other: FableSolHdrVividToneMapping, amount: Double):
        FableSolHdrVividToneMapping {
        require(splines.size == other.splines.size)
        return FableSolHdrVividToneMapping(
            targetedSystemDisplayMaximumLuminancePq = blendCode(
                targetedSystemDisplayMaximumLuminancePq,
                other.targetedSystemDisplayMaximumLuminancePq,
                amount
            ),
            base = base.blend(other.base, amount),
            splines = splines.indices.map { index ->
                splines[index].blend(other.splines[index], amount)
            }
        )
    }
}

/** 一帧最终发送的 HDR Vivid 元数据；色彩饱和度映射暂不启用。 */
internal data class FableSolHdrVividFrameMetadata(
    val statistics: FableSolHdrVividStatistics,
    val toneMappings: List<FableSolHdrVividToneMapping> = emptyList()
) {

    init {
        require(toneMappings.size in 0..2)
    }

    fun blend(other: FableSolHdrVividFrameMetadata, amount: Double):
        FableSolHdrVividFrameMetadata {
        require(toneMappings.size == other.toneMappings.size)
        val t = amount.coerceIn(0.0, 1.0)
        val from = statistics
        val to = other.statistics
        return FableSolHdrVividFrameMetadata(
            statistics = FableSolHdrVividStatistics(
                minimumMaxRgbPq = blendCode(
                    from.minimumMaxRgbPq, to.minimumMaxRgbPq, t
                ),
                averageMaxRgbPq = blendCode(
                    from.averageMaxRgbPq, to.averageMaxRgbPq, t
                ),
                varianceMaxRgbPq = blendCode(
                    from.varianceMaxRgbPq, to.varianceMaxRgbPq, t
                ),
                maximumMaxRgbPq = blendCode(
                    from.maximumMaxRgbPq, to.maximumMaxRgbPq, t
                )
            ),
            toneMappings = toneMappings.indices.map { index ->
                toneMappings[index].blend(other.toneMappings[index], t)
            }
        )
    }
}

private fun blendCode(from: Int, to: Int, amount: Double): Int =
    (from + (to - from) * amount.coerceIn(0.0, 1.0)).roundToInt()

/**
 * T/UWA 005.2-1 定义的 HDR Vivid 1.0 `user_data_registered_itu_t_t35` 载荷。
 *
 * 支持统计信息模式和 T/UWA 005.1 表 10 的完整 Base Parameters／3Spline Tone Mapping
 * 参数；色彩饱和度映射仍关闭。最后按规范用 0 补齐到字节边界。
 */
internal object FableSolHdrVividMetadata {

    const val COUNTRY_CODE = 0x26
    const val PROVIDER_CODE = 0x0004
    const val VERSION_1_ORIENTED_CODE = 0x0005
    const val SYSTEM_START_CODE = 0x01
    const val VERSION_1_MAP = 0x0001
    /** 仅统计信息模式的固定载荷长度；完整曲线载荷按参数数量动态增长。 */
    const val PAYLOAD_BYTES = 13

    fun payload(statistics: FableSolHdrVividStatistics): ByteArray {
        return payload(FableSolHdrVividFrameMetadata(statistics))
    }

    fun payload(metadata: FableSolHdrVividFrameMetadata): ByteArray {
        val bits = BitWriter(MAX_PAYLOAD_BYTES)
        bits.write(COUNTRY_CODE, 8)
        bits.write(PROVIDER_CODE, 16)
        bits.write(VERSION_1_ORIENTED_CODE, 16)
        bits.write(SYSTEM_START_CODE, 8)
        val statistics = metadata.statistics
        bits.write(statistics.minimumMaxRgbPq, 12)
        bits.write(statistics.averageMaxRgbPq, 12)
        bits.write(statistics.varianceMaxRgbPq, 12)
        bits.write(statistics.maximumMaxRgbPq, 12)
        bits.write(if (metadata.toneMappings.isEmpty()) 0 else 1, 1)
        if (metadata.toneMappings.isNotEmpty()) {
            bits.write(metadata.toneMappings.size - 1, 1)
            for (toneMapping in metadata.toneMappings) {
                bits.write(toneMapping.targetedSystemDisplayMaximumLuminancePq, 12)
                bits.write(1, 1) // base_enable_flag
                val base = toneMapping.base
                bits.write(base.mP, 14)
                bits.write(base.mM, 6)
                bits.write(base.mA, 10)
                bits.write(base.mB, 10)
                bits.write(base.mN, 6)
                bits.write(base.k1, 2)
                bits.write(base.k2, 2)
                bits.write(base.k3, 4)
                bits.write(base.deltaMode, 3)
                bits.write(base.delta, 7)
                bits.write(if (toneMapping.splines.isEmpty()) 0 else 1, 1)
                if (toneMapping.splines.isNotEmpty()) {
                    bits.write(toneMapping.splines.size - 1, 1)
                    for (spline in toneMapping.splines) {
                        bits.write(spline.mode, 2)
                        if (spline.mode == 0 || spline.mode == 2) {
                            bits.write(spline.mb, 8)
                        }
                        bits.write(spline.threshold, 12)
                        bits.write(spline.delta1, 10)
                        bits.write(spline.delta2, 10)
                        bits.write(spline.strength, 8)
                    }
                }
            }
        }
        bits.write(0, 1) // color_saturation_mapping_enable_flag
        val payload = bits.toByteArrayWithZeroPadding()
        check(payload.size <= MAX_PAYLOAD_BYTES) {
            "HDR Vivid payload exceeds the version-1 bound: ${payload.size}"
        }
        return payload
    }

    /** 未经过防竞争处理的固定签名；这五个字节自身不会触发 emulation prevention。 */
    val SIGNATURE = byteArrayOf(
        COUNTRY_CODE.toByte(),
        0x00,
        PROVIDER_CODE.toByte(),
        0x00,
        VERSION_1_ORIENTED_CODE.toByte(),
        SYSTEM_START_CODE.toByte()
    )

    fun containsSignature(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE.size) return false
        outer@ for (start in 0..bytes.size - SIGNATURE.size) {
            for (index in SIGNATURE.indices) {
                if (bytes[start + index] != SIGNATURE[index]) continue@outer
            }
            return true
        }
        return false
    }

    private const val MAX_PAYLOAD_BYTES = 60

    private class BitWriter(initialBytes: Int) {

        private val bytes = ArrayList<Byte>(initialBytes)
        private var current = 0
        private var filled = 0

        fun write(value: Int, bitCount: Int) {
            require(bitCount in 1..31)
            require(value >= 0 && (bitCount == 31 || value < (1 shl bitCount))) {
                "Value $value does not fit in $bitCount bits"
            }
            for (bit in bitCount - 1 downTo 0) {
                current = (current shl 1) or ((value ushr bit) and 1)
                filled++
                if (filled == 8) {
                    bytes += current.toByte()
                    current = 0
                    filled = 0
                }
            }
        }

        fun toByteArrayWithZeroPadding(): ByteArray {
            if (filled > 0) {
                bytes += (current shl (8 - filled)).toByte()
                current = 0
                filled = 0
            }
            return bytes.toByteArray()
        }
    }
}
