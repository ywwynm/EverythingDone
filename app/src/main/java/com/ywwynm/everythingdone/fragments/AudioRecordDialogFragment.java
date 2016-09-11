package com.ywwynm.everythingdone.fragments;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.views.recording.AudioRecorder;
import com.ywwynm.everythingdone.views.recording.VoiceVisualizer;

import java.io.File;

/**
 * Created by ywwynm on 2015/9/29.
 * A subclass of {@link android.app.DialogFragment} used to record audio.
 */
public class AudioRecordDialogFragment extends BaseDialogFragment {

    public static final String TAG = "AudioRecordDialogFragment";

    private DetailActivity mActivity;

    public static final int PREPARED  = 0;
    public static final int RECORDING = 1;
    public static final int STOPPED   = 2;

    private int mState = PREPARED;

    private static final int ANIM_DURATION = 360;

    private AudioRecorder mRecorder;
    private File mFileToSave;

    private LinearLayout mLlFileName;
    private EditText mEtFileName;
    private Chronometer mChronometer;
    private VoiceVisualizer mVisualizer;
    private View mBase;

    private FloatingActionButton mFabMain;
    private ImageView mIvReRecording;
    private ImageView mIvCancelRecording;

    private boolean mConfirmClicked = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        mActivity = (DetailActivity) getActivity();
        mRecorder = new AudioRecorder();

        mLlFileName  = f(R.id.ll_audio_file_name);
        mEtFileName  = f(R.id.et_audio_file_name);
        mChronometer = f(R.id.chronometer_record_audio);
        mVisualizer  = f(R.id.voice_visualizer);
        mBase        = f(R.id.view_voice_visualizer_base);

        mFabMain           = f(R.id.fab_record_main);
        mIvReRecording     = f(R.id.iv_re_recording_audio);
        mIvCancelRecording = f(R.id.iv_cancel_recording_audio);

        int accentColor = mActivity.getAccentColor();
        mVisualizer.setRenderColor(accentColor);
        mBase.setBackgroundColor(accentColor);

        mEtFileName.setHighlightColor(DisplayUtil.getLightColor(accentColor, mActivity));
        DisplayUtil.setSelectionHandlersColor(mEtFileName, accentColor);
        DisplayUtil.tintView(mEtFileName, ContextCompat.getColor(mActivity, R.color.black_26p));

        mRecorder.link(mVisualizer);
        mRecorder.startListening();

        setEvents();

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_record_audio;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mConfirmClicked) {
            mRecorder.stopListening(false);

            File parent = mFileToSave.getParentFile();

            String name = mEtFileName.getText().toString();
            File fileToSave = new File(parent, name + ".wav");
            String pathName = mFileToSave.getAbsolutePath();
            boolean renamed = mFileToSave.renameTo(fileToSave);
            if (renamed) {
                pathName = fileToSave.getAbsolutePath();
            }

            mActivity.attachmentTypePathName = AttachmentHelper.AUDIO + pathName;
            mActivity.addAttachment(0);
        } else {
            if (mFileToSave != null) {
                FileUtil.deleteFile(mFileToSave.getAbsolutePath());
            }
            mChronometer.stop();
            mRecorder.stopListening(false);
        }
        mRecorder.release();

        FileUtil.deleteDirectory(FileUtil.TEMP_PATH + "/audio_raw");

        super.onDismiss(dialog);
    }

    private void setEvents() {
        final int normalColor = ContextCompat.getColor(mActivity, R.color.black_26p);
        final int accentColor = mActivity.getAccentColor();
        mEtFileName.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    DisplayUtil.tintView(mEtFileName, accentColor);
                } else {
                    DisplayUtil.tintView(mEtFileName, normalColor);
                }
            }
        });

        mEtFileName.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveFileAndLeave();
                    return true;
                }
                return false;
            }
        });

        mFabMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mState == PREPARED) {
                    mRecorder.startRecording();
                    preparedToRecording();
                    mState = RECORDING;
                } else if (mState == RECORDING) {
                    mRecorder.stopListening(true);
                    mFileToSave = mRecorder.getSavedFile();
                    mRecorder.startListening();
                    recordingToStopped();
                    mState = STOPPED;
                } else {
                    saveFileAndLeave();
                }
            }
        });

        mIvReRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FileUtil.deleteFile(mFileToSave.getAbsolutePath());
                mRecorder.stopListening(false);
                mRecorder.startListening();
                stoppedToPrepared();
                mState = PREPARED;
            }
        });

        mIvCancelRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private void preparedToRecording() {
        mChronometer.setBase(SystemClock.elapsedRealtime());
        mChronometer.start();
        mChronometer.animate().alpha(0.54f).setDuration(ANIM_DURATION);

        mVisualizer.animate().alpha(1.0f).setDuration(ANIM_DURATION);
        mBase.animate().alpha(1.0f).setDuration(ANIM_DURATION);
        mFabMain.setImageResource(R.drawable.act_stop_recording_audio);

        mFabMain.setContentDescription(getString(R.string.cd_stop_record_audio));
    }

    private void recordingToStopped() {
        mLlFileName.animate().translationY(mActivity.screenDensity * 32).setDuration(ANIM_DURATION);

        String name = mFileToSave.getName();
        mEtFileName.setText(name.substring(0, name.length() - 4));

        mChronometer.stop();
        mChronometer.animate().translationY(mActivity.screenDensity * 72).setDuration(ANIM_DURATION);

        mVisualizer.animate().alpha(0.16f).setDuration(ANIM_DURATION);
        mBase.animate().alpha(0.16f).setDuration(ANIM_DURATION);

        mFabMain.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
        mFabMain.setImageResource(R.drawable.act_save_audio);

        mIvReRecording.setClickable(true);
        mIvCancelRecording.setClickable(true);
        mIvReRecording.animate().alpha(1.0f).setDuration(ANIM_DURATION);
        mIvCancelRecording.animate().alpha(1.0f).setDuration(ANIM_DURATION);

        mFabMain.setContentDescription(getString(R.string.cd_save_recorded_audio_file));
    }

    private void stoppedToPrepared() {
        mLlFileName.animate().translationY(-mActivity.screenDensity * 72).setDuration(ANIM_DURATION);
        mChronometer.setBase(SystemClock.elapsedRealtime());
        mChronometer.animate().alpha(0.26f).setDuration(ANIM_DURATION);
        mChronometer.animate().translationY(0).setDuration(ANIM_DURATION);

        mFabMain.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFFFFF")));
        mFabMain.setImageResource(R.drawable.act_start_recording_audio);

        mIvReRecording.setClickable(false);
        mIvCancelRecording.setClickable(false);
        mIvReRecording.animate().alpha(0).setDuration(ANIM_DURATION >> 4);
        mIvCancelRecording.animate().alpha(0).setDuration(ANIM_DURATION >> 4);

        mFabMain.setContentDescription(getString(R.string.cd_start_record_audio));
    }

    private void saveFileAndLeave() {
        String name = mEtFileName.getText().toString();
        if (name.isEmpty()) {
            return;
        }
        mConfirmClicked = true;
        dismiss();
    }
}
