package com.ywwynm.everythingdone.views.recording.fablesol

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity
import com.ywwynm.everythingdone.fragments.FableSolExportProgressDialogFragment
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.services.FableSolVideoExportService
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 两个入口共用的导出发起点：录音对话框的「保存并导出视频」FAB 与播放对话框的「导出视频」
 * 按钮（fablesol-video-export D14）。
 *
 * 点下立即开始，并弹出进度对话框。**对话框只是观察者**——导出全程都在前台服务里跑、
 * 通知栏也一直有通知，「在后台运行」只是把这个对话框关掉。
 */
object FableSolVideoExportLauncher {

    /**
     * GLES 是否可用。离线渲染同样依赖 GLES，所以 Canvas 回退设备上入口应当**直接不出现**，
     * 而不是点了才失败。
     */
    @JvmStatic
    fun isSupported(host: WaveVisualizerFableSolHost?): Boolean = host?.isGlActive() ?: false

    @JvmStatic
    fun launch(
        activity: Activity,
        audioPath: String,
        water: ThingBackground,
        accent: ThingBackground,
        host: WaveVisualizerFableSolHost?
    ) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissionHost = activity as? EverythingDoneBaseActivity ?: return
            permissionHost.doWithPermissionChecked(
                object : SimplePermissionCallback(permissionHost) {
                    override fun onGranted() {
                        if (!permissionHost.isFinishing && !permissionHost.isDestroyed) {
                            launchGranted(
                                permissionHost, audioPath, water, accent, host
                            )
                        }
                    }
                },
                Def.Communication.REQUEST_PERMISSION_EXPORT_VIDEO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            return
        }
        launchGranted(activity, audioPath, water, accent, host)
    }

    private fun launchGranted(
        activity: Activity,
        audioPath: String,
        water: ThingBackground,
        accent: ThingBackground,
        host: WaveVisualizerFableSolHost?
    ) {
        val density = activity.resources.displayMetrics.density.toDouble()
        val widthPx = host?.width ?: 0
        val cardWidthDp = if (widthPx > 0 && density > 0.0) widthPx / density else 280.0
        // 每次导出一个 id：两个对话框可能同时在听同一条总线，没有 id 就会互相串状态。
        val jobId = FableSolVideoExportBus.newJobId()
        try {
            FableSolVideoExportService.start(
                activity.applicationContext,
                jobId,
                audioPath,
                displayName(audioPath, jobId),
                water,
                accent,
                cardWidthDp
            )
        } catch (error: Throwable) {
            FableSolVideoExportBus.post(
                FableSolVideoExportBus.State.Failed(
                    jobId, error.message ?: error.javaClass.simpleName
                )
            )
        }
        val fragmentHost = activity as? FragmentActivity ?: return
        if (fragmentHost.isFinishing || fragmentHost.isDestroyed) return
        val manager = fragmentHost.supportFragmentManager
        if (manager.isStateSaved) return
        val tag = FableSolExportProgressDialogFragment.tagFor(jobId)
        if (manager.findFragmentByTag(tag) != null) return
        FableSolExportProgressDialogFragment.newInstance(jobId)
            .show(manager, tag)
    }

    /**
     * 完成态的对话框在导出结束后还开着才有意义；这里只是给调用方一个查询入口，
     * 真正的状态由 [FableSolVideoExportBus] 持有。
     */
    @JvmStatic
    fun isExporting(): Boolean =
        FableSolVideoExportBus.hasActiveJobs()

    private fun displayName(audioPath: String, jobId: Long): String {
        val rawBase = File(audioPath).nameWithoutExtension
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .ifBlank { "FableSol" }
        // ext4/f2fs 通常限制单个文件名 255 UTF-8 bytes；原附件名已可能贴近上限，必须给
        // 时间戳、jobId 和旧系统的冲突后缀留空间。
        val base = truncateUtf8(rawBase, 160)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "${base}_${stamp}_$jobId.mp4"
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val result = StringBuilder(value.length)
        var byteCount = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = String(Character.toChars(codePoint))
            val bytes = chars.toByteArray(StandardCharsets.UTF_8).size
            if (byteCount + bytes > maxBytes) break
            result.appendCodePoint(codePoint)
            byteCount += bytes
            index += Character.charCount(codePoint)
        }
        return result.toString().ifBlank { "FableSol" }
    }
}
