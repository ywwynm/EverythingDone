package com.ywwynm.everythingdone.hdr

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class HdrApp5Test {
    @get:Rule val temporary = TemporaryFolder()
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val primary = hex("49545554333526040006000202000000006b26a20066a622")
    private val secondary = hex("4954555433352600040006020200010000200000080000000600000000b70000009a4261ebcd000000c8000000013fb95be0")
    private fun marker(code: Int, p: ByteArray) = byteArrayOf(-1, code.toByte(), ((p.size + 2) shr 8).toByte(), (p.size + 2).toByte()) + p
    private fun jpeg(width: Int, height: Int, app: ByteArray) = byteArrayOf(-1, -40) + marker(0xe5, app) +
        marker(0xc0, byteArrayOf(8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 1, 1, 0x11, 0)) +
        marker(0xda, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(12, -1, 0, 42, -1, -39)
    private fun sample(aux: ByteArray = secondary, offsetDelta: Int = 0): java.io.File {
        val p = primary.copyOf()
        val first = jpeg(4368, 5824, p)
        ByteBuffer.wrap(p).putInt(16, first.size + offsetDelta).putInt(20, first.size - p.size - 6 + offsetDelta)
        return temporary.newFile().apply { writeBytes(jpeg(4368, 5824, p) + jpeg(1536, 2048, aux) + "PRIVATE_TRAILER".toByteArray()) }
    }

    @Test fun acceptsKnownApp5LayoutAndExcludesVendorTailAndColorMetadata() {
        val parsed = requireNotNull(HdrApp5.read(sample()))
        assertEquals(1536, parsed.width); assertEquals(2048, parsed.height)
        assertEquals(4368, parsed.baseWidth); assertEquals(5824, parsed.baseHeight)
        assertEquals(183L, parsed.words[2]); assertEquals(0x3fb95be0L, parsed.words[7])
        assertEquals(listOf(255, 217), parsed.jpeg.takeLast(2).map { it.toInt() and 255 })
        assertFalse(String(parsed.jpeg, Charsets.ISO_8859_1).contains("ITUT35"))
        assertFalse(parsed.description.contains("PRIVATE_TRAILER"))
        assertTrue(parsed.description.contains("metadataSemantics=unverified"))
    }

    @Test fun metadataEditCanMoveAuxiliaryWithoutBreakingRelativeOffset() {
        val f = sample(); val bytes = f.readBytes()
        val extra = marker(0xe1, "PRIVATE_EXIF".toByteArray())
        f.writeBytes(bytes.copyOfRange(0, 30) + extra + bytes.copyOfRange(30, bytes.size))
        assertNotNull(HdrApp5.read(f))
    }

    @Test fun rejectsOffsetsDimensionsUnknownVersionsAndTruncatedImages() {
        assertThrows(IllegalArgumentException::class.java) { HdrApp5.read(sample(offsetDelta = 2)) }
        val wrongSize = secondary.copyOf().apply { this[25] = 1 }
        assertThrows(IllegalArgumentException::class.java) { HdrApp5.read(sample(wrongSize)) }
        val wrongVersion = secondary.copyOf().apply { this[10] = 99 }
        assertThrows(IllegalArgumentException::class.java) { HdrApp5.read(sample(wrongVersion)) }
        val f = sample(); f.writeBytes(f.readBytes().dropLast(16).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { HdrApp5.read(f) }
    }

    @Test fun ordinaryAndIsoJpegsDoNotBecomeApp5Hdr() {
        val f = temporary.newFile().apply { writeBytes(jpeg(4, 3, "ordinary".toByteArray())) }
        assertNull(HdrApp5.read(f))
        f.writeBytes(byteArrayOf(-1, -40) + marker(0xe2, "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray()) + byteArrayOf(-1, -39))
        assertNull(HdrApp5.read(f))
    }

    @Test fun absoluteOffsetVariantAndMetadataStrippingKeepEncodedPixels() {
        val f = sample(); val bytes = f.readBytes().apply { this[18] = 1 }
        // fileType=1 使用绝对偏移，即使相对偏移字段不可用也可定位。
        ByteBuffer.wrap(bytes).putInt(26, -1)
        f.writeBytes(bytes)
        assertEquals(1536, requireNotNull(HdrApp5.read(f)).width)
    }

    @Test fun impossibleUnsignedOffsetAndDuplicateRecordsAreRejected() {
        val f = sample(); val bytes = f.readBytes()
        ByteBuffer.wrap(bytes).putInt(26, -1)
        f.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { HdrApp5.read(f) }
        val valid = sample().readBytes()
        f.writeBytes(valid.copyOfRange(0, 30) + marker(0xe5, primary) + valid.copyOfRange(30, valid.size))
        assertThrows(IllegalArgumentException::class.java) { HdrApp5.read(f) }
    }

    @Test fun orientationMappingMatchesExifCornersWithoutChangingGainValues() {
        val expected = listOf(0, 2, 5, 3, 0, 3, 5, 2)
        for (orientation in 1..8) assertEquals(expected[orientation - 1], HdrApp5.pixelIndex(0, 0, 3, 2, orientation))
        assertEquals(0, HdrApp5.pixelIndex(0, 0, 3, 2, 0))
        for (orientation in 1..8) {
            val width = if (orientation >= 5) 2 else 3
            val height = if (orientation >= 5) 3 else 2
            val indices = (0 until height).flatMap { y -> (0 until width).map { x -> HdrApp5.pixelIndex(x, y, 3, 2, orientation) } }
            assertEquals((0..5).toList(), indices.sorted())
        }
    }

    @Test fun optionalLocalOriginalsReplay() {
        val paths = listOf("05b8ecf4-9078-46fc-8721-9ee46baa1772" to 305523,
            "2cbaeba9-3073-4014-9cf5-10b24f409e10" to 510428)
        org.junit.Assume.assumeTrue(System.getenv("HDR_APP5_ORIGINALS_DIR") != null)
        val hashes = listOf("7bf7d84b2d6db28610e0f749a5f7722bab031d85f596389176bbd617636ad466",
            "5ebc2d2337e2018a978bbd9f291e396fb4fddca0165bc9af0d29708cd1311195")
        for ((index, item) in paths.withIndex()) {
            val (name, bytes) = item
            val p = requireNotNull(HdrApp5.read(java.io.File(System.getenv("HDR_APP5_ORIGINALS_DIR"), "codex-clipboard-$name.jpg")))
            assertEquals(bytes, p.encodedBytes); assertEquals(1536, p.width); assertEquals(2048, p.height)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(p.jpeg).joinToString("") { "%02x".format(it) }
            assertEquals(hashes[index], digest)
        }
        assertNull(HdrApp5.read(java.io.File(System.getenv("HDR_APP5_ORIGINALS_DIR"),
            "codex-clipboard-00bf1bb5-33c0-4082-88ef-b4239b29d97d.jpg")))
    }
}
