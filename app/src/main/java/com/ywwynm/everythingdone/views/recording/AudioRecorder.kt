@file:Suppress("MissingPermission")

package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolCaptureProfile
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrontEndTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrameReceiver
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolRealtimeAnalyzer
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Created by tyorikan on 2015/06/09.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Updated by ywwynm on 2015/10/02 to meet requirements.
 * Updated by ywwynm on 2016/7/8 to get real decibel
 *
 * Sampling AudioRecord Input
 * This output sends semantic wave drive frames to [RecordingWaveFrameReceiver],
 * and Opus wave frames to [WaveFrameReceiverOpus]. Each analyzer chain only runs
 * when it has at least one linked receiver.
 */
open class AudioRecorder(private val appContext: Context?) {

    private var mSamplingInterval: Int = 20

    private var mAudioRecord: AudioRecord? = null
    private var mRecordingThread: RecordingThread? = null

    private var mBufSize: Int = 0

    /**
     * 实际协商到的采集采样率（[RECORDING_SAMPLE_RATES] 里第一个真正 INITIALIZED 的）。
     *
     * 2026-07-22：由 44100 常量改为运行期协商，优先 48000。现代 Android 的音频 HAL
     * 原生跑 48kHz，请求 44100 会让框架插一个重采样器——而重采样恰好抹平瞬态，
     * 偏偏 FableSol 的巨浪门就靠 punch / energy_rising 这类瞬态证据判定。同时
     * Python 模拟器的全部标定（含母带四个巨浪窗口）都在 48kHz 上做，设备对齐到
     * 48kHz 才是让真机对上工具。三个分析器与 WAV 头都吃这个运行期值。
     */
    private var mSampleRate: Int = RECORDING_SAMPLE_RATES[0]

    private val mWaveReceivers: MutableList<RecordingWaveFrameReceiver> = ArrayList()
    private val mOpusReceivers: MutableList<WaveFrameReceiverOpus> = ArrayList()
    private val mFableSolReceivers: MutableList<FableSolFrameReceiver> = ArrayList()
    private val mFableSolFrontEndTuning = FableSolFrontEndTuning().also { tuning ->
        appContext?.let { FableSolTuning.applyFrontEndStored(it, tuning) }
    }

    // 采集端音效（D6）：改用 UNPROCESSED/VOICE_RECOGNITION 后仍显式关掉 AGC/NS/AEC，持引用防 GC。
    private var mAgc: AutomaticGainControl? = null
    private var mNs: NoiseSuppressor? = null
    private var mAec: AcousticEchoCanceler? = null

    @Volatile
    private var mIsListening: Boolean = false
    @Volatile
    private var mIsRecording: Boolean = false

    private var mRawFile: File? = null
    private var mOutputFile: File? = null

    init {
        initAudioRecord()
    }

    /**
     * link to recording wave receiver
     */
    fun link(receiver: RecordingWaveFrameReceiver) {
        mWaveReceivers.add(receiver)
    }

    /**
     * link to Opus wave receiver（Opus 方案入口，D13）。链路只在有接收器时运行。
     */
    fun linkOpus(receiver: WaveFrameReceiverOpus) {
        mOpusReceivers.add(receiver)
    }

    /**
     * link to FableSol wave receiver（FableSol 方案入口，见 docs/features/audio-visualization-fable-sol/）。
     * 链路只在有接收器时运行；采集线程直接把 PCM 喂给分析器并分发产出的 frames/events。
     */
    fun linkFableSol(receiver: FableSolFrameReceiver) {
        mFableSolReceivers.add(receiver)
    }

    /** 设置 Dialog 调用：下一批 PCM 开始使用新的声音分析参数。 */
    fun setFableSolFrontEndTuning(key: String, value: Double): Boolean =
        mFableSolFrontEndTuning.set(key, value)

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
        // D6：单声道采集（多数手机 stereo 只是复制单声道，mono 减半数据/计算，特征全可从单声道得出）。
        // 采样率优先 48000、回退 44100：不能直接把常量改成 48000，万一某台设备的
        // AudioRecord 不支持，getMinBufferSize 会返回 ERROR_BAD_VALUE，旧写法直接
        // return 就是**静默录不到音**。协商到的值写进 mSampleRate，贯穿三个分析器与 WAV 头。
        for (rate in RECORDING_SAMPLE_RATES) {
            val bufSize: Int = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            )
            if (bufSize <= 0) continue

            // 依次尝试候选采集源，直到某个真正 INITIALIZED；避免"报告支持但实际失败"时静默无数据。
            for (source in candidateSources()) {
                val record: AudioRecord = try {
                    AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize
                    )
                } catch (e: Exception) {
                    continue
                }
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    mAudioRecord = record
                    mBufSize = bufSize
                    mSampleRate = rate
                    // 无论哪种源都尝试关 AGC/NS/AEC：AGC 实时压动态、NS 削小声/高频，都会抹平"大声 vs 小声"。
                    // 部分定制 ROM 在 HAL 层压、关不掉（setEnabled 返回成功却无效），此时靠可视化侧半绝对映射兜底（D21）。
                    val preproc = disablePreprocessing(record.audioSessionId)
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "AudioRecord source=$source rate=$rate " +
                                "preferMic=$PREFER_MIC preproc=$preproc")
                    }
                    return
                }
                try { record.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * D6：优先 UNPROCESSED（保留动态与频谱最佳），再回退 VOICE_RECOGNITION（不加 AGC、NS 默认关），
     * 最后回退 MIC 兜底可用性。minSdk 26，常量均可直接引用。
     */
    private fun candidateSources(): IntArray {
        if (PREFER_MIC) return intArrayOf(MediaRecorder.AudioSource.MIC)
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) {
            intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }
    }

    /**
     * D6：即便选了低处理采集源，仍在 session 上显式关闭 AGC/NS/AEC（双保险）。部分机型只读不可关，
     * `isAvailable()` 为 false 时无能为力（正常）。
     */
    /**
     * 尝试关闭采集端 AGC/NS/AEC，返回可读报告（DEBUG 下打 log，便于在真机上判断各机型能否关掉）。
     * 正确姿势：create 后先读默认态，setEnabled(false) 后再复核 getEnabled——若仍为 true，说明压缩在框架
     * 够不到的 HAL/硬件层（定制 ROM 常见），此时无能为力，靠可视化侧半绝对映射兜底（D21）。
     */
    private fun disablePreprocessing(sessionId: Int): String {
        releaseEffects()
        val sb = StringBuilder()
        try {
            if (AutomaticGainControl.isAvailable()) {
                val agc = AutomaticGainControl.create(sessionId)
                if (agc != null) {
                    val before = agc.enabled
                    val ok = agc.setEnabled(false) == AudioEffect.SUCCESS
                    mAgc = agc
                    sb.append("AGC[before=$before,ok=$ok,after=${agc.enabled}] ")
                } else sb.append("AGC[create=null] ")
            } else sb.append("AGC[unavail] ")
            if (NoiseSuppressor.isAvailable()) {
                val ns = NoiseSuppressor.create(sessionId)
                if (ns != null) {
                    val ok = ns.setEnabled(false) == AudioEffect.SUCCESS
                    mNs = ns
                    sb.append("NS[ok=$ok,after=${ns.enabled}] ")
                } else sb.append("NS[create=null] ")
            } else sb.append("NS[unavail] ")
            if (AcousticEchoCanceler.isAvailable()) {
                mAec = AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(false) }
            }
        } catch (e: Exception) {
            sb.append("EX:${e.message} ")   // 某些机型 create/setEnabled 抛异常，忽略即可（退回默认处理）
        }
        return sb.toString()
    }

    private fun releaseEffects() {
        mAgc?.release(); mAgc = null
        mNs?.release(); mNs = null
        mAec?.release(); mAec = null
    }

    /**
     * start AudioRecord.read
     */
    @Synchronized
    fun startListening() {
        if (mIsListening || mRecordingThread?.isAlive == true) {
            return
        }

        val audioRecord = ensureAudioRecord() ?: return
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
        }
        val rawFile = FileUtil.createTempAudioFile(".raw") ?: return

        audioRecord.startRecording()
        mRawFile = rawFile
        mIsListening = true
        mRecordingThread = RecordingThread(rawFile, audioRecord).apply { start() }
    }

    /**
     * stop AudioRecord.read
     */
    @Synchronized
    fun stopListening(saveFile: Boolean) {
        mIsRecording = false
        stopListeningThread()

        if (saveFile) {
            saveToWaveFile()
        }

        if (!mWaveReceivers.isEmpty()) {
            for (i in mWaveReceivers.indices) {
                mWaveReceivers[i].receive(RecordingWaveDriveFrame.SILENCE)
            }
        }
        for (i in mOpusReceivers.indices) {
            mOpusReceivers[i].receive(WaveDriveFrameOpus.SILENCE)
        }
    }

    @Synchronized
    fun restartListening() {
        stopListening(false)
        startListening()
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
            val rawFile = mRawFile ?: return
            val outputFile = mOutputFile ?: return
            input = FileInputStream(rawFile)
            out = FileOutputStream(outputFile)
            val audioLength: Long = input.getChannel().size()
            val dataLength: Long = audioLength + 36

            // D6：单声道，channels=1、byteRate 相应减半。
            writeWaveFileHeader(out, audioLength, dataLength,
                    mSampleRate.toLong(), 1, (16 * mSampleRate * 1 / 8).toLong())

            val data = ByteArray(mBufSize)

            var readSize: Int
            while (input.read(data).also { readSize = it } != -1) {
                out.write(data, 0, readSize)
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
        stopListening(false)
        releaseEffects()
        mAudioRecord?.release()
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
        header[32] = (channels * 16 / 8).toByte() // block align
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

    private fun ensureAudioRecord(): AudioRecord? {
        val current = mAudioRecord
        if (current != null && current.state == AudioRecord.STATE_INITIALIZED) {
            return current
        }
        initAudioRecord()
        return mAudioRecord?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    private fun stopListeningThread() {
        val thread = mRecordingThread
        mIsListening = false
        thread?.requestStop()
        val audioRecord = mAudioRecord
        if (audioRecord != null &&
            audioRecord.state == AudioRecord.STATE_INITIALIZED &&
            audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
        ) {
            audioRecord.stop()
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.join(STOP_THREAD_JOIN_MS)
        }
        if (mRecordingThread === thread) {
            mRecordingThread = null
        }
    }

    private inner class RecordingThread(
        private val rawFile: File,
        private val audioRecord: AudioRecord
    ) : Thread() {

        var time: Long = System.currentTimeMillis()
        // 三个分析器都吃运行期协商到的采样率（各自内部由 sr 推导频段、帧率与滤波器）。
        private val mAnalyzer: RecordingAudioAnalyzer = RecordingAudioAnalyzer(mSampleRate)
        private val mOpusAnalyzer: WaveAudioAnalyzerOpus =
            WaveAudioAnalyzerOpus(mSampleRate)
        private val mFableSolAnalyzer: FableSolRealtimeAnalyzer =
            FableSolRealtimeAnalyzer(
                mSampleRate,
                FableSolCaptureProfile.PHONE_CAPTURE_V1
            )
        private var mLastLogTime: Long = 0L
        @Volatile private var mShouldRun: Boolean = true

        fun requestStop() {
            mShouldRun = false
        }

        override fun run() {
            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(rawFile)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }

            var readSize: Int
            val readBufferBytes = (VISUAL_READ_FRAMES * RECORDING_BYTES_PER_FRAME)
                .coerceAtMost(mBufSize)
                .coerceAtLeast(RECORDING_BYTES_PER_FRAME)
            val audioBytes = ByteArray(readBufferBytes - readBufferBytes % RECORDING_BYTES_PER_FRAME)
            // 单声道 float 缓冲按最大读长预分配复用；原先每次 read 都新建一只
            // DoubleArray(sampleCount)，在采集线程上持续制造 GC 压力。
            val mono = DoubleArray(audioBytes.size / 2)
            while (mShouldRun) {
                readSize = audioRecord.read(audioBytes, 0, audioBytes.size)
                if (!mShouldRun) {
                    break
                }
                if (readSize > 0) {
                    if (mWaveReceivers.isNotEmpty()) {
                        mAnalyzer.ingest(audioBytes, readSize)
                    }
                    if (mOpusReceivers.isNotEmpty()) {
                        mOpusAnalyzer.ingest(audioBytes, readSize)
                    }
                    if (mFableSolReceivers.isNotEmpty()) {
                        // FableSol 自带滑窗分析器：单声道 16-bit PCM 转 float 直接 feed，
                        // 立即产出 frames/events 并分发（与 20ms 采样间隔解耦）。
                        val sampleCount = readSize / 2
                        var bi = 0
                        for (s in 0 until sampleCount) {
                            val v = ((audioBytes[bi].toInt() and 0xff) or
                                    (audioBytes[bi + 1].toInt() shl 8)).toShort().toInt()
                            mono[s] = v / 32768.0
                            bi += 2
                        }
                        mFableSolFrontEndTuning.applyTo(mFableSolAnalyzer)
                        val (fsFrames, fsEvents) = mFableSolAnalyzer.feed(mono, sampleCount)
                        if (fsFrames.isNotEmpty() || fsEvents.isNotEmpty()) {
                            for (r in mFableSolReceivers.indices) {
                                mFableSolReceivers[r].onAudioFrames(fsFrames, fsEvents)
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = now - time
                if (elapsed >= mSamplingInterval) {
                    if (mWaveReceivers.isNotEmpty()) {
                        val frame: RecordingWaveDriveFrame = mAnalyzer.analyze(elapsed)
                        if (BuildConfig.DEBUG && now - mLastLogTime >= DEBUG_FRAME_LOG_INTERVAL_MS) {
                            Log.i(
                                TAG,
                                "wave drive: level=${frame.level}, presence=${frame.presence}, " +
                                        "swell=${frame.swell}, wake=${frame.wake}, pace=${frame.pace}, " +
                                        "bass=${frame.bassWeight}, voice=${frame.voiceWeight}, " +
                                        "brightness=${frame.brightnessWeight}, pitch=${frame.feature.pitchHz}, " +
                                        "pitchConfidence=${frame.pitchConfidence}, beat=${frame.feature.beatPulse}, " +
                                        "tempo=${frame.feature.tempoBpm}, confidence=${frame.feature.tempoConfidence}, " +
                                        "noise=${frame.feature.noiseLike}"
                            )
                            mLastLogTime = now
                        }
                        for (i in mWaveReceivers.indices) {
                            mWaveReceivers[i].receive(frame)
                        }
                    }
                    if (mOpusReceivers.isNotEmpty()) {
                        val opusFrame: WaveDriveFrameOpus = mOpusAnalyzer.analyze(elapsed)
                        if (BuildConfig.DEBUG && now - mLastLogTime >= DEBUG_FRAME_LOG_INTERVAL_MS) {
                            Log.i(
                                TAG,
                                "opus drive: loud=${opusFrame.loudness}, intensity=${opusFrame.intensity}, " +
                                        "quiet=${opusFrame.quietness}, pace=${opusFrame.pace}, " +
                                        "bright=${opusFrame.brightness}, bass=${opusFrame.bassWeight}, " +
                                        "mid=${opusFrame.midWeight}, treble=${opusFrame.trebleWeight}, " +
                                        "onset=${opusFrame.onset}, sustain=${opusFrame.sustainDrive}, " +
                                        "water=${opusFrame.waterLevel}, pitch=${opusFrame.feature.pitchHz}, " +
                                        "pconf=${opusFrame.pitchConfidence}, noise=${opusFrame.noiseLike}"
                            )
                            mLastLogTime = now
                        }
                        for (i in mOpusReceivers.indices) {
                            mOpusReceivers[i].receive(opusFrame)
                        }
                    }
                    time = now
                }

                if (mIsRecording) {
                    if (fos != null && readSize > 0) {
                        try {
                            fos.write(audioBytes, 0, readSize)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            FileUtil.closeStream(fos)
            synchronized(this@AudioRecorder) {
                if (mRecordingThread === this) {
                    mIsListening = false
                    mRecordingThread = null
                }
            }
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
        private var mPreviousFastLoudness: Float = 0f
        private var mPreviousOnsetScore: Float = 0f
        private var mOnsetMean: Float = 0f
        private var mOnsetDeviation: Float = 0.08f
        private var mRhythmEnergy: Float = 0f
        private var mVisualActivity: Float = 0f
        private var mAdaptiveFloorDb: Float = ADAPTIVE_FLOOR_START_DB
        private var mAdaptivePeakDb: Float = ADAPTIVE_FLOOR_START_DB + ADAPTIVE_MIN_RANGE_DB
        private var mPaceEnergy: Float = 0f
        private var mFrameCounter: Int = 0
        private var mLastFastOnsetFrame: Int = -RHYTHM_HISTORY_SIZE
        private var mLastStrongOnsetFrame: Int = -RHYTHM_HISTORY_SIZE
        private var mLastBeatFrame: Int = -RHYTHM_HISTORY_SIZE
        private var mBeatPeriodFrames: Float = 0f
        private var mTempoBpm: Float = 0f
        private var mTempoConfidence: Float = 0f
        private val mOnsetHistory: FloatArray = FloatArray(RHYTHM_HISTORY_SIZE)
        private var mOnsetHistoryIndex: Int = 0
        private var mOnsetHistoryCount: Int = 0

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

        fun analyze(elapsedMs: Long): VoiceAudioFrame {
            if (mSampleCount <= 0) {
                return VoiceAudioFrame.SILENCE
            }
            val frameSeconds = (elapsedMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)).toFloat() / 1000f

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
            val fastLoudness = recentLoudness(FAST_RMS_SIZE)
            val intensity = loudnessIntensity(
                decibel = decibel.toFloat(),
                loudness = loudness,
                fastLoudness = fastLoudness,
                frameSeconds = frameSeconds
            )

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
            var lowFlux = 0f
            var midFlux = 0f
            var highFlux = 0f
            for (band in 0 until BAND_COUNT) {
                val bandFlux = max(0f, rawBands[band] - mPreviousBands[band])
                positiveFlux += bandFlux
                when (band) {
                    0 -> lowFlux += bandFlux * 1.35f
                    1 -> lowFlux += bandFlux * 0.90f
                    2 -> midFlux += bandFlux * 0.85f
                    3 -> highFlux += bandFlux * 0.70f
                    4 -> highFlux += bandFlux * 0.55f
                }
                mPreviousBands[band] = rawBands[band]

                val k = if (rawBands[band] > mSmoothedBands[band]) BAND_ATTACK else BAND_RELEASE
                mSmoothedBands[band] += (rawBands[band] - mSmoothedBands[band]) * k
            }

            val loudnessRise = max(0f, loudness - mPreviousLoudness)
            val fastLoudnessRise = max(0f, fastLoudness - mPreviousFastLoudness)
            mPreviousLoudness = loudness
            mPreviousFastLoudness = fastLoudness
            val transientFlux = lowFlux * 0.28f + midFlux * 0.32f +
                    highFlux * 0.10f + positiveFlux / BAND_COUNT * 0.18f
            val transient = clamp01(loudnessRise * 2.2f + transientFlux * 1.8f)
            val rhythm = analyzeRhythm(
                frameSeconds = frameSeconds,
                loudness = loudness,
                fastLoudness = fastLoudness,
                loudnessRise = loudnessRise,
                fastLoudnessRise = fastLoudnessRise,
                positiveFlux = positiveFlux,
                lowFlux = lowFlux,
                midFlux = midFlux,
                highFlux = highFlux,
                intensity = intensity
            )

            return VoiceAudioFrame(
                loudness = loudness,
                intensity = intensity,
                low = mSmoothedBands[0],
                lowMid = mSmoothedBands[1],
                mid = mSmoothedBands[2],
                high = mSmoothedBands[3],
                air = mSmoothedBands[4],
                transient = transient,
                onset = rhythm.onset,
                beatPulse = rhythm.beatPulse,
                beatPhase = rhythm.beatPhase,
                tempoBpm = rhythm.tempoBpm,
                tempoConfidence = rhythm.tempoConfidence,
                rhythmEnergy = rhythm.rhythmEnergy,
                lowPulse = rhythm.lowPulse,
                highPulse = rhythm.highPulse,
                pace = rhythm.pace,
                activity = rhythm.activity
            )
        }

        private class RhythmFrame(
            val onset: Float,
            val beatPulse: Float,
            val beatPhase: Float,
            val tempoBpm: Float,
            val tempoConfidence: Float,
            val rhythmEnergy: Float,
            val lowPulse: Float,
            val highPulse: Float,
            val pace: Float,
            val activity: Float
        )

        private fun analyzeRhythm(
            frameSeconds: Float,
            loudness: Float,
            fastLoudness: Float,
            loudnessRise: Float,
            fastLoudnessRise: Float,
            positiveFlux: Float,
            lowFlux: Float,
            midFlux: Float,
            highFlux: Float,
            intensity: Float
        ): RhythmFrame {
            val broadFlux = positiveFlux / BAND_COUNT
            val bodyFlux = lowFlux * 0.38f + midFlux * 0.55f + broadFlux * 0.08f
            val rawOnset = fastLoudnessRise * FAST_ONSET_GAIN +
                    loudnessRise * 0.90f +
                    bodyFlux * 1.05f +
                    highFlux * 0.06f

            val meanDelta = rawOnset - mOnsetMean
            mOnsetMean += meanDelta * ONSET_MEAN_ALPHA
            mOnsetDeviation += (abs(meanDelta) - mOnsetDeviation) * ONSET_DEVIATION_ALPHA

            val adaptiveFloor = mOnsetMean + mOnsetDeviation * ONSET_DEVIATION_BIAS
            val onsetScore = clamp01((rawOnset - adaptiveFloor) / (mOnsetDeviation * ONSET_GAIN + ONSET_MIN_RANGE))
            val fastImpact = clamp01(
                (fastLoudnessRise * FAST_IMPACT_GAIN +
                        lowFlux * 0.85f +
                        midFlux * 0.45f +
                        broadFlux * 0.08f -
                        adaptiveFloor * FAST_IMPACT_FLOOR_MIX) /
                        (mOnsetDeviation * FAST_IMPACT_DEVIATION_GAIN + FAST_IMPACT_MIN_RANGE)
            )
            val lowPulse = clamp01((lowFlux + fastLoudnessRise * 0.30f + loudnessRise * 0.18f) * LOW_PULSE_GAIN)
            val highPulseGate = clamp01((max(onsetScore, fastImpact) - HIGH_PULSE_ONSET_GATE) /
                    (1f - HIGH_PULSE_ONSET_GATE))
            val eventStrength = max(onsetScore, fastImpact)
            val sustainedEnergy = max(loudness, fastLoudness)
            val fluxActivity = max(bodyFlux, broadFlux * 0.12f)
            val activityTarget = max(
                max(
                    smoothStep(ACTIVITY_LOUDNESS_START, ACTIVITY_LOUDNESS_FULL, sustainedEnergy),
                    smoothStep(ACTIVITY_EVENT_START, ACTIVITY_EVENT_FULL, eventStrength)
                ),
                smoothStep(ACTIVITY_FLUX_START, ACTIVITY_FLUX_FULL, fluxActivity)
            )
            mVisualActivity += (activityTarget - mVisualActivity) *
                    if (activityTarget > mVisualActivity) ACTIVITY_ATTACK else ACTIVITY_RELEASE
            val activity = clamp01(mVisualActivity)
            val eventAllowed = activity >= EVENT_ACTIVITY_GATE || eventStrength >= WAKE_EVENT_TRIGGER

            val highPulse = clamp01((highFlux * 0.70f + broadFlux * 0.10f) * HIGH_PULSE_GAIN) *
                    highPulseGate * HIGH_PULSE_VISUAL_SCALE
            val gatedLowPulse = lowPulse * activity
            val gatedHighPulse = highPulse * activity
            val paceImpulse = if (eventAllowed) {
                clamp01(
                    (eventStrength - PACE_EVENT_GATE) / (1f - PACE_EVENT_GATE) * PACE_EVENT_WEIGHT +
                            fastImpact * PACE_FAST_IMPACT_WEIGHT +
                            bodyFlux * PACE_BODY_FLUX_WEIGHT
                ) * (PACE_ACTIVITY_FLOOR + activity * (1f - PACE_ACTIVITY_FLOOR))
            } else {
                0f
            }
            val paceTau = if (paceImpulse > mPaceEnergy) PACE_ATTACK_TAU else PACE_RELEASE_TAU
            mPaceEnergy += (paceImpulse - mPaceEnergy) * smoothingFactor(frameSeconds, paceTau)

            val tempoOnsetScore = if (eventAllowed) onsetScore else 0f
            pushOnset(tempoOnsetScore)
            estimateTempo(frameSeconds)

            val minOnsetSpacing = max(1, (MIN_ONSET_SPACING_SEC / frameSeconds).roundToInt())
            val minFastSpacing = max(1, (MIN_FAST_ONSET_SPACING_SEC / frameSeconds).roundToInt())
            val isFastOnset = eventAllowed &&
                    fastImpact >= FAST_ONSET_TRIGGER &&
                    mFrameCounter - mLastFastOnsetFrame >= minFastSpacing
            val isStrongOnset = eventAllowed &&
                    onsetScore >= ONSET_TRIGGER &&
                    onsetScore >= mPreviousOnsetScore * ONSET_PEAK_HOLD &&
                    mFrameCounter - mLastStrongOnsetFrame >= minOnsetSpacing

            if (isFastOnset) {
                mLastFastOnsetFrame = mFrameCounter
            }

            var beatPulse = 0f
            if (isStrongOnset) {
                mLastStrongOnsetFrame = mFrameCounter
                if (shouldAcceptBeat(frameSeconds)) {
                    if (mLastBeatFrame > -RHYTHM_HISTORY_SIZE / 2) {
                        val interval = mFrameCounter - mLastBeatFrame
                        val minPeriod = (60f / MAX_BPM / frameSeconds).roundToInt()
                        val maxPeriod = (60f / MIN_BPM / frameSeconds).roundToInt()
                        if (interval in minPeriod..maxPeriod) {
                            mBeatPeriodFrames = if (mBeatPeriodFrames <= 0f) {
                                interval.toFloat()
                            } else {
                                mBeatPeriodFrames * BEAT_PERIOD_SMOOTH + interval * (1f - BEAT_PERIOD_SMOOTH)
                            }
                            mTempoBpm = 60f / (mBeatPeriodFrames * frameSeconds)
                            mTempoConfidence = (mTempoConfidence + onsetScore * 0.20f).coerceAtMost(1f)
                        }
                    }
                    mLastBeatFrame = mFrameCounter
                    beatPulse = onsetScore
                }
            }

            val rhythmEnergyTarget = clamp01(
                (eventStrength * 0.40f +
                        fastLoudness * 0.42f +
                        intensity * 0.30f +
                        loudness * 0.14f +
                        gatedLowPulse * 0.24f) * activity
            )
            mRhythmEnergy += (rhythmEnergyTarget - mRhythmEnergy) *
                    if (rhythmEnergyTarget > mRhythmEnergy) RHYTHM_ENERGY_ATTACK else RHYTHM_ENERGY_RELEASE

            val beatPhase = currentBeatPhase()
            val predictedPulse = predictedBeatPulse(beatPhase)
            beatPulse = max(beatPulse, predictedPulse * (0.45f + loudness * 0.55f) * activity)

            mPreviousOnsetScore = tempoOnsetScore
            mFrameCounter++
            val tempoPace = if (mTempoConfidence > TEMPO_PACE_MIN_CONFIDENCE) {
                smoothStep(PACE_TEMPO_MIN_BPM, PACE_TEMPO_FULL_BPM, mTempoBpm) * mTempoConfidence
            } else {
                0f
            }
            val pace = max(mPaceEnergy, tempoPace)

            return RhythmFrame(
                onset = if (isStrongOnset || isFastOnset) {
                    max(onsetScore, fastImpact)
                } else {
                    max(onsetScore * ONSET_CONTINUOUS_SCALE, fastImpact * FAST_ONSET_CONTINUOUS_SCALE) *
                            activity
                },
                beatPulse = clamp01(beatPulse),
                beatPhase = beatPhase,
                tempoBpm = mTempoBpm,
                tempoConfidence = mTempoConfidence,
                rhythmEnergy = clamp01(mRhythmEnergy),
                lowPulse = gatedLowPulse,
                highPulse = gatedHighPulse,
                pace = clamp01(pace),
                activity = activity
            )
        }

        private fun loudnessIntensity(
            decibel: Float,
            loudness: Float,
            fastLoudness: Float,
            frameSeconds: Float
        ): Float {
            val db = decibel.coerceIn(ADAPTIVE_FLOOR_MIN_DB, ADAPTIVE_PEAK_MAX_DB)
            val floorTau = if (db < mAdaptiveFloorDb) ADAPTIVE_FLOOR_FALL_TAU else ADAPTIVE_FLOOR_RISE_TAU
            mAdaptiveFloorDb += (db - mAdaptiveFloorDb) * smoothingFactor(frameSeconds, floorTau)
            val peakTau = if (db > mAdaptivePeakDb) ADAPTIVE_PEAK_RISE_TAU else ADAPTIVE_PEAK_FALL_TAU
            mAdaptivePeakDb += (db - mAdaptivePeakDb) * smoothingFactor(frameSeconds, peakTau)
            if (mAdaptivePeakDb < mAdaptiveFloorDb + ADAPTIVE_MIN_RANGE_DB) {
                mAdaptivePeakDb = mAdaptiveFloorDb + ADAPTIVE_MIN_RANGE_DB
            }

            val usableRange = (mAdaptivePeakDb - mAdaptiveFloorDb)
                .coerceIn(ADAPTIVE_MIN_RANGE_DB, ADAPTIVE_MAX_RANGE_DB)
            val relative = clamp01((db - mAdaptiveFloorDb - ADAPTIVE_RELATIVE_OFFSET_DB) / usableRange)
            return clamp01(
                loudness * ABSOLUTE_LOUDNESS_INTENSITY_MIX +
                        fastLoudness * FAST_LOUDNESS_INTENSITY_MIX +
                        relative * RELATIVE_LOUDNESS_INTENSITY_MIX
            )
        }

        private fun smoothingFactor(frameSeconds: Float, tau: Float): Float {
            return 1f - exp(-frameSeconds / tau)
        }

        private fun smoothStep(start: Float, end: Float, v: Float): Float {
            if (end <= start) return if (v >= end) 1f else 0f
            val t = clamp01((v - start) / (end - start))
            return t * t * (3f - 2f * t)
        }

        private fun recentLoudness(windowSize: Int): Float {
            val available = mSampleCount.coerceAtMost(windowSize)
            if (available <= 0) return 0f
            val start = (mWriteIndex - available + FFT_SIZE) % FFT_SIZE
            var sumSq = 0.0
            for (i in 0 until available) {
                val sample = mSampleRing[(start + i) % FFT_SIZE]
                sumSq += sample.toDouble() * sample.toDouble()
            }
            val rms = sqrt(sumSq / available)
            val db = if (rms <= SILENCE_RMS) {
                0.0
            } else {
                20.0 * log10(rms * PCM_16_MAX.toDouble())
            }
            return clamp01(((db - VISUAL_MIN_DB) / (VISUAL_MAX_DB - VISUAL_MIN_DB)).toFloat())
        }

        private fun pushOnset(onset: Float) {
            mOnsetHistory[mOnsetHistoryIndex] = onset
            mOnsetHistoryIndex = (mOnsetHistoryIndex + 1) % RHYTHM_HISTORY_SIZE
            if (mOnsetHistoryCount < RHYTHM_HISTORY_SIZE) {
                mOnsetHistoryCount++
            }
        }

        private fun onsetAtAge(age: Int): Float {
            val index = (mOnsetHistoryIndex - 1 - age + RHYTHM_HISTORY_SIZE) % RHYTHM_HISTORY_SIZE
            return mOnsetHistory[index]
        }

        private fun estimateTempo(frameSeconds: Float) {
            val minPeriod = max(2, (60f / MAX_BPM / frameSeconds).roundToInt())
            val maxPeriod = min(RHYTHM_HISTORY_SIZE / 2, (60f / MIN_BPM / frameSeconds).roundToInt())
            if (mOnsetHistoryCount < max(minPeriod * 3, 16) || minPeriod >= maxPeriod) {
                mTempoConfidence *= TEMPO_CONFIDENCE_DECAY
                return
            }

            var bestPeriod = 0
            var bestScore = 0f
            var scoreSum = 0f
            var scoreCount = 0

            for (period in minPeriod..maxPeriod) {
                var score = 0f
                var pairs = 0
                var age = 0
                while (age + period < mOnsetHistoryCount) {
                    score += onsetAtAge(age) * onsetAtAge(age + period)
                    pairs++
                    age++
                }
                if (pairs > 0) {
                    score /= pairs
                    scoreSum += score
                    scoreCount++
                    if (score > bestScore) {
                        bestScore = score
                        bestPeriod = period
                    }
                }
            }

            if (bestPeriod <= 0 || scoreCount <= 0) {
                mTempoConfidence *= TEMPO_CONFIDENCE_DECAY
                return
            }

            val avgScore = scoreSum / scoreCount
            val rawConfidence = clamp01((bestScore - avgScore) / (bestScore + TEMPO_SCORE_EPSILON) * 2.4f)
            if (rawConfidence >= TEMPO_LOCK_THRESHOLD) {
                mBeatPeriodFrames = if (mBeatPeriodFrames <= 0f) {
                    bestPeriod.toFloat()
                } else {
                    mBeatPeriodFrames * TEMPO_PERIOD_SMOOTH + bestPeriod * (1f - TEMPO_PERIOD_SMOOTH)
                }
                mTempoBpm = 60f / (mBeatPeriodFrames * frameSeconds)
                mTempoConfidence += (rawConfidence - mTempoConfidence) * TEMPO_CONFIDENCE_ATTACK
            } else {
                mTempoConfidence *= TEMPO_CONFIDENCE_DECAY
            }
        }

        private fun shouldAcceptBeat(frameSeconds: Float): Boolean {
            if (mBeatPeriodFrames <= 0f || mLastBeatFrame <= -RHYTHM_HISTORY_SIZE / 2) {
                return true
            }
            val framesSinceBeat = mFrameCounter - mLastBeatFrame
            val minGap = max(1, (mBeatPeriodFrames * BEAT_MIN_GAP).roundToInt())
            if (framesSinceBeat < minGap) {
                return false
            }
            if (mTempoConfidence < BEAT_SYNC_CONFIDENCE) {
                return true
            }

            val expected = mBeatPeriodFrames
            val phaseError = abs(framesSinceBeat - expected) / expected
            val lateEnough = framesSinceBeat >= (expected * BEAT_FORCE_AFTER).roundToInt()
            val inTempoWindow = phaseError <= BEAT_PHASE_TOLERANCE
            val minAbsoluteGap = framesSinceBeat >= (MIN_BEAT_GAP_SEC / frameSeconds).roundToInt()
            return minAbsoluteGap && (inTempoWindow || lateEnough)
        }

        private fun currentBeatPhase(): Float {
            if (mBeatPeriodFrames <= 0f || mLastBeatFrame <= -RHYTHM_HISTORY_SIZE / 2) {
                return 0f
            }
            val phase = ((mFrameCounter - mLastBeatFrame).toFloat() / mBeatPeriodFrames) % 1f
            return if (phase < 0f) phase + 1f else phase
        }

        private fun predictedBeatPulse(beatPhase: Float): Float {
            if (mTempoConfidence < PREDICTED_BEAT_MIN_CONFIDENCE || mRhythmEnergy < PREDICTED_BEAT_MIN_ENERGY) {
                return 0f
            }
            val distance = min(beatPhase, 1f - beatPhase)
            if (distance > PREDICTED_BEAT_WIDTH) {
                return 0f
            }
            val t = 1f - distance / PREDICTED_BEAT_WIDTH
            return t * t * mTempoConfidence * mRhythmEnergy
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
            private const val FAST_RMS_SIZE = 512
            private const val BAND_COUNT = 5
            private const val BYTES_PER_SAMPLE = 2
            private const val BYTES_PER_STEREO_FRAME = 4
            private const val PCM_16_MAX = 32768f
            private const val SILENCE_RMS = 1.0e-7

            private const val VISUAL_MIN_DB = 25.0
            private const val VISUAL_MAX_DB = 65.0
            private const val ADAPTIVE_FLOOR_START_DB = 25f
            private const val ADAPTIVE_FLOOR_MIN_DB = 18f
            private const val ADAPTIVE_PEAK_MAX_DB = 84f
            private const val ADAPTIVE_MIN_RANGE_DB = 34f
            private const val ADAPTIVE_MAX_RANGE_DB = 54f
            private const val ADAPTIVE_RELATIVE_OFFSET_DB = 6.5f
            private const val ADAPTIVE_FLOOR_FALL_TAU = 0.85f
            private const val ADAPTIVE_FLOOR_RISE_TAU = 5.5f
            private const val ADAPTIVE_PEAK_RISE_TAU = 0.18f
            private const val ADAPTIVE_PEAK_FALL_TAU = 3.2f
            private const val ABSOLUTE_LOUDNESS_INTENSITY_MIX = 0.48f
            private const val FAST_LOUDNESS_INTENSITY_MIX = 0.20f
            private const val RELATIVE_LOUDNESS_INTENSITY_MIX = 0.42f
            private const val BAND_MIN_DB = -82f
            private const val BAND_MAX_DB = -26f
            private const val BAND_ATTACK = 0.62f
            private const val BAND_RELEASE = 0.34f

            private const val MIN_FRAME_MS = 16L
            private const val MAX_FRAME_MS = 120L
            private const val RHYTHM_HISTORY_SIZE = 256
            private const val MIN_BPM = 70f
            private const val MAX_BPM = 200f

            private const val ONSET_MEAN_ALPHA = 0.035f
            private const val ONSET_DEVIATION_ALPHA = 0.060f
            private const val ONSET_DEVIATION_BIAS = 0.20f
            private const val FAST_ONSET_GAIN = 2.15f
            private const val ONSET_GAIN = 2.55f
            private const val ONSET_MIN_RANGE = 0.045f
            private const val ONSET_TRIGGER = 0.42f
            private const val ONSET_PEAK_HOLD = 0.82f
            private const val ONSET_CONTINUOUS_SCALE = 0.24f
            private const val FAST_ONSET_TRIGGER = 0.36f
            private const val FAST_ONSET_CONTINUOUS_SCALE = 0.18f
            private const val FAST_IMPACT_GAIN = 3.10f
            private const val FAST_IMPACT_FLOOR_MIX = 0.28f
            private const val FAST_IMPACT_DEVIATION_GAIN = 1.15f
            private const val FAST_IMPACT_MIN_RANGE = 0.040f
            private const val MIN_ONSET_SPACING_SEC = 0.08f
            private const val MIN_FAST_ONSET_SPACING_SEC = 0.045f
            private const val LOW_PULSE_GAIN = 2.35f
            private const val HIGH_PULSE_GAIN = 2.10f
            private const val HIGH_PULSE_ONSET_GATE = 0.50f
            private const val HIGH_PULSE_VISUAL_SCALE = 0.36f
            private const val RHYTHM_ENERGY_ATTACK = 0.26f
            private const val RHYTHM_ENERGY_RELEASE = 0.060f
            private const val ACTIVITY_LOUDNESS_START = 0.16f
            private const val ACTIVITY_LOUDNESS_FULL = 0.42f
            private const val ACTIVITY_EVENT_START = 0.24f
            private const val ACTIVITY_EVENT_FULL = 0.70f
            private const val ACTIVITY_FLUX_START = 0.055f
            private const val ACTIVITY_FLUX_FULL = 0.22f
            private const val ACTIVITY_ATTACK = 0.30f
            private const val ACTIVITY_RELEASE = 0.060f
            private const val EVENT_ACTIVITY_GATE = 0.24f
            private const val WAKE_EVENT_TRIGGER = 0.62f

            private const val TEMPO_PERIOD_SMOOTH = 0.86f
            private const val BEAT_PERIOD_SMOOTH = 0.70f
            private const val TEMPO_CONFIDENCE_ATTACK = 0.18f
            private const val TEMPO_CONFIDENCE_DECAY = 0.96f
            private const val TEMPO_LOCK_THRESHOLD = 0.14f
            private const val TEMPO_SCORE_EPSILON = 0.0001f

            private const val BEAT_MIN_GAP = 0.45f
            private const val BEAT_FORCE_AFTER = 1.35f
            private const val BEAT_PHASE_TOLERANCE = 0.30f
            private const val BEAT_SYNC_CONFIDENCE = 0.36f
            private const val MIN_BEAT_GAP_SEC = 0.22f
            private const val PREDICTED_BEAT_MIN_CONFIDENCE = 0.34f
            private const val PREDICTED_BEAT_MIN_ENERGY = 0.10f
            private const val PREDICTED_BEAT_WIDTH = 0.16f
            private const val PACE_EVENT_GATE = 0.18f
            private const val PACE_EVENT_WEIGHT = 0.76f
            private const val PACE_FAST_IMPACT_WEIGHT = 0.24f
            private const val PACE_BODY_FLUX_WEIGHT = 1.25f
            private const val PACE_ACTIVITY_FLOOR = 0.24f
            private const val PACE_ATTACK_TAU = 0.070f
            private const val PACE_RELEASE_TAU = 0.42f
            private const val TEMPO_PACE_MIN_CONFIDENCE = 0.18f
            private const val PACE_TEMPO_MIN_BPM = 82f
            private const val PACE_TEMPO_FULL_BPM = 176f

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

        // 采集源开关（编译期）：true=MIC（兼容性好、远场佳），false=UNPROCESSED（回退 VOICE_RECOGNITION）。
        // 两种模式现在都会尝试关 AGC/NS/AEC 以保留动态（D21）；能否真正关掉由 disablePreprocessing 的 log 判断。
        private const val PREFER_MIC: Boolean = true

        /**
         * 采集采样率候选，按优先级排列；实际生效值见 [mSampleRate]。
         *
         * 48000 在前：现代 Android 的音频 HAL 原生就是 48kHz，请求 44100 会让框架
         * 插一层重采样，把瞬态抹平（FableSol 的巨浪门正是靠瞬态证据判定）。44100
         * 保留为兼容回退——它是 AudioRecord 唯一被规范保证支持的采样率。
         */
        private val RECORDING_SAMPLE_RATES: IntArray = intArrayOf(48000, 44100)
        private const val DEBUG_FRAME_LOG_INTERVAL_MS: Long = 400L
        private const val VISUAL_READ_FRAMES: Int = 512
        private const val RECORDING_BYTES_PER_FRAME: Int = 2  // 单声道 16-bit
        private const val STOP_THREAD_JOIN_MS: Long = 600L
    }
}
