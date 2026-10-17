package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import java.io.IOException

/** 只管理本功能的数值诊断；调用者须使用 DebugFileLogger 的同一 IO 队列。 */
internal class FableSolDiagnosticLogFiles(private val directory: File) {
    private val versionFile = File(directory, "fablesol-diagnostics-version.txt")

    fun ensureVersion(version: String): Boolean {
        if (versionFile.isFile && versionFile.readText() == version) return false
        clear(version)
        return true
    }

    fun clear(version: String) {
        check(directory.isDirectory || directory.mkdirs())
        for (name in FILES) for (suffix in listOf("", ".old")) {
            val file = File(directory, name + suffix)
            if (file.exists() && !file.delete()) throw IOException("无法清理诊断文件：${file.name}")
        }
        // 只有清理成功才记录版本；进程中断时允许下次重试。
        versionFile.writeText(version, Charsets.UTF_8)
    }

    companion object {
        val FILES = listOf("fablesol_hdr.log", "fablesol_dialog_dim.log", "fablesol_direct.log",
            "fablesol_frame_perf.log", "fablesol_composition_perf.log")
    }
}
