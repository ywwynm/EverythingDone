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
 * 骁龙 NPU 运行组件的**独立**下载任务。
 *
 * **为什么必须独立于 [SpatialRuntimeDownloadCoordinator]**：WorkManager 的进度是挂在
 * 「唯一任务名」上的，两个变体共用一个任务名，就只有一份 WorkInfo，UI 无法把进度分别
 * 显示在两行里——实际表现是勾选「骁龙 NPU 加速」之后，下载进度出现在上面的
 * 「推理运行环境」那一行（2026-08-14 用户连续两次指出）。
 *
 * 第一次只拆了存储（`current.json` / `current-qnn.json`），没拆这条管线，所以问题依旧。
 */
object SpatialQnnRuntimeDownloadCoordinator {

    const val UNIQUE_WORK_NAME = "spatial-qnn-runtime-download"

    fun enqueue(context: Context, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<SpatialQnnRuntimeDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
                    )
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("spatial-qnn-runtime")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}

class SpatialQnnRuntimeDownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        // **不能在这里要求查得到 dsp_arch**（D271）：查不到正是全 arch 包要接的那条路，
        // 而设置页早已按"有全 arch 包就可用"放行。两边不一致的后果是用户点了开关、
        // 任务瞬间 failure、界面回到"未下载"且没有任何提示——点了什么都不发生。
        // 能不能下由 catalog 有没有本机可用的条目决定，那个判断在 ensureQnnInstalled 里。
        if (!SpatialQnnSupport.isNpuPossible(applicationContext)) {
            return failure("本机不是受支持的骁龙 NPU 机型")
        }
        if (SpatialRuntimeStore.isVariantInstalled(applicationContext, qnn = true)) {
            return Result.success()
        }
        return try {
            val catalog = SpatialCatalogClient(applicationContext).fetchOrCached().catalog
            SpatialRuntimeInstaller.ensureQnnInstalled(
                context = applicationContext,
                catalog = catalog,
                shouldStop = { isStopped },
                onProgress = { progress ->
                    setProgressAsync(
                        Data.Builder()
                            .putLong(KEY_DOWNLOADED, progress.downloaded)
                            .putLong(KEY_TOTAL, progress.total)
                            .putString(
                                KEY_STATE,
                                when (progress.stage) {
                                    SpatialRuntimeInstaller.Stage.DOWNLOADING -> STATE_DOWNLOADING
                                    SpatialRuntimeInstaller.Stage.VERIFYING -> STATE_VERIFYING
                                    SpatialRuntimeInstaller.Stage.INSTALLING -> STATE_INSTALLING
                                }
                            )
                            .build()
                    )
                }
            )
            // 组件装好的这一刻探一次架构：本机架构还是未知的话，Big-LaMa（NPU 版）取不到
            // 预编译产物、整行不可见。放在这里而不是等用户去生成一张空间照片——那要用户
            // 离开设置页、做一次生成、再回来，中间没有任何东西提示他为什么要这么做。
            SpatialQnnArchProbe.probeIfNeeded(applicationContext)
            Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Throwable) {
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(message: String): Result =
        Result.failure(Data.Builder().putString(KEY_ERROR, message).build())

    companion object {
        const val KEY_DOWNLOADED = "qnn_runtime_downloaded"
        const val KEY_TOTAL = "qnn_runtime_total"
        const val KEY_STATE = "qnn_runtime_state"
        const val KEY_ERROR = "qnn_runtime_error"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_VERIFYING = "verifying"
        const val STATE_INSTALLING = "installing"
    }
}
