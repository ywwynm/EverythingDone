package com.ywwynm.everythingdone.hdr

import java.io.File
import java.io.RandomAccessFile

/** 仅用于已无原生 Gainmap 的位图：普通 JPEG 可立即显示，未知格式仍交给原 HDR 解码器。 */
internal object HdrViewerSource {
    fun needsHdrInspection(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { input ->
            if (input.readUnsignedShort() != 0xffd8) return true
            var segments = 0
            while (input.filePointer < minOf(input.length(), 2L * 1024 * 1024) && segments++ < 1024) {
                if (input.readUnsignedByte() != 0xff) return true
                var marker = input.readUnsignedByte()
                while (marker == 0xff) {
                    if (input.filePointer >= 2L * 1024 * 1024) return true
                    marker = input.readUnsignedByte()
                }
                if (marker == 0xd9) return false
                if (marker == 0 || marker == 1 || marker in 0xd0..0xd8) return true
                val length = input.readUnsignedShort()
                val end = input.filePointer + length - 2
                if (length < 2 || end > input.length()) return true
                if (marker == 0xda) return false // 不读取熵编码像素或动态照片尾部视频。
                if (marker in 0xe0..0xef) {
                    val prefix = ByteArray(minOf(length - 2, 12))
                    input.readFully(prefix)
                    val name = prefix.toString(Charsets.ISO_8859_1)
                    val knownSdrMetadata = when (marker) {
                        0xe0 -> name.startsWith("JFIF\u0000") || name.startsWith("JFXX\u0000")
                        0xe1 -> name.startsWith("Exif\u0000\u0000")
                        0xe2 -> name.startsWith("ICC_PROFILE\u0000")
                        0xee -> name.startsWith("Adobe")
                        else -> false
                    }
                    // MPF、ISO、XMP、APP5 及未知扩展均保留检查，不能凭预检宣告没有 HDR。
                    if (!knownSdrMetadata) return true
                }
                input.seek(end)
            }
            true
        }
    } catch (_: Exception) { true }
}
