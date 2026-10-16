package com.ywwynm.everythingdone.hdr

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HdrViewerSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun marker(code: Int, data: ByteArray) = byteArrayOf(-1, code.toByte(),
        ((data.size + 2) shr 8).toByte(), (data.size + 2).toByte()) + data
    private val start = byteArrayOf(-1, -40)
    private val scan = marker(0xda, byteArrayOf(1, 1, 0, 0, 63, 0))
    private fun inspect(bytes: ByteArray) = HdrViewerSource.needsHdrInspection(temporary.newFile().apply { writeBytes(bytes) })

    @Test fun ordinaryJpegMetadataDoesNotRequireHdrDecode() {
        val metadata = listOf(0xe0 to "JFIF\u0000", 0xe1 to "Exif\u0000\u0000", 0xe2 to "ICC_PROFILE\u0000", 0xee to "Adobe")
            .fold(start) { bytes, (code, name) -> bytes + marker(code, name.toByteArray()) }
        assertFalse(inspect(metadata + scan + byteArrayOf(50, 60, -1, -39)))
    }

    @Test fun hdrContainersAndUnknownExtensionsKeepDecoderFallback() {
        for ((code, name) in listOf(0xe2 to "MPF\u0000", 0xe2 to "urn:iso:std:iso:ts:21496:-1",
            0xe1 to "http://ns.adobe.com/xap/1.0/", 0xe5 to "ITUT35", 0xe8 to "HDR Vivid", 0xeb to "unknown")) {
            assertTrue(name, inspect(start + marker(code, name.toByteArray()) + scan))
        }
    }

    @Test fun truncatedMalformedAndOtherFormatsRemainUnknown() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, 1, 2, 3), start,
            start + byteArrayOf(-1, -31, 0, 1), start + byteArrayOf(-1, -31, 0, 100, 1))) {
            assertTrue(inspect(bytes))
        }
    }

    @Test fun onlyHeaderIsReadEvenWhenTrailerContainsHdrSignatures() {
        val f = temporary.newFile()
        f.outputStream().use { out ->
            out.write(start + scan)
            repeat(64) { out.write(ByteArray(1024 * 1024)) }
            out.write("MPF\u0000ITUT35".toByteArray())
        }
        assertFalse(HdrViewerSource.needsHdrInspection(f))
    }

    @Test fun oversizedHeadersPreserveFallbackInsteadOfBlockingOrGuessingSdr() {
        val segment = marker(0xe1, "Exif\u0000\u0000".toByteArray() + ByteArray(60_000))
        val bytes = (0..35).fold(start) { b, _ -> b + segment } + scan
        assertTrue(inspect(bytes))
    }

    @Test fun localHuaweiOriginalsKeepHdrInspection() {
        val dir = System.getenv("HDR_APP5_ORIGINALS_DIR")
        assumeTrue(dir != null)
        for (name in listOf("codex-clipboard-05b8ecf4-9078-46fc-8721-9ee46baa1772.jpg",
            "codex-clipboard-2cbaeba9-3073-4014-9cf5-10b24f409e10.jpg",
            "codex-clipboard-00bf1bb5-33c0-4082-88ef-b4239b29d97d.jpg")) {
            assertTrue(name, HdrViewerSource.needsHdrInspection(File(dir, name)))
        }
    }
}
