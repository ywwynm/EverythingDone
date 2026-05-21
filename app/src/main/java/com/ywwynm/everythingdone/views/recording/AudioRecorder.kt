@file:Suppress("MissingPermission")

package com.ywwynm.everythingdone.views.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.utils.FileUtil

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList

/**
 * Created by tyorikan on 2015/06/09.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Updated by ywwynm on 2015/10/02 to meet requirements.
 * Updated by ywwynm on 2016/7/8 to get real decibel
 *
 * Sampling AudioRecord Input
 * This output send to [VoiceVisualizer]
 */
open class AudioRecorder {

    private var mSamplingInterval: Int = 100

    private var mAudioRecord: AudioRecord? = null

    private var mBufSize: Int = 0

    private val mVoiceVisualizers: MutableList<VoiceVisualizer> = ArrayList()

    private var mIsListening: Boolean = false
    private var mIsRecording: Boolean = false

    private var mRawFile: File? = null
    private var mOutputFile: File? = null

    init {
        initAudioRecord()
    }

    /**
     * link to VisualizerView
     *
     * @param voiceVisualizer [VoiceVisualizer]
     */
    fun link(voiceVisualizer: VoiceVisualizer) {
        mVoiceVisualizers.add(voiceVisualizer)
    }

    /**
     * setter of samplingInterval
     *
     * @param samplingInterval interval volume sampling
     */
    fun setSamplingInterval(samplingInterval: Int) {
        mSamplingInterval = samplingInterval
    }

    /**
     * getter isListening
     *
     * @return true:recording, false:not recording
     */
    fun isListening(): Boolean {
        return mIsListening
    }

    private fun initAudioRecord() {
        val bufSize: Int = AudioRecord.getMinBufferSize(
                RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        )

        mAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
        )

        if (mAudioRecord!!.state == AudioRecord.STATE_INITIALIZED) {
            mBufSize = bufSize
        }
    }

    /**
     * start AudioRecord.read
     */
    fun startListening() {
        mRawFile = FileUtil.createTempAudioFile(".raw")
        if (mRawFile == null) {
            return
        }

        mIsListening = true
        mAudioRecord!!.startRecording()
        RecordingThread().start()
    }

    /**
     * stop AudioRecord.read
     */
    fun stopListening(saveFile: Boolean) {
        mIsRecording = false
        mIsListening = false

        if (saveFile) {
            saveToWaveFile()
        }

        if (!mVoiceVisualizers.isEmpty()) {
            for (i in mVoiceVisualizers.indices) {
                mVoiceVisualizers[i].receive(0)
            }
        }
    }

    fun isRecording(): Boolean {
        return mIsRecording
    }

    fun startRecording() {
        mOutputFile = AttachmentHelper.createAttachmentFile(AttachmentHelper.AUDIO)
        if (mOutputFile == null) {
            return
        }
        mIsRecording = true
    }

    fun getSavedFile(): File? {
        return mOutputFile
    }

    private fun saveToWaveFile() {
        var input: FileInputStream? = null
        var out: FileOutputStream? = null
        try {
            input = FileInputStream(mRawFile)
            out = FileOutputStream(mOutputFile)
            val audioLength: Long = input.getChannel().size()
            val dataLength: Long = audioLength + 36

            writeWaveFileHeader(out, audioLength, dataLength,
                    RECORDING_SAMPLE_RATE.toLong(), 2, (16 * RECORDING_SAMPLE_RATE * 2 / 8).toLong())

            val data = ByteArray(mBufSize)

            while (input.read(data) != -1) {
                out.write(data)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            FileUtil.closeStream(input)
            FileUtil.closeStream(out)
        }
    }

    /**
     * release member object
     */
    fun release() {
        mAudioRecord!!.release()
        mAudioRecord = null
    }

    @Throws(IOException::class)
    private fun writeWaveFileHeader(out: FileOutputStream, audioLength: Long,
                                    dataLength: Long, sampleRate: Long, channels: Int, byteRate: Long) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (dataLength and 0xff).toByte()
        header[5] = ((dataLength shr 8) and 0xff).toByte()
        header[6] = ((dataLength shr 16) and 0xff).toByte()
        header[7] = ((dataLength shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (2 * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (audioLength and 0xff).toByte()
        header[41] = ((audioLength shr 8) and 0xff).toByte()
        header[42] = ((audioLength shr 16) and 0xff).toByte()
        header[43] = ((audioLength shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private inner class RecordingThread : Thread() {

        var time: Long = System.currentTimeMillis()

        override fun run() {
            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(mRawFile)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }

            var readSize: Int
            val audioBytes = ByteArray(mBufSize)
            while (mIsListening) {
                readSize = mAudioRecord!!.read(audioBytes, 0, mBufSize)

                if (System.currentTimeMillis() - time >= mSamplingInterval) {
                    val decibel: Int = calculateDecibel(audioBytes, readSize)
                    if (!mVoiceVisualizers.isEmpty()) {
                        for (i in mVoiceVisualizers.indices) {
                            mVoiceVisualizers[i].receive(decibel)
                        }
                    }
                    time = System.currentTimeMillis()
                }

                if (mIsRecording) {
                    if (fos != null && readSize != AudioRecord.ERROR_INVALID_OPERATION) {
                        try {
                            fos.write(audioBytes)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            FileUtil.closeStream(fos)
        }

        private fun calculateDecibel(buf: ByteArray, byteReadSize: Int): Int {
            if (byteReadSize == 0) {
                return 0
            }

            var sum: Long = 0
            for (i in 0 until buf.size / 2) {
                val data: Short = ((buf[i * 2].toInt() and 0xff) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
                sum += data * data
            }

            val amplitude: Double = sum / (byteReadSize / 2.0) // 振幅
            val decibel: Double = 10 * Math.log10(amplitude)

            if (BuildConfig.DEBUG) {
                Log.i(TAG, "decibel: $decibel")
            }
            return decibel.toInt()
        }
    }

    companion object {
        const val TAG: String = "AudioRecorder"

        private const val RECORDING_SAMPLE_RATE: Int = 44100
    }
}
