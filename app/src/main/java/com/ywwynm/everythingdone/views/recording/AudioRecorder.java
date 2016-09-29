package com.ywwynm.everythingdone.views.recording;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by tyorikan on 2015/06/09.
 * Updated by ywwynm on 2015/10/02 to meet requirements.
 * Updated by ywwynm on 2016/7/8 to get real decibel
 *
 * Sampling AudioRecord Input
 * This output send to {@link VoiceVisualizer}
 */
public class AudioRecorder {

    public static final String TAG = "AudioRecorder";

    private static final int RECORDING_SAMPLE_RATE = 44100;

    private int mSamplingInterval = 100;

    private AudioRecord mAudioRecord;

    private int mBufSize;

    private List<VoiceVisualizer> mVoiceVisualizers = new ArrayList<>();

    private boolean mIsListening;
    private boolean mIsRecording;

    private File mRawFile;
    private File mOutputFile;

    public AudioRecorder() {
        initAudioRecord();
    }

    /**
     * link to VisualizerView
     *
     * @param voiceVisualizer {@link VoiceVisualizer}
     */
    public void link(VoiceVisualizer voiceVisualizer) {
        mVoiceVisualizers.add(voiceVisualizer);
    }

    /**
     * setter of samplingInterval
     *
     * @param samplingInterval interval volume sampling
     */
    public void setSamplingInterval(int samplingInterval) {
        mSamplingInterval = samplingInterval;
    }

    /**
     * getter isListening
     *
     * @return true:recording, false:not recording
     */
    public boolean isListening() {
        return mIsListening;
    }

    private void initAudioRecord() {
        int bufSize = AudioRecord.getMinBufferSize(
                RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
        );

        if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            mBufSize = bufSize;
        }
    }

    /**
     * start AudioRecord.read
     */
    public void startListening() {
        mRawFile = FileUtil.createTempAudioFile(".raw");
        if (mRawFile == null) {
            return;
        }

        mIsListening = true;
        mAudioRecord.startRecording();
        new RecordingThread().start();
    }

    /**
     * stop AudioRecord.read
     */
    public void stopListening(boolean saveFile) {
        mIsRecording = false;
        mIsListening = false;

        if (saveFile) {
            saveToWaveFile();
        }

        if (mVoiceVisualizers != null && !mVoiceVisualizers.isEmpty()) {
            for (int i = 0; i < mVoiceVisualizers.size(); i++) {
                mVoiceVisualizers.get(i).receive(0);
            }
        }
    }

    public boolean isRecording() {
        return mIsRecording;
    }

    public void startRecording() {
        mOutputFile = AttachmentHelper.createAttachmentFile(AttachmentHelper.AUDIO);
        if (mOutputFile == null) {
            return;
        }
        mIsRecording = true;
    }

    public File getSavedFile() {
        return mOutputFile;
    }

    private void saveToWaveFile() {
        FileInputStream  in  = null;
        FileOutputStream out = null;
        try {
            in  = new FileInputStream(mRawFile);
            out = new FileOutputStream(mOutputFile);
            long audioLength = in.getChannel().size();
            long dataLength  = audioLength + 36;

            writeWaveFileHeader(out, audioLength, dataLength,
                    RECORDING_SAMPLE_RATE, 2, 16 * RECORDING_SAMPLE_RATE * 2 / 8);

            byte[] data = new byte[mBufSize];

            while (in.read(data) != -1) {
                out.write(data);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            FileUtil.closeStream(in);
            FileUtil.closeStream(out);
        }
    }

    /**
     * release member object
     */
    public void release() {
        mAudioRecord.release();
        mAudioRecord = null;
    }

    private void writeWaveFileHeader(FileOutputStream out, long audioLength,
                                     long dataLength, long sampleRate, int channels, long byteRate)
            throws IOException {
        byte[] header = new byte[44];
        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (dataLength & 0xff);
        header[5] = (byte) ((dataLength >> 8) & 0xff);
        header[6] = (byte) ((dataLength >> 16) & 0xff);
        header[7] = (byte) ((dataLength >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (2 * 16 / 8); // block align
        header[33] = 0;
        header[34] = 16; // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (audioLength & 0xff);
        header[41] = (byte) ((audioLength >> 8) & 0xff);
        header[42] = (byte) ((audioLength >> 16) & 0xff);
        header[43] = (byte) ((audioLength >> 24) & 0xff);
        out.write(header, 0, 44);
    }

    private class RecordingThread extends Thread {

        long time = System.currentTimeMillis();

        @Override
        public void run() {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(mRawFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            int readSize;
            byte[] audioBytes  = new byte [mBufSize];
            while (mIsListening) {
                readSize  = mAudioRecord.read(audioBytes,  0, mBufSize);

                if (System.currentTimeMillis() - time >= mSamplingInterval) {
                    int decibel = calculateDecibel(audioBytes, readSize);
                    if (mVoiceVisualizers != null && !mVoiceVisualizers.isEmpty()) {
                        for (int i = 0; i < mVoiceVisualizers.size(); i++) {
                            mVoiceVisualizers.get(i).receive(decibel);
                        }
                    }
                    time = System.currentTimeMillis();
                }

                if (mIsRecording) {
                    if (fos != null && readSize != AudioRecord.ERROR_INVALID_OPERATION) {
                        try {
                            fos.write(audioBytes);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            FileUtil.closeStream(fos);
        }

        private int calculateDecibel(byte[] buf, int byteReadSize) {
//            int sum = 0;
//            for (int i = 0; i < mBufSize; i++) {
//                sum += Math.abs(buf[i]);
//            }
//            // avg 10-50
//            return (int) (sum / mBufSize / 1.2f);

            if (byteReadSize == 0) {
                return 0;
            }

            long sum = 0;
            for (int i = 0; i < buf.length / 2; i++) {
                short data = (short) ((buf[i * 2] & 0xff) | (buf[i * 2 + 1] << 8));
                sum += data * data;
            }

            double amplitude = sum / (byteReadSize / 2d); // 振幅
            double decibel = 10 * Math.log10(amplitude);

            if (BuildConfig.DEBUG) {
                Log.i(TAG, "decibel: " + decibel);
            }
            return (int) decibel;
        }
    }
}
