package com.ywwynm.everythingdone.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportBitrateText
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportColorMode
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportOptions
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportPublicSpec
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportRetryNotice
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportRuntimeDiagnostics
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSink
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolExportSpecText
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolHdrExportCapability
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolHdrPolicy
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning
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
        /** 入队时冻结；等待确认期间设置页变化不得改写当前任务。 */
        val options: FableSolExportOptions,
        val hdrStrength: Float,
        var targetSpec: FableSolExportPublicSpec? = null,
        var failedSpecs: Set<FableSolExportPublicSpec> = emptySet(),
        var attemptedSpecCount: Int = 0,
        var retryNotice: FableSolExportRetryNotice? = null,
        var waiting: FableSolVideoExporter.Result.NeedsConfirmation? = null,
        var progressTotal: Int = 0,
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
            ACTION_ACCEPT_SUGGESTED ->
                acceptSuggestedSpec(intent.getLongExtra(EXTRA_JOB_ID, 0L))
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
                        jobId, getString(R.string.fablesol_export_invalid_request)
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
            if (current.waiting != null) {
                FableSolExportRuntimeDiagnostics.record(
                    "用户在等待规格确认时结束导出。"
                )
                releaseWakeLock(current)
                active = null
                FableSolVideoExportBus.post(
                    FableSolVideoExportBus.State.Cancelled(current.job.id)
                )
                dispatchNext()
                return
            }
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
        val options = FableSolExportOptions.read(applicationContext)
        val hdrStrength = FableSolTuning.hdrStrength(applicationContext)
        val resolutionColorMode = if (
            options.colorMode == FableSolExportColorMode.HDR_AUTO &&
            hdrStrength <= FableSolHdrPolicy.STRENGTH_OFF
        ) {
            FableSolExportColorMode.SDR_NATIVE
        } else {
            options.colorMode
        }
        // 导出前检查必须读同一份五维结论（D183）。进程内的 lastMatrix 在冷进程为空——
        // 用户可以不经过设置页直接发起导出；此时回退到持久化矩阵。两者都空（从未探测过）
        // 才跳过检查，由导出器的解析与其正式失败文案兜底。
        val matrix = FableSolHdrExportCapability.lastMatrix.takeIf { !it.isEmpty }
            ?: FableSolHdrExportCapability.cachedMatrix(applicationContext)
        val resolved = if (matrix.isEmpty) {
            null
        } else {
            matrix.resolve(
                colorMode = resolutionColorMode,
                codec = options.codec,
                frameRate = options.frameRate,
                sdrBitDepth = options.sdrBitDepth,
                rateControl = options.rateControl
            )
        }
        if (!matrix.isEmpty && resolved == null) {
            val message = getString(
                R.string.fablesol_export_no_exact_specification,
                options.frameRate
            )
            ensureForeground(message, 0, 0, job.id)
            FableSolVideoExportBus.post(
                FableSolVideoExportBus.State.Failed(
                    job.id, message, adjustSettingsActionable = true
                )
            )
            // 文案本身就写着"请调整导出设置后重新开始"，通知给出同名入口（D107、D183）。
            notifyFailureText(message, adjustSettingsJobId = job.id)
            dispatchNext()
            return
        }
        val token = AtomicBoolean(false)
        val activeJob = ActiveJob(
            job = job,
            cancel = token,
            wakeLock = acquireWakeLock(),
            options = options,
            hdrStrength = hdrStrength,
            targetSpec = resolved?.let {
                FableSolExportPublicSpec(
                    format = it.format,
                    family = it.family,
                    tenBit = it.tenBit,
                    softwareOnly = it.outcome.softwareOnly,
                    frameRate = it.frameRate,
                    rateControl = options.rateControl
                )
            }
        )
        active = activeJob
        FableSolVideoExportBus.post(
            FableSolVideoExportBus.State.Running(job.id, 0, 0, -1L)
        )
        ensureForeground(
            getString(R.string.fablesol_export_preparing), 0, 0, job.id
        )

        val handler = checkNotNull(workerHandler)
        handler.post {
            val completion = performJob(activeJob)
            mainHandler.post {
                completeJob(job.id, token, completion)
            }
        }
    }

    private fun performJob(activeJob: ActiveJob): JobCompletion {
        val job = activeJob.job
        val cancelToken = activeJob.cancel
        var sink: FableSolExportSink? = null
        return try {
            val water = ThingBackground.fromJson(job.waterJson)
                ?: ThingBackground.pure(0xFFF02A4B.toInt())
            val accent = ThingBackground.fromJson(job.accentJson) ?: water
            sink = FableSolExportSink.create(this, job.displayName)
            val exporter = FableSolVideoExporter(
                applicationContext,
                FableSolVideoExporter.Request(
                    audioPath = job.audioPath,
                    sink = sink,
                    waterBackground = water,
                    accentBackground = accent,
                    cardWidthDp = job.cardWidthDp,
                    options = activeJob.options,
                    hdrStrength = activeJob.hdrStrength,
                    targetSpec = activeJob.targetSpec,
                    failedSpecs = activeJob.failedSpecs,
                    attemptedSpecCount = activeJob.attemptedSpecCount,
                    retryNotice = activeJob.retryNotice
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
                            current.progressTotal = framesTotal
                            FableSolVideoExportBus.post(
                                FableSolVideoExportBus.State.Running(
                                    job.id,
                                    framesDone,
                                    framesTotal,
                                    etaMs,
                                    current.retryNotice
                                )
                            )
                            ensureForeground(
                                FableSolExportSpecText.appendRetrySummary(
                                    this@FableSolVideoExportService,
                                    buildProgressText(
                                        framesDone, framesTotal, etaMs
                                    ),
                                    current.retryNotice
                                ),
                                framesDone,
                                framesTotal,
                                job.id
                            )
                        }
                    }

                    override fun onPreparing(stageId: String) {
                        mainHandler.post preparing@{
                            val current = active
                            if (destroyed ||
                                current?.job?.id != job.id ||
                                current.cancel !== cancelToken ||
                                current.timedOut
                            ) {
                                return@preparing
                            }
                            FableSolVideoExportBus.post(
                                FableSolVideoExportBus.State.Preparing(
                                    job.id, stageId, current.retryNotice
                                )
                            )
                            ensureForeground(
                                FableSolExportSpecText.appendRetrySummary(
                                    this@FableSolVideoExportService,
                                    FableSolExportSpecText.preparingStage(
                                        this@FableSolVideoExportService, stageId
                                    ),
                                    current.retryNotice
                                ),
                                0,
                                0,
                                job.id
                            )
                        }
                    }

                    override fun onRetryingSameSpec(notice: FableSolExportRetryNotice) {
                        mainHandler.post retrying@{
                            val current = active
                            if (destroyed ||
                                current?.job?.id != job.id ||
                                current.cancel !== cancelToken ||
                                current.timedOut
                            ) {
                                return@retrying
                            }
                            current.retryNotice = notice
                            FableSolVideoExportBus.post(
                                FableSolVideoExportBus.State.Running(
                                    jobId = job.id,
                                    done = 0,
                                    total = current.progressTotal,
                                    etaMs = -1L,
                                    retryNotice = notice
                                )
                            )
                            ensureForeground(
                                FableSolExportSpecText.appendRetrySummary(
                                    this@FableSolVideoExportService,
                                    getString(R.string.fablesol_export_progress_unknown),
                                    notice
                                ),
                                0,
                                current.progressTotal,
                                job.id
                            )
                        }
                    }

                    override fun isCancelled(): Boolean = cancelToken.get()
                }
            )
            JobCompletion(exporter.run(), sink)
        } catch (error: Throwable) {
            Log.e(TAG, "Export worker failed before a structured result was produced", error)
            JobCompletion(
                FableSolVideoExporter.Result.Failure(
                    getString(R.string.fablesol_export_internal_error)
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

        val confirmation = completion.result as? FableSolVideoExporter.Result.NeedsConfirmation
        if (confirmation != null && !current.timedOut && !cancelToken.get()) {
            releaseWakeLock(current)
            try {
                completion.sink?.discard()
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to discard the failed public-specification sink", error)
            }
            current.waiting = confirmation
            current.targetSpec = null
            current.failedSpecs = confirmation.failedSpecs
            current.attemptedSpecCount = confirmation.attemptedSpecCount
            current.progressTotal = 0
            val state = FableSolVideoExportBus.State.AwaitingConfirmation(
                jobId = jobId,
                failedSpec = confirmation.failedSpec,
                reason = confirmation.reason,
                suggestedSpec = confirmation.suggestedSpec,
                attemptedSpecCount = confirmation.attemptedSpecCount,
                detail = confirmation.detail
            )
            FableSolVideoExportBus.post(state)
            ensureConfirmationForeground(state)
            return
        }

        releaseWakeLock(current)
        active = null

        if (!current.timedOut) {
            val uncancelledResult = if (
                cancelToken.get() &&
                completion.result !is FableSolVideoExporter.Result.Success
            ) {
                FableSolVideoExporter.Result.Cancelled
            } else {
                completion.result
            }
            val effectiveResult = if (
                uncancelledResult is FableSolVideoExporter.Result.Success
            ) {
                uncancelledResult.copy(
                    retryNotice = current.retryNotice ?: uncancelledResult.retryNotice
                )
            } else {
                uncancelledResult
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
                codecLabel = result.codecLabel,
                softwareCodec = result.softwareCodec,
                frameRate = result.frameRate,
                frames = result.frames,
                pqWhiteNits = result.pqWhiteNits,
                peakNits = result.peakNits,
                highlightStartPercent = result.highlightStartPercent,
                hdr10PlusRequestedKneeNits = result.hdr10PlusRequestedKneeNits,
                hdr10PlusKneeNits = result.hdr10PlusKneeNits,
                hdr10PlusFbpUnavailable = result.hdr10PlusFbpUnavailable,
                hdr10PlusSeiSamples = result.hdr10PlusSeiSamples,
                hdr10PlusSeiTotal = result.hdr10PlusSeiTotal,
                sdrFallbackNotice = result.sdrFallbackNotice,
                staticMetadataConflict = result.staticMetadataConflict,
                hdr10PlusIdentity = result.hdr10PlusIdentity,
                luminance = result.luminance,
                hlgRange = result.hlgRange,
                encoding = result.encoding,
                attemptedSpecCount = result.attemptedSpecCount,
                retryNotice = result.retryNotice
            )
        FableSolVideoExporter.Result.Cancelled ->
            FableSolVideoExportBus.State.Cancelled(jobId)
        is FableSolVideoExporter.Result.OutOfSpace ->
            FableSolVideoExportBus.State.Failed(
                jobId, getString(R.string.fablesol_export_no_space)
            )
        is FableSolVideoExporter.Result.Failure ->
            FableSolVideoExportBus.State.Failed(
                jobId = jobId,
                message = if (result.failedSpec != null && result.reason != null) {
                    FableSolExportSpecText.terminalFailure(
                        this,
                        result.failedSpec,
                        result.reason,
                        result.attemptedSpecCount,
                        detail = result.detail
                    )
                } else {
                    // detail 是**已本地化**的正式结论（如无完整规格），直达用户；只有真正
                    // 未结构化的原文才落到通用内部错误并只进日志（D178、D183）。
                    result.detail ?: run {
                        Log.w(TAG, "Unstructured export failure: ${result.message}")
                        getString(R.string.fablesol_export_internal_error)
                    }
                },
                failedSpec = result.failedSpec,
                reason = result.reason,
                attemptedSpecCount = result.attemptedSpecCount,
                adjustSettingsActionable =
                    result.failedSpec != null || result.detail != null
            )
        is FableSolVideoExporter.Result.NeedsConfirmation ->
            error("Confirmation results must remain attached to the active job")
    }

    /**
     * 通知正文与“使用建议规格”动作都进入本方法。waiting 先清空，因此重复点击是幂等的。
     */
    private fun acceptSuggestedSpec(jobId: Long) {
        val current = active
        if (current == null || current.job.id != jobId) {
            val message = getString(R.string.fablesol_export_service_interrupted)
            if (jobId > 0L) {
                FableSolVideoExportBus.post(
                    FableSolVideoExportBus.State.Failed(jobId, message)
                )
            }
            notifyFailureText(message)
            stopForegroundCompat()
            stopIfIdle()
            return
        }
        val confirmation = current.waiting ?: return
        if (current.timedOut || current.cancel.get()) return

        current.waiting = null
        current.targetSpec = confirmation.suggestedSpec
        current.failedSpecs = confirmation.failedSpecs
        current.attemptedSpecCount = confirmation.attemptedSpecCount
        current.retryNotice = FableSolExportRetryNotice(
            failedSpec = confirmation.failedSpec,
            reason = confirmation.reason,
            currentSpec = confirmation.suggestedSpec,
            attemptedSpecCount = confirmation.attemptedSpecCount + 1,
            sameSpec = false
        )
        FableSolExportRuntimeDiagnostics.record(
            "用户确认建议规格：" +
                FableSolExportRuntimeDiagnostics.specification(confirmation.suggestedSpec)
        )
        current.wakeLock = acquireWakeLock()
        current.progressTotal = 0
        FableSolVideoExportBus.post(
            FableSolVideoExportBus.State.Running(
                jobId = jobId,
                done = 0,
                total = 0,
                etaMs = -1L,
                retryNotice = current.retryNotice
            )
        )
        ensureForeground(
            FableSolExportSpecText.appendRetrySummary(
                this,
                getString(R.string.fablesol_export_preparing),
                current.retryNotice
            ),
            0,
            0,
            jobId
        )
        ensureWorker()
        checkNotNull(workerHandler).post {
            val completion = performJob(current)
            mainHandler.post {
                completeJob(jobId, current.cancel, completion)
            }
        }
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
        active?.let { current ->
            current.cancel.set(true)
            releaseWakeLock(current)
            if (!current.timedOut) {
                val message = getString(R.string.fablesol_export_service_interrupted)
                FableSolVideoExportBus.post(
                    FableSolVideoExportBus.State.Failed(
                        jobId = current.job.id,
                        message = message,
                        failedSpec = current.waiting?.failedSpec,
                        reason = current.waiting?.reason,
                        attemptedSpecCount = current.attemptedSpecCount
                    )
                )
                notifyFailureText(message)
            }
        }
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
                    FableSolExportSpecText.appendRetrySummary(
                        this,
                        getString(
                            R.string.fablesol_export_dialog_done,
                            FableSolExportSpecText.specification(
                                this, done.formatLabel, done.codecLabel, done.softwareCodec
                            ),
                            done.frameRate,
                            Formatter.formatFileSize(this, done.fileSizeBytes),
                            FableSolExportBitrateText.of(done.bitrateBps),
                            done.displayLocation,
                            FableSolExportSpecText.detail(
                                this,
                                done.pqWhiteNits,
                                done.peakNits,
                                done.highlightStartPercent,
                                done.hdr10PlusIdentity,
                                done.luminance,
                                done.hlgRange,
                                done.encoding,
                                hdr10PlusRequestedKneeNits = done.hdr10PlusRequestedKneeNits,
                                hdr10PlusKneeNits = done.hdr10PlusKneeNits,
                                hdr10PlusFbpUnavailable = done.hdr10PlusFbpUnavailable,
                                hdr10PlusSeiSamples = done.hdr10PlusSeiSamples,
                                hdr10PlusSeiTotal = done.hdr10PlusSeiTotal,
                                sdrFallbackNotice = done.sdrFallbackNotice,
                                staticMetadataConflict = done.staticMetadataConflict
                            )
                        ),
                        done.retryNotice
                    )
                } else {
                    FableSolExportSpecText.appendRetrySummary(
                        this,
                        getString(
                            R.string.fablesol_export_done,
                            FableSolExportSpecText.specification(
                                this,
                                result.formatLabel,
                                result.codecLabel,
                                result.softwareCodec
                            ),
                            result.frameRate
                        ),
                        result.retryNotice
                    )
                }
            }
            FableSolVideoExporter.Result.Cancelled ->
                getString(R.string.fablesol_export_cancelled)
            is FableSolVideoExporter.Result.OutOfSpace ->
                getString(R.string.fablesol_export_no_space)
            is FableSolVideoExporter.Result.Failure ->
                getString(
                    R.string.fablesol_export_failed,
                    (state as? FableSolVideoExportBus.State.Failed)?.message
                        ?: getString(R.string.fablesol_export_internal_error)
                )
            is FableSolVideoExporter.Result.NeedsConfirmation ->
                return
        }
        val builder = resultNotificationBuilder(text)
        if (result is FableSolVideoExporter.Result.Success) {
            (state as? FableSolVideoExportBus.State.Done)?.uri?.let { uri ->
                try {
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
                } catch (ignored: Throwable) {
                    // 同上：点不开也比崩掉好。
                }
                // **分享动作可能建不出来，而那绝不能拖垮一次已经成功的导出。**
                // `Intent.createChooser` 带着 EXTRA_STREAM 时，`PendingIntent.getActivity`
                // 会当场走一遍 URI 授权（`migrateExtraStreamToClipData`）；授权被拒就抛
                // SecurityException。华为平板（EMUI，Android 12）上实测抛出
                // `UID … does not have permission to content://media/external/video/media/…`，
                // 视频已经写完并入库，进程却在发通知这一步崩掉（2026-07-28）。
                //
                // 授权失败的原因在厂商实现里，我们改不了；但产物是好的，通知本身也是好的，
                // 少一个分享按钮而已。对话框里的分享走 Activity 上下文，不受影响。
                shareIntent(uri, jobId)?.let { pending ->
                    builder.addAction(0, getString(R.string.fablesol_export_share), pending)
                }
            }
        }
        // 「调整导出设置」（D107、D183）：属于与设置相关的失败——规格候选耗尽
        // （failedSpec 存在）或携带正式本地化结论（detail 存在，如无完整规格）。
        // 空间不足、超时等环境性失败没有可调的规格，不加这个动作。
        if (result is FableSolVideoExporter.Result.Failure &&
            (result.failedSpec != null || result.detail != null)
        ) {
            adjustSettingsIntent(jobId)?.let { pending ->
                builder.addAction(
                    0, getString(R.string.fablesol_export_adjust_settings), pending
                )
            }
        }
        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        try {
            manager.notify(RESULT_NOTIFICATION_ID, builder.build())
        } catch (ignored: Throwable) {
            // 终态已经通过 Bus 发给界面了；通知发不出去不该再影响任何东西。
        }
    }

    /**
     * 通知栏那个分享按钮的 PendingIntent；建不出来就返回 null，由调用方省掉这个动作。
     *
     * 不能让它抛出去：产物已经落盘入库，一次成功的导出不该因为通知栏少不了一个按钮而崩。
     */
    private fun shareIntent(uri: Uri, jobId: Long): PendingIntent? = try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = VIDEO_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send, getString(R.string.fablesol_export_share)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(
            this,
            requestCode(jobId, 1),
            chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (ignored: Throwable) {
        null
    }

    /**
     * 失败通知的「调整导出设置」动作（D107）：打开 FableSol 设置并定位到「视频导出」组。
     * 只做导航，不改写偏好、不自动重试；建不出来就省掉这个动作，与分享按钮同一容错。
     */
    private fun adjustSettingsIntent(jobId: Long): PendingIntent? = try {
        val open = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_SHOW_FABLESOL_EXPORT, true)
        }
        PendingIntent.getActivity(
            this,
            requestCode(jobId, 4),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (ignored: Throwable) {
        null
    }

    private fun notifyFailureText(
        text: String,
        /** 非 null 时附「调整导出设置」动作（D107、D183）；环境性失败传 null。 */
        adjustSettingsJobId: Long? = null
    ) {
        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        val builder = resultNotificationBuilder(text)
        adjustSettingsJobId?.let { jobId ->
            adjustSettingsIntent(jobId)?.let { pending ->
                builder.addAction(
                    0, getString(R.string.fablesol_export_adjust_settings), pending
                )
            }
        }
        manager.notify(RESULT_NOTIFICATION_ID, builder.build())
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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

    private fun ensureConfirmationForeground(
        state: FableSolVideoExportBus.State.AwaitingConfirmation
    ) {
        ensureNotificationChannel()
        val acceptIntent = Intent(
            this, FableSolVideoExportService::class.java
        ).apply {
            action = ACTION_ACCEPT_SUGGESTED
            putExtra(EXTRA_JOB_ID, state.jobId)
        }
        val cancelIntent = Intent(
            this, FableSolVideoExportService::class.java
        ).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_JOB_ID, state.jobId)
        }
        val acceptPending = PendingIntent.getService(
            this,
            requestCode(state.jobId, 3),
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPending = PendingIntent.getService(
            this,
            requestCode(state.jobId, 2),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = FableSolExportSpecText.retryConfirmation(this, state.notice)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_fablesol_export_video)
            .setContentTitle(getString(R.string.fablesol_export_confirmation_title))
            .setContentText(
                getString(R.string.fablesol_export_confirmation_notification_summary)
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // 正文与显式确认动作复用同一个幂等 PendingIntent。
            .setContentIntent(acceptPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .addAction(
                0,
                getString(R.string.fablesol_export_use_suggested_spec),
                acceptPending
            )
            .addAction(
                0,
                getString(R.string.fablesol_export_end_export),
                cancelPending
            )
            .build()
        startForegroundCompat(notification)
    }

    private fun startForegroundCompat(notification: Notification) {
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
        private const val ACTION_ACCEPT_SUGGESTED =
            "com.ywwynm.everythingdone.action.FABLESOL_EXPORT_ACCEPT_SUGGESTED"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_DISPLAY_NAME = "display_name"
        private const val EXTRA_WATER_JSON = "water_json"
        private const val EXTRA_ACCENT_JSON = "accent_json"
        private const val EXTRA_CARD_WIDTH_DP = "card_width_dp"
        private const val CHANNEL_ID = "fablesol_video_export"
        private const val WAKE_LOCK_TAG =
            "EverythingDone:FableSolVideoExport"
        private const val TAG = "FableSolVideoExport"
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
        fun acceptSuggested(context: Context, jobId: Long) {
            val intent = Intent(
                context, FableSolVideoExportService::class.java
            ).apply {
                action = ACTION_ACCEPT_SUGGESTED
                putExtra(EXTRA_JOB_ID, jobId)
            }
            try {
                context.startService(intent)
            } catch (ignored: Throwable) {
                FableSolVideoExportBus.post(
                    FableSolVideoExportBus.State.Failed(
                        jobId,
                        context.getString(R.string.fablesol_export_service_interrupted)
                    )
                )
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
