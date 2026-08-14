package com.ywwynm.everythingdone.spatial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

object SpatialMattingDownloadCoordinator {

    fun enqueue(
        context: Context,
        model: SpatialMattingModel,
        allowMetered: Boolean
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            )
            .build()
        val request = OneTimeWorkRequestBuilder<SpatialMattingDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(SpatialMattingDownloadWorker.KEY_MODEL_ID, model.stableId)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueWorkName(model),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context, model: SpatialMattingModel) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName(model))
        SpatialMattingDownloadWorker.partialFile(context, model).delete()
    }

    fun uniqueWorkName(model: SpatialMattingModel): String =
        "spatial-matting-download-${model.stableId}"
}

class SpatialMattingDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters
) : Worker(appContext, parameters) {

    override fun doWork(): Result {
        val model = SpatialMattingModel.fromStableId(
            inputData.getString(KEY_MODEL_ID)
        ) ?: return failure("未知 matting 模型")
        val wasInstalled =
            SpatialMattingModelStore.isInstalled(applicationContext, model)
        if (!SpatialMattingModelStore.isDeviceEligible(applicationContext, model)) {
            return failure("设备内存等级不足")
        }
        if (wasInstalled && SpatialRuntimeStore.isInstalled(applicationContext)) {
            return Result.success()
        }
        return try {
            setForegroundAsync(foregroundInfo(model, 0L, model.sizeBytes)).get()
            val catalog = SpatialCatalogClient(applicationContext).fetchOrCached().catalog
            if (!SpatialRuntimeStore.isInstalled(applicationContext)) {
                SpatialRuntimeInstaller.ensureSelectedInstalled(
                    context = applicationContext,
                    catalog = catalog,
                    shouldStop = { isStopped },
                    onProgress = { progress ->
                        val state = when (progress.stage) {
                            SpatialRuntimeInstaller.Stage.DOWNLOADING ->
                                STATE_RUNTIME_DOWNLOADING
                            SpatialRuntimeInstaller.Stage.VERIFYING ->
                                STATE_RUNTIME_VERIFYING
                            SpatialRuntimeInstaller.Stage.INSTALLING ->
                                STATE_RUNTIME_INSTALLING
                        }
                        publishProgress(
                            model,
                            progress.downloaded,
                            progress.total,
                            state
                        )
                    }
                )
            }
            if (wasInstalled) return Result.success()

            val entry = catalog.mattingModels.orEmpty()
                .firstOrNull { it.id == model.stableId }
                ?: return failure("可信目录中没有 matting 模型")
            if (!entry.enabled) {
                return failure(entry.disabledReason ?: "matting 模型已被目录禁用")
            }
            check(entry.builtInModel() == model) { "matting 模型目录与 App ABI 不一致" }
            val partial = partialFile(applicationContext, model)
            check(partial.parentFile?.exists() == true || partial.parentFile?.mkdirs() == true)
            ensureFreeSpace(partial.parentFile!!, model, partial.length())
            downloadResumable(entry, model, partial)
            if (isStopped) return Result.retry()

            publishProgress(model, model.sizeBytes, model.sizeBytes, STATE_VERIFYING)
            SpatialMattingModelStore.installVerified(
                applicationContext,
                model,
                partial,
                markReady = false
            )
            if (isStopped) {
                SpatialMattingModelStore.delete(applicationContext, model)
                return Result.retry()
            }
            publishProgress(model, model.sizeBytes, model.sizeBytes, STATE_SELF_TEST)
            runSelfTest { SpatialMattingEngine(applicationContext).selfTest(model) }
            if (isStopped) {
                SpatialMattingModelStore.delete(applicationContext, model)
                return Result.retry()
            }
            SpatialMattingModelStore.writeReadyMarker(applicationContext, model)
            partial.delete()
            Result.success()
        } catch (error: SelfTestException) {
            if (!wasInstalled) {
                SpatialMattingModelStore.delete(applicationContext, model)
            }
            failure(error.message ?: error.javaClass.simpleName, ERROR_STAGE_SELF_TEST)
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Throwable) {
            if (!wasInstalled) {
                SpatialMattingModelStore.delete(applicationContext, model)
            }
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun downloadResumable(
        entry: SpatialMattingCatalogEntry,
        model: SpatialMattingModel,
        partial: File
    ) {
        if (partial.length() > model.sizeBytes) check(partial.delete())
        var offset = partial.length()
        if (offset == model.sizeBytes) return
        val connection = URL(entry.url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val status = connection.responseCode
            if (status >= 500) throw IOException("模型服务器 HTTP $status")
            if (offset > 0L && status == HttpURLConnection.HTTP_OK) {
                check(partial.delete()) { "服务器不支持续传且无法重建临时文件" }
                offset = 0L
            } else if (offset > 0L) {
                check(status == HttpURLConnection.HTTP_PARTIAL) { "续传 HTTP $status" }
                check(
                    connection.getHeaderField("Content-Range")
                        .orEmpty()
                        .startsWith("bytes $offset-")
                ) { "续传 Content-Range 不匹配" }
            } else {
                check(status == HttpURLConnection.HTTP_OK) { "模型下载 HTTP $status" }
            }
            FileOutputStream(partial, offset > 0L).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = offset
                    var lastPublished = offset
                    while (true) {
                        if (isStopped) throw IOException("下载已停止")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        check(downloaded <= model.sizeBytes) {
                            "模型响应超过签名字节数"
                        }
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES) {
                            lastPublished = downloaded
                            publishProgress(
                                model,
                                downloaded,
                                model.sizeBytes,
                                STATE_DOWNLOADING
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() == model.sizeBytes) { "matting 模型下载不完整" }
        } finally {
            connection.disconnect()
        }
    }

    private fun publishProgress(
        model: SpatialMattingModel,
        downloaded: Long,
        total: Long,
        state: String
    ) {
        setProgressAsync(
            Data.Builder()
                .putString(KEY_MODEL_ID, model.stableId)
                .putLong(KEY_DOWNLOADED, downloaded)
                .putLong(KEY_TOTAL, total)
                .putString(KEY_STATE, state)
                .build()
        )
        setForegroundAsync(foregroundInfo(model, downloaded, total, state)).get()
    }

    private fun foregroundInfo(
        model: SpatialMattingModel,
        downloaded: Long,
        total: Long,
        state: String = STATE_DOWNLOADING
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.spatial_download_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val percent = if (total <= 0L) 0 else ((downloaded * 100L) / total).toInt()
        val content = when (state) {
            STATE_RUNTIME_VERIFYING ->
                applicationContext.getString(R.string.spatial_runtime_verifying)
            STATE_RUNTIME_INSTALLING ->
                applicationContext.getString(R.string.spatial_runtime_installing)
            STATE_VERIFYING ->
                applicationContext.getString(R.string.spatial_download_verifying)
            STATE_SELF_TEST ->
                applicationContext.getString(R.string.spatial_download_self_test)
            else -> applicationContext.getString(
                R.string.spatial_download_progress,
                percent
            )
        }
        val notification = NotificationCompat.Builder(
            applicationContext,
            NOTIFICATION_CHANNEL
        )
            .setSmallIcon(R.drawable.ic_spatial_effect)
            .setContentTitle(
                applicationContext.getString(
                    R.string.spatial_download_title,
                    model.displayName
                )
            )
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, state != STATE_DOWNLOADING)
            .addAction(
                0,
                applicationContext.getString(R.string.cancel),
                WorkManager.getInstance(applicationContext)
                    .createCancelPendingIntent(id)
            )
            .build()
        val notificationId = NOTIFICATION_ID_BASE + model.ordinal
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureFreeSpace(
        directory: File,
        model: SpatialMattingModel,
        existingBytes: Long
    ) {
        val required = (model.sizeBytes - existingBytes).coerceAtLeast(0L) +
            model.sizeBytes +
            MIN_FREE_MARGIN_BYTES
        check(StatFs(directory.absolutePath).availableBytes >= required) {
            "存储空间不足"
        }
    }

    private fun failure(message: String, stage: String? = null): Result = Result.failure(
        Data.Builder()
            .putString(KEY_ERROR, message)
            .apply { if (stage != null) putString(KEY_ERROR_STAGE, stage) }
            .build()
    )

    /** 自检失败必须与下载/校验失败区分：字节已完整落地，问题在真机模型初始化。 */
    private class SelfTestException(message: String) : RuntimeException(message)

    private fun runSelfTest(test: () -> Boolean) {
        val passed = try {
            test()
        } catch (error: Throwable) {
            throw SelfTestException(error.message ?: error.javaClass.simpleName)
        }
        if (!passed) throw SelfTestException("模型输出无效")
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_STATE = "state"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_STAGE = "error_stage"

        const val ERROR_STAGE_SELF_TEST = "self_test"

        const val STATE_DOWNLOADING = "downloading"
        const val STATE_VERIFYING = "verifying"
        const val STATE_SELF_TEST = "self_test"
        const val STATE_RUNTIME_DOWNLOADING = "runtime_downloading"
        const val STATE_RUNTIME_VERIFYING = "runtime_verifying"
        const val STATE_RUNTIME_INSTALLING = "runtime_installing"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_STEP_BYTES = 4L * 1024L * 1024L
        private const val MIN_FREE_MARGIN_BYTES = 32L * 1024L * 1024L
        private const val NOTIFICATION_CHANNEL = "spatial_model_downloads"
        private const val NOTIFICATION_ID_BASE = 18_460

        fun partialFile(
            context: Context,
            model: SpatialMattingModel
        ): File = File(
            context.noBackupFilesDir,
            "spatial-photo/downloads/${model.stableId}-${model.version}.part"
        )
    }
}
