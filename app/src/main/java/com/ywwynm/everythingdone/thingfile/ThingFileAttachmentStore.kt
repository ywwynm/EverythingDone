package com.ywwynm.everythingdone.thingfile

import com.ywwynm.everythingdone.helpers.AttachmentHelper
import java.io.File
import java.io.IOException

/** 接收附件的文件操作；普通导入与更新共用，独立于界面和数据库。 */
internal object ThingFileAttachmentStore {
    private const val MAX_NAME_BYTES = 255
    private val unsafeCharacters = Regex("[\\p{Cntrl}<>:\"|?*]")
    private val entryIndex = Regex("^[0-9]+_")

    data class Entry(val key: String, val type: Int, val file: File,
        val name: String = file.name, val reused: Boolean = false)

    class Moved(val filesByKey: Map<String, File>, val newFiles: List<File>)

    /** 清单缺少名称时从条目恢复，绝不能使用带 download／copy 前缀的临时文件名。 */
    fun originalName(name: String?, entry: String): String =
        name?.takeIf { it.isNotBlank() }
            ?: entry.substringAfterLast('/').replaceFirst(entryIndex, "")

    fun move(entries: List<Entry>, root: File, attachmentSeparator: String): Moved {
        val mapped = LinkedHashMap<String, File>()
        val done = ArrayList<File>(entries.size)
        try {
            for (entry in entries) {
                val src = entry.file
                if (!src.isFile) throw IOException("附件已不存在：${entry.name}")
                if (entry.reused) {
                    mapped[entry.key] = src
                    continue
                }
                val dst = reserveFile(root, entry, attachmentSeparator)
                // 名称已被本次操作独占；复制或移动失败也必须清掉当前占位文件。
                done.add(dst)
                if (!src.renameTo(dst)) {
                    src.inputStream().use { input -> dst.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    } }
                }
                mapped[entry.key] = dst
            }
        } catch (e: IOException) {
            done.forEach { it.delete() }
            throw e
        }
        return Moved(mapped, done)
    }

    private fun reserveFile(root: File, entry: Entry, attachmentSeparator: String): File {
        val folder = File(root, when (entry.type) {
            AttachmentHelper.IMAGE -> "images"
            AttachmentHelper.VIDEO -> "videos"
            else -> "audios"
        })
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("建不了附件目录")
        val name = entry.name.replace('\\', '/').substringAfterLast('/')
            .replace(unsafeCharacters, "_")
            .let { if (attachmentSeparator.isEmpty()) it else it.replace(attachmentSeparator, "_") }
            .trimEnd(' ', '.')
            .ifBlank {
                when (entry.type) {
                    AttachmentHelper.IMAGE -> "attachment.jpg"
                    AttachmentHelper.VIDEO -> "attachment.mp4"
                    else -> "attachment.wav"
                }
            }
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        for (number in 0..Int.MAX_VALUE) {
            val suffix = if (number == 0) "" else "-$number"
            val candidate = fitName(stem, suffix, extension)
            val file = File(folder, candidate)
            // exists() 后直接写入存在竞争窗口；原子创建让并发导入只能占到不同名称。
            if (file.createNewFile()) return file
        }
        throw IOException("没有可用的附件文件名")
    }

    /** 按 UTF-8 字节限制文件系统名称长度，追加编号时保留扩展名且不截断 Unicode 码位。 */
    private fun fitName(stem: String, suffix: String, extension: String): String {
        val full = stem + suffix + extension
        if (full.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return full
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        val keptExtension = takeUtf8(extension,
            MAX_NAME_BYTES - suffixBytes - minOf(4, stem.toByteArray(Charsets.UTF_8).size))
        val keptStem = takeUtf8(stem,
            MAX_NAME_BYTES - suffixBytes - keptExtension.toByteArray(Charsets.UTF_8).size)
        return keptStem + suffix + keptExtension
    }

    private fun takeUtf8(text: String, maxBytes: Int): String {
        var end = 0
        var bytes = 0
        while (end < text.length) {
            val next = end + Character.charCount(text.codePointAt(end))
            val count = text.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (bytes + count > maxBytes) break
            bytes += count
            end = next
        }
        return text.substring(0, end)
    }
}
