package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * HDR Vivid `cuvv` Configuration Box 的生成与 MP4 后处理。
 *
 * Android `MediaMuxer` 没有写入自定义 VisualSampleEntry 子盒的公开接口，因此只能在
 * `stop()/release()` 完成后、文件发布前补盒。实现只采用不会移动 `mdat` 的两种安全布局：
 *
 * 1. `mdat` 在前、`moov` 在文件尾：直接扩展文件尾；
 * 2. 流式 MP4 的 `moov + free + mdat`：让 `moov` 吃掉 30 字节保留区并缩小 `free`。
 *
 * 若布局不满足这两项则明确失败，不在缺少 chunk-offset 重写证据时移动媒体数据。
 */
internal object FableSolHdrVividMp4 {

    data class PatchResult(
        val changed: Boolean,
        val placement: Placement
    )

    enum class Placement {
        ALREADY_PRESENT,
        MOOV_AT_END,
        RESERVED_FREE_SPACE
    }

    fun configurationBox(
        versionMap: Int = FableSolHdrVividMetadata.VERSION_1_MAP,
        orientedCode: Int = FableSolHdrVividMetadata.VERSION_1_ORIENTED_CODE
    ): ByteArray {
        require(versionMap in 1..0xFFFF)
        require(orientedCode in 0..0xFFFF)
        return ByteBuffer.allocate(CUVV_BOX_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(CUVV_BOX_BYTES)
            .put("cuvv".toByteArray(Charsets.US_ASCII))
            .putShort(versionMap.toShort())
            .putShort(FableSolHdrVividMetadata.PROVIDER_CODE.toShort())
            .putShort(orientedCode.toShort())
            .put(ByteArray(RESERVED_BYTES))
            .array()
    }

    /** 纯内存变换；输入必须恰好是一整个 `moov` box。 */
    fun patchMoov(moov: ByteArray): ByteArray {
        val root = parseBox(moov, 0, moov.size)
        require(root.type == "moov" && root.start == 0 && root.end == moov.size) {
            "Input is not one complete moov box"
        }
        val targets = findHevcSampleEntries(moov, root)
        require(targets.size == 1) {
            "Expected exactly one HEVC VisualSampleEntry, found ${targets.size}"
        }
        val target = targets.single()
        val childStart = target.start + target.headerSize + VISUAL_SAMPLE_ENTRY_FIELDS
        require(childStart <= target.end) { "Truncated HEVC VisualSampleEntry" }
        val children = parseChildren(moov, childStart, target.end)
        require(children.any { it.type == "hvcC" }) {
            "HEVC VisualSampleEntry has no hvcC box"
        }
        val expected = configurationBox()
        val existing = children.filter { it.type == "cuvv" }
        if (existing.isNotEmpty()) {
            require(existing.size == 1) { "HEVC VisualSampleEntry has duplicate cuvv boxes" }
            val box = existing.single()
            require(moov.copyOfRange(box.start, box.end).contentEquals(expected)) {
                "Existing cuvv box does not describe HDR Vivid 1.0"
            }
            return moov.copyOf()
        }

        val insertion = target.end
        val result = ByteArray(moov.size + expected.size)
        moov.copyInto(result, endIndex = insertion)
        expected.copyInto(result, destinationOffset = insertion)
        moov.copyInto(
            result,
            destinationOffset = insertion + expected.size,
            startIndex = insertion
        )
        for (ancestor in target.ancestors + target) {
            incrementDeclaredSize(result, ancestor, expected.size)
        }
        return result
    }

    fun containsConfiguration(moov: ByteArray): Boolean {
        return try {
            val root = parseBox(moov, 0, moov.size)
            if (root.type != "moov" || root.end != moov.size) {
                false
            } else {
                findHevcSampleEntries(moov, root).any { entry ->
                    val start = entry.start + entry.headerSize + VISUAL_SAMPLE_ENTRY_FIELDS
                    start <= entry.end && parseChildren(moov, start, entry.end).any { child ->
                        child.type == "cuvv" &&
                            moov.copyOfRange(child.start, child.end)
                                .contentEquals(configurationBox())
                    }
                }
            }
        } catch (ignored: IllegalArgumentException) {
            false
        }
    }

    fun patchInPlace(file: File): PatchResult =
        RandomAccessFile(file, "rw").use { random ->
            patchInPlace(random.channel)
        }

    fun patchInPlace(channel: FileChannel): PatchResult =
        patchInPlace(reader = channel, writer = channel)

    /**
     * MediaStore 的同一文件需分别以只读和读写描述符打开；Java 没有把一个
     * `FileDescriptor` 公共构造成双向 FileChannel 的接口，因此保留双通道入口。
     */
    fun patchInPlace(reader: FileChannel, writer: FileChannel): PatchResult {
        val fileSize = reader.size()
        val topLevel = scanTopLevel(reader, fileSize)
        val moov = topLevel.singleOrNull { it.type == "moov" }
            ?: error("Expected exactly one top-level moov box")
        require(moov.size <= MAX_MOOV_BYTES) {
            "moov box is too large for bounded HDR Vivid patching: ${moov.size}"
        }
        val original = ByteArray(moov.size.toInt())
        readFully(reader, moov.start, original)
        val patched = patchMoov(original)
        if (patched.contentEquals(original)) {
            return PatchResult(changed = false, placement = Placement.ALREADY_PRESENT)
        }
        val growth = patched.size - original.size
        check(growth == CUVV_BOX_BYTES)

        val placement = when {
            moov.end == fileSize -> {
                writeFully(writer, moov.start, patched)
                writer.truncate(moov.start + patched.size)
                Placement.MOOV_AT_END
            }
            else -> {
                val next = topLevel.firstOrNull { it.start == moov.end }
                require(next?.type == "free") {
                    "moov precedes media data without adjacent reserved free space"
                }
                val remainingFree = next.size - growth
                require(remainingFree == 0L || remainingFree >= BOX_HEADER_BYTES) {
                    "Reserved free box is too small for cuvv"
                }
                writeFully(writer, moov.start, patched)
                if (remainingFree > 0L) {
                    writeFreeHeader(writer, next.start + growth, remainingFree)
                }
                Placement.RESERVED_FREE_SPACE
            }
        }
        writer.force(true)

        val updated = ByteArray(patched.size)
        readFully(reader, moov.start, updated)
        check(containsConfiguration(updated)) { "cuvv verification failed after patching" }
        return PatchResult(changed = true, placement = placement)
    }

    private data class MemoryBox(
        val start: Int,
        val end: Int,
        val headerSize: Int,
        val type: String,
        val extendedSize: Boolean,
        val ancestors: List<MemoryBox> = emptyList()
    )

    private data class FileBox(
        val start: Long,
        val size: Long,
        val type: String
    ) {
        val end: Long get() = start + size
    }

    private fun findHevcSampleEntries(
        bytes: ByteArray,
        moov: MemoryBox
    ): List<MemoryBox> {
        val result = ArrayList<MemoryBox>(1)
        for (trak in parseChildren(bytes, moov.start + moov.headerSize, moov.end)) {
            if (trak.type != "trak") continue
            for (mdia in parseChildren(bytes, trak.start + trak.headerSize, trak.end)) {
                if (mdia.type != "mdia") continue
                for (minf in parseChildren(bytes, mdia.start + mdia.headerSize, mdia.end)) {
                    if (minf.type != "minf") continue
                    for (stbl in parseChildren(bytes, minf.start + minf.headerSize, minf.end)) {
                        if (stbl.type != "stbl") continue
                        for (stsd in parseChildren(
                            bytes,
                            stbl.start + stbl.headerSize,
                            stbl.end
                        )) {
                            if (stsd.type != "stsd") continue
                            val entriesStart = stsd.start + stsd.headerSize + STSD_PREFIX_BYTES
                            require(entriesStart <= stsd.end) { "Truncated stsd box" }
                            val entryCount = readUInt32(
                                bytes,
                                stsd.start + stsd.headerSize + FULL_BOX_HEADER_BYTES
                            ).toInt()
                            val entries = parseChildren(bytes, entriesStart, stsd.end)
                            require(entries.size == entryCount) {
                                "stsd entry_count does not match its sample entries"
                            }
                            for (entry in entries) {
                                if (entry.type == "hvc1" || entry.type == "hev1") {
                                    result += entry.copy(
                                        ancestors = listOf(
                                            moov, trak, mdia, minf, stbl, stsd
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parseChildren(
        bytes: ByteArray,
        start: Int,
        limit: Int
    ): List<MemoryBox> {
        val result = ArrayList<MemoryBox>(8)
        var offset = start
        while (offset < limit) {
            val box = parseBox(bytes, offset, limit)
            result += box
            offset = box.end
        }
        require(offset == limit) { "Child boxes do not exactly fill their parent" }
        return result
    }

    private fun parseBox(bytes: ByteArray, start: Int, limit: Int): MemoryBox {
        require(start >= 0 && limit <= bytes.size && start + BOX_HEADER_BYTES <= limit) {
            "Truncated MP4 box header"
        }
        val size32 = readUInt32(bytes, start)
        val type = String(bytes, start + 4, 4, Charsets.US_ASCII)
        val extended = size32 == 1L
        val header = if (extended) EXTENDED_BOX_HEADER_BYTES else BOX_HEADER_BYTES
        require(start + header <= limit) { "Truncated extended MP4 box header" }
        val size = when {
            extended -> readInt64(bytes, start + BOX_HEADER_BYTES)
            size32 == 0L -> (limit - start).toLong()
            else -> size32
        }
        require(size >= header && size <= Int.MAX_VALUE) { "Invalid MP4 box size: $size" }
        val end = start + size.toInt()
        require(end <= limit) { "MP4 box $type exceeds its parent" }
        return MemoryBox(start, end, header, type, extended)
    }

    private fun incrementDeclaredSize(bytes: ByteArray, box: MemoryBox, delta: Int) {
        val oldSize = (box.end - box.start).toLong()
        val newSize = oldSize + delta
        if (box.extendedSize) {
            writeInt64(bytes, box.start + BOX_HEADER_BYTES, newSize)
        } else {
            require(newSize <= UINT32_MAX) { "MP4 box exceeds 32-bit size after cuvv insertion" }
            writeUInt32(bytes, box.start, newSize)
        }
    }

    private fun scanTopLevel(channel: FileChannel, fileSize: Long): List<FileBox> {
        val boxes = ArrayList<FileBox>(8)
        var offset = 0L
        val header = ByteArray(EXTENDED_BOX_HEADER_BYTES)
        while (offset < fileSize) {
            require(fileSize - offset >= BOX_HEADER_BYTES) { "Truncated top-level MP4 box" }
            readFully(channel, offset, header, BOX_HEADER_BYTES)
            val size32 = readUInt32(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            val size = when {
                size32 == 1L -> {
                    require(fileSize - offset >= EXTENDED_BOX_HEADER_BYTES) {
                        "Truncated extended top-level MP4 box"
                    }
                    readFully(channel, offset, header, EXTENDED_BOX_HEADER_BYTES)
                    readInt64(header, BOX_HEADER_BYTES)
                }
                size32 == 0L -> fileSize - offset
                else -> size32
            }
            val minimum = if (size32 == 1L) {
                EXTENDED_BOX_HEADER_BYTES
            } else {
                BOX_HEADER_BYTES
            }
            require(size >= minimum && size <= fileSize - offset) {
                "Invalid top-level MP4 box $type size: $size"
            }
            boxes += FileBox(offset, size, type)
            offset += size
        }
        return boxes
    }

    private fun writeFreeHeader(channel: FileChannel, offset: Long, size: Long) {
        val header = if (size <= UINT32_MAX) {
            ByteBuffer.allocate(BOX_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(size.toInt())
                .put("free".toByteArray(Charsets.US_ASCII))
                .array()
        } else {
            require(size >= EXTENDED_BOX_HEADER_BYTES)
            ByteBuffer.allocate(EXTENDED_BOX_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(1)
                .put("free".toByteArray(Charsets.US_ASCII))
                .putLong(size)
                .array()
        }
        writeFully(channel, offset, header)
    }

    private fun readFully(
        channel: FileChannel,
        offset: Long,
        destination: ByteArray,
        length: Int = destination.size
    ) {
        val buffer = ByteBuffer.wrap(destination, 0, length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            require(read > 0) { "Unexpected EOF while reading MP4" }
            position += read
        }
    }

    private fun writeFully(channel: FileChannel, offset: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        var position = offset
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, position)
            require(written > 0) { "Unable to write MP4 patch" }
            position += written
        }
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        require(value in 0..UINT32_MAX)
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readInt64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .long

    private fun writeInt64(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(value)
    }

    private const val BOX_HEADER_BYTES = 8
    private const val EXTENDED_BOX_HEADER_BYTES = 16
    private const val FULL_BOX_HEADER_BYTES = 4
    private const val STSD_PREFIX_BYTES = 8
    /** SampleEntry 的 8 字节字段 + VisualSampleEntry 的 70 字节字段。 */
    private const val VISUAL_SAMPLE_ENTRY_FIELDS = 78
    private const val RESERVED_BYTES = 16
    const val CUVV_BOX_BYTES = 30
    private const val MAX_MOOV_BYTES = 64L * 1024L * 1024L
    private const val UINT32_MAX = 0xFFFF_FFFFL
}
