package com.ywwynm.everythingdone.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.recording.AudioCaptureEngine
import com.ywwynm.everythingdone.views.recording.AudioInputMode
import com.ywwynm.everythingdone.views.recording.AudioInputPreferences
import com.ywwynm.everythingdone.views.recording.AudioInputSelectionPersistencePolicy
import com.ywwynm.everythingdone.views.recording.AudioRecordingNotice
import com.ywwynm.everythingdone.views.recording.AudioRecordingPhase
import com.ywwynm.everythingdone.views.recording.AudioRecordingSnapshot
import com.ywwynm.everythingdone.views.recording.StoppedRecordingSession
import com.ywwynm.everythingdone.views.recording.stoppedSessionRemainsConfigured
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrameReceiver
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 录音会话的唯一所有者。Dialog 只绑定、发命令和订阅快照；切到后台后正式录音不依赖
 * Fragment/View 生命周期继续运行。
 */
class AudioRecordingService : Service(), AudioCaptureEngine.Listener {

    fun interface Observer {
        fun onAudioRecordingStateChanged(snapshot: AudioRecordingSnapshot)
    }

    inner class LocalBinder : Binder() {
        fun snapshot(): AudioRecordingSnapshot = currentSnapshot()

        fun addObserver(observer: Observer) {
            observers.addIfAbsent(observer)
            observer.onAudioRecordingStateChanged(currentSnapshot())
        }

        fun removeObserver(observer: Observer) {
            observers.remove(observer)
        }

        fun setSessionSource(intent: Intent?, thingId: Long, accent: ThingBackground?) {
            // 归属防御：会话进行中（含恢复的停止态）且已有有效归属时，属于其他记事的
            // 绑定不得改写归属——入口拦截失守时这里是防止录音被错误认领的最后一道闸。
            if (snapshot.phase != AudioRecordingPhase.IDLE &&
                activeSessionThingId != -1L && thingId != activeSessionThingId
            ) {
                return
            }
            returnIntent = intent?.let(::Intent)
            activeReturnIntent = intent?.let(::Intent)
            activeSessionThingId = thingId
            sessionAccent = accent
        }

        /**
         * 新建记事首次入库后把会话归属从 -1 升级为正式 id（返回入口一并换成按 id 的
         * 标准打开 intent），此后跨记事拦截、停止态恢复与图标接力全部生效。已有正式
         * 归属的会话不受影响。
         */
        fun upgradeSessionThing(thingId: Long, intent: Intent?) {
            if (thingId == -1L) return
            if (activeSessionThingId != -1L) return
            if (snapshot.phase == AudioRecordingPhase.IDLE) return
            activeSessionThingId = thingId
            intent?.let {
                returnIntent = Intent(it)
                activeReturnIntent = Intent(it)
            }
            AudioInputPreferences.loadStoppedSession(this@AudioRecordingService)?.let { stopped ->
                if (stopped.thingId == -1L) {
                    AudioInputPreferences.saveStoppedSession(
                        this@AudioRecordingService, stopped.copy(thingId = thingId)
                    )
                }
            }
        }

        fun setDialogVisible(visible: Boolean) {
            this@AudioRecordingService.setDialogVisible(visible)
        }

        fun prepareMode(mode: AudioInputMode): Boolean {
            if (mode.requiresSystemAudio && mediaProjection == null) return false
            prepareConfiguredMode(mode)
            return true
        }

        fun prepareSystemMode(mode: AudioInputMode, resultCode: Int, data: Intent) {
            prepareSystemModeInternal(mode, resultCode, data)
        }

        fun fallbackToMicrophone(notice: AudioRecordingNotice) {
            fallbackToMicrophoneInternal(notice)
        }

        fun startRecording(): Boolean = startRecordingInternal()

        fun stopRecording() {
            stopRecordingInternal(AudioRecordingNotice.NONE)
        }

        /** 返回 false 表示系统类来源需要重新请求 MediaProjection。 */
        fun restartRecording(): Boolean = restartRecordingInternal()

        fun finishSession(keepFile: Boolean) {
            finishSessionInternal(keepFile)
        }

        fun linkFableSol(receiver: FableSolFrameReceiver) {
            engine.linkFableSol(receiver)
        }

        fun unlinkFableSol(receiver: FableSolFrameReceiver) {
            engine.unlinkFableSol(receiver)
        }

        fun setGravityTrackEnabled(enabled: Boolean) {
            engine.setGravityTrackEnabled(enabled)
        }

        fun offerGravitySample(x: Float, y: Float, z: Float) {
            engine.offerGravitySample(x, y, z)
        }

        fun lastGravitySample(): FloatArray? = engine.lastGravitySample()
    }

    private val binder = LocalBinder()
    private val observers = CopyOnWriteArrayList<Observer>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineTasks: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AudioRecordingService")
    }
    private lateinit var engine: AudioCaptureEngine

    @Volatile
    private var snapshot = AudioRecordingSnapshot()
    private var operationGeneration = 0L
    /**
     * 会话收尾（[finishSessionInternal]）进行中。收尾期间外部异步事件（输入故障、
     * 投影撤销、系统静音）一律忽略：它们的处置会递增 [operationGeneration]，作废
     * 收尾的完成回调，留下无通知的 started 服务和非 IDLE 的假快照。收尾完成回调
     * 必然执行（串行引擎队列 + 主线程 post，进程死则一切重置），在那里清除。
     */
    private var sessionClosing = false
    @Volatile
    private var dialogVisible = false
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var returnIntent: Intent? = null
    /** 录音所属记事的背景；通知底色取其代表色，渐变记事另配渐变徽章。 */
    private var sessionAccent: ThingBackground? = null
    private var foregroundActive = false
    private var foregroundForPreview = false

    override fun onCreate() {
        super.onCreate()
        engine = AudioCaptureEngine(applicationContext)
        engine.listener = this
        ensureNotificationChannel()
        restoreStoppedSessionIfPersisted()
    }

    /**
     * 进程被回收后重建的服务从持久化记录恢复停止态：待处理 WAV 重新变成可保存/可丢弃的
     * 会话，`activeSession` 复活，通知点击、图标接力与详情页入口全部照常工作。
     */
    private fun restoreStoppedSessionIfPersisted() {
        if (snapshot.phase != AudioRecordingPhase.IDLE) return
        val stopped = AudioInputPreferences.loadStoppedSession(this) ?: return
        activeSessionThingId = stopped.thingId
        if (stopped.thingId != -1L) {
            activeReturnIntent = DetailActivity.getOpenIntentForUpdate(
                this, null, stopped.thingId, -1
            )
            returnIntent = activeReturnIntent?.let(::Intent)
        }
        updateSnapshot(
            AudioRecordingSnapshot(
                phase = AudioRecordingPhase.STOPPED,
                inputMode = stopped.mode,
                // 诚实值：重建的引擎没有任何 AudioRecord，重新录音路径按未配置补齐。
                configured = false,
                recordedDurationMillis = stopped.durationMillis,
                savedFile = stopped.file,
                notice = stopped.notice
            )
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AND_KEEP -> stopRecordingInternal(AudioRecordingNotice.NONE)
            ACTION_KEEP_ALIVE -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        operationGeneration++
        engine.listener = null
        engine.release()
        releaseProjection()
        engineTasks.shutdown()
        observers.clear()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
        activeSession = false
        activeReturnIntent = null
        activeSessionThingId = -1L
        super.onDestroy()
    }

    override fun onSystemSilenceChanged(mode: AudioInputMode, silent: Boolean) {
        mainHandler.post {
            if (sessionClosing) return@post
            if (snapshot.inputMode != mode || !mode.requiresSystemAudio) return@post
            updateSnapshot(snapshot.copy(systemSilent = silent))
        }
    }

    override fun onRecordingSizeLimitReached(mode: AudioInputMode) {
        mainHandler.post {
            if (snapshot.inputMode != mode || snapshot.phase != AudioRecordingPhase.RECORDING) {
                return@post
            }
            stopRecordingInternal(AudioRecordingNotice.SIZE_LIMIT_REACHED)
        }
    }

    override fun onCaptureFailure(
        mode: AudioInputMode,
        source: AudioCaptureEngine.CaptureSource,
        errorCode: Int
    ) {
        mainHandler.post {
            if (sessionClosing) return@post
            if (snapshot.inputMode != mode) return@post
            if (snapshot.phase == AudioRecordingPhase.RECORDING) {
                stopRecordingInternal(
                    // OUTPUT 是 raw 文件写入中断（输入源都还活着），不能说"必需的音频
                    // 输入已停止"；此前写入的部分能否保留由收尾结果决定。
                    if (source == AudioCaptureEngine.CaptureSource.OUTPUT) {
                        AudioRecordingNotice.FILE_WRITE_INTERRUPTED
                    } else {
                        AudioRecordingNotice.CAPTURE_FAILED
                    }
                )
            } else if (snapshot.phase != AudioRecordingPhase.PREPARED) {
                return@post
            } else when (source) {
                // raw 文件打不开/写不了是存储问题，与输入源无关：回落麦克风解决不了
                // 还会把用户的来源偏好改写成麦克风，直接失败并给存储相关提示。
                AudioCaptureEngine.CaptureSource.OUTPUT ->
                    failPreparedCapture(AudioRecordingNotice.FILE_OUTPUT_FAILED)
                // 系统流死亡：回落麦克风，"无法启动系统音频，已恢复为麦克风"语义吻合。
                AudioCaptureEngine.CaptureSource.SYSTEM ->
                    fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
                // 麦克风流死亡（含混合模式）：回落到刚失败的麦克风没有意义，直接报
                // 麦克风不可用，ERROR 态来源选择器仍可用，由用户决定下一步。
                AudioCaptureEngine.CaptureSource.MICROPHONE ->
                    failPreparedCapture(AudioRecordingNotice.MICROPHONE_UNAVAILABLE)
            }
        }
    }

    private fun setDialogVisible(visible: Boolean) {
        dialogVisible = visible
        when {
            visible && snapshot.phase == AudioRecordingPhase.PREPARED &&
                snapshot.configured && !snapshot.busy && !engine.isListening() -> startPreview()

            // 预览停止时输入流死亡（lastInputFaulted）会把 configured 判掉；纯麦克风
            // 回到 Dialog 直接自动重建，不用用户再手动选一次来源。系统类来源需要
            // 重新走授权流程，保持由用户从来源选择器发起。
            visible && snapshot.phase == AudioRecordingPhase.PREPARED &&
                !snapshot.configured && !snapshot.busy &&
                !snapshot.inputMode.requiresSystemAudio ->
                prepareConfiguredMode(snapshot.inputMode)

            // busy 的 PREPARED 表示配置或会话收尾在途：此时叠加 stopPreview 会抢占
            // operationGeneration、作废在途操作的完成回调（收尾场景会泄漏投影/通知/服务）。
            !visible && snapshot.phase == AudioRecordingPhase.PREPARED &&
                !snapshot.busy && engine.isListening() -> stopPreview()
        }
    }

    private fun prepareConfiguredMode(mode: AudioInputMode) {
        val projection = if (mode.requiresSystemAudio) mediaProjection else null
        if (mode.requiresSystemAudio && projection == null) {
            fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
            return
        }
        // 麦克风是不需要外部授权的安全恢复值，可以立即记忆；系统类来源只有在投影和
        // AudioRecord/预览全部就绪后才提交，避免一次未完成的授权污染下次 Dialog。
        AudioInputSelectionPersistencePolicy.modeToCommitWhenPreparationStarts(mode)?.let {
            AudioInputPreferences.save(this, it)
        }
        val generation = ++operationGeneration
        updateSnapshot(
            snapshot.copy(
                phase = AudioRecordingPhase.PREPARED,
                inputMode = mode,
                busy = true,
                configured = false,
                hasProjection = projection != null,
                recordedDurationMillis = 0L,
                savedFile = null,
                systemSilent = false,
                aecEnabled = false,
                notice = AudioRecordingNotice.NONE
            )
        )

        if (mode.requiresSystemAudio && dialogVisible) {
            try {
                ensureForeground(preview = true, mode = mode)
            } catch (_: Throwable) {
                fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
                return
            }
        }
        schedulePreparedOperationTimeout(generation, mode)
        submitEngineTask(generation, mode) {
            val result = try {
                engine.configure(mode, projection)
            } catch (error: Throwable) {
                AudioCaptureEngine.ConfigurationResult(false, error = error.message)
            }
            val previewRequested = result.success && dialogVisible
            val previewStarted = try {
                !previewRequested || engine.startListening()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post {
                if (generation != operationGeneration) return@post
                if (!result.success || (previewRequested && !previewStarted)) {
                    if (previewRequested && !previewStarted && engine.lastStartFailedOnOutput) {
                        // raw 临时目录/文件创建失败是存储问题：回落麦克风解决不了还会
                        // 改写来源偏好，直接按文件输出故障失败。
                        failPreparedCapture(AudioRecordingNotice.FILE_OUTPUT_FAILED)
                    } else if (mode.requiresSystemAudio) {
                        fallbackToMicrophoneInternal(
                            AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED
                        )
                    } else {
                        releaseProjection()
                        updateSnapshot(
                            snapshot.copy(
                                phase = AudioRecordingPhase.ERROR,
                                busy = false,
                                configured = false,
                                hasProjection = false,
                                notice = AudioRecordingNotice.MICROPHONE_UNAVAILABLE
                            )
                        )
                        if (foregroundForPreview) {
                            stopForegroundAndService(removeNotification = true)
                        }
                    }
                    return@post
                }
                if (!mode.requiresSystemAudio) releaseProjection()
                // 只有预览真正启动过（raw 打开 + AudioRecord 启动都验证过）才确认偏好。
                // Dialog 在配置期间转后台时预览被跳过，此时确认会让一次未验证的授权
                // 会话改写默认来源；推迟到回到 Dialog 后 startPreview 成功时补记。
                if (previewRequested) {
                    AudioInputPreferences.save(
                        this,
                        AudioInputSelectionPersistencePolicy.modeToCommitAfterSuccess(mode)
                    )
                }
                updateSnapshot(
                    snapshot.copy(
                        busy = false,
                        configured = true,
                        hasProjection = mediaProjection != null,
                        aecEnabled = result.aecEnabled,
                        // 同 startPreview 成功沿：兜底清掉配置期间迟到入队的旧静音警告。
                        systemSilent = false
                    )
                )
                if (!mode.requiresSystemAudio && foregroundForPreview) {
                    stopForegroundAndService(removeNotification = true)
                }
                reconcilePreparedPreview()
            }
        }
    }

    private fun prepareSystemModeInternal(mode: AudioInputMode, resultCode: Int, data: Intent) {
        if (BuildConfig.DEBUG) android.util.Log.i("AudioRecProbe", "svc prepareSystemMode mode=$mode")
        if (!mode.requiresSystemAudio || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
            return
        }
        releaseProjection()
        try {
            ensureForeground(preview = true, mode = mode)
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
                return
            }
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { handleProjectionStopped(projection) }
                }
            }
            mediaProjection = projection
            projectionCallback = callback
            projection.registerCallback(callback, mainHandler)
            prepareConfiguredMode(mode)
        } catch (error: Throwable) {
            android.util.Log.e("AudioRecProbe", "svc prepareSystemMode failed", error)
            releaseProjection()
            fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
        }
    }

    private fun fallbackToMicrophoneInternal(notice: AudioRecordingNotice) {
        if (BuildConfig.DEBUG) android.util.Log.i("AudioRecProbe", "svc fallbackToMicrophone notice=$notice")
        AudioInputPreferences.save(
            this,
            AudioInputSelectionPersistencePolicy.modeToCommitOnFallback()
        )
        val generation = ++operationGeneration
        updateSnapshot(
            snapshot.copy(
                phase = AudioRecordingPhase.PREPARED,
                inputMode = AudioInputMode.MICROPHONE,
                busy = true,
                configured = false,
                hasProjection = false,
                recordedDurationMillis = 0L,
                savedFile = null,
                systemSilent = false,
                aecEnabled = false,
                notice = notice
            )
        )
        schedulePreparedOperationTimeout(generation, AudioInputMode.MICROPHONE)
        submitEngineTask(generation, AudioInputMode.MICROPHONE) {
            val result = try {
                engine.configure(AudioInputMode.MICROPHONE, null)
            } catch (error: Throwable) {
                AudioCaptureEngine.ConfigurationResult(false, error = error.message)
            }
            val previewRequested = result.success && dialogVisible
            val previewStarted = try {
                !previewRequested || engine.startListening()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post {
                if (generation != operationGeneration) return@post
                // 先由串行采集任务停掉旧的系统 AudioRecord，再结束投影，避免把主动
                // 切换来源误判为一次系统捕获硬故障。
                releaseProjection()
                updateSnapshot(
                    snapshot.copy(
                        phase = if (result.success && previewStarted) {
                            AudioRecordingPhase.PREPARED
                        } else {
                            AudioRecordingPhase.ERROR
                        },
                        busy = false,
                        configured = result.success && previewStarted,
                        notice = when {
                            result.success && previewStarted -> notice
                            // startListening 真被调用且因 raw 文件失败：麦克风是好的，
                            // 报"无法启动麦克风"会把用户引向错误的排查方向。
                            previewRequested && !previewStarted &&
                                engine.lastStartFailedOnOutput ->
                                AudioRecordingNotice.FILE_OUTPUT_FAILED
                            else -> AudioRecordingNotice.MICROPHONE_UNAVAILABLE
                        }
                    )
                )
                if (foregroundForPreview) stopForegroundAndService(removeNotification = true)
                reconcilePreparedPreview()
            }
        }
    }

    private fun failPreparedCapture(notice: AudioRecordingNotice) {
        val generation = ++operationGeneration
        updateSnapshot(
            snapshot.copy(
                phase = AudioRecordingPhase.ERROR,
                busy = true,
                configured = false,
                notice = notice
            )
        )
        engineTasks.execute {
            engine.stopListening(saveFile = false)
            mainHandler.post {
                if (generation != operationGeneration) return@post
                releaseProjection()
                if (foregroundForPreview) stopForegroundAndService(removeNotification = true)
                updateSnapshot(
                    snapshot.copy(
                        phase = AudioRecordingPhase.ERROR,
                        busy = false,
                        configured = false,
                        hasProjection = false
                    )
                )
            }
        }
    }

    private fun startPreview() {
        val generation = ++operationGeneration
        val mode = snapshot.inputMode
        // systemSilent 在入口即清：迟到的旧静音事件可能在 stopPreview 清零后写回，
        // 留到启动完成才清会让恢复预览的前几百毫秒~数秒显示假的"未检测到系统音频"
        // ——违反"持续静音约 6 秒才提示"的语义。
        updateSnapshot(
            snapshot.copy(
                busy = true,
                notice = AudioRecordingNotice.NONE,
                systemSilent = false
            )
        )
        if (mode.requiresSystemAudio) {
            try {
                ensureForeground(preview = true, mode = mode)
            } catch (_: Throwable) {
                fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
                return
            }
        }
        schedulePreparedOperationTimeout(generation, mode)
        submitEngineTask(generation, mode) {
            val started = try {
                engine.startListening()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post {
                if (generation != operationGeneration) return@post
                if (!started) {
                    if (engine.lastStartFailedOnOutput) {
                        failPreparedCapture(AudioRecordingNotice.FILE_OUTPUT_FAILED)
                    } else if (mode.requiresSystemAudio) {
                        fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
                    } else {
                        updateSnapshot(
                            snapshot.copy(
                                phase = AudioRecordingPhase.ERROR,
                                busy = false,
                                configured = false,
                                notice = AudioRecordingNotice.MICROPHONE_UNAVAILABLE
                            )
                        )
                    }
                } else {
                    // 预览此刻已真正验证（raw + AudioRecord 全链），补记来源偏好——
                    // 覆盖"配置期间转后台、确认被推迟"的路径；重复写入幂等。
                    AudioInputPreferences.save(
                        this,
                        AudioInputSelectionPersistencePolicy.modeToCommitAfterSuccess(mode)
                    )
                    // systemSilent 一并清零：旧预览迟到的 silent=true 可能在 stopPreview
                    // 清零之后才送达主线程（引擎身份闸只挡"发出时已被替换"的线程，挡不住
                    // 已入队的事件）；新预览启动时兜底纠正，此后由新线程的检测接管。
                    updateSnapshot(snapshot.copy(busy = false, systemSilent = false))
                    reconcilePreparedPreview()
                }
            }
        }
    }

    /**
     * 厂商音频栈若抛出未预期异常或长时间不返回，不能让快照永久停在 busy=true。
     * 系统类来源超时后按既定规则回退麦克风；麦克风自身超时则进入可恢复的 ERROR，
     * Dialog 仍可重新展开来源选择器。
     */
    private fun schedulePreparedOperationTimeout(generation: Long, mode: AudioInputMode) {
        mainHandler.postDelayed({
            if (generation != operationGeneration ||
                snapshot.phase != AudioRecordingPhase.PREPARED ||
                !snapshot.busy ||
                snapshot.inputMode != mode
            ) {
                return@postDelayed
            }
            if (mode.requiresSystemAudio) {
                releaseProjection()
                fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
            } else {
                operationGeneration++
                releaseProjection()
                updateSnapshot(
                    snapshot.copy(
                        phase = AudioRecordingPhase.ERROR,
                        busy = false,
                        configured = false,
                        hasProjection = false,
                        notice = AudioRecordingNotice.MICROPHONE_UNAVAILABLE
                    )
                )
                if (foregroundForPreview) stopForegroundAndService(removeNotification = true)
                // 超时只作废了迟到任务的结果回调，挡不住它的副作用：任务若在超时后
                // 才从 configure/startListening 返回，会留下无人认领的活麦克风。追加
                // 一个串行清理任务（必然排在迟到任务之后）兜底停掉它；若用户随后重试，
                // 新 configure 进门也会 stopListening，这里的清理幂等无害。
                // （系统类分支无需追加：fallbackToMicrophoneInternal 入队的 configure
                // 本身就排在迟到任务之后并在进门时停掉监听。）
                engineTasks.execute { engine.stopListening(saveFile = false) }
            }
        }, PREPARED_OPERATION_TIMEOUT_MS)
    }

    private fun submitEngineTask(
        generation: Long,
        mode: AudioInputMode,
        task: () -> Unit
    ) {
        try {
            engineTasks.execute(task)
        } catch (_: Throwable) {
            mainHandler.post {
                if (generation != operationGeneration) return@post
                if (mode.requiresSystemAudio) {
                    releaseProjection()
                    fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
                } else {
                    operationGeneration++
                    releaseProjection()
                    updateSnapshot(
                        snapshot.copy(
                            phase = AudioRecordingPhase.ERROR,
                            busy = false,
                            configured = false,
                            hasProjection = false,
                            notice = AudioRecordingNotice.MICROPHONE_UNAVAILABLE
                        )
                    )
                    if (foregroundForPreview) {
                        stopForegroundAndService(removeNotification = true)
                    }
                }
            }
        }
    }

    private fun stopPreview() {
        val generation = ++operationGeneration
        updateSnapshot(snapshot.copy(busy = true, systemSilent = false))
        engineTasks.execute {
            engine.stopListening(saveFile = false)
            mainHandler.post {
                if (generation != operationGeneration) return@post
                updateSnapshot(
                    snapshot.copy(
                        busy = false,
                        // 预览期输入流死亡（read 负值）与停止并发时故障回调可能被抑制，
                        // 引擎标志是唯一可靠信号：死过就不许复用，回到 Dialog 走重建。
                        configured = snapshot.configured && !engine.lastInputFaulted
                    )
                )
                if (foregroundForPreview) {
                    if (snapshot.inputMode.requiresSystemAudio && mediaProjection != null) {
                        updatePreparedAuthorizationNotification(snapshot.inputMode)
                    } else {
                        stopForegroundAndService(removeNotification = true)
                    }
                }
                reconcilePreparedPreview()
            }
        }
    }

    private fun reconcilePreparedPreview() {
        if (snapshot.phase != AudioRecordingPhase.PREPARED || snapshot.busy || !snapshot.configured) {
            return
        }
        when {
            dialogVisible && !engine.isListening() -> startPreview()
            !dialogVisible && engine.isListening() -> stopPreview()
            !dialogVisible && foregroundForPreview &&
                (!snapshot.inputMode.requiresSystemAudio || mediaProjection == null) ->
                stopForegroundAndService(removeNotification = true)
            !dialogVisible && foregroundForPreview ->
                updatePreparedAuthorizationNotification(snapshot.inputMode)
        }
    }

    private fun startRecordingInternal(): Boolean {
        if (snapshot.phase != AudioRecordingPhase.PREPARED || snapshot.busy || !snapshot.configured) {
            return false
        }
        val mode = snapshot.inputMode
        if (!engine.isListening() && !engine.startListening()) {
            if (engine.lastStartFailedOnOutput) {
                failPreparedCapture(AudioRecordingNotice.FILE_OUTPUT_FAILED)
            } else if (mode.requiresSystemAudio) {
                fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
            } else {
                updateSnapshot(
                    snapshot.copy(
                        phase = AudioRecordingPhase.ERROR,
                        configured = false,
                        notice = AudioRecordingNotice.MICROPHONE_UNAVAILABLE
                    )
                )
            }
            return false
        }
        ensureForeground(preview = false, mode = mode)
        val file = engine.startRecording() ?: run {
            val generation = ++operationGeneration
            updateSnapshot(
                snapshot.copy(
                    phase = AudioRecordingPhase.ERROR,
                    busy = true,
                    configured = false,
                    // 一个字节都没录上，不能用带"已保留此前录音"的 CAPTURE_FAILED。
                    notice = AudioRecordingNotice.RECORDING_START_FAILED
                )
            )
            engineTasks.execute {
                engine.stopListening(saveFile = false)
                mainHandler.post {
                    if (generation != operationGeneration) return@post
                    releaseProjection()
                    stopForegroundAndService(removeNotification = true)
                    updateSnapshot(
                        snapshot.copy(
                            phase = AudioRecordingPhase.ERROR,
                            busy = false,
                            configured = false,
                            hasProjection = false
                        )
                    )
                }
            }
            return false
        }
        val baseElapsed = SystemClock.elapsedRealtime()
        updateSnapshot(
            snapshot.copy(
                phase = AudioRecordingPhase.RECORDING,
                busy = false,
                recordingBaseElapsed = baseElapsed,
                savedFile = file,
                notice = AudioRecordingNotice.NONE
            )
        )
        updateForegroundNotification(mode, baseElapsed)
        return true
    }

    /**
     * [stopNotice] 是停止的**原因**，只在收尾成功（文件真的保留）后进入完成快照——
     * 收尾期间快照持 [AudioRecordingNotice.FINALIZING]，不提前宣称"已保留"。
     * 是否需要停止完成通知也在完成时刻按当下的 [dialogVisible] 判定，而不是停止瞬间：
     * 长收尾（大文件封装）期间用户可能切换前后台，停止瞬间的判定会过时。
     */
    private fun stopRecordingInternal(stopNotice: AudioRecordingNotice) {
        if (snapshot.phase != AudioRecordingPhase.RECORDING || snapshot.busy) return
        val generation = ++operationGeneration
        val recordedDuration =
            (SystemClock.elapsedRealtime() - snapshot.recordingBaseElapsed).coerceAtLeast(0L)
        updateSnapshot(
            snapshot.copy(
                phase = AudioRecordingPhase.STOPPED,
                busy = true,
                recordedDurationMillis = recordedDuration,
                notice = AudioRecordingNotice.FINALIZING
            )
        )
        updateFinalizingNotification()
        engineTasks.execute {
            val file = engine.stopListening(saveFile = true)
            mainHandler.post {
                if (generation != operationGeneration) return@post
                releaseProjection()
                val completedSnapshot = snapshot.copy(
                    phase = AudioRecordingPhase.STOPPED,
                    busy = false,
                    // lastInputFaulted 在 stopListening（join 采集线程）之后读取，
                    // read 失败处的写入对这里可见。
                    configured = stoppedSessionRemainsConfigured(
                        snapshot.inputMode,
                        stopNotice,
                        engine.lastInputFaulted
                    ),
                    hasProjection = false,
                    savedFile = file,
                    systemSilent = false,
                    notice = when {
                        file != null -> stopNotice
                        engine.lastFinalizeNoSpace -> AudioRecordingNotice.STORAGE_FULL
                        // CAPTURE_FAILED 的文案是"已保留此前录音"，封装失败什么都没保留，
                        // 必须用独立提示，否则给出与事实相反的结论。
                        else -> AudioRecordingNotice.FINALIZE_FAILED
                    }
                )
                updateSnapshot(completedSnapshot)
                if (file != null) {
                    AudioInputPreferences.saveStoppedSession(
                        this,
                        StoppedRecordingSession(
                            file = file,
                            thingId = activeSessionThingId,
                            durationMillis = completedSnapshot.recordedDurationMillis,
                            mode = completedSnapshot.inputMode,
                            notice = completedSnapshot.notice
                        )
                    )
                }
                if (dialogVisible) {
                    stopForegroundAndService(removeNotification = true)
                } else {
                    showStoppedNotification(completedSnapshot)
                }
            }
        }
    }

    /** 停止到收尾完成之间，把仍在走秒的录音通知换成"正在保存"，不再假装录音继续。 */
    private fun updateFinalizingNotification() {
        if (!foregroundActive) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_stop_recording_audio)
            .setContentTitle(getString(R.string.audio_recording_finalizing_notification_title))
            .setContentText(inputModeLabel(snapshot.inputMode))
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NOTIFICATION_GROUP)
            .setColorized(true)
            .setColor(notificationAccentColor())
            .apply { gradientBadge()?.let(::setLargeIcon) }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun restartRecordingInternal(): Boolean {
        if (snapshot.phase != AudioRecordingPhase.STOPPED || snapshot.busy) return true
        val oldFile = snapshot.savedFile
        val mode = snapshot.inputMode
        if (mode.requiresSystemAudio && mediaProjection == null) {
            oldFile?.let { FileUtil.deleteFile(it.absolutePath) }
            updateSnapshot(
                snapshot.copy(
                    phase = AudioRecordingPhase.PREPARED,
                    savedFile = null,
                    configured = false,
                    notice = AudioRecordingNotice.NONE
                )
            )
            return false
        }
        oldFile?.let { FileUtil.deleteFile(it.absolutePath) }
        if (!snapshot.configured) {
            // 跨进程恢复的停止态：引擎还没有 AudioRecord，直接开预览必然报"麦克风不可用"，
            // 走完整配置（含预览启动）。
            prepareConfiguredMode(mode)
            return true
        }
        updateSnapshot(
            snapshot.copy(
                phase = AudioRecordingPhase.PREPARED,
                savedFile = null,
                notice = AudioRecordingNotice.NONE
            )
        )
        if (dialogVisible) startPreview()
        return true
    }

    private fun finishSessionInternal(keepFile: Boolean) {
        // Mic 准备态只有 bound service。先把服务转为 started，避免 Dialog 随后 unbind 时
        // onDestroy 与串行收尾任务并发；任务末尾会 stopSelf()。
        startService(Intent(this, AudioRecordingService::class.java).setAction(ACTION_KEEP_ALIVE))
        activeReturnIntent = null
        activeSessionThingId = -1L
        AudioInputPreferences.clearStoppedSession(this)
        sessionClosing = true
        val generation = ++operationGeneration
        val file = snapshot.savedFile
        updateSnapshot(snapshot.copy(busy = true))
        engineTasks.execute {
            if (engine.isRecording()) engine.stopListening(saveFile = keepFile)
            // 服务可能在旧 Dialog 收尾期间已经被新 Dialog 绑定；保留监听器与新绑定的
            // FableSol 接收器，使同一 Service 实例可以从随后发布的 IDLE 快照开始新会话。
            engine.release(clearConsumers = false)
            if (!keepFile && file != null) FileUtil.deleteFile(file.absolutePath)
            FileUtil.deleteDirectory(
                FileUtil.getTempPath(this) + AudioCaptureEngine.SERVICE_RAW_TEMP_PATH_SEGMENT
            )
            mainHandler.post {
                sessionClosing = false
                if (generation != operationGeneration) return@post
                releaseProjection()
                stopForegroundAndService(removeNotification = true)
                updateSnapshot(AudioRecordingSnapshot())
                stopSelf()
            }
        }
    }

    private fun handleProjectionStopped(projection: MediaProjection) {
        if (mediaProjection !== projection) return
        mediaProjection = null
        projectionCallback = null
        // 收尾在途：投影引用已置空即可，处置（自动停止/回落）会抢占收尾的代次，
        // 且会话本来就在结束，无需任何响应。
        if (sessionClosing) return
        if (snapshot.phase == AudioRecordingPhase.RECORDING) {
            stopRecordingInternal(AudioRecordingNotice.SYSTEM_CAPTURE_REVOKED)
        } else if (snapshot.phase == AudioRecordingPhase.PREPARED) {
            // 准备态还没有录音，REVOKED 的"已保留此前录音"文案不适用。
            fallbackToMicrophoneInternal(AudioRecordingNotice.SYSTEM_CAPTURE_ENDED)
        } else {
            updateSnapshot(snapshot.copy(hasProjection = false, configured = false))
        }
    }

    private fun releaseProjection() {
        val projection = mediaProjection ?: return
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }
        try {
            projection.stop()
        } catch (_: Throwable) {
        }
    }

    private fun ensureForeground(preview: Boolean, mode: AudioInputMode) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AudioRecordingService::class.java).setAction(ACTION_KEEP_ALIVE)
        )
        foregroundForPreview = preview
        val notification = if (preview) {
            buildPreviewNotification(mode)
        } else {
            buildRecordingNotification(mode, SystemClock.elapsedRealtime())
        }
        startForegroundCompat(notification, mode)
        foregroundActive = true
    }

    private fun updateForegroundNotification(mode: AudioInputMode, baseElapsed: Long) {
        if (!foregroundActive) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildRecordingNotification(mode, baseElapsed))
        foregroundForPreview = false
    }

    private fun buildPreviewNotification(mode: AudioInputMode): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_stop_recording_audio)
            .setContentTitle(getString(R.string.audio_input_preview_notification_title))
            .setContentText(inputModeLabel(mode))
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NOTIFICATION_GROUP)
            // colorized 前台服务通知满足 Android 16 的 promotable 条件（ongoing + 标题 +
            // 无自定义视图 + colorized + 默认样式），系统不再把它折进自动聚合组——
            // 聚合组行会吞掉点击，用户点通知就回不到录音 Dialog。
            .setColorized(true)
            .setColor(notificationAccentColor())
            .apply { gradientBadge()?.let(::setLargeIcon) }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updatePreparedAuthorizationNotification(mode: AudioInputMode) {
        if (!foregroundActive || !mode.requiresSystemAudio) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_stop_recording_audio)
            .setContentTitle(getString(R.string.audio_input_authorization_notification_title))
            .setContentText(inputModeLabel(mode))
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NOTIFICATION_GROUP)
            .setColorized(true)
            .setColor(notificationAccentColor())
            .apply { gradientBadge()?.let(::setLargeIcon) }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildRecordingNotification(mode: AudioInputMode, baseElapsed: Long): Notification {
        val stopIntent = Intent(this, AudioRecordingService::class.java).apply {
            action = ACTION_STOP_AND_KEEP
        }
        val wallClockBase = System.currentTimeMillis() -
            (SystemClock.elapsedRealtime() - baseElapsed).coerceAtLeast(0L)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_stop_recording_audio)
            .setContentTitle(getString(R.string.audio_recording_notification_title))
            .setContentText(inputModeLabel(mode))
            .setContentIntent(contentPendingIntent())
            .setWhen(wallClockBase)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NOTIFICATION_GROUP)
            .setColorized(true)
            .setColor(notificationAccentColor())
            .apply { gradientBadge()?.let(::setLargeIcon) }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                getString(R.string.audio_recording_notification_stop_keep),
                PendingIntent.getService(
                    this,
                    REQUEST_STOP,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun showStoppedNotification(state: AudioRecordingSnapshot) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
        foregroundForPreview = false
        // 封装失败（savedFile 为空）时通知不得宣称"已保留"——那是用户看到的第一个结论。
        val kept = state.savedFile != null
        val title = if (kept) {
            getString(R.string.audio_recording_stopped_notification_title)
        } else {
            getString(R.string.audio_recording_stop_not_kept_notification_title)
        }
        val text = if (kept) {
            // 自动停止（容量上限、授权撤销、输入或写入中断）要把原因带到通知正文，
            // 用户不点开 Dialog 也知道为什么停了；主动停止仍显示来源标签。
            when (state.notice) {
                AudioRecordingNotice.SIZE_LIMIT_REACHED ->
                    getString(R.string.audio_recording_size_limit_reached)
                AudioRecordingNotice.SYSTEM_CAPTURE_REVOKED ->
                    getString(R.string.audio_input_projection_revoked)
                AudioRecordingNotice.CAPTURE_FAILED ->
                    getString(R.string.audio_input_capture_failed)
                AudioRecordingNotice.FILE_WRITE_INTERRUPTED ->
                    getString(R.string.audio_recording_file_write_interrupted)
                else -> inputModeLabel(state.inputMode)
            }
        } else {
            getString(
                if (state.notice == AudioRecordingNotice.STORAGE_FULL) {
                    R.string.audio_recording_storage_full
                } else {
                    R.string.audio_recording_finalize_failed
                }
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.act_stop_recording_audio)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(NOTIFICATION_GROUP)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun contentPendingIntent(): PendingIntent? {
        val target = returnIntent?.let(::Intent)
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: return null
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        target.putExtra(EXTRA_OPEN_RECORDING_DIALOG, true)
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun inputModeLabel(mode: AudioInputMode): String = getString(
        when (mode) {
            AudioInputMode.MICROPHONE -> R.string.audio_input_microphone
            AudioInputMode.SYSTEM -> R.string.audio_input_system
            AudioInputMode.SYSTEM_AND_MICROPHONE -> R.string.audio_input_system_and_microphone
        }
    )

    /**
     * 通知底色用录音所属记事的颜色（渐变取项目惯例的代表色）。colorized 背景 API 只收
     * 单色，且自定义视图会破坏 promotable 条件（掉回聚合组、点击失效），因此真实渐变
     * 由 [gradientBadge] 以 largeIcon 呈现。
     */
    private fun notificationAccentColor(): Int =
        sessionAccent?.representativeColor()
            ?: ContextCompat.getColor(this, R.color.app_accent)

    /** 渐变记事的圆形渐变徽章；纯色记事返回 null（底色已经是完整表达）。 */
    private fun gradientBadge(): android.graphics.Bitmap? {
        val background = sessionAccent ?: return null
        if (background.mode != ThingBackground.Mode.GRADIENT) return null
        val size = GRADIENT_BADGE_SIZE_PX
        val edge = size.toFloat()
        val (from, to) = when (background.orientation) {
            ThingBackground.Orientation.L_R -> floatArrayOf(0f, 0f) to floatArrayOf(edge, 0f)
            ThingBackground.Orientation.R_L -> floatArrayOf(edge, 0f) to floatArrayOf(0f, 0f)
            ThingBackground.Orientation.T_B -> floatArrayOf(0f, 0f) to floatArrayOf(0f, edge)
            ThingBackground.Orientation.B_T -> floatArrayOf(0f, edge) to floatArrayOf(0f, 0f)
            ThingBackground.Orientation.LT_RB -> floatArrayOf(0f, 0f) to floatArrayOf(edge, edge)
            ThingBackground.Orientation.RB_LT -> floatArrayOf(edge, edge) to floatArrayOf(0f, 0f)
            ThingBackground.Orientation.RT_LB -> floatArrayOf(edge, 0f) to floatArrayOf(0f, edge)
            ThingBackground.Orientation.LB_RT -> floatArrayOf(0f, edge) to floatArrayOf(edge, 0f)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(
            from[0], from[1], to[0], to[1],
            background.color, background.endColor,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(edge / 2f, edge / 2f, edge / 2f, paint)
        return bitmap
    }

    private fun startForegroundCompat(notification: Notification, mode: AudioInputMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (mode.requiresSystemAudio) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode.requiresMicrophone) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (type != 0) {
                startForeground(NOTIFICATION_ID, notification, type)
                return
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundAndService(removeNotification: Boolean) {
        if (foregroundActive) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        }
        foregroundActive = false
        foregroundForPreview = false
        if (removeNotification) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_DEFAULT + 静音：Android 16 会把低重要度通知折进自动聚合节
        // （Aggregate_AlertingSection），聚合组行的点击只展开分组、不触发 contentIntent，
        // 用户点通知就回不到录音 Dialog。channel 的重要度只能降不能升，因此换新 id 并
        // 删除旧 channel。
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.audio_recording_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.setShowBadge(false)
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    @Synchronized
    private fun currentSnapshot(): AudioRecordingSnapshot = snapshot

    @Synchronized
    private fun updateSnapshot(next: AudioRecordingSnapshot) {
        snapshot = next
        activeSession = next.phase != AudioRecordingPhase.IDLE
        for (observer in observers) observer.onAudioRecordingStateChanged(next)
    }

    companion object {
        const val EXTRA_OPEN_RECORDING_DIALOG =
            "com.ywwynm.everythingdone.extra.OPEN_AUDIO_RECORDING_DIALOG"
        private const val ACTION_KEEP_ALIVE =
            "com.ywwynm.everythingdone.action.AUDIO_RECORDING_KEEP_ALIVE"
        private const val ACTION_STOP_AND_KEEP =
            "com.ywwynm.everythingdone.action.AUDIO_RECORDING_STOP_AND_KEEP"
        private const val LEGACY_CHANNEL_ID = "audio_recording"
        private const val CHANNEL_ID = "audio_recording_v2"
        // 显式分组：无分组的通知会被系统自动聚合，聚合组行吞掉点击（见 ensureNotificationChannel）。
        private const val NOTIFICATION_GROUP = "com.ywwynm.everythingdone.AUDIO_RECORDING"
        private const val NOTIFICATION_ID = 20_260_815
        private const val REQUEST_CONTENT = 81
        private const val REQUEST_STOP = 82
        private const val PREPARED_OPERATION_TIMEOUT_MS = 8_000L
        // 渐变徽章边长（px）：largeIcon 的目标显示约 48dp，3x 密度下取偏好数 144。
        private const val GRADIENT_BADGE_SIZE_PX = 144

        @Volatile
        var activeSession: Boolean = false
            private set

        /** 活跃会话的返回入口与所属记事；供图标接力与通知落点校验使用。 */
        @Volatile
        var activeReturnIntent: Intent? = null
            private set

        @Volatile
        var activeSessionThingId: Long = -1L
            private set
    }
}
