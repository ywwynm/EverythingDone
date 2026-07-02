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
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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
                mVoiceVisualizers[i].receive(VoiceAudioFrame.SILENCE)
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
        private val mAnalyzer: VoiceAudioAnalyzer = VoiceAudioAnalyzer(RECORDING_SAMPLE_RATE)

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
                if (readSize > 0) {
                    mAnalyzer.ingest(audioBytes, readSize)
                }

                if (System.currentTimeMillis() - time >= mSamplingInterval) {
                    val frame: VoiceAudioFrame = mAnalyzer.analyze()
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            TAG,
                            "audio frame: loudness=${frame.loudness}, low=${frame.low}, " +
                                    "lowMid=${frame.lowMid}, mid=${frame.mid}, high=${frame.high}, " +
                                    "air=${frame.air}, transient=${frame.transient}"
                        )
                    }
                    if (!mVoiceVisualizers.isEmpty()) {
                        for (i in mVoiceVisualizers.indices) {
                            mVoiceVisualizers[i].receive(frame)
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
    }

    /**
     * 从录音 PCM 中提取给水波使用的轻量特征：RMS/响度、5 个 FFT 频段、瞬态。
     * 不引入第三方依赖，2048 点 FFT 每 100ms 跑一次，对当前录音对话框足够轻。
     */
    private class VoiceAudioAnalyzer(private val sampleRate: Int) {

        private val mSampleRing: FloatArray = FloatArray(FFT_SIZE)
        private var mWriteIndex: Int = 0
        private var mSampleCount: Int = 0

        private val mWindow: FloatArray = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
        }
        private val mReal: DoubleArray = DoubleArray(FFT_SIZE)
        private val mImag: DoubleArray = DoubleArray(FFT_SIZE)

        private val mBandStartBins: IntArray = IntArray(BAND_COUNT)
        private val mBandEndBins: IntArray = IntArray(BAND_COUNT)
        private val mPreviousBands: FloatArray = FloatArray(BAND_COUNT)
        private val mSmoothedBands: FloatArray = FloatArray(BAND_COUNT)
        private var mPreviousLoudness: Float = 0f

        init {
            val nyquistBin = FFT_SIZE / 2
            for (i in 0 until BAND_COUNT) {
                val startHz = BAND_RANGES[i * 2]
                val endHz = BAND_RANGES[i * 2 + 1]
                mBandStartBins[i] = (startHz * FFT_SIZE / sampleRate).coerceIn(1, nyquistBin - 1)
                mBandEndBins[i] = (endHz * FFT_SIZE / sampleRate).coerceIn(mBandStartBins[i] + 1, nyquistBin)
            }
        }

        fun ingest(buf: ByteArray, byteReadSize: Int) {
            var i = 0
            val usable = byteReadSize - (byteReadSize % BYTES_PER_STEREO_FRAME)
            while (i + BYTES_PER_STEREO_FRAME <= usable) {
                val left = readPcm16(buf, i)
                val right = readPcm16(buf, i + BYTES_PER_SAMPLE)
                val mono = ((left + right) * 0.5f) / PCM_16_MAX
                mSampleRing[mWriteIndex] = mono.coerceIn(-1f, 1f)
                mWriteIndex = (mWriteIndex + 1) % FFT_SIZE
                if (mSampleCount < FFT_SIZE) {
                    mSampleCount++
                }
                i += BYTES_PER_STEREO_FRAME
            }
        }

        fun analyze(): VoiceAudioFrame {
            if (mSampleCount <= 0) {
                return VoiceAudioFrame.SILENCE
            }

            val available = mSampleCount.coerceAtMost(FFT_SIZE)
            val leadingZeros = FFT_SIZE - available
            val start = (mWriteIndex - available + FFT_SIZE) % FFT_SIZE
            var sumSq = 0.0

            for (i in 0 until FFT_SIZE) {
                val sample: Float = if (i < leadingZeros) {
                    0f
                } else {
                    mSampleRing[(start + i - leadingZeros) % FFT_SIZE]
                }
                if (i >= leadingZeros) {
                    sumSq += sample.toDouble() * sample.toDouble()
                }
                mReal[i] = (sample * mWindow[i]).toDouble()
                mImag[i] = 0.0
            }

            val rms = sqrt(sumSq / available.coerceAtLeast(1))
            val decibel = if (rms <= SILENCE_RMS) {
                0.0
            } else {
                20.0 * log10(rms * PCM_16_MAX.toDouble())
            }
            val loudness = clamp01(((decibel - VISUAL_MIN_DB) / (VISUAL_MAX_DB - VISUAL_MIN_DB)).toFloat())

            fft(mReal, mImag)

            val rawBands = FloatArray(BAND_COUNT)
            for (band in 0 until BAND_COUNT) {
                var energy = 0.0
                var bins = 0
                for (bin in mBandStartBins[band] until mBandEndBins[band]) {
                    val re = mReal[bin]
                    val im = mImag[bin]
                    val mag = sqrt(re * re + im * im) / (FFT_SIZE * 0.5)
                    energy += mag * mag
                    bins++
                }
                val bandRms = sqrt(energy / bins.coerceAtLeast(1))
                val bandDb = if (bandRms <= SILENCE_RMS) BAND_MIN_DB.toDouble()
                else 20.0 * log10(bandRms)
                val normalized = clamp01(
                    ((bandDb - BAND_MIN_DB.toDouble()) /
                            (BAND_MAX_DB - BAND_MIN_DB).toDouble()).toFloat()
                )
                rawBands[band] = clamp01(normalized * (0.45f + loudness * 0.9f))
            }

            var positiveFlux = 0f
            for (band in 0 until BAND_COUNT) {
                positiveFlux += max(0f, rawBands[band] - mPreviousBands[band])
                mPreviousBands[band] = rawBands[band]

                val k = if (rawBands[band] > mSmoothedBands[band]) BAND_ATTACK else BAND_RELEASE
                mSmoothedBands[band] += (rawBands[band] - mSmoothedBands[band]) * k
            }

            val loudnessRise = max(0f, loudness - mPreviousLoudness)
            mPreviousLoudness = loudness
            val transient = clamp01(loudnessRise * 2.2f + positiveFlux / BAND_COUNT * 1.8f)

            return VoiceAudioFrame(
                loudness = loudness,
                low = mSmoothedBands[0],
                lowMid = mSmoothedBands[1],
                mid = mSmoothedBands[2],
                high = mSmoothedBands[3],
                air = mSmoothedBands[4],
                transient = transient
            )
        }

        private fun readPcm16(buf: ByteArray, index: Int): Int {
            return ((buf[index].toInt() and 0xff) or (buf[index + 1].toInt() shl 8)).toShort().toInt()
        }

        private fun fft(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = real[i]
                    real[i] = real[j]
                    real[j] = tr
                    val ti = imag[i]
                    imag[i] = imag[j]
                    imag[j] = ti
                }
            }

            var len = 2
            while (len <= n) {
                val angle = -2.0 * Math.PI / len
                val wLenReal = cos(angle)
                val wLenImag = sin(angle)
                var i = 0
                while (i < n) {
                    var wReal = 1.0
                    var wImag = 0.0
                    val half = len / 2
                    for (offset in 0 until half) {
                        val even = i + offset
                        val odd = even + half
                        val oddReal = real[odd] * wReal - imag[odd] * wImag
                        val oddImag = real[odd] * wImag + imag[odd] * wReal
                        real[odd] = real[even] - oddReal
                        imag[odd] = imag[even] - oddImag
                        real[even] += oddReal
                        imag[even] += oddImag

                        val nextWReal = wReal * wLenReal - wImag * wLenImag
                        wImag = wReal * wLenImag + wImag * wLenReal
                        wReal = nextWReal
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun clamp01(v: Float): Float {
            if (v < 0f) return 0f
            if (v > 1f) return 1f
            return v
        }

        companion object {
            private const val FFT_SIZE = 2048
            private const val BAND_COUNT = 5
            private const val BYTES_PER_SAMPLE = 2
            private const val BYTES_PER_STEREO_FRAME = 4
            private const val PCM_16_MAX = 32768f
            private const val SILENCE_RMS = 1.0e-7

            private const val VISUAL_MIN_DB = 25.0
            private const val VISUAL_MAX_DB = 65.0
            private const val BAND_MIN_DB = -82f
            private const val BAND_MAX_DB = -26f
            private const val BAND_ATTACK = 0.62f
            private const val BAND_RELEASE = 0.34f

            // low, lowMid, mid, high, air。高频上限保守截到 12k，避免麦克风噪声完全主导画面。
            private val BAND_RANGES = intArrayOf(
                60, 250,
                250, 600,
                600, 1600,
                1600, 4000,
                4000, 12000
            )
        }
    }

    companion object {
        const val TAG: String = "AudioRecorder"

        private const val RECORDING_SAMPLE_RATE: Int = 44100
    }
}
