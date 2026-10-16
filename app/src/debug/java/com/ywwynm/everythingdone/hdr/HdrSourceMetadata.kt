package com.ywwynm.everythingdone.hdr

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.DataInputStream
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import javax.xml.parsers.SAXParserFactory

/** 只输出结构和 HDR 白名单字段；不输出 EXIF、任意 XML、文件名或原始字节。 */
internal object HdrSourceMetadata {
    data class Report(val summary: String, val details: String)
    private const val XMP = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val EXTENDED_XMP = "http://ns.adobe.com/xmp/extension/\u0000"
    private const val ISO = "urn:iso:std:iso:ts:21496:-1\u0000"
    private const val GAIN = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private val gainFields = setOf("Version", "BaseRenditionIsHDR", "GainMapMin", "GainMapMax", "Gamma",
        "OffsetSDR", "OffsetHDR", "HDRCapacityMin", "HDRCapacityMax")
    private val numericValue = Regex("[+\\-0-9.eE,\\s]+")
    private const val MAX_METADATA = 2 * 1024 * 1024
    private data class Extended(val bytes: ByteArray, val ranges: MutableList<IntRange> = mutableListOf())
    private data class Entry(val start: Long, val size: Long)

    fun inspect(file: File): Report {
        val started = System.nanoTime()
        // 输入为复制完成的私有文件。不能在逐字节循环中反复触发文件系统属性查询。
        val fileSize = file.length()
        var hashFinished = started
        val lines = mutableListOf<String>()
        val signals = linkedSetOf<String>()
        val starts = mutableListOf<Long>()
        val entries = mutableListOf<Entry>()
        val extended = mutableMapOf<String, Extended>()
        val counts = sortedMapOf<Int, Int>()
        var complete = false
        var metadataBytes = 0
        var position = 0L
        var inImage = false
        var entropy = false
        var firstEnd = -1L
        var segmentCount = 0
        fun line(value: String) { if (lines.size < 256) lines.add(value) }

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            hashFinished = System.nanoTime()
            line("sourceBytes=$fileSize; sha256=${digest.digest().joinToString("") { "%02x".format(it) }}")
            file.inputStream().buffered(65536).use { buffered ->
                val input = DataInputStream(buffered)
                fun read(): Int { val value = input.read(); if (value >= 0) position++; return value }
                if (read() != 0xff || read() != 0xd8) {
                    line("format=not_JPEG; parserSupported=false; 未解析此容器的 HDR 元数据")
                    error("not_jpeg")
                }
                starts.add(0); inImage = true
                line("format=JPEG; bytes=$fileSize; inspection=encoded_bytes_before_ImageDecoder")
                while (position < fileSize) {
                    var b = read()
                    if (b < 0) break
                    if (b != 0xff) {
                        check(entropy || !inImage) { "unexpected_data_between_markers" }
                        continue
                    }
                    val markerOffset = position - 1
                    do { b = read() } while (b == 0xff)
                    check(b >= 0) { "truncated_marker" }
                    if (b == 0 || b in 0xd0..0xd7) { check(entropy || !inImage); continue }
                    if (b == 0xd8) {
                        check(!inImage && starts.size < 16) { "unexpected_or_excess_images" }
                        starts.add(markerOffset); inImage = true; entropy = false
                        continue
                    }
                    if (!inImage) continue // 尾部可能还有视频等数据；只识别独立 JPEG 的 SOI。
                    if (b == 0xd9) {
                        inImage = false; entropy = false
                        if (firstEnd < 0) firstEnd = position
                        line("jpeg[${starts.lastIndex}]: start=${starts.last()}; end=$position; bytes=${position - starts.last()}")
                        continue
                    }
                    if (b == 1) continue
                    check(++segmentCount <= 2048) { "segment_limit" }
                    val high = read(); val low = read()
                    check(high >= 0 && low >= 0) { "truncated_length" }
                    val size = ((high shl 8) or low) - 2
                    check(size >= 0 && position + size <= fileSize) { "invalid_segment_size" }
                    val payloadOffset = position
                    val payload = ByteArray(size)
                    input.readFully(payload); position += size
                    counts[b] = (counts[b] ?: 0) + 1
                    val location = "jpeg[${starts.lastIndex}] marker=0x${b.toString(16)} offset=$markerOffset bytes=$size"
                    if (b in 0xe0..0xef) {
                        metadataBytes += size
                        check(metadataBytes <= MAX_METADATA) { "metadata_limit" }
                        line(location)
                    }
                    when {
                        b == 0xe1 && payload.startsWithAscii(XMP) -> parseXmp(payload.copyOfRange(XMP.length, size), signals, ::line)
                        b == 0xe1 && payload.startsWithAscii(EXTENDED_XMP) -> {
                            val h = EXTENDED_XMP.length
                            check(size >= h + 40) { "extended_xmp_header" }
                            val id = String(payload, h, 32, Charsets.US_ASCII)
                            check(id.matches(Regex("[0-9a-fA-F]{32}")))
                            val length = payload.u32(h + 32, false)
                            val offset = payload.u32(h + 36, false)
                            val n = size - h - 40
                            check(length in 1..MAX_METADATA.toLong() && offset + n <= length) { "extended_xmp_bounds" }
                            check(extended.size < 4 || id in extended)
                            check(extended.values.sumOf { it.bytes.size.toLong() } + (if (id in extended) 0 else length) <= MAX_METADATA)
                            val packet = extended.getOrPut(id) { Extended(ByteArray(length.toInt())) }
                            check(packet.bytes.size.toLong() == length && n > 0)
                            val range = offset.toInt() until (offset.toInt() + n)
                            check(packet.ranges.none { it.first <= range.last && range.first <= it.last }) { "extended_xmp_overlap" }
                            payload.copyInto(packet.bytes, offset.toInt(), h + 40)
                            packet.ranges.add(range)
                            line("extendedXmpChunk: total=$length; offset=$offset; size=$n")
                        }
                        b == 0xe2 && payload.startsWithAscii(ISO) -> {
                            signals.add("ISO 21496-1")
                            line("iso21496: payloadBytes=${size - ISO.length}; " + if (size >= ISO.length + 4)
                                "minimumVersion=${payload.u16(ISO.length, false)}; writerVersion=${payload.u16(ISO.length + 2, false)}"
                                else "truncatedVersion=true")
                        }
                        b == 0xe2 && payload.startsWithAscii("MPF\u0000") -> {
                            signals.add("MPF（多图容器）")
                            parseMpf(payload, payloadOffset, starts.last(), entries, ::line)
                        }
                        b == 0xe2 && payload.startsWithAscii("ICC_PROFILE\u0000") ->
                            line("iccProfile: chunk=${payload.getOrNull(12)?.toInt()?.and(255)}; total=${payload.getOrNull(13)?.toInt()?.and(255)}; bytes=$size")
                        b == 0xe5 && payload.startsWithAscii("ITUT35") -> {
                            signals.add("华为 APP5／CUVA 线索")
                            line("huaweiApp5: bytes=$size; " + if (size >= 16)
                                "country=${payload[6].toInt() and 255}; providerBytes=${payload.u16(7, false)}; " +
                                    "orientedBytes=${payload.u16(9, false)}; frames=${payload[11].toInt() and 255}; " +
                                    "fileType=${payload[12].toInt() and 255}; reconstructionRequiresAdapter=true"
                                else "truncatedHeader=true")
                        }
                        b == 0xe8 && payload.startsWithAscii("ITUT35") -> {
                            signals.add("Vivid APP8 线索")
                            line("vividApp8: bytes=$size; " + if (size >= 16)
                                "country=${payload[6].toInt() and 255}; provider=${payload.u16(7, false)}; " +
                                    "providerOriented=${payload.u16(9, false)}; frames=${payload[11].toInt() and 255}; " +
                                    "fileType=${payload[12].toInt() and 255}; metaType=${payload[13].toInt() and 255}; " +
                                    "enhanceType=${payload[14].toInt() and 255}; hdrType=${payload[15].toInt() and 255}"
                                else "truncatedHeader=true")
                        }
                        b == 0xe5 -> line("app5: privatePayloadPresent=true; 未解析私有 CUVA／其他厂商封装，不能仅凭 APP5 判为 HDR")
                        b in 0xc0..0xcf && b !in setOf(0xc4, 0xc8, 0xcc) && size >= 6 ->
                            line("encodedFrame: bits=${payload[0].toInt() and 255}; width=${payload.u16(3, false)}; height=${payload.u16(1, false)}; components=${payload[5].toInt() and 255}")
                    }
                    if (b == 0xda) entropy = true
                }
                complete = !inImage && firstEnd >= 0
            }
            extended.values.forEach { packet ->
                val ordered = packet.ranges.sortedBy { it.first }
                val assembled = ordered.isNotEmpty() && ordered.first().first == 0 && ordered.last().last == packet.bytes.lastIndex &&
                    ordered.zipWithNext().all { (a, b) -> a.last + 1 == b.first }
                line("extendedXmpComplete=$assembled")
                if (assembled) parseXmp(packet.bytes, signals, ::line) else complete = false
            }
        } catch (failure: Exception) {
            // XML／二进制异常可能含原始数据，因此只记录类型，不输出异常消息。
            line("inspectionStopped: offset=$position; exception=${failure.javaClass.simpleName}")
        }
        entries.forEachIndexed { index, e ->
            line("mpfEntry[$index]: offset=${e.start}; bytes=${e.size}; inFile=${e.start >= 0 && e.size > 0 && e.start <= fileSize - e.size}; jpegAtOffset=${e.start in starts}")
        }
        line("markerCounts=" + counts.entries.joinToString { "0x${it.key.toString(16)}:${it.value}" })
        line("scanComplete=$complete; jpegCount=${starts.size}; bytesAfterPrimary=${if (firstEnd < 0) -1 else fileSize - firstEnd}; metadataBytes=$metadataBytes")
        if (starts.size >= 2 && (counts[0xe5] ?: 0) >= 2 && "华为 APP5／CUVA 线索" !in signals)
            signals.add("多 JPEG／APP5（私有格式待确认）")
        val summary = if (signals.isEmpty()) "未检出已支持的 HDR 标记" else signals.joinToString("、")
        line("signals=$summary; 仅为格式线索，不等于元数据有效、Android 可解码或屏幕已输出 HDR。")
        line("scope=应用实际收到的文件；不能据此证明选图提供方没有转换原文件。非 JPEG／私有格式或不完整扫描不能排除 HDR。")
        val finished = System.nanoTime()
        lines.add("metadataTiming: hashMs=${(hashFinished - started) / 1_000_000}; scanMs=${(finished - hashFinished) / 1_000_000}; totalMs=${(finished - started) / 1_000_000}")
        return Report((if (complete) "" else "检查不完整；") + summary, lines.joinToString("\n"))
    }

    private fun parseXmp(bytes: ByteArray, signals: MutableSet<String>, line: (String) -> Unit) {
        val xml = String(bytes, Charsets.UTF_8)
        if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) {
            line("xmpParse=blocked_DTD_or_entity"); return
        }
        var depth = 0
        var captured: Pair<Int, String>? = null
        val text = StringBuilder()
        fun field(uri: String, name: String, value: String) {
            val allowed = (uri == GAIN && name in gainFields) || (uri == ITEM && name in setOf("Semantic", "Mime", "Length", "Padding"))
            if (!allowed) return
            val normalized = value.trim().replace(Regex("\\s+"), " ")
            val safe = normalized.length in 1..192 && (numericValue.matches(normalized) || normalized in setOf("True", "False", "true", "false", "Primary", "GainMap", "image/jpeg"))
            line("xmp.${if (uri == GAIN) "hdrgm" else "Item"}.$name=${if (safe) normalized else "[非白名单值，已省略]"}")
        }
        try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities"))
                runCatching { reader.setFeature(feature, false) }
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                    check(++depth <= 32)
                    if (uri == GAIN) signals.add("Ultra HDR XMP")
                    if (uri == GAIN && local in gainFields) { captured = depth to local; text.setLength(0) }
                    for (i in 0 until attributes.length) {
                        if (attributes.getURI(i) == GAIN) signals.add("Ultra HDR XMP")
                        field(attributes.getURI(i), attributes.getLocalName(i), attributes.getValue(i))
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (captured != null && text.length < 193) text.append(ch, start, minOf(length, 193 - text.length))
                }
                override fun endElement(uri: String, local: String, qName: String) {
                    if (captured?.first == depth) { field(GAIN, captured!!.second, text.toString()); captured = null }
                    else if (captured != null && text.length < 193) text.append(' ')
                    depth--
                }
            }
            reader.contentHandler = handler; reader.errorHandler = handler
            reader.parse(InputSource(StringReader(xml)))
            line("xmpParse=ok")
        } catch (failure: Exception) { line("xmpParse=failed_${failure.javaClass.simpleName}") }
    }

    private fun parseMpf(data: ByteArray, offset: Long, imageStart: Long, entries: MutableList<Entry>, line: (String) -> Unit) {
        try {
            val little = data.getOrNull(4) == 'I'.code.toByte() && data.getOrNull(5) == 'I'.code.toByte()
            check(little || (data.getOrNull(4) == 'M'.code.toByte() && data.getOrNull(5) == 'M'.code.toByte()))
            check(data.u16(6, little) == 42)
            val ifd = 4 + data.u32(8, little)
            check(ifd <= data.size - 2)
            val count = data.u16(ifd.toInt(), little)
            check(count <= 256 && ifd + 2 + count * 12 <= data.size)
            repeat(count) { index ->
                val p = ifd.toInt() + 2 + index * 12
                val tag = data.u16(p, little)
                val type = data.u16(p + 2, little)
                val n = data.u32(p + 4, little)
                val value = data.u32(p + 8, little)
                if (tag == 0xb001 && type == 4 && n == 1L) line("mpfImageCount=$value")
                if (tag == 0xb002 && type == 7) {
                    check(n % 16 == 0L && n in 16..4096 && 4 + value + n <= data.size)
                    repeat((n / 16).toInt()) { e ->
                        val pos = 4 + value.toInt() + e * 16
                        val relative = data.u32(pos + 8, little)
                        entries.add(Entry(if (relative == 0L) imageStart else offset + 4 + relative, data.u32(pos + 4, little)))
                    }
                }
            }
        } catch (_: Exception) { line("mpfParse=invalid_or_unsupported") }
    }

    private fun ByteArray.startsWithAscii(prefix: String) = size >= prefix.length && prefix.indices.all { this[it].toInt() and 255 == prefix[it].code }
    private fun ByteArray.u16(offset: Int, little: Boolean): Int = if (little)
        (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
        else ((this[offset].toInt() and 255) shl 8) or (this[offset + 1].toInt() and 255)
    private fun ByteArray.u32(offset: Int, little: Boolean): Long {
        var result = 0L
        for (i in 0..3) result = (result shl 8) or (this[offset + if (little) 3 - i else i].toLong() and 255)
        return result
    }
}
