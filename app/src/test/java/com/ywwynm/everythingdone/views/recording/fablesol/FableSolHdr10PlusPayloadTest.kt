package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ST 2094-40 的载荷是**逐位打包**的，不是字节对齐结构——写错一位，后面所有字段全部错位，
 * 而错位的后果只会表现为"编码器不认"或"播放端色调映射离谱"，都极难反查。所以这里把
 * 固定头与总位数钉死。
 */
class FableSolHdr10PlusPayloadTest {

    /** 前 7 字节是规范写死的常量，正好落在字节边界上，可以逐字节核对。 */
    @Test
    fun fixedHeaderMatchesTheSpec() {
        val bytes = FableSolExportHdr10PlusMetadata.payload(stats(), curve()).array()
        assertEquals(0xB5, bytes[0].toInt() and 0xFF)   // itu_t_t35_country_code
        assertEquals(0x00, bytes[1].toInt() and 0xFF)   // terminal_provider_code 高字节
        assertEquals(0x3C, bytes[2].toInt() and 0xFF)   // terminal_provider_code 低字节
        assertEquals(0x00, bytes[3].toInt() and 0xFF)   // oriented_code 高字节
        assertEquals(0x01, bytes[4].toInt() and 0xFF)   // oriented_code 低字节
        assertEquals(0x04, bytes[5].toInt() and 0xFF)   // application_identifier，固定 4
        assertEquals(0x01, bytes[6].toInt() and 0xFF)   // application_version
    }

    /**
     * 单窗口、9 个百分位、**带**色调映射曲线时的总位数：
     * 8+16+16+8+8 = 56，+2（num_windows）+27+1 = 86，+3×17（maxscl）+17（average）= 154，
     * +4（百分位个数）+9×(7+17) = 374，+10（fraction_bright_pixels）+1 = 385，
     * +1（tone_mapping_flag）+12+12（膝点）+4（锚点个数）+9×10（锚点）= 504，
     * +1（color_saturation_mapping_flag）= 505 位 → 64 字节。
     */
    @Test
    fun payloadLengthMatchesTheBitBudget() {
        assertEquals(64, FableSolExportHdr10PlusMetadata.payload(stats(), curve()).array().size)
    }

    /** 不带曲线时回到 387 位 → 49 字节，说明曲线那一段的位数正好是 118。 */
    @Test
    fun payloadWithoutCurveKeepsTheOldLength() {
        assertEquals(49, FableSolExportHdr10PlusMetadata.payload(stats(), null).array().size)
    }

    private fun stats() = FableSolExportHdr10PlusMetadata.placeholder(1949.0)

    private fun curve() = FableSolExportHdr10PlusCurve(1949.0).next(stats(), 1.0 / 120.0)
}
