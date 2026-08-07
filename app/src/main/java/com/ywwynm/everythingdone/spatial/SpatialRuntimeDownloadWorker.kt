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
        if (SpatialRuntimeStore.isInstalled(context)) return entry
        check(!shouldStop()) { "下载已停止" }

        val partial = SpatialRuntimeStore.partialFile(context, entry)
        check(partial.parentFile?.exists() == true || partial.parentFile?.mkdirs() == true)
        if (partial.length() > entry.sizeBytes) check(partial.delete()) {
            "无法重建运行组件临时文件"
        }
        ensureFreeSpace(partial.parentFile!!, entry, partial.length())
        downloadResumable(entry, partial, shouldStop, onProgress)
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.VERIFYING, entry.sizeBytes, entry.sizeBytes))
        check(SpatialModelStore.sha256(partial).equals(entry.sha256, ignoreCase = true)) {
            "空间计算组件 SHA-256 不符"
        }
        check(!shouldStop()) { "下载已停止" }

        onProgress(Progress(Stage.INSTALLING, entry.sizeBytes, entry.sizeBytes))
        SpatialRuntimeStore.installVerified(context, entry, partial)
        check(SpatialRuntimeStore.isInstalled(context)) { "空间计算组件安装后校验失败" }
        partial.delete()
        return entry
    }

    fun deletePartials(context: Context) {
        val directory = File(context.noBackupFilesDir, "spatial-photo/downloads")
        directory.listFiles()
            ?.filter { it.name.startsWith("runtime-") && it.name.endsWith(".zip.part") }
            ?.forEach { it.delete() }
    }

    private fun downloadResumable(
        entry: SpatialRuntimeCatalogEntry,
        partial: File,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        var offset = partial.length()
        onProgress(Progress(Stage.DOWNLOADING, offset, entry.sizeBytes))
        if (offset == entry.sizeBytes) return

        val connection = URL(entry.url).openConnection() as HttpURLConnection
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
                        check(downloaded <= entry.sizeBytes) {
                            "运行组件响应超过签名字节数"
                        }
                        output.write(buffer, 0, read)
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES) {
                            lastPublished = downloaded
                            onProgress(Progress(Stage.DOWNLOADING, downloaded, entry.sizeBytes))
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() == entry.sizeBytes) {
                "运行组件下载不完整：${partial.length()} / ${entry.sizeBytes}"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureFreeSpace(
        directory: File,
        entry: SpatialRuntimeCatalogEntry,
        existingBytes: Long
    ) {
        val required = (entry.sizeBytes - existingBytes).coerceAtLeast(0L) +
            entry.unpackedSizeBytes +
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
        if (SpatialRuntimeStore.isInstalled(applicationContext)) {
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
