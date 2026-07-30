package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.ByteArrayOutputStream

/**
 * HDR Vivid 动态元数据在 HEVC 访问单元中的封装。
 *
 * 输出统一转成 4 字节 start code 的 Annex B：Android `MediaMuxer` 会识别一个样本里的多个
 * NAL，并分别改写成 MP4 的长度前缀。这样 prefix SEI 与 VCL 仍是两个合法 NAL，而不是把
 * start code 塞进一个错误的长度前缀 NAL。
 */
internal object FableSolHdrVividHevc {

    data class Injection(
        val annexB: ByteArray,
        val inserted: Boolean
    )

    /**
     * 生成 HEVC prefix SEI NAL（不含 Annex B start code）。
     *
     * nal_unit_type 39、layer_id 0、temporal_id_plus1 1 对应固定 NAL header `4E 01`。
     */
    fun prefixSeiNal(t35Payload: ByteArray): ByteArray {
        require(t35Payload.isNotEmpty())
        val rbsp = ByteArrayOutputStream(t35Payload.size + 8).apply {
            writeExtendedValue(SEI_USER_DATA_REGISTERED_ITU_T_T35)
            writeExtendedValue(t35Payload.size)
            write(t35Payload)
            write(RBSP_TRAILING_BITS)
        }.toByteArray()
        val escaped = applyEmulationPrevention(rbsp)
        return ByteArray(HEVC_NAL_HEADER.size + escaped.size).also { result ->
            HEVC_NAL_HEADER.copyInto(result)
            escaped.copyInto(result, HEVC_NAL_HEADER.size)
        }
    }

    /**
     * 在第一个 VCL NAL 之前插入 prefix SEI。
     *
     * 接受 Annex B、1/2/4 字节长度前缀或单个裸 NAL，输出一律为 Annex B。若样本已经包含
     * HDR Vivid 1.0 签名则原样返回，保证重复调用不会重复注入。
     */
    fun inject(sample: ByteArray, t35Payload: ByteArray): Injection {
        require(sample.isNotEmpty()) { "Empty HEVC access unit" }
        if (FableSolHdrVividMetadata.containsSignature(sample)) {
            return Injection(sample.copyOf(), inserted = false)
        }
        val units = parseUnits(sample)
        val firstVcl = units.indexOfFirst { nalType(it) in VCL_NAL_TYPES }
        require(firstVcl >= 0) { "HEVC access unit contains no VCL NAL" }
        val sei = prefixSeiNal(t35Payload)
        val output = ByteArrayOutputStream(sample.size + sei.size + START_CODE.size)
        for (index in 0..units.size) {
            if (index == firstVcl) {
                output.write(START_CODE)
                output.write(sei)
            }
            if (index < units.size) {
                output.write(START_CODE)
                output.write(units[index])
            }
        }
        return Injection(output.toByteArray(), inserted = true)
    }

    fun containsHdrVivid(bytes: ByteArray): Boolean =
        FableSolHdrVividMetadata.containsSignature(bytes)

    /** 测试与结构核对使用：移除 NAL header 后的 emulation-prevention 字节。 */
    internal fun unescapeRbsp(nal: ByteArray): ByteArray {
        require(nal.size >= HEVC_NAL_HEADER.size)
        val result = ByteArrayOutputStream(nal.size)
        var zeroCount = 0
        var index = HEVC_NAL_HEADER.size
        while (index < nal.size) {
            val value = nal[index].toInt() and 0xFF
            if (
                zeroCount >= 2 &&
                value == EMULATION_PREVENTION_BYTE &&
                index + 1 < nal.size &&
                (nal[index + 1].toInt() and 0xFF) <= 0x03
            ) {
                zeroCount = 0
                index++
                continue
            }
            result.write(value)
            zeroCount = if (value == 0) zeroCount + 1 else 0
            index++
        }
        return result.toByteArray()
    }

    private fun parseUnits(sample: ByteArray): List<ByteArray> {
        parseAnnexB(sample)?.let { return it }
        for (lengthBytes in intArrayOf(4, 2, 1)) {
            parseLengthPrefixed(sample, lengthBytes)?.let { return it }
        }
        require(sample.size >= HEVC_NAL_HEADER.size) { "Truncated HEVC NAL" }
        require(nalType(sample) in 0..63) { "Invalid HEVC NAL header" }
        return listOf(sample.copyOf())
    }

    private fun parseAnnexB(sample: ByteArray): List<ByteArray>? {
        val first = findStartCode(sample, 0) ?: return null
        if (first.first != 0) return null
        val units = ArrayList<ByteArray>(4)
        var start = first
        while (true) {
            val nalStart = start.first + start.second
            val next = findStartCode(sample, nalStart)
            val nalEnd = next?.first ?: sample.size
            require(nalEnd > nalStart) { "Empty HEVC NAL in Annex B access unit" }
            units += sample.copyOfRange(nalStart, nalEnd)
            if (next == null) break
            start = next
        }
        return units
    }

    private fun findStartCode(bytes: ByteArray, from: Int): Pair<Int, Int>? {
        var index = from.coerceAtLeast(0)
        while (index + 2 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (
                    index + 3 < bytes.size &&
                    bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte()
                ) {
                    return index to 4
                }
                if (bytes[index + 2] == 1.toByte()) return index to 3
            }
            index++
        }
        return null
    }

    private fun parseLengthPrefixed(
        sample: ByteArray,
        lengthBytes: Int
    ): List<ByteArray>? {
        val units = ArrayList<ByteArray>(4)
        var offset = 0
        while (offset < sample.size) {
            if (sample.size - offset < lengthBytes) return null
            var size = 0
            repeat(lengthBytes) {
                size = (size shl 8) or (sample[offset + it].toInt() and 0xFF)
            }
            offset += lengthBytes
            if (size < HEVC_NAL_HEADER.size || size > sample.size - offset) return null
            val nal = sample.copyOfRange(offset, offset + size)
            if (nalType(nal) !in 0..63) return null
            units += nal
            offset += size
        }
        return units.takeIf { it.isNotEmpty() }
    }

    private fun nalType(nal: ByteArray): Int {
        require(nal.size >= HEVC_NAL_HEADER.size) { "Truncated HEVC NAL" }
        return (nal[0].toInt() ushr 1) and 0x3F
    }

    private fun applyEmulationPrevention(rbsp: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(rbsp.size + 8)
        var zeroCount = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xFF
            if (zeroCount >= 2 && value <= 0x03) {
                output.write(EMULATION_PREVENTION_BYTE)
                zeroCount = 0
            }
            output.write(value)
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeExtendedValue(value: Int) {
        require(value >= 0)
        var remaining = value
        while (remaining >= 0xFF) {
            write(0xFF)
            remaining -= 0xFF
        }
        write(remaining)
    }

    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private val HEVC_NAL_HEADER = byteArrayOf(0x4E, 0x01)
    private val VCL_NAL_TYPES = 0..31
    private const val SEI_USER_DATA_REGISTERED_ITU_T_T35 = 4
    private const val RBSP_TRAILING_BITS = 0x80
    private const val EMULATION_PREVENTION_BYTE = 0x03
}
