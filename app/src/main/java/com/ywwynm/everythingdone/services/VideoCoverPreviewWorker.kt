package com.ywwynm.everythingdone.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo

import androidx.work.Worker
import androidx.work.WorkerParameters

import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.helpers.CoverGenerationState
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * 在后台为视频封面生成 Thing Card Video Preview（派生 GIF）。用 WorkManager 跑，因此持久化、
 * 扛进程被杀、进程重启后恢复、失败可重试。见 ADR-0012 与 docs/features/animated-video-cover/。
 *
 * 按"视频+帧"定唯一 work 名（[VideoCoverPreviewManager] 的缓存 key）、`ExistingWorkPolicy.KEEP`
 * 去重，因此各种 UI 变化无需特殊处理：
 * - 封面中途从视频 A 改成图片 B / 自动变成新增图片 C：A 的生成不被取消、跑完留作备用；
 * - 视频 A 被删除：文件不存在，优雅失败、不再重试。
 */
class VideoCoverPreviewWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val videoPath = inputData.getString(KEY_VIDEO_PATH) ?: return Result.failure()
        val frameRaw = inputData.getLong(KEY_VIDEO_FRAME_MS, -1L)
        val videoFrameMs = if (frameRaw < 0L) null else frameRaw
        // 缓存 key（=唯一 work 名）随 inputData 传入：视频被删后无法重算 key，失败通知仍需它。
        val outputName = inputData.getString(KEY_OUTPUT_NAME)

        VideoCoverPreviewManager.debugLog(
            "WORKER run name=${File(videoPath).name} frameMs=$videoFrameMs attempt=$runAttemptCount"
        )

        // 视频已被删除/移走：没法生成，终态失败、不重试，并清掉进程内回调。
        if (!File(videoPath).exists()) {
            VideoCoverPreviewManager.debugLog("WORKER fail(video missing) name=${File(videoPath).name}")
            notifyFailed(outputName)
            return Result.failure()
        }
        if (outputName != null && !VideoCoverPreviewManager.matchesSource(applicationContext,
                videoPath, videoFrameMs, outputName)) {
            VideoCoverPreviewManager.debugLog("WORKER obsolete source work=$outputName")
            notifyFailed(outputName)
            return Result.failure()
        }

        if (isStopped) return Result.failure()
        VideoCoverPreviewManager.getReadyPreview(applicationContext, videoPath, videoFrameMs)?.let { ready ->
            VideoCoverPreviewManager.notifyGenerated(applicationContext, outputName ?: ready.name, ready)
            return Result.success()
        }
        // 连续取帧会跨进程访问媒体服务。只依赖内存里的 Worker 时，长后台会被当作缓存进程回收。
        try {
            setForegroundAsync(foregroundInfo()).get()
        } catch (e: ExecutionException) {
            if (isStopped) return Result.failure()
            if (Build.VERSION.SDK_INT >= 31 && e.cause is ForegroundServiceStartNotAllowedException) {
                VideoCoverPreviewManager.debugLog("WORKER waiting(foreground restricted) work=$id")
                return Result.retry()
            }
            throw e
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.failure()
        }

        var lastUpdate = 0L
        var lastPhase: CoverGenerationState.Phase? = null
        val file = try {
            VideoCoverPreviewManager.generateBlocking(applicationContext, videoPath, videoFrameMs,
                { isStopped }, outputName) { state ->
                if (isStopped) throw CancellationException("封面任务已停止")
                val now = SystemClock.elapsedRealtime()
                if (state.phase != lastPhase || now - lastUpdate >= 200L) {
                    lastUpdate = now
                    lastPhase = state.phase
                    setProgressAsync(Data.Builder()
                        .putBoolean(VideoCoverPreviewManager.KEY_GENERATING,
                            state.phase == CoverGenerationState.Phase.GENERATING)
                        .putInt(VideoCoverPreviewManager.KEY_PERCENT, state.percent)
                        .putString(VideoCoverPreviewManager.KEY_PHASE, state.phase.name)
                        .putInt(VideoCoverPreviewManager.KEY_ATTEMPT, runAttemptCount + 1)
                        .build()).get()
                }
            }
        } catch (_: CancellationException) {
            // 停止的 Result 会被 WorkManager 忽略；不能把它持久化为用户可见的失败。
            return Result.failure()
        }
        if (isStopped) return Result.failure()
        if (file != null) {
            VideoCoverPreviewManager.notifyGenerated(applicationContext, outputName ?: file.name, file)
            return Result.success()
        }

        if (outputName != null && !VideoCoverPreviewManager.matchesSource(applicationContext,
                videoPath, videoFrameMs, outputName)) {
            notifyFailed(outputName)
            return Result.failure()
        }

        // 生成失败：视频还在多半是瞬时问题（解码器忙、低存储等），有限次重试（重试期间保留回调）；
        // 视频已没或重试用尽则终态失败 + 清回调。
        val failures = VideoCoverPreviewManager.recordGenerationFailure(applicationContext, outputName)
        if (File(videoPath).exists() && failures <= MAX_RETRIES) {
            VideoCoverPreviewManager.debugLog("WORKER retry name=${File(videoPath).name} attempt=$runAttemptCount")
            return Result.retry()
        }
        VideoCoverPreviewManager.debugLog("WORKER fail-final name=${File(videoPath).name} attempt=$runAttemptCount")
        notifyFailed(outputName)
        return Result.failure()
    }

    private fun notifyFailed(outputName: String?) {
        if (outputName != null) VideoCoverPreviewManager.notifyGenerationFailed(applicationContext, outputName)
    }

    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.cover_generation_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        // 不包含附件名称或记事正文；每个 WorkRequest 使用独立通知身份，避免并发完成互相清除。
        val notificationId = 0x43000000 or (id.hashCode() and 0x00ffffff)
        val open = PendingIntent.getActivity(context, notificationId,
            Intent(context, ThingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_fablesol_export_video)
            .setContentTitle(context.getString(R.string.cover_generation_running))
            .setContentText(context.getString(R.string.cover_generation_background))
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setGroup(CHANNEL_ID).setProgress(0, 0, true)
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(notificationId, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(notificationId, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_VIDEO_PATH: String = "video_path"
        const val KEY_VIDEO_FRAME_MS: String = "video_frame_ms"
        const val KEY_OUTPUT_NAME: String = "output_name"
        const val TAG: String = "video-cover-preview"
        private const val MAX_RETRIES = 3
        private const val CHANNEL_ID = "animated-cover-generation"
    }
}
