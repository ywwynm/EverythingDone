package com.ywwynm.everythingdone.hdr

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HdrSourceMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private val xml = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:g="http://ns.adobe.com/hdr-gain-map/1.0/" xmlns:exif="http://ns.adobe.com/exif/1.0/">
        <rdf:Description g:Version="1.0" g:BaseRenditionIsHDR="False" exif:GPSLatitude="PRIVATE_LOCATION">
          <g:GainMapMax><rdf:Seq><rdf:li>1</rdf:li><rdf:li>2</rdf:li><rdf:li>3</rdf:li></rdf:Seq></g:GainMapMax>
        </rdf:Description></rdf:RDF>""".trimIndent().toByteArray()

    private fun marker(code: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xff.toByte(), code.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
    private fun jpeg(vararg segments: ByteArray) = byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
        segments.fold(ByteArray(0)) { a, b -> a + b } + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    private fun inspect(bytes: ByteArray): HdrSourceMetadata.Report {
        val file = temporary.newFile(); file.writeBytes(bytes)
        return HdrSourceMetadata.inspect(file)
    }

    @Test fun multiMegabyteScanDoesNotQueryFileSystemForEveryEntropyByte() {
        val source = temporary.newFile()
        source.writeBytes(jpeg(marker(0xda, byteArrayOf()), ByteArray(7_000_000) { 42 }) +
            jpeg(marker(0xe1, xmpHeader + xml)))
        val expectedSize = source.length()
        val counted = object : File(source.path) {
            var sizeQueries = 0
            override fun length(): Long { sizeQueries++; return expectedSize }
        }
        val report = HdrSourceMetadata.inspect(counted)
        assertTrue(report.details.contains("scanComplete=true; jpegCount=2"))
        assertTrue(report.summary.contains("Ultra HDR XMP"))
        assertTrue("File.length() called ${counted.sizeQueries} times", counted.sizeQueries <= 4)
    }

    @Test fun detectsGainMetadataInSecondaryJpegWithoutExportingExifOrGps() {
        val primary = jpeg(marker(0xe1, "Exif\u0000\u0000PRIVATE_EXIF_GPS".toByteArray()),
            marker(0xda, byteArrayOf()), byteArrayOf(12, 0xff.toByte(), 0, 9, 0xff.toByte(), 0xd0.toByte(), 1))
        val report = inspect(primary + jpeg(marker(0xe1, xmpHeader + xml)))
        assertTrue(report.summary.contains("Ultra HDR XMP"))
        assertTrue(report.details.contains("xmp.hdrgm.Version=1.0"))
        assertTrue(report.details.contains("xmp.hdrgm.GainMapMax=1 2 3"))
        assertTrue(report.details.contains("scanComplete=true; jpegCount=2"))
        assertFalse(report.details.contains("PRIVATE_"))
        assertFalse(report.details.contains("GPSLatitude"))
    }

    @Test fun detectsIsoAndVividHeadersAsCluesWithoutAssumingValidHdr() {
        val iso = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray() + byteArrayOf(0, 0, 0, 1)
        val vivid = "ITUT35".toByteArray() + byteArrayOf(26, 0, 4, 0, 1, 2, 1, 0, 1, 1)
        val report = inspect(jpeg(marker(0xe2, iso), marker(0xe8, vivid), marker(0xe5, "PRIVATE_PAYLOAD".toByteArray())))
        assertTrue(report.summary.contains("ISO 21496-1"))
        assertTrue(report.summary.contains("Vivid APP8 线索"))
        assertTrue(report.details.contains("minimumVersion=0; writerVersion=1"))
        assertTrue(report.details.contains("frames=2; fileType=1"))
        assertTrue(report.details.contains("不能仅凭 APP5 判为 HDR"))
        assertFalse(report.details.contains("PRIVATE_PAYLOAD"))
    }

    @Test fun distinguishesHuaweiApp5SignatureFromUnknownApp5Payload() {
        val huawei = "ITUT35".toByteArray() + byteArrayOf(0x26, 4, 0, 6, 0, 2, 2, 0, 0, 0)
        val report = inspect(jpeg(marker(0xe5, huawei)))
        assertTrue(report.summary.contains("华为 APP5／CUVA 线索"))
        assertTrue(report.details.contains("reconstructionRequiresAdapter=true"))
        assertFalse(report.summary.contains("Vivid APP8"))
    }

    @Test fun extendedXmpReassemblesOutOfOrderChunksAndReportsMissingChunks() {
        fun chunk(offset: Int, bytes: ByteArray): ByteArray = marker(0xe1,
            "http://ns.adobe.com/xmp/extension/\u0000".toByteArray() + "0123456789abcdef0123456789abcdef".toByteArray() +
                ByteBuffer.allocate(8).putInt(xml.size).putInt(offset).array() + bytes)
        val split = xml.size / 2
        val first = chunk(0, xml.copyOfRange(0, split))
        val second = chunk(split, xml.copyOfRange(split, xml.size))
        val report = inspect(jpeg(second, first))
        assertTrue(report.details.contains("extendedXmpComplete=true"))
        assertTrue(report.summary.contains("Ultra HDR XMP"))
        val missing = inspect(jpeg(second))
        assertTrue(missing.details.contains("extendedXmpComplete=false"))
        assertTrue(missing.summary.startsWith("检查不完整"))
    }

    @Test fun oversizedExtendedXmpIsRejectedWithoutAllocatingFromUntrustedLengths() {
        val header = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray() + "0123456789abcdef0123456789abcdef".toByteArray()
        val huge = header + ByteBuffer.allocate(8).putInt(Int.MAX_VALUE).putInt(0).array() + byteArrayOf(1)
        val report = inspect(jpeg(marker(0xe1, huge)))
        assertTrue(report.summary.startsWith("检查不完整"))
        assertTrue(report.details.contains("inspectionStopped"))
    }

    @Test fun parsesBothMpfByteOrdersAndValidatesOffsetsAgainstRealJpegStarts() {
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val secondary = jpeg(marker(0xe1, xmpHeader + xml))
            val payload = ByteBuffer.allocate(74).order(order)
            payload.put("MPF\u0000".toByteArray()).put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
            payload.putShort(42).putInt(8).putShort(2)
            payload.putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(2)
            payload.putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(38)
            payload.putInt(0)
            payload.putInt(0).putInt(82).putInt(0).putShort(0).putShort(0)
            payload.putInt(0).putInt(secondary.size).putInt(72).putShort(0).putShort(0)
            val report = inspect(jpeg(marker(0xe2, payload.array())) + secondary)
            assertTrue(report.details.contains("mpfImageCount=2"))
            assertTrue(report.details.contains("mpfEntry[1]: offset=82; bytes=${secondary.size}; inFile=true; jpegAtOffset=true"))
        }
    }

    @Test fun plainJpegAndUnsupportedContainersDoNotBecomeHdr() {
        val plain = inspect(jpeg(marker(0xe1, "Exif\u0000\u0000hdrgm:Version=1.0".toByteArray())))
        assertEquals("未检出已支持的 HDR 标记", plain.summary)
        val other = inspect("not_jpeg PRIVATE_BYTES".toByteArray())
        assertTrue(other.summary.startsWith("检查不完整"))
        assertFalse(other.details.contains("PRIVATE_BYTES"))
    }

    @Test fun malformedSegmentsAndTruncatedJpegRemainExplicitlyIncomplete() {
        val bad = inspect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 99, 1))
        assertTrue(bad.details.contains("scanComplete=false"))
        val truncated = inspect(jpeg(marker(0xe1, xmpHeader + xml)).dropLast(2).toByteArray())
        assertTrue(truncated.details.contains("scanComplete=false"))
    }

    @Test fun xmpCannotReadExternalEntitiesOrExportUnrelatedText() {
        val malicious = """<!DOCTYPE x [<!ENTITY xx SYSTEM "file:///PRIVATE_FILE">]><x>&xx;</x>""".toByteArray()
        val report = inspect(jpeg(marker(0xe1, xmpHeader + malicious)))
        assertTrue(report.details.contains("xmpParse=blocked_DTD_or_entity"))
        assertFalse(report.details.contains("PRIVATE_FILE"))
    }

    @Test fun fullResolutionBudgetAllowsSuppliedPhotoOnlyWhenMemoryIsSufficient() {
        assertTrue(HdrFullSizeBudget.decision(4368, 5824, 512L * 1024 * 1024).first)
        assertFalse(HdrFullSizeBudget.decision(4368, 5824, 128L * 1024 * 1024).first)
        assertFalse(HdrFullSizeBudget.decision(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE).first)
        assertFalse(HdrFullSizeBudget.decision(-1, 100, Long.MAX_VALUE).first)
    }
}
