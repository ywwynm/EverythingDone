package com.ywwynm.everythingdone.hdr

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/** 常规 debug 不记录；仅显式启用的回归仪器临时记录或覆盖环境。 */
internal object HdrViewerDiagnostics {
    @Volatile var forceCompatibility = false
    @Volatile var recordingEnabled = false
    private val io by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "hdr-viewer-journal") }
    }
    private val lock = Any()

    fun event(context: Context, name: String, details: String) {
        if (!recordingEnabled) return
        val app = context.applicationContext
        Log.d("HdrViewer", "$name $details")
        io.execute {
            runCatching { synchronized(lock) {
                val file = File(app.filesDir, "hdr-diagnostics/viewer.txt")
                file.parentFile?.mkdirs()
                // 限制常驻日志体积；不保存图片、原始路径或记事内容。
                if (file.length() > 1024 * 1024) file.writeText(file.readText().takeLast(512 * 1024))
                file.appendText("[${System.currentTimeMillis()}] $name\n$details\n\n")
            } }
        }
    }

    fun appendSnapshot(context: Context, destination: File) {
        if (!recordingEnabled) return
        // 和事件写入共用队列，确保进入诊断页前的查看器事件已落盘。
        io.submit { synchronized(lock) {
            val file = File(context.filesDir, "hdr-diagnostics/viewer.txt")
            if (file.isFile) destination.appendText("\n===== ImageViewerActivity 诊断 =====\n${file.readText()}")
        } }.get()
    }
}
