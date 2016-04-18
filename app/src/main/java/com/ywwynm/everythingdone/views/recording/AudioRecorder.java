package com.ywwynm.everythingdone.views.recording;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

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
 *
 * Sampling AudioRecord Input
 * This output send to {@link VoiceVisualizer}
 */
public class AudioRecorder {

    private static final int RECORDING_SAMPLE_RATE = 44100;

    private AudioRecord mAudioRecord;
    private boolean mIsListening;
    private boolean mIsRecording;
    private int mBufSize;

    private int mSamplingInterval = 100;

    private List<VoiceVisualizer> mVoiceVisualizers = new ArrayList<>();

    private File mRawFile;
    private File mOutputFile;

    public AudioRecorder() {
        initAudioRecord();
    }

    /**
     * link to VisualizerView
     *
     * @param visualizerView {@link VisualizerView}
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
        int bufferSize = AudioRecord.getMinBufferSize(
                RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            mBufSize = bufferSize;
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

    private int calculateDecibel(byte[] buf) {
        int sum = 0;
        for (int i = 0; i < mBufSize; i++) {
            sum += Math.abs(buf[i]);
        }
        // avg 10-50
        return (int) (sum / mBufSize / 1.2f);
    }

    private void saveToWaveFile() {
        try {
            FileInputStream  in  = new FileInputStream(mRawFile);
            FileOutputStream out = new FileOutputStream(mOutputFile);

            long audioLength = in.getChannel().size();
            long dataLength  = audioLength + 36;

            writeWaveFileHeader(out, audioLength, dataLength,
                    RECORDING_SAMPLE_RATE, 2, 16 * RECORDING_SAMPLE_RATE * 2 / 8);

            byte[] data = new byte[mBufSize];

            while (in.read(data) != -1) {
                out.write(data);
            }

            in.close();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
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

    class RecordingThread extends Thread {

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
            byte[] audioData = new byte[mBufSize];
            while (mIsListening) {
                readSize = mAudioRecord.read(audioData, 0, mBufSize);

                if (System.currentTimeMillis() - time >= mSamplingInterval) {
                    int decibel = calculateDecibel(audioData);
                    if (mVoiceVisualizers != null && !mVoiceVisualizers.isEmpty()) {
                        for (int i = 0; i < mVoiceVisualizers.size(); i++) {
                            mVoiceVisualizers.get(i).receive(decibel);
                        }
                    }
                    time = System.currentTimeMillis();
                }

                if (mIsRecording) {
                    if (readSize != AudioRecord.ERROR_INVALID_OPERATION) {
                        try {
                            fos.write(audioData);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
