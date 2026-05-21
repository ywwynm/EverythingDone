@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private var mBase: View? = null

    private var mFabMain: FloatingActionButton? = null
    private var mIvReRecording: ImageView? = null
    private var mIvCancelRecording: ImageView? = null

    private var mConfirmClicked: Boolean = false

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
        mBase        = f(R.id.view_voice_visualizer_base)

        mFabMain           = f(R.id.fab_record_main)
        mIvReRecording     = f(R.id.iv_re_recording_audio)
        mIvCancelRecording = f(R.id.iv_cancel_recording_audio)

        val accentBg: ThingBackground? = mActivity!!.getAccentBackground()
        val accentColor: Int = accentBg?.representativeColor() ?: mActivity!!.getAccentColor()
        mVisualizer!!.setRenderColor(accentColor)
        if (accentBg != null) {
            BackgroundUtil.applyBackground(mBase, accentBg)
        } else {
            mBase!!.setBackgroundColor(accentColor)
        }

        mEtFileName!!.highlightColor = DisplayUtil.getLightColor(accentColor, mActivity)
        DisplayUtil.setSelectionHandlersColor(mEtFileName, accentColor)
        DisplayUtil.tintView(mEtFileName, ContextCompat.getColor(mActivity!!, R.color.black_26p))

        mRecorder!!.link(mVisualizer!!)
        mRecorder!!.startListening()

        setEvents()

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_record_audio

    override fun onDismiss(dialog: DialogInterface) {
        if (mConfirmClicked) {
            mRecorder!!.stopListening(false)

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
            mRecorder!!.stopListening(false)
        }
        mRecorder!!.release()

        FileUtil.deleteDirectory(FileUtil.getTempPath(mActivity) + "/audio_raw")

        super.onDismiss(dialog)
    }

    private fun setEvents() {
        val normalColor = ContextCompat.getColor(mActivity!!, R.color.black_26p)
        val accentBg: ThingBackground? = mActivity!!.getAccentBackground()
        val accentColor: Int = accentBg?.representativeColor() ?: mActivity!!.getAccentColor()
        mEtFileName!!.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
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

        mFabMain!!.setOnClickListener {
            if (mState == PREPARED) {
                mRecorder!!.startRecording()
                preparedToRecording()
                mState = RECORDING
            } else if (mState == RECORDING) {
                mRecorder!!.stopListening(true)
                mFileToSave = mRecorder!!.getSavedFile()
                mRecorder!!.startListening()
                recordingToStopped()
                mState = STOPPED
            } else {
                saveFileAndLeave()
            }
        }

        mIvReRecording!!.setOnClickListener {
            FileUtil.deleteFile(mFileToSave!!.absolutePath)
            mRecorder!!.stopListening(false)
            mRecorder!!.startListening()
            stoppedToPrepared()
            mState = PREPARED
        }

        mIvCancelRecording!!.setOnClickListener { dismiss() }
    }

    private fun preparedToRecording() {
        mChronometer!!.base = SystemClock.elapsedRealtime()
        mChronometer!!.start()
        mChronometer!!.animate().alpha(0.54f).setDuration(ANIM_DURATION.toLong())

        mVisualizer!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        mBase!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        mFabMain!!.setImageResource(R.drawable.act_stop_recording_audio)

        mFabMain!!.contentDescription = getString(R.string.cd_stop_record_audio)
    }

    private fun recordingToStopped() {
        mLlFileName!!.animate().translationY(mActivity!!.screenDensity * 32).setDuration(ANIM_DURATION.toLong())

        val name: String = mFileToSave!!.name
        mEtFileName!!.setText(name.substring(0, name.length - 4))

        mChronometer!!.stop()
        mChronometer!!.animate().translationY(mActivity!!.screenDensity * 72).setDuration(ANIM_DURATION.toLong())

        mVisualizer!!.animate().alpha(0.16f).setDuration(ANIM_DURATION.toLong())
        mBase!!.animate().alpha(0.16f).setDuration(ANIM_DURATION.toLong())

        mFabMain!!.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        mFabMain!!.setImageResource(R.drawable.act_save_audio)

        mIvReRecording!!.isClickable = true
        mIvCancelRecording!!.isClickable = true
        mIvReRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())
        mIvCancelRecording!!.animate().alpha(1.0f).setDuration(ANIM_DURATION.toLong())

        mFabMain!!.contentDescription = getString(R.string.cd_save_recorded_audio_file)
    }

    private fun stoppedToPrepared() {
        mLlFileName!!.animate().translationY(-mActivity!!.screenDensity * 72).setDuration(ANIM_DURATION.toLong())
        mChronometer!!.base = SystemClock.elapsedRealtime()
        mChronometer!!.animate().alpha(0.26f).setDuration(ANIM_DURATION.toLong())
        mChronometer!!.animate().translationY(0f).setDuration(ANIM_DURATION.toLong())

        mFabMain!!.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
        mFabMain!!.setImageResource(R.drawable.act_start_recording_audio)

        mIvReRecording!!.isClickable = false
        mIvCancelRecording!!.isClickable = false
        mIvReRecording!!.animate().alpha(0f).setDuration((ANIM_DURATION shr 4).toLong())
        mIvCancelRecording!!.animate().alpha(0f).setDuration((ANIM_DURATION shr 4).toLong())

        mFabMain!!.contentDescription = getString(R.string.cd_start_record_audio)
    }

    private fun saveFileAndLeave() {
        val name: String = mEtFileName!!.text.toString()
        if (name.isEmpty()) {
            return
        }
        mConfirmClicked = true
        dismiss()
    }

    companion object {
        const val TAG: String = "AudioRecordDialogFragment"

        const val PREPARED: Int  = 0
        const val RECORDING: Int = 1
        const val STOPPED: Int   = 2

        private const val ANIM_DURATION = 360
    }
}
