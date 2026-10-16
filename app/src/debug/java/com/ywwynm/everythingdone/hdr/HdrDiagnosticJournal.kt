package com.ywwynm.everythingdone.hdr

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 每条事件立即落盘；进程退出后再次进入仍可直接导出，不依赖 logcat。 */
internal class HdrDiagnosticJournal(val directory: File) {
    val logFile = File(directory, "hdr-diagnostics.txt")

    init { check(directory.isDirectory || directory.mkdirs()) }

    fun event(name: String, details: String = "") = synchronized(lock) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())
        logFile.appendText("[$stamp] $name\n$details\n\n", Charsets.UTF_8)
    }

    fun snapshot(destination: File): File = synchronized(lock) {
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        logFile.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        destination
    }

    companion object {
        // 窗口重建时，新旧 Activity 的 IO 任务可能短暂重叠。
        private val lock = Any()
        fun newId() = "hdr-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        fun validId(id: String?) = id?.matches(Regex("hdr-[0-9]+-[a-f0-9]{8}")) == true
    }
}
