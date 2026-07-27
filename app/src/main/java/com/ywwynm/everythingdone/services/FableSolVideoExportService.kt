package com.ywwynm.everythingdone.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportBitrateText
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSink
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSpecText
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolVideoExportBus
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolVideoExporter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FableSol 可视化视频导出的前台服务。
 *
 * 队列、当前任务、前台状态和 stopSelfResult 全部只在 Service 主线程切换；工作线程每次只执行
 * 一个完整导出，再把结果投回主线程。这样不存在“旧 worker 在锁外 stopForeground，恰好把
 * 新 worker 降成后台服务”的窗口。
 */
class FableSolVideoExportService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Job>()
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var active: ActiveJob? = null
    private var latestStartId = 0
    private var shuttingDown = false
    private var destroyed = false

    private data class Job(
        val id: Long,
        val audioPath: String,
        val displayName: String,
        val waterJson: String,
        val accentJson: String,
        val cardWidthDp: Double
    )

    private data class ActiveJob(
        val job: Job,
        val cancel: AtomicBoolean,
        var wakeLock: PowerManager.WakeLock?,
        var timedOut: Boolean = false
    )

    private data class JobCompletion(
        val result: FableSolVideoExporter.Result,
        val sink: FableSolExportSink?
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> enqueue(intent, startId)
            ACTION_CANCEL -> cancelJob(intent.getLongExtra(EXTRA_JOB_ID, 0L))
        }
        return START_NOT_STICKY
    }

    private fun enqueue(intent: Intent, startId: Int) {
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, 0L)
        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        if (jobId <= 0L || audioPath.isNullOrEmpty()) {
            ensureForeground(
                getString(R.string.fablesol_export_preparing), 0, 0, jobId
            )
            if (jobId > 0L) {
                FableSolVideoExportBus.post(
                    FableSolVideoExportBus.State.Failed(
                        jobId, "Invalid export request"
                    )
                )
            }
            stopForegroundCompat()
            stopSelfResult(startId)
            return
        }
        if (shuttingDown) {
            FableSolVideoExportBus.post(
                FableSolVideoExportBus.State.Failed(
                    jobId, getString(R.string.fablesol_export_timeout)
                )
            )
            stopSelfResult(startId)
            return
        }
        if (active?.job?.id == jobId || pending.any { it.id == jobId }) return

        val job = Job(
            id = jobId,
            audioPath = audioPath,
            displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: defaultName(),
            waterJson = intent.getStringExtra(EXTRA_WATER_JSON) ?: "",
            accentJson = intent.getStringExtra(EXTRA_ACCENT_JSON) ?: "",
            cardWidthDp = intent.getDoubleExtra(EXTRA_CARD_WIDTH_DP, 280.0)
        )
        FableSolVideoExportBus.post(FableSolVideoExportBus.State.Queued(job.id))
        pending.addLast(job)
        ensureWorker()
        dispatchNext()
    }

    /**
     * 取消严格按 jobId：排队任务直接移除并获得自己的 Cancelled 终态；当前任务只置自己的令牌。
     * 通知栏兼容 jobId=0，此时只取消当前任务，不清后来排队的任务。
     */
    private fun cancelJob(jobId: Long) {
        val current = active
        if (current != null && (jobId == 0L || current.job.id == jobId)) {
            current.cancel.set(true)
        }

        if (jobId > 0L) {
            var removed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == jobId) {
                    iterator.remove()
                    removed = true
                }
            }
            if (removed) {
                FableSolVideoExportBus.post(
                    FableSolVideoExportBus.State.Cancelled(jobId)
                )
            }
        }
        stopIfIdle()
    }

    private fun ensureWorker() {
        if (workerThread != null) return
        val thread = HandlerThread(
            "FableSolVideoExport",
            android.os.Process.THREAD_PRIORITY_DEFAULT
        )
        thread.start()
        workerThread = thread
        workerHandler = Handler(thread.looper)
    }

    private fun dispatchNext() {
        if (destroyed || shuttingDown || active != null) return
        val job = pending.pollFirst()
        if (job == null) {
            stopIfIdle()
            return
        }
        val token = AtomicBoolean(false)
        val activeJob = ActiveJob(job, token, acquireWakeLock())
        active = activeJob
        FableSolVideoExportBus.post(
            FableSolVideoExportBus.State.Running(job.id, 0, 0, -1L)
        )
        ensureForeground(
            getString(R.string.fablesol_export_preparing), 0, 0, job.id
        )

        val handler = checkNotNull(workerHandler)
        handler.post {
            val completion = performJob(job, token)
            mainHandler.post {
                completeJob(job.id, token, completion)
            }
        }
    }

    private fun performJob(job: Job, cancelToken: AtomicBoolean): JobCompletion {
        var sink: FableSolExportSink? = null
        return try {
            val water = ThingBackground.fromJson(job.waterJson)
                ?: ThingBackground.pure(0xFFF02A4B.toInt())
            val accent = ThingBackground.fromJson(job.accentJson) ?: water
            sink = FableSolExportSink.create(this, job.displayName)
            val exporter = FableSolVideoExporter(
                applicationContext,
                FableSolVideoExporter.Request(
                    job.audioPath,
                    sink,
                    water,
                    accent,
                    job.cardWidthDp
                ),
                object : FableSolVideoExporter.Listener {
                    override fun onProgress(
                        framesDone: Int,
                        framesTotal: Int,
                        etaMs: Long
                    ) {
                        mainHandler.post progress@{
                            val current = active
                            if (destroyed ||
                                current?.job?.id != job.id ||
                                current.cancel !== cancelToken ||
                                current.timedOut
                            ) {
                                return@progress
                            }
                            FableSolVideoExportBus.post(
                                FableSolVideoExportBus.State.Running(
                                    job.id, framesDone, framesTotal, etaMs
                                )
                            )
                            ensureForeground(
                                buildProgressText(
                                    framesDone, framesTotal, etaMs
                                ),
                                framesDone,
                                framesTotal,
                                job.id
                            )
                        }
                    }

                    override fun isCancelled(): Boolean = cancelToken.get()
                }
            )
            JobCompletion(exporter.run(), sink)
        } catch (error: Throwable) {
            JobCompletion(
                FableSolVideoExporter.Result.Failure(
                    error.message ?: error.javaClass.simpleName
                ),
                sink
            )
        }
    }

    private fun completeJob(
        jobId: Long,
        cancelToken: AtomicBoolean,
        completion: JobCompletion
    ) {
        if (destroyed) return
        val current = active ?: return
        if (current.job.id != jobId || current.cancel !== cancelToken) return
        releaseWakeLock(current)
        active = null

        if (!current.timedOut) {
            val effectiveResult = if (
                cancelToken.get() &&
                completion.result !is FableSolVideoExporter.Result.Success
            ) {
                FableSolVideoExporter.Result.Cancelled
            } else {
                completion.result
            }
            val state = stateFor(jobId, effectiveResult, completion.sink)
            FableSolVideoExportBus.post(state)
            notifyResult(jobId, effectiveResult, state)
        }
        dispatchNext()
    }

    private fun stateFor(
        jobId: Long,
        result: FableSolVideoExporter.Result,
        sink: FableSolExportSink?
    ): FableSolVideoExportBus.State = when (result) {
        is FableSolVideoExporter.Result.Success ->
            FableSolVideoExportBus.State.Done(
                jobId = jobId,
                uri = sink?.contentUri(),
                localPath = sink?.localPath(),
                fileSizeBytes = sink?.fileSizeBytes() ?: 0L,
                displayLocation = sink?.displayLocation().orEmpty(),
                tierLabel = result.tierLabel,
                hdr = result.hdr,
                formatLabel = result.formatLabel,
                frameRate = result.frameRate,
                frames = result.frames,
                pqWhiteNits = result.pqWhiteNits,
                peakNits = result.peakNits,
                highlightStartPercent = result.highlightStartPercent
            )
        FableSolVideoExporter.Result.Cancelled ->
            FableSolVideoExportBus.State.Cancelled(jobId)
        is FableSolVideoExporter.Result.OutOfSpace ->
            FableSolVideoExportBus.State.Failed(
                jobId, getString(R.string.fablesol_export_no_space)
            )
        is FableSolVideoExporter.Result.Failure ->
            FableSolVideoExportBus.State.Failed(jobId, result.message)
    }

    /**
     * Android 15+ mediaProcessing FGS 达到累计时限后必须立即终止。终态在这里同步写入 Bus，
     * 不再等待 codec 工作线程返回；Bus 会拒绝所有稍后的旧进度或 Cancelled 覆盖它。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        shuttingDown = true
        val timeoutMessage = getString(R.string.fablesol_export_timeout)
        active?.let { current ->
            current.timedOut = true
            current.cancel.set(true)
            releaseWakeLock(current)
            FableSolVideoExportBus.post(
                FableSolVideoExportBus.State.Failed(
                    current.job.id, timeoutMessage
                )
            )
        }
        while (pending.isNotEmpty()) {
            val job = pending.removeFirst()
            FableSolVideoExportBus.post(
                FableSolVideoExportBus.State.Failed(job.id, timeoutMessage)
            )
        }
        notifyFailureText(timeoutMessage)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        active?.cancel?.set(true)
        active?.let(::releaseWakeLock)
        active = null
        pending.clear()
        workerHandler = null
        workerThread?.quitSafely()
        workerThread = null
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (destroyed || shuttingDown || active != null || pending.isNotEmpty()) return
        workerHandler = null
        workerThread?.quitSafely()
        workerThread = null
        val stopped = latestStartId > 0 && stopSelfResult(latestStartId)
        if (stopped) stopForegroundCompat()
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val lock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG
        )
        lock.setReferenceCounted(false)
        return try {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lock
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun releaseWakeLock(activeJob: ActiveJob) {
        val lock = activeJob.wakeLock ?: return
        activeJob.wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (ignored: Throwable) {
        }
    }

    private fun buildProgressText(done: Int, total: Int, etaMs: Long): String {
        if (etaMs < 0L || total <= 0 || total == Int.MAX_VALUE) {
            return getString(R.string.fablesol_export_progress_unknown)
        }
        val minutes = (etaMs / 60_000L).toInt()
        val seconds = ((etaMs % 60_000L) / 1000L).toInt()
        val remaining = if (minutes > 0) {
            getString(R.string.fablesol_export_eta_minutes, minutes, seconds)
        } else {
            getString(R.string.fablesol_export_eta_seconds, seconds)
        }
        return getString(
            R.string.fablesol_export_progress,
            done.toLong() * 100L / total,
            remaining
        )
    }

    private fun notifyResult(
        jobId: Long,
        result: FableSolVideoExporter.Result,
        state: FableSolVideoExportBus.State
    ) {
        val text = when (result) {
            is FableSolVideoExporter.Result.Success -> {
                val done = state as? FableSolVideoExportBus.State.Done
                if (done != null) {
                    getString(
                        R.string.fablesol_export_dialog_done,
                        done.formatLabel,
                        done.frameRate,
                        Formatter.formatFileSize(this, done.fileSizeBytes),
                        FableSolExportBitrateText.of(done.bitrateBps),
                        done.displayLocation,
                        FableSolExportSpecText.detail(
                            this,
                            done.pqWhiteNits,
                            done.peakNits,
                            done.highlightStartPercent
                        )
                    )
                } else {
                    getString(
                        R.string.fablesol_export_done,
                        result.formatLabel,
                        result.frameRate
                    )
                }
            }
            FableSolVideoExporter.Result.Cancelled ->
                getString(R.string.fablesol_export_cancelled)
            is FableSolVideoExporter.Result.OutOfSpace ->
                getString(R.string.fablesol_export_no_space)
            is FableSolVideoExporter.Result.Failure ->
                getString(R.string.fablesol_export_failed, result.message)
        }
        val builder = resultNotificationBuilder(text)
        if (result is FableSolVideoExporter.Result.Success) {
            (state as? FableSolVideoExportBus.State.Done)?.uri?.let { uri ->
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, VIDEO_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        requestCode(jobId, 0),
                        view,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                    )
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = VIDEO_MIME
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(
                    send, getString(R.string.fablesol_export_share)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(
                    0,
                    getString(R.string.fablesol_export_share),
                    PendingIntent.getActivity(
                        this,
                        requestCode(jobId, 1),
                        chooser,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        manager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun notifyFailureText(text: String) {
        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        manager.notify(
            RESULT_NOTIFICATION_ID,
            resultNotificationBuilder(text).build()
        )
    }

    private fun resultNotificationBuilder(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            // 用导出图标本身，不要拿"新建"的加号顶替——通知栏里显示出来就是个加号。
            .setSmallIcon(R.drawable.act_fablesol_export_video)
            .setContentTitle(getString(R.string.fablesol_export_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

    private fun ensureForeground(
        text: String,
        done: Int,
        total: Int,
        jobId: Long
    ) {
        ensureNotificationChannel()
        val cancelIntent = Intent(
            this, FableSolVideoExportService::class.java
        ).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_JOB_ID, jobId)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // 用导出图标本身，不要拿"新建"的加号顶替——通知栏里显示出来就是个加号。
            .setSmallIcon(R.drawable.act_fablesol_export_video)
            .setContentTitle(getString(R.string.fablesol_export_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.fablesol_export_cancel),
                PendingIntent.getService(
                    this,
                    requestCode(jobId, 2),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
            )
        if (total > 0 && total != Int.MAX_VALUE) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                PROGRESS_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fablesol_export_title),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun requestCode(jobId: Long, action: Int): Int =
        ((jobId xor (jobId ushr 32)).toInt() * 31 + action) and Int.MAX_VALUE

    private fun defaultName(): String =
        "FableSol_${System.currentTimeMillis()}.mp4"

    companion object {
        private const val ACTION_START =
            "com.ywwynm.everythingdone.action.FABLESOL_EXPORT_START"
        private const val ACTION_CANCEL =
            "com.ywwynm.everythingdone.action.FABLESOL_EXPORT_CANCEL"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_DISPLAY_NAME = "display_name"
        private const val EXTRA_WATER_JSON = "water_json"
        private const val EXTRA_ACCENT_JSON = "accent_json"
        private const val EXTRA_CARD_WIDTH_DP = "card_width_dp"
        private const val CHANNEL_ID = "fablesol_video_export"
        private const val WAKE_LOCK_TAG =
            "EverythingDone:FableSolVideoExport"
        private const val WAKE_LOCK_TIMEOUT_MS =
            6L * 60L * 60L * 1000L
        private const val PROGRESS_NOTIFICATION_ID = 9701
        private const val RESULT_NOTIFICATION_ID = 9702
        private const val VIDEO_MIME = "video/mp4"

        @JvmStatic
        fun cancel(context: Context, jobId: Long) {
            val intent = Intent(
                context, FableSolVideoExportService::class.java
            ).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            try {
                context.startService(intent)
            } catch (ignored: Throwable) {
                // 服务已经停了时，Bus 中已保存的终态仍可供 Dialog 恢复。
            }
        }

        @JvmStatic
        fun start(
            context: Context,
            jobId: Long,
            audioPath: String,
            displayName: String,
            water: ThingBackground,
            accent: ThingBackground,
            cardWidthDp: Double
        ) {
            val intent = Intent(
                context, FableSolVideoExportService::class.java
            ).apply {
                action = ACTION_START
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_AUDIO_PATH, audioPath)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_WATER_JSON, water.toJson())
                putExtra(EXTRA_ACCENT_JSON, accent.toJson())
                putExtra(EXTRA_CARD_WIDTH_DP, cardWidthDp)
            }
            context.startForegroundService(intent)
        }
    }
}
