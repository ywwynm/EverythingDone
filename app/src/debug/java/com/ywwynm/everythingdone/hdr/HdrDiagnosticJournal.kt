package com.ywwynm.everythingdone.hdr

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 每条事件立即落盘；进程退出后再次进入仍可直接导出，不依赖 logcat。 */
internal class HdrDiagnosticJournal(val directory: File, private val maxBytes: Long = Long.MAX_VALUE) {
    val logFile = File(directory, "hdr-diagnostics.txt")

    init {
        require(maxBytes >= 256)
        check(directory.isDirectory || directory.mkdirs())
        synchronized(lock) {
            if (logFile.length() > maxBytes) {
                logFile.writeText(formatEvent("journal_reset", "reason=previous_file_exceeds_limit"))
            }
        }
    }

    fun event(name: String, details: String = "") = synchronized(lock) {
        val entry = formatEvent(name, details).toByteArray(Charsets.UTF_8)
        val rotation = formatEvent("journal_rotated", "previousBytes=${logFile.length()}").toByteArray(Charsets.UTF_8)
        if (entry.size > maxBytes - rotation.size) {
            val omitted = formatEvent("journal_event_omitted", "eventBytes=${entry.size}").toByteArray(Charsets.UTF_8)
            if (logFile.length() + omitted.size > maxBytes) logFile.writeBytes(rotation)
            logFile.appendBytes(omitted)
        } else {
            if (logFile.length() + entry.size > maxBytes) logFile.writeBytes(rotation)
            logFile.appendBytes(entry)
        }
    }

    fun clear() = synchronized(lock) { logFile.writeText("", Charsets.UTF_8) }

    /** 附件只写入导出副本，不回写 journal，重复导出不会重复累积生产日志。 */
    fun snapshot(destination: File, attachments: List<Pair<String, String>> = emptyList()): File = synchronized(lock) {
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        destination.outputStream().buffered().use { output ->
            if (logFile.isFile) logFile.inputStream().use { it.copyTo(output) }
            attachments.forEach { (name, details) -> output.write(formatEvent(name, details).toByteArray(Charsets.UTF_8)) }
        }
        destination
    }

    companion object {
        // 窗口重建时，新旧 Activity 的 IO 任务可能短暂重叠。
        private val lock = Any()
        private fun formatEvent(name: String, details: String): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())
            return "[$stamp] $name\n$details\n\n"
        }
        fun newId() = "hdr-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        fun validId(id: String?) = id?.matches(Regex("hdr-[0-9]+-[a-f0-9]{8}")) == true
    }
}
