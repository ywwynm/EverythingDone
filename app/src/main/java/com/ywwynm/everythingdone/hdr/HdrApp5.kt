package com.ywwynm.everythingdone.hdr

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** 仅解析已核对的旧华为 APP5 双 JPEG；参数字保留原值，不猜测其增益语义。 */
internal object HdrApp5 {
    private const val HEADER_LIMIT = 2 * 1024 * 1024
    private const val AUX_LIMIT = 16 * 1024 * 1024
    private val signature = "ITUT35".toByteArray(Charsets.US_ASCII)
    data class Payload(val jpeg: ByteArray, val width: Int, val height: Int,
        val baseWidth: Int, val baseHeight: Int, val encodedBytes: Int,
        val words: List<Long>, val description: String)
    private data class Segment(val marker: Int, val start: Int, val end: Int, val data: ByteArray)
    private data class Jpeg(val width: Int, val height: Int, val segments: List<Segment>, val end: Int)

    fun read(file: File): Payload? = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        require(length <= 80L * 1024 * 1024) { "app5_source_too_large" }
        val header = ByteArray(minOf(length, HEADER_LIMIT.toLong()).toInt())
        input.readFully(header)
        if (header.size < 2 || header.u16(0) != 0xffd8) return null
        val base = parse(header, headerOnly = true)
        val records = base.segments.filter { it.marker == 0xe5 && it.data.hasSignature() }
        if (records.isEmpty()) return null
        require(records.size == 1) { "app5_ambiguous_primary" }
        val p = records.single().data
        require(p.size == 24 && validHeader(p, primary = true)) { "app5_unsupported_primary_layout" }
        // 原生解析器的 allAppSize 还包括主 JPEG 的两个 SOI 字节。
        val appBytes = 2L + base.segments.filter { it.marker in 0xe0..0xef }.sumOf { (it.end - it.start).toLong() }
        val kind = p[12].toInt() and 255
        val offset = if (kind == 1) p.u32(16) else p.u32(20) + appBytes
        require(offset >= base.end && offset < length - 4 && length - offset <= AUX_LIMIT) { "app5_auxiliary_bounds" }
        input.seek(offset - 2)
        require(input.readUnsignedShort() == 0xffd9) { "app5_primary_end_mismatch" }
        val bytes = ByteArray((length - offset).toInt())
        input.seek(offset); input.readFully(bytes)
        val aux = parse(bytes, headerOnly = false)
        val auxRecords = aux.segments.filter { it.marker == 0xe5 && it.data.hasSignature() }
        require(auxRecords.size == 1) { "app5_missing_or_ambiguous_auxiliary_metadata" }
        val a = auxRecords.single().data
        require(a.size == 50 && validHeader(a, primary = false) && a.u16(16) == 32) { "app5_unsupported_auxiliary_layout" }
        val words = (18..46 step 4).map { a.u32(it) }
        require(words[0] == aux.height.toLong() && words[1] == aux.width.toLong()) { "app5_metadata_dimensions_mismatch" }
        require(base.width > 0 && base.height > 0 && aux.width > 0 && aux.height > 0 &&
            aux.width.toLong() * aux.height <= 16_000_000 && compatible(base.width, base.height, aux.width, aux.height)) {
            "app5_incompatible_geometry"
        }
        // ICC / EXIF 是颜色或拍摄信息，不能作用于作为数值使用的灰度增强图。
        // 保留 JPEG 本身及 APP14 编码信息；不落盘、不改写原图。
        val removed = aux.segments.filter { it.marker in 0xe0..0xef && it.marker != 0xee }
        val stripped = ByteArrayOutputStream(aux.end).apply {
            var cursor = 0
            for (segment in removed) { write(bytes, cursor, segment.start - cursor); cursor = segment.end }
            write(bytes, cursor, aux.end - cursor)
        }.toByteArray()
        Payload(stripped, aux.width, aux.height, base.width, base.height, aux.end, words,
            "container=Huawei_APP5_CUVA; primaryFileType=$kind; auxiliaryOffset=$offset; " +
                "recordedAbsoluteOffset=${p.u32(16)}; appBytesIncludingSoi=$appBytes; auxiliaryBytes=${aux.end}; " +
                "base=${base.width}x${base.height}; auxiliary=${aux.width}x${aux.height}; " +
                "metadataWordsU32=$words; metadataWord4Float=${Float.fromBits(words[4].toInt())}; " +
                "metadataWord7Float=${Float.fromBits(words[7].toInt())}; metadataSemantics=unverified; " +
                "strippedMetadataSegments=${removed.size}; trailingBytes=${bytes.size - aux.end}")
    }

    private fun validHeader(p: ByteArray, primary: Boolean): Boolean {
        if (p.size < 16 || !p.hasSignature() || p[6].toInt() != 0x26 || p[11].toInt() != 2) return false
        val provider = p.u16(7); val oriented = p.u16(9)
        if (!(provider == 4 && oriented == 6 || primary && provider == 0x400 && oriented == 0x600)) return false
        return if (primary) (p[12].toInt() == 1 || p[12].toInt() == 2) && p[13].toInt() == 0 && p[14].toInt() == 0 && p[15].toInt() == 0
        else p[12].toInt() == 2 && p[13].toInt() == 0 && p[14].toInt() == 1 && p[15].toInt() == 0
    }

    private fun parse(bytes: ByteArray, headerOnly: Boolean): Jpeg {
        require(bytes.size >= 4 && bytes.u16(0) == 0xffd8) { "app5_invalid_jpeg_start" }
        var position = 2; var width = 0; var height = 0; var entropy = false
        val segments = mutableListOf<Segment>()
        while (position < bytes.size) {
            if (bytes[position].toInt() and 255 != 255) {
                require(entropy) { "app5_invalid_jpeg_marker" }; position++; continue
            }
            val start = position++
            while (position < bytes.size && bytes[position].toInt() and 255 == 255) position++
            require(position < bytes.size) { "app5_truncated_marker" }
            val marker = bytes[position++].toInt() and 255
            if (marker == 0 || marker in 0xd0..0xd7) { require(entropy) { "app5_unexpected_entropy" }; continue }
            if (marker == 0xd9) return Jpeg(width, height, segments, position)
            require(marker != 0xd8 && marker != 1 && position + 2 <= bytes.size && segments.size < 1024) { "app5_invalid_segment" }
            val size = bytes.u16(position)
            require(size >= 2 && position + size <= bytes.size) { "app5_truncated_segment" }
            val end = position + size
            val payload = bytes.copyOfRange(position + 2, end)
            segments.add(Segment(marker, start, end, payload))
            if (marker in listOf(0xc0, 0xc1, 0xc2)) {
                require(payload.size >= 6 && payload[0].toInt() == 8) { "app5_unsupported_jpeg_precision" }
                height = payload.u16(1); width = payload.u16(3)
            }
            position = end; entropy = marker == 0xda
            if (headerOnly && entropy) return Jpeg(width, height, segments, position)
        }
        throw IllegalArgumentException("app5_truncated_jpeg")
    }

    fun compatible(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean =
        abs(width.toLong() * otherHeight - otherWidth.toLong() * height) <= 2L * maxOf(width, height, otherWidth, otherHeight)

    /** EXIF=0 与缺省都按未旋转处理，和系统解码器保持一致。 */
    fun pixelIndex(x: Int, y: Int, width: Int, height: Int, orientation: Int): Int = when (orientation) {
        2 -> y * width + width - 1 - x
        3 -> (height - 1 - y) * width + width - 1 - x
        4 -> (height - 1 - y) * width + x
        5 -> x * width + y
        6 -> (height - 1 - x) * width + y
        7 -> (height - 1 - x) * width + width - 1 - y
        8 -> x * width + width - 1 - y
        else -> y * width + x
    }

    private fun ByteArray.hasSignature() = size >= signature.size && signature.indices.all { this[it] == signature[it] }
    private fun ByteArray.u16(index: Int) = ((this[index].toInt() and 255) shl 8) or (this[index + 1].toInt() and 255)
    private fun ByteArray.u32(index: Int) = (0..3).fold(0L) { value, i -> (value shl 8) or (this[index + i].toLong() and 255) }
}
