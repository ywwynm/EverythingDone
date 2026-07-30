package com.ywwynm.everythingdone.views.recording.fablesol

import java.nio.ByteBuffer

/**
 * 从**实际编码器产生的码流**读出 4:2:0 色度位置（D154、D170）。
 *
 * 为什么必须读而不是设：Android 的 `MediaFormat` 没有供应用可靠指定 HEVC VUI 色度采样位置
 * 的标准键。我们能做的是生成与码流声明**一致**的相位——在编码器正确声明 Type 2 时用共点
 * 滤波，在它什么都不声明时按 HEVC 生态的 Type 0 解码语义匹配相位，而不是继续生成
 * Type 1（2×2 box average 的等价物）数据让解码端按别的位置解释。
 *
 * 解析失败一律回到 [FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT]，并在诊断里记
 * "码流未显式声明、按 Type 0 匹配"。它不得导致导出失败、格式切换或重编码。
 */
internal object FableSolExportChromaSiting {

    /** 一次解析结论：位置本身，以及它是不是码流显式声明的。 */
    data class Result(
        val siting: FableSolExportP010Math.ChromaSiting,
        /** false 表示码流没有声明，当前相位来自兼容语义而不是编码器的承诺。 */
        val declared: Boolean
    ) {
        companion object {
            val UNDECLARED = Result(
                FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT, declared = false
            )
        }
    }

    /**
     * HEVC：从 csd-0 里的 SPS 读 VUI 的 `chroma_sample_loc_type_top_field`。
     *
     * csd-0 是 Annex-B 字节流，含 VPS/SPS/PPS 若干个 NAL。这里只找 SPS（nal_unit_type 33），
     * 去掉防竞争字节后按 H.265 语法一路走到 VUI。SPS 在 VUI 之前有 scaling list 与
     * short-term ref pic set 这类可变长结构，绕不过去——但凡少走一个字段，后面读出来的就是
     * 别的东西，所以宁可整段解析失败也不猜。
     */
    fun parseHevc(csd: ByteBuffer?): Result {
        val sps = findHevcSps(csd) ?: return Result.UNDECLARED
        return try {
            parseHevcSps(BitReader(removeEmulationPrevention(sps)))
        } catch (ignored: Throwable) {
            Result.UNDECLARED
        }
    }

    /**
     * AV1：从 csd-0 的 `AV1CodecConfigurationRecord` 读 `chroma_sample_position`。
     *
     * 该记录把序列头的 color_config 字段原样复制到固定位置，第 3 个字节的低 2 位就是它，
     * 不需要真的去解 OBU（AV1 也没有防竞争字节，即便解也比 HEVC 简单）。
     *
     * 取值按 D170 映射：`CSP_COLOCATED` = Type 2、`CSP_VERTICAL` = Type 0、
     * `CSP_UNKNOWN` 与保留值按 Type 0 兼容语义。
     */
    fun parseAv1(csd: ByteBuffer?): Result {
        val bytes = toArray(csd) ?: return Result.UNDECLARED
        if (bytes.size < 4) return Result.UNDECLARED
        // marker(1)=1 与 version(7)=1 合起来正好是 0x81；不是这个就不是 av1C。
        if ((bytes[0].toInt() and 0xFF) != 0x81) return Result.UNDECLARED
        val monochrome = (bytes[2].toInt() shr 4) and 0x1
        val subsamplingX = (bytes[2].toInt() shr 3) and 0x1
        val subsamplingY = (bytes[2].toInt() shr 2) and 0x1
        // chroma_sample_position 只在 4:2:0 彩色序列里有意义。
        if (monochrome == 1 || subsamplingX != 1 || subsamplingY != 1) return Result.UNDECLARED
        return when (bytes[2].toInt() and 0x3) {
            CSP_VERTICAL -> Result(FableSolExportP010Math.ChromaSiting.TYPE_0, declared = true)
            CSP_COLOCATED -> Result(FableSolExportP010Math.ChromaSiting.TYPE_2, declared = true)
            else -> Result.UNDECLARED
        }
    }

    /** 按 MIME 分派；未知 MIME 按未声明处理。 */
    fun parse(videoMime: String, csd: ByteBuffer?): Result = when {
        videoMime.equals("video/hevc", ignoreCase = true) ||
            videoMime.equals("video/dolby-vision", ignoreCase = true) -> parseHevc(csd)
        videoMime.equals("video/av01", ignoreCase = true) -> parseAv1(csd)
        else -> Result.UNDECLARED
    }

    // ---- HEVC ----

    private fun findHevcSps(csd: ByteBuffer?): ByteArray? {
        val bytes = toArray(csd) ?: return null
        var index = 0
        var spsStart = -1
        while (index + 3 < bytes.size) {
            val isStart3 = bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0 &&
                bytes[index + 2].toInt() == 1
            val isStart4 = index + 4 < bytes.size && bytes[index].toInt() == 0 &&
                bytes[index + 1].toInt() == 0 && bytes[index + 2].toInt() == 0 &&
                bytes[index + 3].toInt() == 1
            if (!isStart3 && !isStart4) {
                index++
                continue
            }
            val payload = index + if (isStart4) 4 else 3
            if (payload >= bytes.size) break
            val nalType = (bytes[payload].toInt() shr 1) and 0x3F
            if (spsStart >= 0) {
                return bytes.copyOfRange(spsStart, index)
            }
            if (nalType == HEVC_NAL_SPS) {
                // 跳过 2 字节 NAL 头，余下即 SPS 的 RBSP 载荷。
                spsStart = payload + 2
            }
            index = payload
        }
        return if (spsStart in 0 until bytes.size) bytes.copyOfRange(spsStart, bytes.size) else null
    }

    /** 去掉 `00 00 03` 里的那个 `03`；不去掉的话后面所有比特都会错位。 */
    private fun removeEmulationPrevention(bytes: ByteArray): ByteArray {
        val output = ByteArray(bytes.size)
        var length = 0
        var zeros = 0
        for (value in bytes) {
            val unsigned = value.toInt() and 0xFF
            if (zeros >= 2 && unsigned == 0x03) {
                zeros = 0
                continue
            }
            zeros = if (unsigned == 0x00) zeros + 1 else 0
            output[length++] = value
        }
        return output.copyOf(length)
    }

    private fun parseHevcSps(reader: BitReader): Result {
        reader.bits(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = reader.bits(3)
        reader.bits(1) // sps_temporal_id_nesting_flag
        skipProfileTierLevel(reader, maxSubLayersMinus1)
        reader.unsigned() // sps_seq_parameter_set_id
        val chromaFormatIdc = reader.unsigned()
        if (chromaFormatIdc == 3) reader.bits(1) // separate_colour_plane_flag
        reader.unsigned() // pic_width_in_luma_samples
        reader.unsigned() // pic_height_in_luma_samples
        if (reader.bits(1) == 1) { // conformance_window_flag
            repeat(4) { reader.unsigned() }
        }
        reader.unsigned() // bit_depth_luma_minus8
        reader.unsigned() // bit_depth_chroma_minus8
        val log2MaxPocLsb = reader.unsigned() + 4
        val subLayerOrderingInfoPresent = reader.bits(1) == 1
        val first = if (subLayerOrderingInfoPresent) 0 else maxSubLayersMinus1
        for (unused in first..maxSubLayersMinus1) {
            reader.unsigned() // sps_max_dec_pic_buffering_minus1
            reader.unsigned() // sps_max_num_reorder_pics
            reader.unsigned() // sps_max_latency_increase_plus1
        }
        repeat(6) { reader.unsigned() } // log2 大小与变换层级共六个 ue(v)
        if (reader.bits(1) == 1) { // scaling_list_enabled_flag
            if (reader.bits(1) == 1) skipScalingListData(reader)
        }
        reader.bits(1) // amp_enabled_flag
        reader.bits(1) // sample_adaptive_offset_enabled_flag
        if (reader.bits(1) == 1) { // pcm_enabled_flag
            reader.bits(4)
            reader.bits(4)
            reader.unsigned()
            reader.unsigned()
            reader.bits(1)
        }
        val numShortTermRefPicSets = reader.unsigned()
        skipShortTermRefPicSets(reader, numShortTermRefPicSets)
        if (reader.bits(1) == 1) { // long_term_ref_pics_present_flag
            val count = reader.unsigned()
            repeat(count) {
                reader.bits(log2MaxPocLsb)
                reader.bits(1)
            }
        }
        reader.bits(1) // sps_temporal_mvp_enabled_flag
        reader.bits(1) // strong_intra_smoothing_enabled_flag
        if (reader.bits(1) != 1) return Result.UNDECLARED // vui_parameters_present_flag
        return parseHevcVui(reader)
    }

    private fun parseHevcVui(reader: BitReader): Result {
        if (reader.bits(1) == 1) { // aspect_ratio_info_present_flag
            if (reader.bits(8) == 255) { // EXTENDED_SAR
                reader.bits(16)
                reader.bits(16)
            }
        }
        if (reader.bits(1) == 1) reader.bits(1) // overscan
        if (reader.bits(1) == 1) { // video_signal_type_present_flag
            reader.bits(3) // video_format
            reader.bits(1) // video_full_range_flag
            if (reader.bits(1) == 1) { // colour_description_present_flag
                reader.bits(8)
                reader.bits(8)
                reader.bits(8)
            }
        }
        if (reader.bits(1) != 1) return Result.UNDECLARED // chroma_loc_info_present_flag
        val topField = reader.unsigned()
        reader.unsigned() // chroma_sample_loc_type_bottom_field
        val siting = FableSolExportP010Math.ChromaSiting.fromTypeCode(topField)
            ?: return Result.UNDECLARED
        return Result(siting, declared = true)
    }

    private fun skipProfileTierLevel(reader: BitReader, maxSubLayersMinus1: Int) {
        // general_profile_space(2) + tier(1) + profile_idc(5) + compatibility(32)
        // + 四个 source flag(4) + 保留与 inbld 共 44 位 = 88 位。
        reader.skip(88)
        reader.bits(8) // general_level_idc
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (index in 0 until maxSubLayersMinus1) {
            profilePresent[index] = reader.bits(1) == 1
            levelPresent[index] = reader.bits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) {
            reader.skip(2 * (8 - maxSubLayersMinus1))
        }
        for (index in 0 until maxSubLayersMinus1) {
            if (profilePresent[index]) reader.skip(88)
            if (levelPresent[index]) reader.bits(8)
        }
    }

    private fun skipScalingListData(reader: BitReader) {
        for (sizeId in 0 until 4) {
            var matrixId = 0
            while (matrixId < 6) {
                if (reader.bits(1) == 0) {
                    reader.unsigned() // scaling_list_pred_matrix_id_delta
                } else {
                    val coefficients = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) reader.signed()
                    repeat(coefficients) { reader.signed() }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    private fun skipShortTermRefPicSets(reader: BitReader, count: Int) {
        val numDeltaPocs = IntArray(count + 1)
        for (index in 0 until count) {
            var interPrediction = false
            if (index != 0) interPrediction = reader.bits(1) == 1
            if (interPrediction) {
                // stRpsIdx 恒小于 num_short_term_ref_pic_sets，所以没有 delta_idx_minus1。
                reader.bits(1) // delta_rps_sign
                reader.unsigned() // abs_delta_rps_minus1
                var deltaPocs = 0
                for (unused in 0..numDeltaPocs[index - 1]) {
                    val used = reader.bits(1) == 1
                    val useDelta = if (used) true else reader.bits(1) == 1
                    if (used || useDelta) deltaPocs++
                }
                numDeltaPocs[index] = deltaPocs
            } else {
                val negative = reader.unsigned()
                val positive = reader.unsigned()
                repeat(negative) {
                    reader.unsigned()
                    reader.bits(1)
                }
                repeat(positive) {
                    reader.unsigned()
                    reader.bits(1)
                }
                numDeltaPocs[index] = negative + positive
            }
        }
    }

    private fun toArray(buffer: ByteBuffer?): ByteArray? {
        val source = buffer ?: return null
        val duplicate = source.duplicate()
        duplicate.rewind()
        if (!duplicate.hasRemaining()) return null
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    /** 越界即抛，由调用方整段判为"未声明"——半截解析出来的值比没有值更危险。 */
    private class BitReader(private val bytes: ByteArray) {

        private var position = 0

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byteIndex = position shr 3
                if (byteIndex >= bytes.size) throw IndexOutOfBoundsException("csd exhausted")
                val bit = (bytes[byteIndex].toInt() shr (7 - (position and 7))) and 1
                value = (value shl 1) or bit
                position++
            }
            return value
        }

        fun skip(count: Int) {
            position += count
            if ((position shr 3) > bytes.size) throw IndexOutOfBoundsException("csd exhausted")
        }

        /** 指数哥伦布无符号；leading zero 有上限，坏数据不会把这里变成死循环。 */
        fun unsigned(): Int {
            var leadingZeros = 0
            while (bits(1) == 0) {
                leadingZeros++
                if (leadingZeros > MAX_LEADING_ZEROS) {
                    throw IllegalStateException("malformed exp-golomb")
                }
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + bits(leadingZeros)
        }

        fun signed(): Int {
            val value = unsigned()
            return if (value % 2 == 0) -(value / 2) else (value + 1) / 2
        }
    }

    private const val HEVC_NAL_SPS = 33
    private const val MAX_LEADING_ZEROS = 32
    private const val CSP_VERTICAL = 1
    private const val CSP_COLOCATED = 2
}
