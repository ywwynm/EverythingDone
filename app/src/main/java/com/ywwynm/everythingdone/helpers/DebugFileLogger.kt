package com.ywwynm.everythingdone.helpers

import android.util.Log
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.Executors

object DebugFileLogger {

    private const val TAG = "DebugFileLogger"
    private const val DEBUG_LOG_DIR = "debug_logs"
    private const val MAX_LOG_BYTES = 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor()
    private val sessionStartedFiles: MutableSet<String> = HashSet()

    @JvmStatic
    @JvmOverloads
    fun log(
        fileName: String,
        message: String,
        prefix: String? = null,
        startSession: Boolean = false
    ) {
        executor.execute {
            try {
                val app = App.getApp() ?: return@execute
                val appFileDir = Def.getAppFileDir(app) ?: app.filesDir.absolutePath
                val dir = File(appFileDir, DEBUG_LOG_DIR)
                if (!dir.exists() && !dir.mkdirs()) return@execute

                val file = File(dir, fileName)
                rotateIfNeeded(file)

                FileWriter(file, true).use { writer ->
                    if (startSession && sessionStartedFiles.add(file.absolutePath)) {
                        writer.appendLine()
                        writer.appendLine("==== session ${formatTimestamp()} ====")
                    }
                    writer.appendLine("${formatTimestamp()} ${formatMessage(prefix, message)}")
                }
            } catch (e: IOException) {
                Log.w(TAG, "Unable to write debug log: $fileName", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to write debug log: $fileName", e)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
        val rotated = File(file.parentFile, "${file.name}.old")
        if (rotated.exists()) rotated.delete()
        file.renameTo(rotated)
    }

    private fun formatMessage(prefix: String?, message: String): String {
        return if (prefix.isNullOrBlank()) message else "$prefix $message"
    }

    private fun formatTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }
}
