@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

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
import android.os.Bundle
import android.os.Handler
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
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSol

import java.io.File

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [android.app.DialogFragment] used to record audio.
 */
open class AudioRecordDialogFragment : BaseDialogFragment() {

    private var mActivity: DetailActivity? = null

    private var mState: Int = PREPARED

    private var mRecorder: AudioRecorder? = null
    private var mFileToSave: File? = null

    private var mLlFileName: LinearLayout? = null
    private var mEtFileName: EditText? = null
    private var mClockView: TimelyClockView? = null
    private var mVisualizer: WaveVisualizerFableSol? = null

    private var mIvMainAction: ImageView? = null
    private var mIvReRecording: ImageView? = null
    private var mIvCancelRecording: ImageView? = null

    private var mAccentBackground: ThingBackground? = null

    private var mConfirmClicked: Boolean = false
    private var mRecorderTransitionInProgress: Boolean = false
    private var mClockBreathing: Boolean = false
    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
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

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_record_audio

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
        restoreHostOrientation()
        mVisualizer?.setContainerGravity(0f, 1f, 0f)
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        val recorder: AudioRecorder? = mRecorder
        mRecorder = null
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
        } else {
            if (mFileToSave != null) {
                FileUtil.deleteFile(mFileToSave!!.absolutePath)
            }
        }
        stopClockTicker()
        stopClockBreathing()
        mClockHandler.removeCallbacks(mClockIntro)
        stopTiltSensor()
        restoreHostOrientation()
        releaseRecorderInBackground(recorder)

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
        manager.registerListener(mTiltListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopTiltSensor() {
        mSensorManager?.unregisterListener(mTiltListener)
    }

    private fun dispatchGravityToVisualizer(gx: Float, gy: Float, gz: Float) {
        val (screenX, screenY) = when (mLockedRotation) {
            Surface.ROTATION_90 -> -gy to gx
            Surface.ROTATION_180 -> -gx to -gy
            Surface.ROTATION_270 -> gy to -gx
            else -> gx to gy
        }
        mVisualizer?.setContainerGravity(-screenX, screenY, gz)
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

        mIvReRecording!!.setOnClickListener {
            if (mRecorderTransitionInProgress) {
                return@setOnClickListener
            }
            restartRecordingWithoutBlocking()
        }

        mIvCancelRecording!!.setOnClickListener { dismiss() }
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

        mVisualizer!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
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

        mVisualizer!!.animate().alpha(0.16f).setDuration(ANIM_DURATION.toLong())

        val confirmBg: ThingBackground = currentAccentBackground()
        applyMainButtonConfirmStyle(confirmBg)
        setMainButtonIcon(R.drawable.act_save_audio, BackgroundUtil.onColor(confirmBg, MAIN_BUTTON_CONFIRM_ICON_ALPHA))

        mIvReRecording!!.isClickable = true
        mIvCancelRecording!!.isClickable = true
        mIvReRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        mIvCancelRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())

        mIvMainAction!!.contentDescription = getString(R.string.cd_save_recorded_audio_file)
    }

    private fun stoppedToPrepared() {
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
        mIvReRecording!!.animate().alpha(0f).setDuration((ANIM_DURATION shr 4).toLong())
        mIvCancelRecording!!.animate().alpha(0f).setDuration((ANIM_DURATION shr 4).toLong())

        mIvMainAction!!.contentDescription = getString(R.string.cd_start_record_audio)
    }

    private fun stopRecordingWithoutBlocking() {
        val recorder = mRecorder ?: return
        val fileToSave: File = recorder.getSavedFile() ?: return
        mFileToSave = fileToSave
        recordingToStopped()
        mState = STOPPED
        setRecorderTransitionInProgress(true)

        Thread({
            recorder.stopListening(true)
            recorder.startListening()
            postRecorderTransitionResult(recorder) {
                setRecorderTransitionInProgress(false)
            }
        }, "AudioRecordStop").start()
    }

    private fun restartRecordingWithoutBlocking() {
        val recorder = mRecorder ?: return
        val fileToDelete: File? = mFileToSave
        mFileToSave = null
        stoppedToPrepared()
        mState = PREPARED
        setRecorderTransitionInProgress(true)

        Thread({
            if (fileToDelete != null) {
                FileUtil.deleteFile(fileToDelete.absolutePath)
            }
            recorder.restartListening()
            postRecorderTransitionResult(recorder) {
                setRecorderTransitionInProgress(false)
            }
        }, "AudioRecordRestart").start()
    }

    private fun postRecorderTransitionResult(recorder: AudioRecorder, action: () -> Unit) {
        mContentView?.post {
            if (!isAdded || mRecorder !== recorder) {
                return@post
            }
            action()
        }
    }

    private fun setRecorderTransitionInProgress(inProgress: Boolean) {
        mRecorderTransitionInProgress = inProgress
        mIvMainAction!!.isClickable = !inProgress
        if (mState == STOPPED) {
            mIvReRecording!!.isClickable = !inProgress
            mIvCancelRecording!!.isClickable = !inProgress
        } else {
            mIvReRecording!!.isClickable = false
            mIvCancelRecording!!.isClickable = false
        }
    }

    private fun releaseRecorderInBackground(recorder: AudioRecorder?) {
        Thread({
            recorder?.release()
            FileUtil.deleteDirectory(FileUtil.getTempPath(mActivity) + "/audio_raw")
        }, "AudioRecordRelease").start()
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
