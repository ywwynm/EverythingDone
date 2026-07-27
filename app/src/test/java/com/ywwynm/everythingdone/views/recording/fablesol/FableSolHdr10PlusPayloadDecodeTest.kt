package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **逐字段解回来核对**，而不是只看固定头和总长度。
 *
 * 这条测试是被一个真实的错误逼出来的：`targeted_system_display_maximum_luminance` 的单位是
 * **0.0001 尼特**（27 位这个宽度正是为此存在——上限 10000 尼特 ÷ 0.0001 = 1e8，恰好 27 位），
 * 而实现按 1 尼特写了进去，于是播放端把 1000 尼特读成 0.1 尼特。位数没错、总长没错、固定头
 * 也没错，原有的两条测试全都通过——只有把每个字段解回来比对数值才拦得住。
 *
 * 各字段单位（依 CTA-861 Annex S / ST 2094-40，与 FFmpeg 的 `AVDynamicHDRPlus` 一致）：
 *
 * | 字段 | 位宽 | 单位 |
 * |---|---|---|
 * | targeted_system_display_maximum_luminance | 27 | 0.0001 尼特（**绝对量**） |
 * | maxscl / average_maxrgb / distribution | 17 | 归一化 [0,1]，步长 0.00001 |
 * | knee_point_x / knee_point_y | 12 | 归一化 [0,1]，步长 1/4095 |
 * | bezier_curve_anchors | 10 | 归一化 [0,1]，步长 1/1023 |
 */
class FableSolHdr10PlusPayloadDecodeTest {

    private val masteringPeak = 1949.0

    @Test
    fun everyFieldDecodesBackToWhatWeMeantToWrite() {
        val stats = FableSolExportHdr10PlusMetadata.placeholder(masteringPeak)
        val curve = FableSolExportHdr10PlusCurve(
            masteringPeakNits = masteringPeak,
            targetNits = 2000.0
        ).next(stats, 1.0 / 120.0)
        val reader = BitReader(
            FableSolExportHdr10PlusMetadata.payload(stats, curve).array()
        )

        assertEquals(0xB5, reader.read(8))
        assertEquals(0x003C, reader.read(16))
        assertEquals(0x0001, reader.read(16))
        assertEquals(4, reader.read(8))                    // application_identifier
        assertEquals(1, reader.read(8))                    // application_version
        assertEquals(1, reader.read(2))                    // num_windows

        // **这一格就是踩过的坑**：2000 尼特必须写成 20 000 000，不是 2000。
        assertEquals(20_000_000, reader.read(27))
        assertEquals(0, reader.read(1))                    // targeted_..._actual_peak_flag

        // maxscl 是归一化值：1949 尼特 ÷ 10000 ÷ 0.00001 = 19490。
        repeat(3) { assertEquals(19_490, reader.read(17)) }
        // average_maxrgb 用占位统计里的 203 尼特 → 2030。
        assertEquals(2_030, reader.read(17))

        assertEquals(9, reader.read(4))                    // num_distribution_maxrgb_percentiles
        val expectedPercentages = intArrayOf(1, 5, 10, 25, 50, 75, 90, 95, 99)
        for (percentage in expectedPercentages) {
            assertEquals(percentage, reader.read(7))
            assertEquals(2_030, reader.read(17))
        }

        assertEquals(0, reader.read(10))                   // fraction_bright_pixels
        assertEquals(0, reader.read(1))                    // mastering_display_actual_peak_flag
        assertEquals(1, reader.read(1))                    // tone_mapping_flag

        val kneeX = reader.read(12)
        val kneeY = reader.read(12)
        assertEquals(Math.round(curve.kneeX * 4095).toInt(), kneeX)
        assertEquals(Math.round(curve.kneeY * 4095).toInt(), kneeY)
        assertTrue("膝点必须落在开区间内，贴边会让曲线退化", kneeX in 1..4094 && kneeY in 1..4094)

        assertEquals(9, reader.read(4))                    // num_bezier_curve_anchors
        var previous = 0
        for (anchor in curve.anchors) {
            val value = reader.read(10)
            assertEquals(Math.round(anchor * 1023).toInt(), value)
            assertTrue("控制点必须单调不减，否则曲线会回头", value >= previous)
            previous = value
        }
        assertTrue("最后一个控制点不该已经贴到 1023——那说明肩部被夹死了", previous < 1023)

        assertEquals(0, reader.read(1))                    // color_saturation_mapping_flag
        assertTrue("除末尾补位外不应还有内容", reader.remainingBits() < 8)
    }

    /** 与写入端对称的逐位读取器。 */
    private class BitReader(private val bytes: ByteArray) {

        private var position = 0

        fun read(bitCount: Int): Int {
            var value = 0
            repeat(bitCount) {
                val byte = bytes[position ushr 3].toInt() and 0xFF
                val bit = (byte shr (7 - (position and 7))) and 1
                value = (value shl 1) or bit
                position++
            }
            return value
        }

        fun remainingBits(): Int = bytes.size * 8 - position
    }
}
