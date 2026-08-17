@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Activity
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.services.AudioRecordingService
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.pickers.AudioInputPicker
import com.ywwynm.everythingdone.views.recording.AudioInputMode
import com.ywwynm.everythingdone.views.recording.AudioInputPreferences
import com.ywwynm.everythingdone.views.recording.AudioInputRowPresentation
import com.ywwynm.everythingdone.views.recording.AudioInputRowPresentationPolicy
import com.ywwynm.everythingdone.views.recording.AudioRecordingControlPolicy
import com.ywwynm.everythingdone.views.recording.AudioRecordingNotice
import com.ywwynm.everythingdone.views.recording.AudioRecordingPhase
import com.ywwynm.everythingdone.views.recording.AudioRecordingSnapshot
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolPerformanceMonitor
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolVideoExportLauncher
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolGl
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolHost

import java.io.File
import kotlin.math.roundToInt

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [androidx.fragment.app.DialogFragment] used to record audio.
 */
open class AudioRecordDialogFragment : BaseDialogFragment() {

    private var mActivity: DetailActivity? = null

    private var mState: Int = PREPARED
    private var mRenderedPhase: AudioRecordingPhase? = null

    private var mFileToSave: File? = null

    private var mLlFileName: LinearLayout? = null
    private var mEtFileName: EditText? = null
    private var mClockView: TimelyClockView? = null
    private var mVisualizer: WaveVisualizerFableSolHost? = null
    private var mLlAudioInput: LinearLayout? = null
    private var mLlAudioInputCapsule: LinearLayout? = null
    private var mTvAudioInputLabel: TextView? = null
    private var mTvAudioInputValue: TextView? = null
    private var mIvAudioInputTriangle: ImageView? = null
    private var mTvAudioInputNotice: TextView? = null
    private var mTvFilePostfix: TextView? = null
    private var mAudioInputPicker: AudioInputPicker? = null
    private var mSelectedInputMode: AudioInputMode = AudioInputMode.MICROPHONE

    private var mIvMainAction: ImageView? = null
    private var mIvExportVideo: ImageView? = null
    /** 用户点的是"保存并导出视频"，而不是普通的对号保存。 */
    private var mExportVideoRequested: Boolean = false
    private var mIvReRecording: ImageView? = null
    private var mIvCancelRecording: ImageView? = null

    private var mAccentBackground: ThingBackground? = null

    private var mConfirmClicked: Boolean = false
    private var mRecorderTransitionInProgress: Boolean = false
    private var mRecordingBinder: AudioRecordingService.LocalBinder? = null
    // 与 mRecordingBinder 分开记录：bindService 一经调用成功就必须配对 unbind，
    // 否则"回调到达前关闭 Dialog"会泄漏 ServiceConnection，迟到的回调还能改写会话。
    private var mBindRequested = false
    private var mDialogVisible = false
    private var mSessionClosing = false
    private var mSessionInitializationRequested = false
    private var mProjectionRequestInFlight = false
    private var mPendingProjectionMode: AudioInputMode? = null
    private var mPendingProjectionResult: ActivityResult? = null
    private var mClockBreathing: Boolean = false
    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
    private var mSensorThread: HandlerThread? = null
    private var mTiltSensorRegistered: Boolean = false
    /**
     * 画面是否跟随设备姿态（[FableSolTuning.liveTiltEnabled]）。对话框打开时读一次并固定：
     * 它同时决定要不要锁方向、要不要注册传感器、录音要不要记重力轨迹，中途换值会让这三件
     * 事对不上。关掉时详情页恢复自动旋转。
     */
    private var mLiveTiltEnabled: Boolean = true
    private var mPerformanceMonitor: FableSolPerformanceMonitor? = null
    private var mOriginalRequestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var mOrientationLocked: Boolean = false
    private var mLockedRotation: Int = Surface.ROTATION_0
    private val mClockHandler: Handler = Handler(Looper.getMainLooper())
    private var mRecordingBaseElapsed: Long = 0L
    private val mClockTick: Runnable = object : Runnable {
        override fun run() {
            if (mState != RECORDING || mClockView == null) return
            val elapsed = SystemClock.elapsedRealtime() - mRecordingBaseElapsed
            mClockView!!.setTimeMillis(elapsed, true)
            val delay = 1000L - (elapsed % 1000L)
            mClockHandler.postDelayed(this, delay)
        }
    }
    private val mAudioInputAlignListener = ViewTreeObserver.OnGlobalLayoutListener {
        alignAudioInputToClockContent()
    }

    private val mRecordingObserver = AudioRecordingService.Observer { state ->
        mContentView?.post {
            if (isAdded) renderRecordingSnapshot(state)
        }
    }

    private val mRecordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // 迟到回调防御：视图已销毁的 Fragment 不得注册观察者或改写会话归属。
            if (!isAdded) return
            val recordingBinder = service as? AudioRecordingService.LocalBinder ?: return
            mRecordingBinder = recordingBinder
            // 新建记事的 id 每次进入都会重新生成，记为 -1 跳过通知落点的记事校验，
            // 否则「校验失败 → 按 returnIntent 重启 → 又生成新 id」会循环重启。
            val sessionThingId = mActivity
                ?.takeIf { it.type != DetailActivity.CREATE }
                ?.currentThingId() ?: -1L
            recordingBinder.setSessionSource(
                mActivity?.intent,
                sessionThingId,
                mAccentBackground ?: currentAccentBackground()
            )
            recordingBinder.setGravityTrackEnabled(mLiveTiltEnabled)
            recordingBinder.addObserver(mRecordingObserver)
            // 传感器首个样本到达前先用会话内最后姿态摆正水面，消除重建 Dialog 的倾斜空窗。
            recordingBinder.lastGravitySample()?.let { sample ->
                if (sample.size >= 3) {
                    mVisualizer?.setContainerGravity(sample[0], sample[1], sample[2])
                }
            }
            if (mDialogVisible) {
                mVisualizer?.let(recordingBinder::linkFableSol)
                recordingBinder.setDialogVisible(true)
            }
            consumePendingProjectionResult()
            initializeVisibleSession(recordingBinder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mRecordingBinder = null
            updateControlsEnabled()
        }
    }

    private val mProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (BuildConfig.DEBUG) {
            android.util.Log.i(PROBE_TAG, "projection result=${result.resultCode} binder=${mRecordingBinder != null}")
        }
        mProjectionRequestInFlight = false
        updateControlsEnabled()
        if (mRecordingBinder == null) {
            mPendingProjectionResult = result
        } else {
            handleProjectionResult(result)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        // 系统授权页开着时进程可能被低内存回收；授权结果由 ActivityResult registry 跨
        // 重建送达，但待授权的来源模式必须自己带回来，否则成功授权会被按默认"麦克风"
        // 处理成一次初始化失败。
        savedInstanceState?.let { saved ->
            mProjectionRequestInFlight = saved.getBoolean(STATE_PROJECTION_IN_FLIGHT, false)
            mPendingProjectionMode = saved.getString(STATE_PENDING_PROJECTION_MODE)
                ?.let { value -> AudioInputMode.entries.firstOrNull { it.preferenceValue == value } }
        }

        mActivity = activity as DetailActivity
        mLiveTiltEnabled = FableSolTuning.liveTiltEnabled(mActivity!!)
        lockHostOrientation()
        prepareTiltSensor()

        mLlFileName  = f(R.id.ll_audio_file_name)
        mEtFileName  = f(R.id.et_audio_file_name)
        mClockView   = f(R.id.clock_record_audio)
        mVisualizer  = f(R.id.voice_visualizer)
        mLlAudioInput = f(R.id.ll_audio_input)
        mLlAudioInputCapsule = f(R.id.ll_audio_input_capsule)
        mTvAudioInputLabel = f(R.id.tv_audio_input_label)
        mTvAudioInputValue = f(R.id.tv_audio_input_value)
        mIvAudioInputTriangle = f(R.id.iv_audio_input_triangle)
        mTvAudioInputNotice = f(R.id.tv_audio_input_notice)
        mTvFilePostfix = f(R.id.tv_audio_file_postfix)

        mIvMainAction      = f(R.id.iv_record_main_action)
        mIvReRecording     = f(R.id.iv_re_recording_audio)
        mIvCancelRecording = f(R.id.iv_cancel_recording_audio)
        mIvExportVideo     = f(R.id.iv_export_fablesol_video)

        mAccentBackground = mActivity!!.getAccentBackground()
            ?: ThingBackground.pure(mActivity!!.getAccentColor())
        val accentBg: ThingBackground = mAccentBackground!!
        val accentColor: Int = accentBg.color
        configureClockView(accentBg)
        installAudioInputClockContentAlignment()
        mVisualizer!!.setThingBackground(accentBg)
        createAudioInputPicker(accentBg)
        mSelectedInputMode = AudioInputPreferences.load(requireContext())
        mAudioInputPicker?.pickMode(mSelectedInputMode)
        updateAudioInputValue(mSelectedInputMode)
        showAudioInputForPrepared()

        // 记事色部分（不随深浅色变化）只设一次。
        mEtFileName!!.highlightColor = DisplayUtil.getLightColor(accentColor, mActivity)
        DisplayUtil.setSelectionHandlersColor(mEtFileName, accentColor)

        applyChromeAppearance()

        setEvents()
        // setOnClickListener 会把两个侧边键置为 clickable，但它们此刻 alpha=0：
        // 不在这里按状态收一次，准备态下点到取消键的位置就会把对话框关掉。
        updateControlsEnabled()

        val serviceIntent = Intent(mActivity, AudioRecordingService::class.java)
        mBindRequested = mActivity!!.bindService(
            serviceIntent,
            mRecordingServiceConnection,
            Context.BIND_AUTO_CREATE
        )

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_record_audio

    override fun onStart() {
        super.onStart()
        mDialogVisible = true
        if (mState == RECORDING && mRecordingBaseElapsed > 0L) {
            mClockView?.setTimeMillis(
                (SystemClock.elapsedRealtime() - mRecordingBaseElapsed).coerceAtLeast(0L),
                false
            )
            startClockTicker()
        }
        mRecordingBinder?.let { binder ->
            mVisualizer?.clearPendingAudio()
            mVisualizer?.let(binder::linkFableSol)
            binder.setDialogVisible(true)
            initializeVisibleSession(binder)
        }
        val window = dialog?.window ?: return
        val attributes = window.attributes
        attributes.preferredRefreshRate = TARGET_REFRESH_RATE
        window.attributes = attributes
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 的自适应刷新率默认给窗口投「省电平衡」票，会把对话框里的
            // 普通 View 压到 NORMAL（约 60Hz）。水面是连续动画，需要显式退出该策略；
            // 只作用于本对话框窗口，不影响背后的 Activity。
            window.isFrameRatePowerSavingsBalanced = false
            // 注意：不要对 mVisualizer（FrameLayout 宿主）调 setRequestedFrameRate——
            // 该偏好既不向子 View 传播，其聚合到的窗口图层又被 ViewRootImpl 以
            // FRAME_RATE_SELECTION_STRATEGY_SELF 禁止下传给 SurfaceView 子图层。
            // GL 图层的帧率票由 WaveVisualizerFableSolGl 直接对 Surface 投。
        }
        if (BuildConfig.DEBUG && mPerformanceMonitor == null) {
            val monitor = FableSolPerformanceMonitor(window.context)
            mPerformanceMonitor = monitor
            mVisualizer?.setPerformanceMonitor(monitor)
            monitor.start(window)
        }
    }

    override fun onResume() {
        super.onResume()
        startTiltSensor()
    }

    override fun onPause() {
        stopTiltSensor()
        super.onPause()
    }

    override fun onStop() {
        mDialogVisible = false
        stopClockTicker()
        mAudioInputPicker?.dismiss()
        mRecordingBinder?.let { binder ->
            mVisualizer?.let(binder::unlinkFableSol)
            mVisualizer?.clearPendingAudio()
            // dismiss 流程里 onDismiss 已先调 finishSession（收尾任务在途），此时再同步
            // 可见性会触发服务的 stopPreview 抢占 operationGeneration，作废收尾的完成
            // 回调（留下假 PREPARED 快照、投影与前台通知泄漏）。会话在关就不再同步。
            if (!mSessionClosing) binder.setDialogVisible(false)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PROJECTION_IN_FLIGHT, mProjectionRequestInFlight)
        outState.putString(
            STATE_PENDING_PROJECTION_MODE,
            mPendingProjectionMode?.preferenceValue
        )
    }

    override fun onDestroyView() {
        removeAudioInputClockContentAlignment()
        mRecordingBinder?.let { binder ->
            binder.removeObserver(mRecordingObserver)
            mVisualizer?.let(binder::unlinkFableSol)
        }
        if (mBindRequested) {
            try {
                mActivity?.unbindService(mRecordingServiceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
        mRecordingBinder = null
        mBindRequested = false
        mAudioInputPicker?.dismiss()
        mAudioInputPicker = null
        stopTiltSensor()
        stopPerformanceMonitor()
        restoreHostOrientation()
        mVisualizer?.setContainerGravity(0f, 1f, 0f)
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        mSessionClosing = true
        val recordedFile = mFileToSave
        val keepFile = mConfirmClicked && recordedFile != null
        if (keepFile) {
            val parent: File = recordedFile!!.parentFile!!

            val name: String = mEtFileName!!.text.toString()
            val fileToSave = File(parent, "$name.wav")
            var pathName: String = recordedFile.absolutePath
            val renamed: Boolean = recordedFile.renameTo(fileToSave)
            if (renamed) {
                pathName = fileToSave.absolutePath
            }

            mActivity!!.attachmentTypePathName = AttachmentHelper.AUDIO.toString() + pathName
            mActivity!!.addAttachment(0)
            if (mExportVideoRequested) {
                FableSolVideoExportLauncher.launch(
                    mActivity!!,
                    pathName,
                    currentAccentBackground(),
                    currentAccentBackground(),
                    mVisualizer
                )
            }
        }
        // 宿主被系统清栈（singleTask 回首页等）时不结束会话：录音要作为前台服务继续，
        // 用户可从通知或桌面图标接力回来。只有存活宿主上的主动关闭才收尾。
        if (mActivity?.isFinishing != true) {
            mRecordingBinder?.finishSession(keepFile)
        }
        stopClockTicker()
        stopClockBreathing()
        mClockHandler.removeCallbacks(mClockIntro)
        stopTiltSensor()
        stopPerformanceMonitor()
        restoreHostOrientation()

        super.onDismiss(dialog)
    }

    private val mTiltListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            dispatchGravityToVisualizer(event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 锁方向只为倾斜服务：重力到屏幕坐标的换算按打开时的 rotation 定死，中途转屏就会算错。
     * 因此画面不跟随倾斜时不锁——详情页照常自动旋转。
     */
    private fun lockHostOrientation() {
        val host = mActivity ?: return
        if (!mLiveTiltEnabled) return
        if (mOrientationLocked) return
        mLockedRotation = host.windowManager.defaultDisplay.rotation
        mOriginalRequestedOrientation = host.requestedOrientation
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        mOrientationLocked = true
    }

    private fun restoreHostOrientation() {
        val host = mActivity ?: return
        if (!mOrientationLocked) return
        host.requestedOrientation = mOriginalRequestedOrientation
        mOrientationLocked = false
    }

    private fun prepareTiltSensor() {
        if (!mLiveTiltEnabled) return
        val host = mActivity ?: return
        val manager = host.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        mSensorManager = manager
        mGravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun startTiltSensor() {
        val manager = mSensorManager ?: return
        val sensor = mGravitySensor ?: return
        if (mTiltSensorRegistered) return
        val thread = HandlerThread("FableSolTiltSensor").also { it.start() }
        mSensorThread = thread
        mTiltSensorRegistered = manager.registerListener(
            mTiltListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
            Handler(thread.looper)
        )
        if (!mTiltSensorRegistered) {
            thread.quitSafely()
            mSensorThread = null
        }
    }

    private fun stopTiltSensor() {
        if (mTiltSensorRegistered) {
            mSensorManager?.unregisterListener(mTiltListener)
            mTiltSensorRegistered = false
        }
        mSensorThread?.quitSafely()
        mSensorThread = null
    }

    private fun stopPerformanceMonitor() {
        mVisualizer?.setPerformanceMonitor(null)
        mPerformanceMonitor?.stop()
        mPerformanceMonitor = null
    }

    private fun dispatchGravityToVisualizer(gx: Float, gy: Float, gz: Float) {
        val (screenX, screenY) = when (mLockedRotation) {
            Surface.ROTATION_90 -> -gy to gx
            Surface.ROTATION_180 -> -gx to -gy
            Surface.ROTATION_270 -> gy to -gx
            else -> gx to gy
        }
        mVisualizer?.setContainerGravity(-screenX, screenY, gz)
        // 记进重力轨迹的是**送给可视化的那三个分量**，不是原始传感器读数：屏幕旋转补偿
        // 已经在上面做完，离线重新渲染时直接回放即可，无需再关心当时锁的是哪个方向。
        mRecordingBinder?.offerGravitySample(-screenX, screenY, gz)
    }

    private fun setEvents() {
        val accentBg: ThingBackground? = mAccentBackground
        val accentColor: Int = accentBg?.color ?: mActivity!!.getAccentColor()
        mEtFileName!!.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val useGradientLine = hasFocus
                    && accentBg != null
                    && accentBg.mode === ThingBackground.Mode.GRADIENT
            if (hasFocus) {
                if (useGradientLine) {
                    BackgroundUtil.applyEditTextUnderline(mEtFileName, accentBg)
                    // Hide native underline so only the gradient strip shows.
                    DisplayUtil.tintView(mEtFileName, Color.TRANSPARENT)
                } else {
                    BackgroundUtil.clearEditTextUnderline(mEtFileName)
                    DisplayUtil.tintView(mEtFileName, accentColor)
                }
            } else {
                // 实时取色：深浅色原地切换后，失焦恢复的下划线要用当前配置的 chrome 色。
                DisplayUtil.tintView(
                    mEtFileName,
                    ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_hint)
                )
                BackgroundUtil.clearEditTextUnderline(mEtFileName)
            }
        }

        mEtFileName!!.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveFileAndLeave()
                return@OnEditorActionListener true
            }
            false
        })

        mIvMainAction!!.setOnClickListener {
            if (mRecorderTransitionInProgress) {
                return@setOnClickListener
            }
            when (mState) {
                PREPARED -> mRecordingBinder?.startRecording()
                RECORDING -> mRecordingBinder?.stopRecording()
                else -> saveFileAndLeave()
            }
        }

        mIvReRecording!!.setOnClickListener {
            val binder = mRecordingBinder ?: return@setOnClickListener
            if (!binder.restartRecording()) {
                requestMediaProjection(mSelectedInputMode)
            }
        }

        mIvCancelRecording!!.setOnClickListener { dismiss() }

        mLlAudioInputCapsule!!.setOnClickListener {
            if (mLlAudioInputCapsule?.isClickable == true) {
                mAudioInputPicker?.show()
            }
        }

        mIvExportVideo!!.setOnClickListener {
            if (mRecorderTransitionInProgress) return@setOnClickListener
            // GLES 可能在对话框开着的时候才异步回退到 Canvas；那之后这个按钮点了也只会
            // 启动一个必然失败的离线 GLES 导出，所以按下时再查一次。
            if (!FableSolVideoExportLauncher.isSupported(mVisualizer)) {
                mIvExportVideo!!.visibility = View.GONE
                return@setOnClickListener
            }
            mExportVideoRequested = true
            saveFileAndLeave()
        }
    }

    /**
     * 停止态才出现的"保存并导出视频"。GLES 不可用（走了 Canvas 回退）时整个按钮不出现，
     * 而不是点了才失败——离线渲染同样依赖 GLES（fablesol-video-export D14）。
     */
    private fun showExportVideoAction(confirmBg: ThingBackground) {
        val button = mIvExportVideo ?: return
        if (!FableSolVideoExportLauncher.isSupported(mVisualizer)) return
        // GLES 是异步失败的：回退发生时立刻隐藏，不用等到用户点一下才发现。
        mVisualizer?.onGlFallback = { button.post { button.visibility = View.GONE } }
        button.visibility = View.VISIBLE
        BackgroundUtil.applyOvalBackground(button, confirmBg)
        button.foreground = BackgroundUtil.circularRipple(
            BackgroundUtil.adaptiveRippleColor(confirmBg)
        )
        button.setImageDrawable(
            DisplayUtil.opaqueTintDrawable(
                mActivity!!,
                ContextCompat.getDrawable(mActivity!!, R.drawable.act_fablesol_export_video),
                BackgroundUtil.onColor(confirmBg, MAIN_BUTTON_CONFIRM_ICON_ALPHA)
            )
        )
        button.isClickable = true
        button.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
    }

    private fun preparedToRecording(baseElapsed: Long) {
        mClockHandler.removeCallbacks(mClockIntro)
        mRecordingBaseElapsed = baseElapsed
        val elapsed = (SystemClock.elapsedRealtime() - baseElapsed).coerceAtLeast(0L)
        mClockView!!.setTimeMillis(elapsed, false)
        startClockTicker()
        mClockView!!.animate()
            .alpha(CLOCK_RECORDING_ALPHA_HIGH)
            .setDuration(ANIM_DURATION.toLong())
            .withEndAction { startClockBreathing() }

        // HDR 高光是否随录音激活由设置里的调参 Dialog 决定（默认开）。
        mVisualizer!!.setRecordingHdrActive(FableSolTuning.isHdrEnabled(mActivity!!))
        mVisualizer!!.animatePresentationAlpha(1.0f, ANIM_DURATION.toLong())
        applyMainButtonNormalStyle()
        setMainButtonIcon(R.drawable.act_stop_recording_audio)

        mIvMainAction!!.contentDescription = getString(R.string.cd_stop_record_audio)
    }

    private fun recordingToStopped() {
        // 文件相关 UI（文件名行、导出入口、保存可用性视觉）不在这里处理：停止的中间
        // 快照（busy=true）里 savedFile 还是录音期的旧值，封装结果要等完成快照，而
        // phase 不再变化——由 applyStoppedFileUi 按完成快照水平触发。
        mStoppedFileUiKept = null
        stopClockTicker()
        stopClockBreathing()
        // 从通知停止后重建的 Dialog 没经历过录音计时，时钟要用服务记下的最终时长；
        // Dialog 内正常停止时 ticker 已停在同一值，重设无副作用。
        if (mLastSnapshot.recordedDurationMillis > 0L) {
            mClockView!!.setTimeMillis(mLastSnapshot.recordedDurationMillis, false)
        }
        mClockView!!.animate()
            .alpha(CLOCK_RECORDING_ALPHA_HIGH)
            .translationY(clockStoppedTranslationY())
            .setDuration(ANIM_DURATION.toLong())

        mVisualizer!!.setRecordingHdrActive(false)
        mVisualizer!!.animatePresentationAlpha(
            FABLESOL_IDLE_PRESENTATION_ALPHA,
            ANIM_DURATION.toLong()
        )

        val confirmBg: ThingBackground = currentAccentBackground()
        applyMainButtonConfirmStyle(confirmBg)
        setMainButtonIcon(R.drawable.act_save_audio, BackgroundUtil.onColor(confirmBg, MAIN_BUTTON_CONFIRM_ICON_ALPHA))
        // 收尾（封装）还没出结果，保存与重录先以淡化呈现；完成快照按结果恢复。
        // 取消不淡化——收尾期间允许直接放弃，不必等封装完成。
        mIvMainAction!!.alpha = MAIN_BUTTON_DISABLED_ALPHA

        // 三个副按钮此前只改 alpha、始终占位，主按钮因此在准备/录音态被挤得偏心。
        // 改为 visibility 驱动：准备与录音态整行只有主按钮，它严格居中。
        mIvReRecording!!.visibility = View.VISIBLE
        mIvCancelRecording!!.visibility = View.VISIBLE
        mIvReRecording!!.animate().alpha(MAIN_BUTTON_DISABLED_ALPHA).setDuration(ANIM_DURATION.toLong())
        mIvCancelRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())

        mIvMainAction!!.contentDescription = getString(R.string.cd_save_recorded_audio_file)
    }

    private fun stoppedToPrepared() {
        mVisualizer!!.setRecordingHdrActive(false)
        mStoppedFileUiKept = null
        mIvMainAction!!.alpha = 1f
        mLlFileName!!.animate().translationY(-mActivity!!.screenDensity * 72).setDuration(ANIM_DURATION.toLong())
        stopClockTicker()
        stopClockBreathing()
        mClockHandler.removeCallbacks(mClockIntro)
        mClockView!!.setTimeMillis(0L, false)
        mClockView!!.animate()
            .alpha(CLOCK_PREPARED_ALPHA)
            .translationY(0f)
            .setDuration(ANIM_DURATION.toLong())

        applyMainButtonNormalStyle()
        setMainButtonIcon(R.drawable.act_start_recording_audio)

        mIvReRecording!!.isClickable = false
        mIvCancelRecording!!.isClickable = false
        hideSecondaryAction(mIvReRecording)
        hideSecondaryAction(mIvCancelRecording)
        hideSecondaryAction(mIvExportVideo)

        mIvMainAction!!.contentDescription = getString(R.string.cd_start_record_audio)
    }

    private var mLastSnapshot = AudioRecordingSnapshot()

    /** 停止完成态的文件 UI 当前形态；null 表示尚未按完成快照应用。 */
    private var mStoppedFileUiKept: Boolean? = null

    /**
     * 停止完成（busy=false）后按封装结果应用文件相关 UI。成功：文件名行下移显示、
     * 导出入口出现、保存与重录恢复满色；失败：文件 UI 不出现、保存保持淡化（点击
     * 已由策略禁用）、无障碍描述换成"未能保留"，且把 recordingToStopped 为文件名行
     * 让位而下移的时钟收回原位——否则失败态顶部会留出 80dp 的空白。幂等，同一形态
     * 只应用一次。
     */
    private fun applyStoppedFileUi(kept: Boolean) {
        if (mStoppedFileUiKept == kept) return
        mStoppedFileUiKept = kept
        // 收尾结束，重录恢复可用（收尾期间它与保存一起淡化）。
        mIvReRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        if (kept) {
            mLlFileName!!.animate()
                .translationY(mActivity!!.screenDensity * FILE_NAME_STOPPED_TRANSLATION_Y_DP)
                .setDuration(ANIM_DURATION.toLong())
            mFileToSave?.name?.let { name ->
                mEtFileName!!.setText(if (name.endsWith(".wav")) name.dropLast(4) else name)
            }
            mIvMainAction!!.alpha = 1f
            mIvMainAction!!.contentDescription = getString(R.string.cd_save_recorded_audio_file)
            showExportVideoAction(currentAccentBackground())
        } else {
            mEtFileName!!.setText("")
            mIvMainAction!!.alpha = MAIN_BUTTON_DISABLED_ALPHA
            mIvMainAction!!.contentDescription =
                getString(R.string.cd_save_unavailable_not_kept)
            mClockView!!.animate().translationY(0f).setDuration(ANIM_DURATION.toLong())
        }
    }

    private fun renderRecordingSnapshot(state: AudioRecordingSnapshot) {
        mLastSnapshot = state
        mSelectedInputMode = state.inputMode
        mFileToSave = state.savedFile
        updateAudioInputValue(state.inputMode)
        mAudioInputPicker?.pickMode(state.inputMode)

        if (mRenderedPhase != state.phase) {
            mRenderedPhase = state.phase
            when (state.phase) {
                AudioRecordingPhase.RECORDING -> {
                    mState = RECORDING
                    preparedToRecording(state.recordingBaseElapsed)
                }
                AudioRecordingPhase.STOPPED -> {
                    mState = STOPPED
                    recordingToStopped()
                }
                AudioRecordingPhase.PREPARED -> {
                    mState = PREPARED
                    stoppedToPrepared()
                }
                AudioRecordingPhase.IDLE,
                AudioRecordingPhase.ERROR -> {
                    mState = PREPARED
                }
            }
        }
        updateAudioInputPresentation(state.phase)
        updateAudioInputNotice(state)
        updateControlsEnabled()
        if (state.phase == AudioRecordingPhase.STOPPED && !state.busy) {
            applyStoppedFileUi(state.savedFile != null)
        }
        if (state.phase == AudioRecordingPhase.IDLE && !mSessionClosing) {
            mSessionInitializationRequested = false
            mRecordingBinder?.let(::initializeVisibleSession)
        }
    }

    private fun initializeSessionIfNeeded(state: AudioRecordingSnapshot) {
        if (state.phase != AudioRecordingPhase.IDLE) {
            mSessionInitializationRequested = true
            return
        }
        if (mSessionInitializationRequested || mProjectionRequestInFlight) return
        mSessionInitializationRequested = true
        val mode = AudioInputPreferences.load(requireContext())
        mSelectedInputMode = mode
        updateAudioInputValue(mode)
        mAudioInputPicker?.pickMode(mode)
        if (mode.requiresSystemAudio) {
            requestMediaProjection(mode)
        } else {
            mRecordingBinder?.prepareMode(mode)
        }
    }

    private fun initializeVisibleSession(binder: AudioRecordingService.LocalBinder) {
        mContentView?.post {
            if (!isAdded || !mDialogVisible || mSessionClosing || mRecordingBinder !== binder) {
                return@post
            }
            initializeSessionIfNeeded(binder.snapshot())
        }
    }

    private fun requestMediaProjection(mode: AudioInputMode) {
        if (!mode.requiresSystemAudio || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (mProjectionRequestInFlight) return
        mPendingProjectionMode = mode
        mProjectionRequestInFlight = true
        updateControlsEnabled()
        val manager = requireContext().getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        App.suppressPrivacyAuthClearForActivityResult()
        mProjectionLauncher.launch(intent)
    }

    private fun handleProjectionResult(result: ActivityResult) {
        val binder = mRecordingBinder ?: run {
            mPendingProjectionResult = result
            return
        }
        val mode = mPendingProjectionMode ?: mSelectedInputMode
        mPendingProjectionMode = null
        val data = result.data
        try {
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                binder.prepareSystemMode(mode, result.resultCode, data)
            } else {
                binder.fallbackToMicrophone(AudioRecordingNotice.PROJECTION_DENIED)
            }
        } catch (error: Throwable) {
            android.util.Log.e(PROBE_TAG, "handleResult failed", error)
            binder.fallbackToMicrophone(AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED)
        }
        updateControlsEnabled()
    }

    private fun consumePendingProjectionResult() {
        val result = mPendingProjectionResult ?: return
        mPendingProjectionResult = null
        handleProjectionResult(result)
    }

    private fun selectAudioInput(mode: AudioInputMode) {
        if (!isAdded || mState != PREPARED || mLlAudioInputCapsule?.isClickable != true) return
        if (mode == mSelectedInputMode && mLastSnapshot.configured) return
        mSelectedInputMode = mode
        updateAudioInputValue(mode)
        val binder = mRecordingBinder ?: return
        if (!binder.prepareMode(mode)) requestMediaProjection(mode)
    }

    /** 只构建 Popup 与胶囊样式；当前选择的加载与来源行可见性由调用方按场景处理。 */
    private fun createAudioInputPicker(accentBackground: ThingBackground) {
        val systemEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val items = listOf(
            AudioInputPicker.Item(
                AudioInputMode.MICROPHONE,
                getString(R.string.audio_input_microphone),
                getString(R.string.audio_input_microphone_summary)
            ),
            AudioInputPicker.Item(
                AudioInputMode.SYSTEM,
                getString(R.string.audio_input_system),
                getString(R.string.audio_input_system_summary),
                systemEnabled
            ),
            AudioInputPicker.Item(
                AudioInputMode.SYSTEM_AND_MICROPHONE,
                getString(R.string.audio_input_system_and_microphone),
                getString(R.string.audio_input_system_and_microphone_summary),
                systemEnabled
            )
        )
        mAudioInputPicker = AudioInputPicker(
            mActivity!!,
            mContentView!!,
            items,
            mSelectedInputMode,
            accentBackground
        ) { item -> selectAudioInput(item.mode) }.also { picker ->
            picker.setAnchor(mLlAudioInputCapsule!!)
        }
        applyAudioInputCapsuleStyle(accentBackground)
    }

    /**
     * Chrome 层（随深浅色变化的表面、图标、文字色）的全部取色集中于此；onCreateView 与
     * 宿主深浅色原地切换各调一次。记事色部分（水面、时钟墨色、来源胶囊、停止态主按钮）
     * 不随深浅色变化，不在此列。
     */
    private fun applyChromeAppearance() {
        val host = mActivity ?: return
        dialog?.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(host, R.drawable.bg_app_chrome_surface_elevated_rounded)
        )
        BackgroundUtil.installAppChromeCircleRipple(mIvMainAction, host)
        BackgroundUtil.installAppChromeCircleRipple(mIvReRecording, host)
        BackgroundUtil.installAppChromeCircleRipple(mIvCancelRecording, host)
        installSideControlScrim(mIvReRecording)
        installSideControlScrim(mIvCancelRecording)
        applySecondaryIcon(mIvReRecording, R.drawable.act_re_recording_audio)
        applySecondaryIcon(mIvCancelRecording, R.drawable.act_cancel_recording_audio)
        when (mState) {
            PREPARED -> {
                applyMainButtonNormalStyle()
                setMainButtonIcon(R.drawable.act_start_recording_audio)
            }
            RECORDING -> {
                applyMainButtonNormalStyle()
                setMainButtonIcon(R.drawable.act_stop_recording_audio)
            }
            else -> Unit
        }

        val hint = ContextCompat.getColor(host, R.color.app_chrome_on_surface_hint)
        val secondary = ContextCompat.getColor(host, R.color.app_chrome_on_surface_secondary)
        mEtFileName?.let { editText ->
            editText.setTextColor(secondary)
            editText.setHintTextColor(hint)
            if (!editText.hasFocus()) DisplayUtil.tintView(editText, hint)
        }
        mTvFilePostfix?.setTextColor(secondary)
        mTvAudioInputLabel?.setTextColor(hint)
        mTvAudioInputNotice?.setTextColor(secondary)
        mClockView?.setHostDark(AppearanceUtil.isDarkMode(host))
    }

    private fun applySecondaryIcon(view: ImageView?, iconRes: Int) {
        val host = mActivity ?: return
        val target = view ?: return
        if (AppearanceUtil.isDarkMode(host)) {
            target.imageTintList = null
            target.setImageDrawable(
                DisplayUtil.opaqueTintDrawable(
                    host,
                    ContextCompat.getDrawable(host, iconRes),
                    ContextCompat.getColor(host, R.color.app_chrome_control_unchecked)
                )
            )
        } else {
            target.setImageDrawable(ContextCompat.getDrawable(host, iconRes))
            target.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(host, R.color.app_chrome_control_unchecked)
            )
        }
    }

    /**
     * 宿主记事入库（新建首存）后升级录音会话归属为正式 id；已有正式归属时服务侧幂等
     * 跳过。此后跨记事拦截、停止态跨进程恢复与图标接力对本会话全部生效。
     */
    fun onHostThingSaved() {
        val host = mActivity ?: return
        val thingId = host.currentThingId()
        if (thingId == -1L) return
        mRecordingBinder?.upgradeSessionThing(
            thingId,
            DetailActivity.getOpenIntentForUpdate(host, null, thingId, -1)
        )
    }

    /**
     * 宿主处理深浅色变化（DetailActivity 声明了 uiMode configChanges）时原地换肤：录音
     * 会话与水面动画全程不断——dismiss 重开会把进行中的录音当作用户取消而丢弃。水面与
     * 记事色元素不依赖深浅色，无需处理；Popup 的表面与文字用 chrome 色，重建一个。
     */
    fun onHostAppearanceChanged() {
        if (!isAdded || mActivity == null) return
        applyChromeAppearance()
        mAudioInputPicker?.dismiss()
        createAudioInputPicker(currentAccentBackground())
        mAudioInputPicker?.pickMode(mSelectedInputMode)
        updateAudioInputValue(mSelectedInputMode)
        updateAudioInputPresentation(mLastSnapshot.phase)
        updateControlsEnabled()
    }

    private fun applyAudioInputCapsuleStyle(accentBackground: ThingBackground) {
        val capsule = mLlAudioInputCapsule ?: return
        val fill = BackgroundUtil.fillDrawable(accentBackground)
        if (fill is GradientDrawable) {
            fill.cornerRadius = mActivity!!.screenDensity * AUDIO_INPUT_CAPSULE_RADIUS_DP
        }
        fill.alpha = (255f * FABLESOL_IDLE_PRESENTATION_ALPHA + 0.5f).toInt()
        capsule.background = fill
        capsule.foreground = GradientRippleDrawable(
            accentBackground,
            shapeOval = false,
            cornerRadiusPx = -1f
        )
        capsule.clipToOutline = true

        mIvAudioInputTriangle?.setImageDrawable(
            DisplayUtil.opaqueTintDrawable(
                mActivity!!,
                ContextCompat.getDrawable(mActivity!!, R.drawable.ic_dropdown),
                accentBackground.representativeColor()
            )
        )
    }

    private fun updateAudioInputValue(mode: AudioInputMode) {
        val value = mTvAudioInputValue ?: return
        value.text = getString(
            when (mode) {
                AudioInputMode.MICROPHONE -> R.string.audio_input_microphone
                AudioInputMode.SYSTEM -> R.string.audio_input_system
                AudioInputMode.SYSTEM_AND_MICROPHONE -> R.string.audio_input_system_and_microphone
            }
        )
        val background = mAccentBackground
        if (background != null) {
            BackgroundUtil.applyTextBackground(value, background)
        }
    }

    private fun showAudioInputForPrepared() {
        positionAudioInput(AUDIO_INPUT_PREPARED_TOP_DP)
        mLlAudioInput?.visibility = View.VISIBLE
        mIvAudioInputTriangle?.visibility = View.VISIBLE
    }

    private fun hideAudioInput() {
        mAudioInputPicker?.dismiss()
        mLlAudioInput?.visibility = View.GONE
    }

    private fun updateAudioInputPresentation(phase: AudioRecordingPhase) {
        when (AudioInputRowPresentationPolicy.forPhase(phase)) {
            AudioInputRowPresentation.EDITABLE -> showAudioInputForPrepared()
            AudioInputRowPresentation.HIDDEN -> hideAudioInput()
        }
    }

    private fun positionAudioInput(topDp: Float) {
        val view = mLlAudioInput ?: return
        val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        params.topMargin = (mActivity!!.screenDensity * topDp).toInt()
        view.layoutParams = params
    }

    private fun updateAudioInputNotice(state: AudioRecordingSnapshot) {
        val notice = mTvAudioInputNotice ?: return
        val message = when {
            state.notice == AudioRecordingNotice.FINALIZING ->
                R.string.audio_recording_finalizing
            state.notice == AudioRecordingNotice.PROJECTION_DENIED ->
                R.string.audio_input_projection_denied
            state.notice == AudioRecordingNotice.SYSTEM_INITIALIZATION_FAILED ->
                R.string.audio_input_system_initialization_failed
            state.notice == AudioRecordingNotice.SYSTEM_CAPTURE_REVOKED ->
                R.string.audio_input_projection_revoked
            state.notice == AudioRecordingNotice.SYSTEM_CAPTURE_ENDED ->
                R.string.audio_input_system_capture_ended
            state.notice == AudioRecordingNotice.CAPTURE_FAILED ->
                R.string.audio_input_capture_failed
            state.notice == AudioRecordingNotice.MICROPHONE_UNAVAILABLE ->
                R.string.audio_input_microphone_unavailable
            state.notice == AudioRecordingNotice.RECORDING_START_FAILED ->
                R.string.audio_input_recording_start_failed
            state.notice == AudioRecordingNotice.FILE_OUTPUT_FAILED ->
                R.string.audio_recording_file_output_failed
            state.notice == AudioRecordingNotice.FILE_WRITE_INTERRUPTED ->
                R.string.audio_recording_file_write_interrupted
            state.notice == AudioRecordingNotice.SIZE_LIMIT_REACHED ->
                R.string.audio_recording_size_limit_reached
            state.notice == AudioRecordingNotice.STORAGE_FULL ->
                R.string.audio_recording_storage_full
            state.notice == AudioRecordingNotice.FINALIZE_FAILED ->
                R.string.audio_recording_finalize_failed
            state.systemSilent -> R.string.audio_input_system_silent
            state.configured && state.inputMode == AudioInputMode.SYSTEM_AND_MICROPHONE &&
                !state.aecEnabled -> R.string.audio_input_aec_unavailable
            else -> 0
        }
        if (message == 0) {
            notice.visibility = View.GONE
        } else {
            // Dialog 窗口是 WRAP_CONTENT，宽度由 TimelyClockView 的测量宽决定；长提示
            // 文本若参与首轮测量会把 dialog 撑宽。提示必须用定型后的宽度换行显示：
            // 首次布局尚未完成时（跨进程恢复首帧就带提示的场景）延后一帧再显示。
            val host = mContentView
            if (host == null || host.width == 0) {
                host?.post { if (isAdded) updateAudioInputNotice(mLastSnapshot) }
                return
            }
            notice.maxWidth =
                host.width - (mActivity!!.screenDensity * 2 * AUDIO_NOTICE_SIDE_MARGIN_DP).toInt()
            val params = notice.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (params != null) {
                params.topMargin = (mActivity!!.screenDensity * when (mState) {
                    RECORDING -> AUDIO_NOTICE_RECORDING_TOP_DP
                    // 180dp 是给成功停止态"文件名行 + 下移时钟"占满上方后预留的；
                    // 封装失败时两者都不出现、时钟收回原位，提示对齐录音态位置。
                    STOPPED -> if (!state.busy && state.savedFile == null) {
                        AUDIO_NOTICE_RECORDING_TOP_DP
                    } else {
                        AUDIO_NOTICE_STOPPED_TOP_DP
                    }
                    else -> AUDIO_NOTICE_PREPARED_TOP_DP
                }).toInt()
                notice.layoutParams = params
            }
            notice.setText(message)
            notice.visibility = View.VISIBLE
        }
    }

    private fun updateControlsEnabled() {
        val state = mLastSnapshot
        val controls = AudioRecordingControlPolicy.resolve(
            snapshot = state,
            binderConnected = mRecordingBinder != null,
            projectionRequestInFlight = mProjectionRequestInFlight
        )
        mRecorderTransitionInProgress = !controls.mainActionEnabled
        // isEnabled 与 isClickable 同步：无障碍服务（TalkBack）与键盘焦点按 enabled
        // 判定控件可用性，只挡 isClickable 会让不可操作的按钮仍被宣告成可用操作。
        mIvMainAction?.isClickable = controls.mainActionEnabled
        mIvMainAction?.isEnabled = controls.mainActionEnabled
        mIvReRecording?.isClickable = controls.stoppedActionsEnabled
        mIvReRecording?.isEnabled = controls.stoppedActionsEnabled
        mIvCancelRecording?.isClickable = controls.cancelEnabled
        mIvCancelRecording?.isEnabled = controls.cancelEnabled
        mLlAudioInputCapsule?.isClickable = controls.sourceSelectorEnabled
        mLlAudioInputCapsule?.isEnabled = mLlAudioInputCapsule?.isClickable == true
    }

    private fun saveFileAndLeave() {
        if (mRecorderTransitionInProgress) {
            return
        }
        val name: String = mEtFileName!!.text.toString()
        if (name.isEmpty()) {
            return
        }
        mConfirmClicked = true
        dismiss()
    }

    /** 淡出后收回占位——留在原地会让主按钮在准备态重新偏心。 */
    private fun hideSecondaryAction(view: ImageView?) {
        if (view == null) return
        view.animate()
            .alpha(0f)
            .setDuration((ANIM_DURATION shr 4).toLong())
            .withEndAction { view.visibility = View.GONE }
    }

    private fun setMainButtonIcon(iconRes: Int, tintColor: Int? = null) {
        val color: Int? = tintColor ?: if (AppearanceUtil.isDarkMode(mActivity!!)) {
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_control_unchecked)
        } else {
            null
        }
        if (color != null) {
            mIvMainAction!!.imageTintList = null
            mIvMainAction!!.setImageDrawable(
                DisplayUtil.opaqueTintDrawable(
                    mActivity!!,
                    ContextCompat.getDrawable(mActivity!!, iconRes),
                    color
                )
            )
        } else {
            mIvMainAction!!.imageTintList = null
            mIvMainAction!!.setImageResource(iconRes)
        }
    }

    private fun applyMainButtonNormalStyle() {
        BackgroundUtil.applyOvalBackground(
            mIvMainAction,
            ThingBackground.pure(
                ContextCompat.getColor(mActivity!!, R.color.app_chrome_surface_elevated)
            )
        )
        mIvMainAction!!.foreground = BackgroundUtil.circularRipple(
            BackgroundUtil.appChromeRippleColor(mActivity!!)
        )
    }

    private fun applyMainButtonConfirmStyle(background: ThingBackground) {
        BackgroundUtil.applyOvalBackground(mIvMainAction, background)
        mIvMainAction!!.foreground = BackgroundUtil.circularRipple(
            BackgroundUtil.adaptiveRippleColor(background)
        )
    }

    private fun currentAccentBackground(): ThingBackground {
        return mAccentBackground ?: ThingBackground.pure(mActivity!!.getAccentColor())
    }

    /**
     * 来源行对齐的是当前数字字体真正绘制出的稳定着墨边界，而不是时钟 View 的外框。
     * Dialog 窗口为 wrap_content，测量会经历多轮，因此沿用播放 Dialog 的全局布局监听；
     * 内部按边距判等，尺寸稳定后不会继续触发布局。
     */
    private fun installAudioInputClockContentAlignment() {
        mContentView?.viewTreeObserver?.addOnGlobalLayoutListener(mAudioInputAlignListener)
    }

    private fun removeAudioInputClockContentAlignment() {
        val observer = mContentView?.viewTreeObserver ?: return
        if (observer.isAlive) {
            observer.removeOnGlobalLayoutListener(mAudioInputAlignListener)
        }
    }

    private fun alignAudioInputToClockContent() {
        val clock = mClockView ?: return
        val row = mLlAudioInput ?: return
        if (clock.width <= 0 || clock.height <= 0) return
        val parentWidth = (clock.parent as? View)?.width ?: return
        if (parentWidth <= 0) return

        val contentLeft = (clock.left + clock.contentLeftPx())
            .roundToInt()
            .coerceIn(0, parentWidth)
        val contentRight = (clock.left + clock.contentRightPx())
            .roundToInt()
            .coerceIn(contentLeft, parentWidth)
        if (contentRight <= contentLeft) return

        val endMargin = parentWidth - contentRight
        val params = row.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        if (params.marginStart == contentLeft &&
            params.leftMargin == contentLeft &&
            params.marginEnd == endMargin &&
            params.rightMargin == endMargin
        ) {
            return
        }
        params.marginStart = contentLeft
        params.leftMargin = contentLeft
        params.marginEnd = endMargin
        params.rightMargin = endMargin
        row.layoutParams = params
    }

    private fun configureClockView(accentBg: ThingBackground) {
        val clock = mClockView ?: return
        val sp = mActivity!!.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
        val digitStyle = sp.getString(Def.Meta.KEY_DOING_DIGIT_STYLE, "poppins") ?: "poppins"
        val digitFill = (sp.getString(Def.Meta.KEY_DOING_DIGIT_RENDER, "fill") ?: "fill") == "fill"
        clock.setStyleName(digitStyle)
        clock.setRenderMode(digitFill)
        clock.setClockMode(TimelyClockView.MODE_FULL)
        clock.setColonWidthFactor(0.42f)
        clock.setHostDark(AppearanceUtil.isDarkMode(mActivity!!))
        if (accentBg.mode == ThingBackground.Mode.GRADIENT) {
            clock.setInkGradient(accentBg.color, accentBg.endColor, timelyOrientation(accentBg.orientation))
        } else {
            clock.setInkColor(accentBg.color)
        }
        clock.alpha = CLOCK_PREPARED_ALPHA
        mClockHandler.removeCallbacks(mClockIntro)
        mClockHandler.postDelayed(mClockIntro, CLOCK_INTRO_DELAY_MS)
    }

    private fun startClockTicker() {
        stopClockTicker()
        mClockHandler.post(mClockTick)
    }

    private fun stopClockTicker() {
        mClockHandler.removeCallbacks(mClockTick)
    }

    private fun startClockBreathing() {
        val clock = mClockView ?: return
        if (mState != RECORDING) return
        mClockBreathing = true
        clock.animate().withEndAction(null)
        animateClockBreathTo(CLOCK_RECORDING_ALPHA_LOW)
    }

    private fun animateClockBreathTo(alpha: Float) {
        val clock = mClockView ?: return
        if (!mClockBreathing || mState != RECORDING) return
        clock.animate()
            .alpha(alpha)
            .setDuration(CLOCK_BREATH_DURATION)
            .withEndAction {
                val nextAlpha = if (alpha == CLOCK_RECORDING_ALPHA_LOW) {
                    CLOCK_RECORDING_ALPHA_HIGH
                } else {
                    CLOCK_RECORDING_ALPHA_LOW
                }
                animateClockBreathTo(nextAlpha)
            }
    }

    private fun stopClockBreathing() {
        mClockBreathing = false
        mClockView?.animate()?.withEndAction(null)
        mClockView?.animate()?.cancel()
    }

    private val mClockIntro: Runnable = Runnable {
        if (mState == PREPARED && mClockView != null) {
            mClockView!!.animateIn(0L)
        }
    }

    private fun clockStoppedTranslationY(): Float {
        val clock = mClockView ?: return 0f
        val topMargin = (clock.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val targetTop = mActivity!!.screenDensity * CLOCK_STOPPED_TOP_FROM_PARENT_DP
        return (targetTop - topMargin).coerceAtLeast(0f)
    }

    private fun timelyOrientation(orientation: ThingBackground.Orientation): Int {
        return when (orientation) {
            ThingBackground.Orientation.L_R -> TimelyClockView.ORIENTATION_L_R
            ThingBackground.Orientation.R_L -> TimelyClockView.ORIENTATION_R_L
            ThingBackground.Orientation.T_B -> TimelyClockView.ORIENTATION_T_B
            ThingBackground.Orientation.B_T -> TimelyClockView.ORIENTATION_B_T
            ThingBackground.Orientation.LT_RB -> TimelyClockView.ORIENTATION_LT_RB
            ThingBackground.Orientation.RB_LT -> TimelyClockView.ORIENTATION_RB_LT
            ThingBackground.Orientation.RT_LB -> TimelyClockView.ORIENTATION_RT_LB
            ThingBackground.Orientation.LB_RT -> TimelyClockView.ORIENTATION_LB_RT
        }
    }

    /**
     * 给侧边裸图标（重录 / 取消）加一层柔和的圆形半透明衬底，使其在流动的彩色水体上仍清晰
     * 可读（D8）。在 [BackgroundUtil.installAppChromeCircleRipple] 之后调用：涟漪已设为
     * foreground、oval outline 已就绪，这里设 background 即被裁成同形圆盘，主 FAB 自带悬浮面
     * 不需处理。
     */
    private fun installSideControlScrim(iv: ImageView?) {
        if (iv == null) return
        val scrimColor: Int = DisplayUtil.getTransparentColor(
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_surface_elevated),
            SIDE_CONTROL_SCRIM_ALPHA
        )
        val scrim = GradientDrawable()
        scrim.shape = GradientDrawable.OVAL
        scrim.setColor(scrimColor)
        iv.background = scrim
    }

    companion object {
        // 请求系统切到不超过 120Hz 的高刷模式；FableSol 渲染节奏由
        // WaveVisualizerFableSolGl 的 pacer 跟随实际显示模式，60Hz 面板不受影响。
        private const val TARGET_REFRESH_RATE =
            WaveVisualizerFableSolGl.MAX_RENDER_FPS.toFloat()
        const val TAG: String = "AudioRecordDialogFragment"
        private const val PROBE_TAG: String = "AudioRecProbe"
        private const val STATE_PROJECTION_IN_FLIGHT = "state_projection_in_flight"
        private const val STATE_PENDING_PROJECTION_MODE = "state_pending_projection_mode"

        const val PREPARED: Int  = 0
        const val RECORDING: Int = 1
        const val STOPPED: Int   = 2

        private const val ANIM_DURATION = 360
        private const val CLOCK_PREPARED_ALPHA = 0.36f
        private const val CLOCK_RECORDING_ALPHA_HIGH = 1.0f
        private const val CLOCK_RECORDING_ALPHA_LOW = 0.84f
        private const val CLOCK_BREATH_DURATION = 1996L
        private const val CLOCK_INTRO_DELAY_MS = 160L
        private const val CLOCK_STOPPED_TOP_FROM_PARENT_DP = 80f
        private const val FILE_NAME_STOPPED_TRANSLATION_Y_DP = 24f
        private const val AUDIO_INPUT_PREPARED_TOP_DP = 84f
        private const val AUDIO_NOTICE_PREPARED_TOP_DP = 136f
        private const val AUDIO_NOTICE_RECORDING_TOP_DP = 88f
        private const val AUDIO_NOTICE_STOPPED_TOP_DP = 180f
        /** 与布局里 tv_audio_input_notice 的 marginStart/End 保持一致。 */
        private const val AUDIO_NOTICE_SIDE_MARGIN_DP = 36f
        private const val AUDIO_INPUT_CAPSULE_RADIUS_DP = 18f
        private const val FABLESOL_IDLE_PRESENTATION_ALPHA = 0.16f

        // 侧边控件柔和衬底的透明度。
        private const val SIDE_CONTROL_SCRIM_ALPHA = 128
        private const val MAIN_BUTTON_CONFIRM_ICON_ALPHA = 0.96f
        // 封装失败的停止态：保存主按钮点击已被策略禁用，淡化到该值传达不可用。
        private const val MAIN_BUTTON_DISABLED_ALPHA = 0.4f
    }
}
