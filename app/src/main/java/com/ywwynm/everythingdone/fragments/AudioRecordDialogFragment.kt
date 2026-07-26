@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.content.Context
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
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.recording.AudioRecorder
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolPerformanceMonitor
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolVideoExportLauncher
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolGl
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolHost

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [androidx.fragment.app.DialogFragment] used to record audio.
 */
open class AudioRecordDialogFragment : BaseDialogFragment() {

    private var mActivity: DetailActivity? = null

    private var mState: Int = PREPARED

    private var mRecorder: AudioRecorder? = null
    private var mFileToSave: File? = null

    private var mLlFileName: LinearLayout? = null
    private var mEtFileName: EditText? = null
    private var mClockView: TimelyClockView? = null
    private var mVisualizer: WaveVisualizerFableSolHost? = null

    private var mIvMainAction: ImageView? = null
    private var mIvExportVideo: ImageView? = null
    /** 用户点的是"保存并导出视频"，而不是普通的对号保存。 */
    private var mExportVideoRequested: Boolean = false
    private var mIvReRecording: ImageView? = null
    private var mIvCancelRecording: ImageView? = null

    private var mAccentBackground: ThingBackground? = null

    private var mConfirmClicked: Boolean = false
    private var mRecorderTransitionInProgress: Boolean = false
    /** 排队中或正在跑的录音器操作数；>0 时主按钮不可点。 */
    private var mPendingRecorderTasks: Int = 0
    /** 对话框已关闭；队列上还没跑的录音器操作据此跳过重新开麦。 */
    @Volatile private var mDismissed: Boolean = false
    /**
     * 录音器的收尾、重启、释放全部走这一条单线程队列。它们改的是同一个 AudioRecord 与
     * 同一份文件，各起一条线程会真的并发——例如取消时的 `release()` 撞上收尾里的
     * `startListening()`，就是在已释放的 AudioRecord 上调用。
     */
    private val mRecorderTasks: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "AudioRecordWork") }
    private var mClockBreathing: Boolean = false
    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
    private var mSensorThread: HandlerThread? = null
    private var mTiltSensorRegistered: Boolean = false
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        mActivity = activity as DetailActivity
        lockHostOrientation()
        prepareTiltSensor()
        mRecorder = AudioRecorder(mActivity)

        mLlFileName  = f(R.id.ll_audio_file_name)
        mEtFileName  = f(R.id.et_audio_file_name)
        mClockView   = f(R.id.clock_record_audio)
        mVisualizer  = f(R.id.voice_visualizer)

        mIvMainAction      = f(R.id.iv_record_main_action)
        mIvReRecording     = f(R.id.iv_re_recording_audio)
        mIvCancelRecording = f(R.id.iv_cancel_recording_audio)
        mIvExportVideo     = f(R.id.iv_export_fablesol_video)
        BackgroundUtil.installAppChromeCircleRipple(mIvMainAction, mActivity!!)
        BackgroundUtil.installAppChromeCircleRipple(mIvReRecording, mActivity!!)
        BackgroundUtil.installAppChromeCircleRipple(mIvCancelRecording, mActivity!!)
        applyMainButtonNormalStyle()

        if (AppearanceUtil.isDarkMode(mActivity!!)) {
            mIvReRecording!!.setImageDrawable(
                DisplayUtil.opaqueTintDrawable(
                    mActivity!!,
                    ContextCompat.getDrawable(mActivity!!, R.drawable.act_re_recording_audio),
                    ContextCompat.getColor(mActivity!!, R.color.app_chrome_control_unchecked)
                )
            )
            mIvCancelRecording!!.setImageDrawable(
                DisplayUtil.opaqueTintDrawable(
                    mActivity!!,
                    ContextCompat.getDrawable(mActivity!!, R.drawable.act_cancel_recording_audio),
                    ContextCompat.getColor(mActivity!!, R.color.app_chrome_control_unchecked)
                )
            )
            setMainButtonIcon(R.drawable.act_start_recording_audio)
        }

        mAccentBackground = mActivity!!.getAccentBackground()
            ?: ThingBackground.pure(mActivity!!.getAccentColor())
        val accentBg: ThingBackground = mAccentBackground!!
        val accentColor: Int = accentBg.color
        configureClockView(accentBg)
        mVisualizer!!.setThingBackground(accentBg)
        installSideControlScrim(mIvReRecording)
        installSideControlScrim(mIvCancelRecording)

        mEtFileName!!.highlightColor = DisplayUtil.getLightColor(accentColor, mActivity)
        DisplayUtil.setSelectionHandlersColor(mEtFileName, accentColor)
        DisplayUtil.tintView(
            mEtFileName,
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_hint)
        )

        mRecorder!!.linkFableSol(mVisualizer!!)
        mRecorder!!.startListening()

        setEvents()
        // setOnClickListener 会把两个侧边键置为 clickable，但它们此刻 alpha=0：
        // 不在这里按状态收一次，准备态下点到取消键的位置就会把对话框关掉。
        updateControlsEnabled()

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_record_audio

    override fun onStart() {
        super.onStart()
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

    override fun onDestroyView() {
        stopTiltSensor()
        stopPerformanceMonitor()
        restoreHostOrientation()
        mVisualizer?.setContainerGravity(0f, 1f, 0f)
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        mDismissed = true
        val recorder: AudioRecorder? = mRecorder
        mRecorder = null
        var fileToDiscard: File? = null
        if (mConfirmClicked) {
            val parent: File = mFileToSave!!.parentFile!!

            val name: String = mEtFileName!!.text.toString()
            val fileToSave = File(parent, "$name.wav")
            var pathName: String = mFileToSave!!.absolutePath
            val renamed: Boolean = mFileToSave!!.renameTo(fileToSave)
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
        } else {
            fileToDiscard = mFileToSave
        }
        stopClockTicker()
        stopClockBreathing()
        mClockHandler.removeCallbacks(mClockIntro)
        stopTiltSensor()
        stopPerformanceMonitor()
        restoreHostOrientation()
        releaseRecorderInBackground(recorder, fileToDiscard)

        super.onDismiss(dialog)
    }

    private val mTiltListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            dispatchGravityToVisualizer(event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun lockHostOrientation() {
        val host = mActivity ?: return
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
        mRecorder?.offerGravitySample(-screenX, screenY, gz)
    }

    private fun setEvents() {
        val normalColor = ContextCompat.getColor(
            mActivity!!, R.color.app_chrome_on_surface_hint
        )
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
                DisplayUtil.tintView(mEtFileName, normalColor)
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
                PREPARED -> {
                    mRecorder!!.startRecording()
                    preparedToRecording()
                    mState = RECORDING
                }
                RECORDING -> {
                    stopRecordingWithoutBlocking()
                }
                else -> saveFileAndLeave()
            }
        }

        // 重录与取消不看 mRecorderTransitionInProgress：收尾/重启/释放都排在同一条单线程
        // 队列上，后到的操作只会排队，不会与在跑的那次并发。
        mIvReRecording!!.setOnClickListener {
            restartRecordingWithoutBlocking()
        }

        mIvCancelRecording!!.setOnClickListener { dismiss() }

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

    private fun preparedToRecording() {
        mClockHandler.removeCallbacks(mClockIntro)
        mRecordingBaseElapsed = SystemClock.elapsedRealtime()
        mClockView!!.setTimeMillis(0L, false)
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
        mLlFileName!!.animate()
            .translationY(mActivity!!.screenDensity * FILE_NAME_STOPPED_TRANSLATION_Y_DP)
            .setDuration(ANIM_DURATION.toLong())

        val name: String = mFileToSave!!.name
        mEtFileName!!.setText(name.substring(0, name.length - 4))

        stopClockTicker()
        stopClockBreathing()
        mClockView!!.animate()
            .alpha(CLOCK_RECORDING_ALPHA_HIGH)
            .translationY(clockStoppedTranslationY())
            .setDuration(ANIM_DURATION.toLong())

        mVisualizer!!.setRecordingHdrActive(false)
        mVisualizer!!.animatePresentationAlpha(0.16f, ANIM_DURATION.toLong())

        val confirmBg: ThingBackground = currentAccentBackground()
        applyMainButtonConfirmStyle(confirmBg)
        setMainButtonIcon(R.drawable.act_save_audio, BackgroundUtil.onColor(confirmBg, MAIN_BUTTON_CONFIRM_ICON_ALPHA))

        // 三个副按钮此前只改 alpha、始终占位，主按钮因此在准备/录音态被挤得偏心。
        // 改为 visibility 驱动：准备与录音态整行只有主按钮，它严格居中。
        mIvReRecording!!.isClickable = true
        mIvCancelRecording!!.isClickable = true
        mIvReRecording!!.visibility = View.VISIBLE
        mIvCancelRecording!!.visibility = View.VISIBLE
        mIvReRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        mIvCancelRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        showExportVideoAction(confirmBg)

        mIvMainAction!!.contentDescription = getString(R.string.cd_save_recorded_audio_file)
    }

    private fun stoppedToPrepared() {
        mVisualizer!!.setRecordingHdrActive(false)
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

    private fun stopRecordingWithoutBlocking() {
        val recorder = mRecorder ?: return
        val fileToSave: File = recorder.getSavedFile() ?: return
        mFileToSave = fileToSave
        recordingToStopped()
        mState = STOPPED
        beginRecorderTask()

        mRecorderTasks.execute {
            recorder.stopListening(true)
            // 对话框已经关掉就不必再开一次麦克风，队列后面紧跟着的就是 release()。
            if (!mDismissed) recorder.startListening()
            postRecorderTransitionResult(recorder) { endRecorderTask() }
        }
    }

    private fun restartRecordingWithoutBlocking() {
        val recorder = mRecorder ?: return
        val fileToDelete: File? = mFileToSave
        mFileToSave = null
        stoppedToPrepared()
        mState = PREPARED
        beginRecorderTask()

        mRecorderTasks.execute {
            // 删除排在收尾任务之后：收尾正在把 raw 抄成 wav，先删会被它重新建出来，
            // 留下一个没人认领的音频文件。
            if (fileToDelete != null) {
                FileUtil.deleteFile(fileToDelete.absolutePath)
            }
            if (!mDismissed) recorder.restartListening()
            postRecorderTransitionResult(recorder) { endRecorderTask() }
        }
    }

    private fun postRecorderTransitionResult(recorder: AudioRecorder, action: () -> Unit) {
        mContentView?.post {
            if (!isAdded || mRecorder !== recorder) {
                return@post
            }
            action()
        }
    }

    private fun beginRecorderTask() {
        mPendingRecorderTasks++
        updateControlsEnabled()
    }

    private fun endRecorderTask() {
        mPendingRecorderTasks = maxOf(mPendingRecorderTasks - 1, 0)
        updateControlsEnabled()
    }

    /**
     * 主按钮在收尾任务跑完前不可点：保存要把 [mFileToSave] 改名，而那份 wav 可能还在写。
     * 侧边两键只看状态——停止后它们随淡入一起可点。此前它们也跟着收尾任务禁用，于是
     * 「点停止、立刻点叉号」在整个收尾窗口（线程 join 上限 600ms + raw→wav 全量抄写）
     * 里都点不动，而按钮偏偏正在淡入、看起来完全可用。
     */
    private fun updateControlsEnabled() {
        mRecorderTransitionInProgress = mPendingRecorderTasks > 0
        mIvMainAction!!.isClickable = !mRecorderTransitionInProgress
        val sideEnabled = mState == STOPPED
        mIvReRecording!!.isClickable = sideEnabled
        mIvCancelRecording!!.isClickable = sideEnabled
    }

    /**
     * 释放排在同一条队列的末尾，因此一定晚于在跑的收尾/重启；[fileToDiscard]（取消录音时
     * 那份 wav）也在这里删，同样是为了不与正在写它的收尾任务撞车。
     */
    private fun releaseRecorderInBackground(recorder: AudioRecorder?, fileToDiscard: File?) {
        val rawPath = FileUtil.getTempPath(mActivity) + "/audio_raw"
        mRecorderTasks.execute {
            recorder?.release()
            if (fileToDiscard != null) {
                FileUtil.deleteFile(fileToDiscard.absolutePath)
            }
            FileUtil.deleteDirectory(rawPath)
        }
        mRecorderTasks.shutdown()
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

        // 侧边控件柔和衬底的透明度。
        private const val SIDE_CONTROL_SCRIM_ALPHA = 128
        private const val MAIN_BUTTON_CONFIRM_ICON_ALPHA = 0.96f
    }
}
