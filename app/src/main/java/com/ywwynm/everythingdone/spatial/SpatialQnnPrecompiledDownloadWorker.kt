package com.ywwynm.everythingdone.spatial

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 单独下载某个模型的 NPU 预编译产物。
 *
 * **为什么要独立于模型下载**：NPU 版在设置页是一个**独立选项**，用户可以只装 CPU 版、
 * 也可以两个都装。把它塞进模型下载流程里的话，用户没法单独取用或删除，而且开总开关时
 * 会莫名其妙地多下几百 MB（2026-08-14 用户明确要求拆开）。
 */
object SpatialQnnPrecompiledDownloadCoordinator {

    fun uniqueWorkName(modelId: String): String = "spatial-qnn-precompiled-$modelId"

    fun enqueue(context: Context, modelId: String, modelVersion: String, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<SpatialQnnPrecompiledDownloadWorker>()
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
                    .putString(SpatialQnnPrecompiledDownloadWorker.KEY_MODEL_ID, modelId)
                    .putString(SpatialQnnPrecompiledDownloadWorker.KEY_MODEL_VERSION, modelVersion)
                    .build()
            )
            .addTag("spatial-qnn-precompiled")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueWorkName(modelId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context, modelId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName(modelId))
    }
}

class SpatialQnnPrecompiledDownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return failure("缺少模型标识")
        val modelVersion = inputData.getString(KEY_MODEL_VERSION) ?: return failure("缺少模型版本")
        val dspArch = SpatialQnnSupport.resolveDspArch(applicationContext)
            ?: return failure("本机不是受支持的骁龙 NPU 机型")
        // **这里不能用 isInstalled 早退**：那只问"这个键下有没有东西"，问不出装的是不是
        // catalog 现在下发的那一份。D262 重编 context binary 后模型没升版、键完全一样，
        // 早退会让装着旧产物的设备永远换不到新的（2026-08-14 真机实测卡在这一行）。
        // 判"已经是最新"交给 ensurePrecompiled——它拿得到 catalog，比得了 sha256。
        return try {
            val catalog = SpatialCatalogClient(applicationContext).fetchOrCached().catalog
            // 运行组件必须先就位：预编译产物与其中的 QAIRT 版本严格绑定，
            // 组件没装好时下下来也用不了（D252）。
            if (!SpatialRuntimeStore.isInstalled(applicationContext)) {
                SpatialRuntimeInstaller.ensureSelectedInstalled(
                    context = applicationContext,
                    catalog = catalog,
                    shouldStop = { isStopped },
                    onProgress = { progress ->
                        publish(progress.downloaded, progress.total, STATE_RUNTIME)
                    }
                )
            }
            val ok = SpatialRuntimeInstaller.ensurePrecompiled(
                context = applicationContext,
                catalog = catalog,
                modelId = modelId,
                modelVersion = modelVersion,
                shouldStop = { isStopped },
                onProgress = { progress ->
                    // 阶段必须如实上报：此前一律报 STATE_DOWNLOADING，于是校验 100 MB 的
                    // sha256 和解包那段时间里，界面一直停在"下载中 100%"，看着像卡死
                    // （2026-08-14 用户指出这一行的行为与其它模型不一致）。
                    publish(
                        progress.downloaded,
                        progress.total,
                        when (progress.stage) {
                            SpatialRuntimeInstaller.Stage.DOWNLOADING -> STATE_DOWNLOADING
                            SpatialRuntimeInstaller.Stage.VERIFYING -> STATE_VERIFYING
                            SpatialRuntimeInstaller.Stage.INSTALLING -> STATE_INSTALLING
                        }
                    )
                }
            )
            if (ok) Result.success() else failure("可信目录中没有适用于本机的 NPU 版本")
        } catch (error: IOException) {
            if (isStopped) Result.retry() else Result.retry()
        } catch (error: Throwable) {
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun publish(downloaded: Long, total: Long, state: String) {
        setProgressAsync(
            Data.Builder()
                .putLong(KEY_DOWNLOADED, downloaded)
                .putLong(KEY_TOTAL, total)
                .putString(KEY_STATE, state)
                .build()
        )
    }

    private fun failure(message: String): Result =
        Result.failure(Data.Builder().putString(KEY_ERROR, message).build())

    companion object {
        const val KEY_MODEL_ID = "qnn_precompiled_model_id"
        const val KEY_MODEL_VERSION = "qnn_precompiled_model_version"
        const val KEY_DOWNLOADED = "qnn_precompiled_downloaded"
        const val KEY_TOTAL = "qnn_precompiled_total"
        const val KEY_STATE = "qnn_precompiled_state"
        const val KEY_ERROR = "qnn_precompiled_error"
        const val STATE_RUNTIME = "runtime"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_VERIFYING = "verifying"
        const val STATE_INSTALLING = "installing"
    }
}
