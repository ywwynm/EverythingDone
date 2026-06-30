package com.ywwynm.everythingdone.services

import android.content.Context

import androidx.work.Worker
import androidx.work.WorkerParameters

import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager

import java.io.File

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

        val file = VideoCoverPreviewManager.generateBlocking(applicationContext, videoPath, videoFrameMs)
        if (file != null) {
            VideoCoverPreviewManager.notifyGenerated(outputName ?: file.name, file)
            return Result.success()
        }

        // 生成失败：视频还在多半是瞬时问题（解码器忙、低存储等），有限次重试（重试期间保留回调）；
        // 视频已没或重试用尽则终态失败 + 清回调。
        if (File(videoPath).exists() && runAttemptCount < MAX_ATTEMPTS) {
            VideoCoverPreviewManager.debugLog("WORKER retry name=${File(videoPath).name} attempt=$runAttemptCount")
            return Result.retry()
        }
        VideoCoverPreviewManager.debugLog("WORKER fail-final name=${File(videoPath).name} attempt=$runAttemptCount")
        notifyFailed(outputName)
        return Result.failure()
    }

    private fun notifyFailed(outputName: String?) {
        if (outputName != null) VideoCoverPreviewManager.notifyGenerationFailed(outputName)
    }

    companion object {
        const val KEY_VIDEO_PATH: String = "video_path"
        const val KEY_VIDEO_FRAME_MS: String = "video_frame_ms"
        const val KEY_OUTPUT_NAME: String = "output_name"
        const val TAG: String = "video-cover-preview"
        private const val MAX_ATTEMPTS = 3
    }
}
