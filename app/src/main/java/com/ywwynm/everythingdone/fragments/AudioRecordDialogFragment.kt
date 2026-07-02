@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Chronometer
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.recording.AudioRecorder
import com.ywwynm.everythingdone.views.recording.VoiceVisualizer

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
    private var mChronometer: Chronometer? = null
    private var mVisualizer: VoiceVisualizer? = null

    private var mIvMainAction: ImageView? = null
    private var mIvReRecording: ImageView? = null
    private var mIvCancelRecording: ImageView? = null

    private var mAccentBackground: ThingBackground? = null

    private var mConfirmClicked: Boolean = false
    private var mRecorderTransitionInProgress: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        mActivity = activity as DetailActivity
        mRecorder = AudioRecorder()

        mLlFileName  = f(R.id.ll_audio_file_name)
        mEtFileName  = f(R.id.et_audio_file_name)
        mChronometer = f(R.id.chronometer_record_audio)
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
        mVisualizer!!.setThingBackground(accentBg)
        installSideControlScrim(mIvReRecording)
        installSideControlScrim(mIvCancelRecording)

        mEtFileName!!.highlightColor = DisplayUtil.getLightColor(accentColor, mActivity)
        DisplayUtil.setSelectionHandlersColor(mEtFileName, accentColor)
        DisplayUtil.tintView(
            mEtFileName,
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_hint)
        )

        mRecorder!!.link(mVisualizer!!)
        mRecorder!!.startListening()

        setEvents()

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_record_audio

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
            mChronometer!!.stop()
        }
        releaseRecorderInBackground(recorder)

        super.onDismiss(dialog)
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
        mChronometer!!.base = SystemClock.elapsedRealtime()
        mChronometer!!.start()
        mChronometer!!.animate().alpha(0.54f).setDuration(ANIM_DURATION.toLong())

        mVisualizer!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        applyMainButtonNormalStyle()
        setMainButtonIcon(R.drawable.act_stop_recording_audio)

        mIvMainAction!!.contentDescription = getString(R.string.cd_stop_record_audio)
    }

    private fun recordingToStopped() {
        mLlFileName!!.animate().translationY(mActivity!!.screenDensity * 32).setDuration(ANIM_DURATION.toLong())

        val name: String = mFileToSave!!.name
        mEtFileName!!.setText(name.substring(0, name.length - 4))

        mChronometer!!.stop()
        mChronometer!!.animate().translationY(mActivity!!.screenDensity * 72).setDuration(ANIM_DURATION.toLong())

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
        mChronometer!!.base = SystemClock.elapsedRealtime()
        mChronometer!!.animate().alpha(0.26f).setDuration(ANIM_DURATION.toLong())
        mChronometer!!.animate().translationY(0f).setDuration(ANIM_DURATION.toLong())

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

        // 侧边控件柔和衬底的透明度（约 45%）。
        private const val SIDE_CONTROL_SCRIM_ALPHA = 115
        private const val MAIN_BUTTON_CONFIRM_ICON_ALPHA = 0.94f
    }
}
