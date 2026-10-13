package com.ywwynm.everythingdone.taptransfer.diagnostics

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.ywwynm.everythingdone.R
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 用户点击后导出当前诊断快照；只打开系统分享面板，由用户选择去向。 */
internal object TapDiagnosticsSharer {
    private val executor by lazy { Executors.newSingleThreadExecutor() }

    fun share(activity: FragmentActivity) {
        if (!TapDiagnostics.enabled) return
        val context = activity.applicationContext
        val report = TapDiagnostics.report(context)
        val host = WeakReference(activity)
        executor.execute {
            val file = try {
                val directory = File(context.cacheDir, "tap-diagnostics")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建诊断缓存目录")
                val now = System.currentTimeMillis()
                directory.listFiles()?.filter {
                    it.isFile && it.name.startsWith("TapDiagnostics-") && it.extension == "txt" &&
                        now - it.lastModified() > TimeUnit.DAYS.toMillis(7)
                }?.forEach { if (!it.delete()) Log.w("TapDiagnostics", "未能清理过期诊断文件") }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
                File.createTempFile("TapDiagnostics-$stamp-", ".txt", directory).also {
                    it.writeText(report, Charsets.UTF_8)
                }
            } catch (e: IOException) {
                Log.w("TapDiagnostics", "导出诊断文件失败", e)
                null
            }
            val current = host.get() ?: return@execute
            current.runOnUiThread {
                if (current.isFinishing || current.isDestroyed) return@runOnUiThread
                if (file == null) {
                    showFailure(current)
                    return@runOnUiThread
                }
                try {
                    val uri = FileProvider.getUriForFile(current, current.packageName, file)
                    val title = current.getString(R.string.tap_diagnostic_title)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(current.contentResolver, title, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    current.startActivity(Intent.createChooser(intent, title))
                } catch (e: ActivityNotFoundException) {
                    Log.w("TapDiagnostics", "没有可接收诊断文件的应用", e)
                    showFailure(current)
                } catch (e: IllegalArgumentException) {
                    Log.w("TapDiagnostics", "诊断文件分享路径无效", e)
                    showFailure(current)
                } catch (e: SecurityException) {
                    Log.w("TapDiagnostics", "系统拒绝分享诊断文件", e)
                    showFailure(current)
                }
            }
        }
    }

    private fun showFailure(activity: FragmentActivity) {
        Toast.makeText(activity, R.string.tap_diagnostic_share_failed, Toast.LENGTH_LONG).show()
    }
}
