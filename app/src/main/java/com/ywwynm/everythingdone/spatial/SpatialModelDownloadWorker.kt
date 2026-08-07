package com.ywwynm.everythingdone.spatial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

object SpatialModelDownloadCoordinator {

    fun enqueue(
        context: Context,
        model: SpatialDepthModel,
        allowMetered: Boolean
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            )
            .build()
        val request = OneTimeWorkRequestBuilder<SpatialModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(SpatialModelDownloadWorker.KEY_MODEL_ID, model.stableId)
                    .putBoolean(SpatialModelDownloadWorker.KEY_ALLOW_METERED, allowMetered)
                    .build()
            )
            .addTag(tag(model))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueWorkName(model),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context, model: SpatialDepthModel) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName(model))
        SpatialModelDownloadWorker.partialFile(context, model).delete()
    }

    fun uniqueWorkName(model: SpatialDepthModel): String =
        "spatial-model-download-${model.stableId}"

    fun tag(model: SpatialDepthModel): String =
        "spatial-model-${model.stableId}"
}

class SpatialModelDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters
) : Worker(appContext, parameters) {

    override fun doWork(): Result {
        val model = SpatialDepthModel.fromStableId(inputData.getString(KEY_MODEL_ID))
            ?: return failure("未知模型")
        val modelWasInstalled = SpatialModelStore.isInstalled(applicationContext, model)
        if (!SpatialModelStore.isDeviceEligible(applicationContext, model)) {
            return failure("设备内存等级不足")
        }
        if (modelWasInstalled && SpatialRuntimeStore.isInstalled(applicationContext)) {
            return Result.success(successData(model))
        }

        return try {
            setForegroundAsync(foregroundInfo(model, 0L, model.sizeBytes)).get()
            val catalog = SpatialCatalogClient(applicationContext).fetchOrCached().catalog
            if (!SpatialRuntimeStore.isInstalled(applicationContext)) {
                SpatialRuntimeInstaller.ensureInstalled(
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
                        setProgressAsync(
                            progressData(model, progress.downloaded, progress.total, state)
                        )
                        setForegroundAsync(
                            foregroundInfo(
                                model = model,
                                downloaded = progress.downloaded,
                                total = progress.total,
                                state = state,
                                componentName = applicationContext.getString(
                                    R.string.spatial_runtime_title
                                )
                            )
                        ).get()
                    }
                )
            }
            if (modelWasInstalled) return Result.success(successData(model))

            val entry = catalog.models.firstOrNull { it.id == model.stableId }
                ?: return failure("可信目录中没有该模型")
            if (!entry.enabled) {
                return failure(entry.disabledReason ?: "该模型已被目录禁用")
            }
            check(entry.builtInModel() == model) { "模型目录与 App ABI 不一致" }

            val partial = partialFile(applicationContext, model)
            check(partial.parentFile?.exists() == true || partial.parentFile?.mkdirs() == true)
            ensureFreeSpace(partial.parentFile!!, model, partial.length())
            downloadResumable(entry, model, partial)
            if (isStopped) return Result.retry()

            setProgressAsync(progressData(model, model.sizeBytes, model.sizeBytes, STATE_VERIFYING))
            setForegroundAsync(
                foregroundInfo(model, model.sizeBytes, model.sizeBytes, STATE_VERIFYING)
            ).get()
            SpatialModelStore.installVerified(
                applicationContext,
                model,
                partial,
                markReady = false
            )
            if (isStopped) {
                SpatialModelStore.delete(applicationContext, model)
                return Result.retry()
            }
            setProgressAsync(progressData(model, model.sizeBytes, model.sizeBytes, STATE_SELF_TEST))
            setForegroundAsync(
                foregroundInfo(model, model.sizeBytes, model.sizeBytes, STATE_SELF_TEST)
            ).get()
            runSelfTest { SpatialDepthEngine(applicationContext).selfTest(model) }
            if (isStopped) {
                SpatialModelStore.delete(applicationContext, model)
                return Result.retry()
            }
            SpatialModelStore.writeReadyMarker(applicationContext, model)
            if (isStopped) {
                SpatialModelStore.delete(applicationContext, model)
                return Result.retry()
            }
            partial.delete()
            Result.success(successData(model))
        } catch (error: SelfTestException) {
            if (!modelWasInstalled) SpatialModelStore.delete(applicationContext, model)
            failure(error.message ?: error.javaClass.simpleName, ERROR_STAGE_SELF_TEST)
        } catch (error: IOException) {
            if (isStopped) Result.retry() else Result.retry()
        } catch (error: Throwable) {
            if (!modelWasInstalled) SpatialModelStore.delete(applicationContext, model)
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun downloadResumable(
        entry: SpatialModelCatalogEntry,
        model: SpatialDepthModel,
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
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                check(contentRange.startsWith("bytes $offset-")) { "续传 Content-Range 不匹配" }
            } else {
                check(status == HttpURLConnection.HTTP_OK) { "模型下载 HTTP $status" }
            }

            val append = offset > 0L
            FileOutputStream(partial, append).use { output ->
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
                        check(downloaded <= model.sizeBytes) { "模型响应超过签名字节数" }
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES) {
                            lastPublished = downloaded
                            setProgressAsync(
                                progressData(model, downloaded, model.sizeBytes, STATE_DOWNLOADING)
                            )
                            setForegroundAsync(
                                foregroundInfo(
                                    model,
                                    downloaded,
                                    model.sizeBytes,
                                    STATE_DOWNLOADING
                                )
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() == model.sizeBytes) {
                "模型下载不完整：${partial.length()} / ${model.sizeBytes}"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureFreeSpace(directory: File, model: SpatialDepthModel, existingBytes: Long) {
        val available = StatFs(directory.absolutePath).availableBytes
        // 下载剩余字节 + 原子安装副本 + 32 MiB 安全余量。
        val required = (model.sizeBytes - existingBytes).coerceAtLeast(0L) +
            model.sizeBytes +
            MIN_FREE_MARGIN_BYTES
        check(available >= required) { "存储空间不足" }
    }

    private fun foregroundInfo(
        model: SpatialDepthModel,
        downloaded: Long,
        total: Long,
        state: String = STATE_DOWNLOADING,
        componentName: String = model.displayName
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
        val cancelIntent = Intent(
            applicationContext,
            SpatialModelDownloadCancelReceiver::class.java
        ).putExtra(KEY_MODEL_ID, model.stableId)
        val cancelPending = PendingIntent.getBroadcast(
            applicationContext,
            model.ordinal + 8000,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = if (total <= 0L) 0 else ((downloaded * 100L) / total).toInt()
        val content = when (state) {
            STATE_RUNTIME_VERIFYING ->
                applicationContext.getString(R.string.spatial_runtime_verifying)
            STATE_RUNTIME_INSTALLING ->
                applicationContext.getString(R.string.spatial_runtime_installing)
            STATE_VERIFYING -> applicationContext.getString(R.string.spatial_download_verifying)
            STATE_SELF_TEST -> applicationContext.getString(R.string.spatial_download_self_test)
            else -> applicationContext.getString(
                R.string.spatial_download_progress,
                percent
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_spatial_effect)
            .setContentTitle(
                applicationContext.getString(
                    R.string.spatial_download_title,
                    componentName
                )
            )
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, state != STATE_DOWNLOADING)
            .addAction(0, applicationContext.getString(R.string.cancel), cancelPending)
            .build()
        val id = NOTIFICATION_ID_BASE + model.ordinal
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun failure(message: String, stage: String? = null): Result =
        Result.failure(
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

    private fun successData(model: SpatialDepthModel): Data =
        Data.Builder()
            .putString(KEY_MODEL_ID, model.stableId)
            .putString(KEY_STATE, STATE_READY)
            .build()

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_ALLOW_METERED = "allow_metered"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_STATE = "state"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_STAGE = "error_stage"

        const val ERROR_STAGE_SELF_TEST = "self_test"

        const val STATE_DOWNLOADING = "downloading"
        const val STATE_VERIFYING = "verifying"
        const val STATE_SELF_TEST = "self_test"
        const val STATE_READY = "ready"
        const val STATE_RUNTIME_DOWNLOADING = "runtime_downloading"
        const val STATE_RUNTIME_VERIFYING = "runtime_verifying"
        const val STATE_RUNTIME_INSTALLING = "runtime_installing"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_STEP_BYTES = 4L * 1024L * 1024L
        private const val MIN_FREE_MARGIN_BYTES = 32L * 1024L * 1024L
        private const val NOTIFICATION_CHANNEL = "spatial_model_downloads"
        private const val NOTIFICATION_ID_BASE = 18_400

        fun partialFile(context: Context, model: SpatialDepthModel): File =
            File(
                context.noBackupFilesDir,
                "spatial-photo/downloads/${model.stableId}-${model.version}.part"
            )

        private fun progressData(
            model: SpatialDepthModel,
            downloaded: Long,
            total: Long,
            state: String
        ): Data = Data.Builder()
            .putString(KEY_MODEL_ID, model.stableId)
            .putLong(KEY_DOWNLOADED, downloaded)
            .putLong(KEY_TOTAL, total)
            .putString(KEY_STATE, state)
            .build()
    }
}

class SpatialModelDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val model = SpatialDepthModel.fromStableId(
            intent.getStringExtra(SpatialModelDownloadWorker.KEY_MODEL_ID)
        ) ?: return
        SpatialModelDownloadCoordinator.cancel(context, model)
    }
}
