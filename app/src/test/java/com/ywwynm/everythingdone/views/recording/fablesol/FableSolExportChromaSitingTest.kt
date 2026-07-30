package com.ywwynm.everythingdone.views.recording.fablesol

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色度位置解析（D154、D170）的门禁。
 *
 * 这段解析器是典型的"错了也不报错"的代码：SPS 在 VUI 之前有 profile_tier_level、scaling
 * list、short-term ref pic set 这类可变长结构，少走一个字段后面读出来的就是别的东西，而
 * 结果只是相位差半个像素——画面上表现为高饱和细边缘的色边，很难被归因到这里。因此测试
 * 自己按 H.265 语法**写**一份 SPS 再读回来。
 */
class FableSolExportChromaSitingTest {

    /** 编码器显式声明 Type 2 时必须原样读出，并标记为"已声明"。 */
    @Test
    fun declaredHevcChromaLocationIsParsed() {
        for (type in 0..3) {
            val result = FableSolExportChromaSiting.parseHevc(
                ByteBuffer.wrap(hevcCsd(chromaLocPresent = true, chromaLocType = type))
            )
            assertTrue("Type $type 应被识别为已声明", result.declared)
            assertEquals(
                FableSolExportP010Math.ChromaSiting.fromTypeCode(type),
                result.siting
            )
        }
    }

    /**
     * **码流没声明就按 Type 0 兼容语义**，而不是继续生成 Type 1 数据（D154 第 3 条）。
     *
     * 两种情形都要落到这里：VUI 里没有 chroma_loc_info，以及整个 VUI 都不存在。
     */
    @Test
    fun undeclaredHevcFallsBackToTheCompatibleType0() {
        val withoutChromaLoc = FableSolExportChromaSiting.parseHevc(
            ByteBuffer.wrap(hevcCsd(chromaLocPresent = false, chromaLocType = 0))
        )
        assertFalse(withoutChromaLoc.declared)
        assertEquals(
            FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT,
            withoutChromaLoc.siting
        )

        val withoutVui = FableSolExportChromaSiting.parseHevc(
            ByteBuffer.wrap(hevcCsd(chromaLocPresent = false, chromaLocType = 0, vui = false))
        )
        assertFalse(withoutVui.declared)
        assertEquals(FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT, withoutVui.siting)
    }

    /** 损坏、截断或压根不是 HEVC 的 csd 一律判为未声明，不得抛异常影响导出。 */
    @Test
    fun malformedInputNeverThrowsAndNeverGuesses() {
        val complete = hevcCsd(chromaLocPresent = true, chromaLocType = 2)
        for (length in listOf(0, 1, 5, 9, complete.size / 2)) {
            val result = FableSolExportChromaSiting.parseHevc(
                ByteBuffer.wrap(complete.copyOf(length))
            )
            assertFalse("截断到 $length 字节仍不得声称已声明", result.declared)
        }
        assertFalse(FableSolExportChromaSiting.parseHevc(null).declared)
        assertFalse(
            FableSolExportChromaSiting.parseHevc(ByteBuffer.wrap(ByteArray(64) { 0x5A })).declared
        )
    }

    /**
     * AV1 的 `chroma_sample_position` 直接躺在 av1C 记录的第 3 个字节里（D170）：
     * `CSP_COLOCATED` = Type 2、`CSP_VERTICAL` = Type 0、`CSP_UNKNOWN` 按兼容语义。
     */
    @Test
    fun av1ChromaSamplePositionMapsThroughH273() {
        val colocated = FableSolExportChromaSiting.parseAv1(ByteBuffer.wrap(av1Csd(2)))
        assertTrue(colocated.declared)
        assertEquals(FableSolExportP010Math.ChromaSiting.TYPE_2, colocated.siting)

        val vertical = FableSolExportChromaSiting.parseAv1(ByteBuffer.wrap(av1Csd(1)))
        assertTrue(vertical.declared)
        assertEquals(FableSolExportP010Math.ChromaSiting.TYPE_0, vertical.siting)

        val unknown = FableSolExportChromaSiting.parseAv1(ByteBuffer.wrap(av1Csd(0)))
        assertFalse(unknown.declared)
        assertEquals(FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT, unknown.siting)

        // 不是 av1C（marker/version 不对）或单色序列时不猜。
        val notAv1c = av1Csd(2).also { it[0] = 0x00 }
        assertFalse(FableSolExportChromaSiting.parseAv1(ByteBuffer.wrap(notAv1c)).declared)
        val monochrome = av1Csd(2).also { it[2] = (it[2].toInt() or 0x10).toByte() }
        assertFalse(FableSolExportChromaSiting.parseAv1(ByteBuffer.wrap(monochrome)).declared)
    }

    /** 按 MIME 分派：杜比视界的基层就是 HEVC，走同一条解析。 */
    @Test
    fun mimeDispatchCoversHevcDolbyVisionAndAv1() {
        val hevc = ByteBuffer.wrap(hevcCsd(chromaLocPresent = true, chromaLocType = 2))
        assertTrue(FableSolExportChromaSiting.parse("video/hevc", hevc).declared)
        assertTrue(FableSolExportChromaSiting.parse("video/dolby-vision", hevc).declared)
        assertTrue(
            FableSolExportChromaSiting.parse("video/av01", ByteBuffer.wrap(av1Csd(2))).declared
        )
        assertFalse(FableSolExportChromaSiting.parse("video/avc", hevc).declared)
    }

    // ---- 测试用的最小 HEVC SPS ----

    private fun hevcCsd(
        chromaLocPresent: Boolean,
        chromaLocType: Int,
        vui: Boolean = true
    ): ByteArray {
        val writer = BitWriter()
        writer.bits(0, 4) // sps_video_parameter_set_id
        writer.bits(0, 3) // sps_max_sub_layers_minus1
        writer.bits(1, 1) // sps_temporal_id_nesting_flag
        repeat(88) { writer.bits(0, 1) } // profile_tier_level 的 profile 部分
        writer.bits(120, 8) // general_level_idc
        writer.unsigned(0) // sps_seq_parameter_set_id
        writer.unsigned(1) // chroma_format_idc = 4:2:0
        writer.unsigned(1024) // pic_width_in_luma_samples
        writer.unsigned(1472) // pic_height_in_luma_samples
        writer.bits(0, 1) // conformance_window_flag
        writer.unsigned(2) // bit_depth_luma_minus8 = 10 bit
        writer.unsigned(2) // bit_depth_chroma_minus8
        writer.unsigned(4) // log2_max_pic_order_cnt_lsb_minus4
        writer.bits(1, 1) // sps_sub_layer_ordering_info_present_flag
        repeat(3) { writer.unsigned(0) }
        writer.unsigned(0) // log2_min_luma_coding_block_size_minus3
        writer.unsigned(3) // log2_diff_max_min_luma_coding_block_size
        writer.unsigned(0) // log2_min_luma_transform_block_size_minus2
        writer.unsigned(3) // log2_diff_max_min_luma_transform_block_size
        writer.unsigned(0) // max_transform_hierarchy_depth_inter
        writer.unsigned(0) // max_transform_hierarchy_depth_intra
        writer.bits(0, 1) // scaling_list_enabled_flag
        writer.bits(1, 1) // amp_enabled_flag
        writer.bits(1, 1) // sample_adaptive_offset_enabled_flag
        writer.bits(0, 1) // pcm_enabled_flag
        writer.unsigned(0) // num_short_term_ref_pic_sets
        writer.bits(0, 1) // long_term_ref_pics_present_flag
        writer.bits(1, 1) // sps_temporal_mvp_enabled_flag
        writer.bits(1, 1) // strong_intra_smoothing_enabled_flag
        writer.bits(if (vui) 1 else 0, 1) // vui_parameters_present_flag
        if (vui) {
            writer.bits(0, 1) // aspect_ratio_info_present_flag
            writer.bits(0, 1) // overscan_info_present_flag
            writer.bits(1, 1) // video_signal_type_present_flag
            writer.bits(5, 3) // video_format = unspecified
            writer.bits(0, 1) // video_full_range_flag = limited
            writer.bits(1, 1) // colour_description_present_flag
            writer.bits(9, 8) // colour_primaries = BT.2020
            writer.bits(16, 8) // transfer_characteristics = PQ
            writer.bits(9, 8) // matrix_coeffs = BT.2020 NCL
            writer.bits(if (chromaLocPresent) 1 else 0, 1)
            if (chromaLocPresent) {
                writer.unsigned(chromaLocType)
                writer.unsigned(chromaLocType)
            }
        }
        writer.bits(1, 1) // rbsp_stop_one_bit
        val payload = escape(writer.toByteArray())
        // 起始码 + NAL 头（nal_unit_type = 33 即 SPS）。
        val header = byteArrayOf(0, 0, 0, 1, 0x42, 0x01)
        return header + payload
    }

    /** RBSP → 字节流：连续两个 0x00 后遇到 <= 0x03 的字节要插入防竞争字节。 */
    private fun escape(rbsp: ByteArray): ByteArray {
        val output = ArrayList<Byte>(rbsp.size + 16)
        var zeros = 0
        for (value in rbsp) {
            val unsigned = value.toInt() and 0xFF
            if (zeros >= 2 && unsigned <= 0x03) {
                output.add(0x03)
                zeros = 0
            }
            output.add(value)
            zeros = if (unsigned == 0x00) zeros + 1 else 0
        }
        return output.toByteArray()
    }

    private fun av1Csd(chromaSamplePosition: Int): ByteArray = byteArrayOf(
        0x81.toByte(), // marker(1) = 1, version(7) = 1
        0x0D, // seq_profile(3) = 0, seq_level_idx_0(5) = 13
        // tier(1)=0, high_bitdepth(1)=1, twelve_bit(1)=0, monochrome(1)=0,
        // subsampling_x(1)=1, subsampling_y(1)=1, chroma_sample_position(2)
        (0x4C or chromaSamplePosition).toByte(),
        0x00
    )

    private class BitWriter {

        private val bytes = ArrayList<Byte>(64)
        private var current = 0
        private var filled = 0

        fun bits(value: Int, count: Int) {
            for (index in count - 1 downTo 0) {
                val bit = (value shr index) and 1
                current = (current shl 1) or bit
                filled++
                if (filled == 8) {
                    bytes.add(current.toByte())
                    current = 0
                    filled = 0
                }
            }
        }

        fun unsigned(value: Int) {
            var leadingZeros = 0
            var range = value + 1
            while (range > 1) {
                range = range shr 1
                leadingZeros++
            }
            bits(0, leadingZeros)
            bits(value + 1, leadingZeros + 1)
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
}
