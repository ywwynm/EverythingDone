package com.ywwynm.everythingdone.spatial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ywwynm.everythingdone.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object SpatialRuntimeDownloadCoordinator {

    const val UNIQUE_WORK_NAME = "spatial-runtime-download"

    fun enqueue(context: Context, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<SpatialRuntimeDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
                    )
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putBoolean(SpatialRuntimeDownloadWorker.KEY_ALLOW_METERED, allowMetered)
                    .build()
            )
            .addTag("spatial-runtime")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        SpatialRuntimeInstaller.deletePartials(context)
    }
}

/**
 * 模型 Worker 与独立运行组件 Worker 共用的事务实现。
 *
 * 整个安装使用对象锁串行化，避免两个模型同时开始下载时重复解包同一 ABI 运行组件。
 */
object SpatialRuntimeInstaller {

    enum class Stage {
        DOWNLOADING,
        VERIFYING,
        INSTALLING
    }

    data class Progress(
        val stage: Stage,
        val downloaded: Long,
        val total: Long
    )

    /**
     * 装**当前选中变体**的运行组件。给模型下载这类"顺带确保运行组件在位"的调用方用。
     *
     * NPU 那一份有自己的入口（[ensureQnnInstalled]）与自己的 Worker——**不能共用**，
     * 否则两者的进度会挤在同一个 WorkInfo 上，UI 只能显示在其中一行里
     * （2026-08-14 用户连续两次指出：勾选 NPU 的下载进度出现在"推理运行环境"那一行）。
     */
    @Synchronized
    fun ensureSelectedInstalled(
        context: Context,
        catalog: SpatialModelCatalog,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        if (SpatialPreferences.qnnEnabled(context)) {
            ensureQnnInstalled(context, catalog, shouldStop, onProgress)
        } else {
            ensureInstalled(context, catalog, shouldStop, onProgress)
        }
    }

    /** 只装 CPU 版。 */
    @Synchronized
    fun ensureInstalled(
        context: Context,
        catalog: SpatialModelCatalog,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): SpatialRuntimeCatalogEntry {
        val entry = catalog.runtimeForCurrentDevice()
            ?: error("可信目录中没有适用于本机 ABI 的空间计算组件")
        check(entry.enabled) { entry.disabledReason ?: "空间计算组件已被目录禁用" }
        if (SpatialRuntimeStore.isVariantInstalled(context, qnn = false)) return entry
        check(!shouldStop()) { "下载已停止" }

        val partial = SpatialRuntimeStore.partialFile(context, entry)
        check(partial.parentFile?.exists() == true || partial.parentFile?.mkdirs() == true)
        if (partial.length() > entry.sizeBytes) check(partial.delete()) {
            "无法重建运行组件临时文件"
        }
        ensureFreeSpace(partial.parentFile!!, entry.sizeBytes, entry.unpackedSizeBytes, partial.length())
        downloadResumable(entry.url, entry.sizeBytes, partial, shouldStop, onProgress)
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.VERIFYING, entry.sizeBytes, entry.sizeBytes))
        check(SpatialModelStore.sha256(partial).equals(entry.sha256, ignoreCase = true)) {
            "空间计算组件 SHA-256 不符"
        }
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.INSTALLING, entry.sizeBytes, entry.sizeBytes))
        SpatialRuntimeStore.installVerified(context, entry, partial)
        check(SpatialRuntimeStore.isVariantInstalled(context, qnn = false)) {
            "空间计算组件安装后校验失败"
        }
        partial.delete()
        return entry
    }

    /** 只装 NPU 版。与 CPU 版在磁盘上共存，互不删除。 */
    @Synchronized
    fun ensureQnnInstalled(
        context: Context,
        catalog: SpatialModelCatalog,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        val dspArch = SpatialQnnSupport.resolveDspArch(context)
            ?: error("本机不是受支持的骁龙 NPU 机型")
        val entry = catalog.qnnRuntimeForCurrentDevice(dspArch)
            ?: error("可信目录中没有适用于本机（$dspArch）的 NPU 运行组件")
        check(entry.enabled) { entry.disabledReason ?: "NPU 运行组件已被目录禁用" }
        if (SpatialRuntimeStore.isVariantInstalled(context, qnn = true)) return
        check(!shouldStop()) { "下载已停止" }

        val partial = SpatialRuntimeStore.partialFileQnn(context, entry)
        check(partial.parentFile?.exists() == true || partial.parentFile?.mkdirs() == true)
        if (partial.length() > entry.sizeBytes) check(partial.delete()) {
            "无法重建 NPU 运行组件临时文件"
        }
        ensureFreeSpace(partial.parentFile!!, entry.sizeBytes, entry.unpackedSizeBytes, partial.length())
        downloadResumable(entry.url, entry.sizeBytes, partial, shouldStop, onProgress)
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.VERIFYING, entry.sizeBytes, entry.sizeBytes))
        check(SpatialModelStore.sha256(partial).equals(entry.sha256, ignoreCase = true)) {
            "NPU 运行组件 SHA-256 不符"
        }
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.INSTALLING, entry.sizeBytes, entry.sizeBytes))
        SpatialRuntimeStore.installVerifiedQnn(context, entry, partial)
        check(SpatialRuntimeStore.isVariantInstalled(context, qnn = true)) {
            "NPU 运行组件安装后校验失败"
        }
        partial.delete()
    }

    /**
     * 下发的 NPU 预编译 context。装在运行组件之后——它对 QAIRT 版本有硬依赖，
     * 运行组件没就位时下下来也用不了。
     *
     * 失败不致命：拿不到就让 [SpatialQnnSessionFactory] 走端上现编或 CPU。
     */
    @Synchronized
    fun ensurePrecompiled(
        context: Context,
        catalog: SpatialModelCatalog,
        modelId: String,
        modelVersion: String,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): Boolean {
        if (!SpatialPreferences.qnnEnabled(context)) return false
        // **不能再问 qnnEnabledFor**（D268）。那个开关的含义是"用户选了 NPU 版"，
        // 而 Big-LaMa／RF-DETR 的默认值是 false（免得一开总开关就被迫用 NPU 版）。
        // 本函数的唯一调用方是用户点下载按钮触发的 worker——**按下按钮本身就是授权**，
        // 在这里再问一遍"选了吗"会形成死锁：
        //   下载要求 qnnEnabledFor=true → 只有选中 NPU 版才会置 true
        //   → 选中要求 npuVariantUsable() → 要求预编译产物已安装 → 要求下载成功
        // 结果是 Big-LaMa（NPU 版）在任何设备上都下不下来，按钮点了没反应，
        // 单选钮永远置灰（2026-08-15 用户在两台真机上复现）。
        val dspArch = SpatialQnnSupport.resolveDspArch(context) ?: return false
        val entry = catalog.qnnPrecompiledFor(modelId, modelVersion, dspArch) ?: return false
        if (!entry.enabled) return false
        // **先比 catalog 再决定要不要下**：键里没有产物内容的信息，同一个键下的 context
        // binary 是会被换掉的（D262 重编后模型没升版）。只问"装过了吗"会让老用户永远
        // 停在旧产物上。装着旧的就先删掉再重下。
        SpatialQnnPrecompiledStore.purgeIfStale(context, entry)
        if (SpatialQnnPrecompiledStore.matchesCatalog(context, entry)) return true
        check(!shouldStop()) { "下载已停止" }

        val partial = SpatialQnnPrecompiledStore.partialFile(context, entry)
        check(partial.parentFile?.exists() == true || partial.parentFile?.mkdirs() == true)
        if (partial.length() > entry.sizeBytes) check(partial.delete()) {
            "无法重建 NPU 预编译临时文件"
        }
        ensureFreeSpace(partial.parentFile!!, entry.sizeBytes, entry.unpackedSizeBytes, partial.length())
        downloadResumable(entry.url, entry.sizeBytes, partial, shouldStop, onProgress)
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.VERIFYING, entry.sizeBytes, entry.sizeBytes))
        check(SpatialModelStore.sha256(partial).equals(entry.sha256, ignoreCase = true)) {
            "NPU 预编译产物 SHA-256 不符"
        }
        onProgress(Progress(Stage.INSTALLING, entry.sizeBytes, entry.sizeBytes))
        SpatialQnnPrecompiledStore.installVerified(context, entry, partial)
        partial.delete()
        // 装完确认的是"装上的就是 catalog 那一份"，不只是"目录里有东西"
        return SpatialQnnPrecompiledStore.matchesCatalog(context, entry)
    }

    fun deletePartials(context: Context) {
        val directory = File(context.noBackupFilesDir, "spatial-photo/downloads")
        directory.listFiles()
            ?.filter {
                (it.name.startsWith("runtime-") || it.name.startsWith("qnn-runtime-") ||
                    it.name.startsWith("qnn-precompiled-")) &&
                    it.name.endsWith(".zip.part")
            }
            ?.forEach { it.delete() }
    }

    private fun downloadResumable(
        url: String,
        sizeBytes: Long,
        partial: File,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        var offset = partial.length()
        onProgress(Progress(Stage.DOWNLOADING, offset, sizeBytes))
        if (offset == sizeBytes) return

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val status = connection.responseCode
            if (status >= 500) throw IOException("运行组件服务器 HTTP $status")
            if (offset > 0L && status == HttpURLConnection.HTTP_OK) {
                check(partial.delete()) { "服务器不支持续传且无法重建运行组件临时文件" }
                offset = 0L
            } else if (offset > 0L) {
                check(status == HttpURLConnection.HTTP_PARTIAL) {
                    "运行组件续传 HTTP $status"
                }
                check(
                    connection.getHeaderField("Content-Range")
                        .orEmpty()
                        .startsWith("bytes $offset-")
                ) {
                    "运行组件续传 Content-Range 不匹配"
                }
            } else {
                check(status == HttpURLConnection.HTTP_OK) {
                    "运行组件下载 HTTP $status"
                }
            }

            FileOutputStream(partial, offset > 0L).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = offset
                    var lastPublished = offset
                    while (true) {
                        if (shouldStop()) throw IOException("下载已停止")
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        check(downloaded <= sizeBytes) {
                            "运行组件响应超过签名字节数"
                        }
                        output.write(buffer, 0, read)
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES) {
                            lastPublished = downloaded
                            onProgress(Progress(Stage.DOWNLOADING, downloaded, sizeBytes))
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() == sizeBytes) {
                "运行组件下载不完整：${partial.length()} / ${sizeBytes}"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureFreeSpace(
        directory: File,
        sizeBytes: Long,
        unpackedSizeBytes: Long,
        existingBytes: Long
    ) {
        val required = (sizeBytes - existingBytes).coerceAtLeast(0L) +
            unpackedSizeBytes +
            MIN_FREE_MARGIN_BYTES
        check(StatFs(directory.absolutePath).availableBytes >= required) {
            "存储空间不足"
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_STEP_BYTES = 1024L * 1024L
    private const val MIN_FREE_MARGIN_BYTES = 32L * 1024L * 1024L
}

class SpatialRuntimeDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters
) : Worker(appContext, parameters) {

    override fun doWork(): Result {
        // 这个 Worker 只负责 CPU 版（NPU 版有自己的 Worker），已装判定必须点名
        // CPU 变体。此前问的是 isInstalled()——它跟随 NPU 总开关指向"当前变体"，
        // 开着 NPU 时问的是 QNN 那份：QNN 装着就立刻返回成功，CPU 版根本没下，
        // 界面毫无动静，表现为"删除后点下载没反应"（2026-08-15 用户实测）。
        if (SpatialRuntimeStore.isVariantInstalled(applicationContext, qnn = false)) {
            return Result.success(readyData())
        }
        return try {
            setForegroundAsync(foregroundInfo(0L, 0L, STATE_CATALOG)).get()
            val catalog = SpatialCatalogClient(applicationContext).fetchOrCached().catalog
            SpatialRuntimeInstaller.ensureInstalled(
                context = applicationContext,
                catalog = catalog,
                shouldStop = { isStopped },
                onProgress = { progress ->
                    val state = when (progress.stage) {
                        SpatialRuntimeInstaller.Stage.DOWNLOADING -> STATE_DOWNLOADING
                        SpatialRuntimeInstaller.Stage.VERIFYING -> STATE_VERIFYING
                        SpatialRuntimeInstaller.Stage.INSTALLING -> STATE_INSTALLING
                    }
                    val data = progressData(progress.downloaded, progress.total, state)
                    setProgressAsync(data)
                    setForegroundAsync(
                        foregroundInfo(progress.downloaded, progress.total, state)
                    ).get()
                }
            )
            if (isStopped) Result.retry() else Result.success(readyData())
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Throwable) {
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun foregroundInfo(
        downloaded: Long,
        total: Long,
        state: String
    ): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.spatial_download_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val cancelPending = PendingIntent.getBroadcast(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, SpatialRuntimeDownloadCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = if (total <= 0L) 0 else ((downloaded * 100L) / total).toInt()
        val content = when (state) {
            STATE_CATALOG -> applicationContext.getString(R.string.spatial_catalog_loading)
            STATE_VERIFYING -> applicationContext.getString(R.string.spatial_runtime_verifying)
            STATE_INSTALLING -> applicationContext.getString(R.string.spatial_runtime_installing)
            else -> applicationContext.getString(R.string.spatial_download_progress, percent)
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_spatial_effect)
            .setContentTitle(applicationContext.getString(R.string.spatial_runtime_title))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, state != STATE_DOWNLOADING)
            .addAction(0, applicationContext.getString(R.string.cancel), cancelPending)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun failure(message: String): Result =
        Result.failure(Data.Builder().putString(KEY_ERROR, message).build())

    private fun readyData(): Data =
        Data.Builder().putString(KEY_STATE, STATE_READY).build()

    companion object {
        const val KEY_ALLOW_METERED = "allow_metered"
        const val KEY_DOWNLOADED = "runtime_downloaded"
        const val KEY_TOTAL = "runtime_total"
        const val KEY_STATE = "runtime_state"
        const val KEY_ERROR = "runtime_error"

        const val STATE_CATALOG = "runtime_catalog"
        const val STATE_DOWNLOADING = "runtime_downloading"
        const val STATE_VERIFYING = "runtime_verifying"
        const val STATE_INSTALLING = "runtime_installing"
        const val STATE_READY = "runtime_ready"

        private const val NOTIFICATION_CHANNEL = "spatial_model_downloads"
        private const val NOTIFICATION_ID = 18_390

        fun progressData(downloaded: Long, total: Long, state: String): Data =
            Data.Builder()
                .putLong(KEY_DOWNLOADED, downloaded)
                .putLong(KEY_TOTAL, total)
                .putString(KEY_STATE, state)
                .build()
    }
}

class SpatialRuntimeDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpatialRuntimeDownloadCoordinator.cancel(context)
    }
}
