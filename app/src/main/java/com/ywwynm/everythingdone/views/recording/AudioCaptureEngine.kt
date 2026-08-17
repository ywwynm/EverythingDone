@file:Suppress("MissingPermission")

package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolCaptureProfile
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrameReceiver
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrontEndTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolGravityTrack
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolRealtimeAnalyzer
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * 录音服务专用的多来源采集引擎。
 *
 * 捕获线程先产生最终要写入 WAV 的 PCM，再从该 PCM 下混出单声道送给 FableSol，保证实时
 * 动画与成品内容一致。生命周期与线程串行化由 AudioRecordingService 负责。
 */
class AudioCaptureEngine(private val appContext: Context) {

    interface Listener {
        fun onSystemSilenceChanged(mode: AudioInputMode, silent: Boolean)
        fun onCaptureFailure(mode: AudioInputMode, source: CaptureSource, errorCode: Int)
        /** 已写入的数据量逼近 WAV 32 位上限；上层应立即"停止并保留"。整个录音只回调一次。 */
        fun onRecordingSizeLimitReached(mode: AudioInputMode)
    }

    enum class CaptureSource {
        MICROPHONE,
        SYSTEM,
        OUTPUT
    }

    data class ConfigurationResult(
        val success: Boolean,
        val sampleRate: Int = 0,
        val outputChannels: Int = 0,
        val aecEnabled: Boolean = false,
        val error: String? = null
    )

    @Volatile
    var listener: Listener? = null

    /** 最近一次 WAV 封装失败是否因磁盘空间不足；供上层给出针对性提示。 */
    @Volatile
    var lastFinalizeNoSpace = false
        private set

    @Volatile
    private var listening = false

    @Volatile
    private var recording = false

    private var inputMode = AudioInputMode.MICROPHONE
    private var sampleRate = SAMPLE_RATE_CANDIDATES[0]
    private var outputChannels = 1
    private var microphoneRecord: AudioRecord? = null
    private var systemRecord: AudioRecord? = null
    // volatile：捕获线程退出时无锁清理自身状态。若退出清理与 stopListening 持有的引擎锁
    // 互斥，join 只会白等满超时——每次停止预览/切换来源都多付 600ms。
    @Volatile
    private var captureThread: CaptureThread? = null
    private var rawFile: File? = null
    private var outputFile: File? = null
    private var recordingSampleRate = sampleRate
    private var recordingChannels = outputChannels

    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var aecEnabled = false

    private val fableSolReceivers = CopyOnWriteArrayList<FableSolFrameReceiver>()
    private val fableSolFrontEndTuning = FableSolFrontEndTuning().also { tuning ->
        FableSolTuning.applyFrontEndStored(appContext, tuning)
    }
    private val gravityTrack = FableSolGravityTrack.Collector()
    private var gravityTrackEnabled = true

    // 会话内最后一次重力样本（已按屏幕方向换算）。新建/重建的 Dialog 在传感器出首个
    // 样本前（GRAVITY 融合预热可达数百毫秒）直接用它摆正水面姿态，消除"刚返回不响应
    // 倾斜"的空窗。
    @Volatile
    private var lastGravitySample: FloatArray? = null

    /**
     * 当前采集对象组内是否有输入流死亡过（`AudioRecord.read` 返回负错误码，如
     * ERROR_DEAD_OBJECT）。在 read 失败处即刻置位，不依赖故障回调的送达——停止与
     * 故障并发时回调可能被 `shouldRun` 抑制或因服务已进入 STOPPED 被忽略，但这个
     * 标志始终反映真相，收尾方以它决定既有配置是否还能复用。[configure] 重建后复位。
     * 输出文件故障（OUTPUT）不置位：采集对象仍健康。
     */
    @Volatile
    var lastInputFaulted: Boolean = false
        private set

    /**
     * 最近一次 [startListening] 返回 false 是否因为输出侧（raw 临时文件/目录创建失败）。
     * 上层据此把失败归类为 [文件输出故障] 而不是输入源故障——后者会触发回落麦克风并
     * 改写来源偏好，对存储问题完全用错了药。
     */
    @Volatile
    var lastStartFailedOnOutput: Boolean = false
        private set

    @Synchronized
    fun configure(mode: AudioInputMode, mediaProjection: MediaProjection?): ConfigurationResult {
        stopListening(saveFile = false)
        releaseAudioRecords()
        lastInputFaulted = false
        inputMode = mode
        outputChannels = mode.outputChannels

        if (mode.requiresSystemAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ConfigurationResult(false, error = "AudioPlaybackCapture requires Android 10+")
        }
        if (mode.requiresSystemAudio && mediaProjection == null) {
            return ConfigurationResult(false, error = "MediaProjection is missing")
        }

        for (rate in SAMPLE_RATE_CANDIDATES) {
            val mic = if (mode.requiresMicrophone) {
                createMicrophoneRecord(rate, enableEchoCancellation = mode.requiresSystemAudio)
            } else {
                null
            }
            if (mode.requiresMicrophone && mic == null) continue

            val system = if (mode.requiresSystemAudio) {
                createSystemRecord(rate, mediaProjection!!)
            } else {
                null
            }
            if (mode.requiresSystemAudio && system == null) {
                releaseEffects()
                mic?.release()
                continue
            }

            microphoneRecord = mic
            systemRecord = system
            sampleRate = rate
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "configured mode=$mode rate=$sampleRate channels=$outputChannels aec=$aecEnabled"
                )
            }
            return ConfigurationResult(
                success = true,
                sampleRate = sampleRate,
                outputChannels = outputChannels,
                aecEnabled = aecEnabled
            )
        }
        releaseAudioRecords()
        return ConfigurationResult(false, error = "No compatible AudioRecord configuration")
    }

    @Synchronized
    fun startListening(): Boolean {
        lastStartFailedOnOutput = false
        if (listening || captureThread?.isAlive == true) return true
        if (!recordsReady()) return false
        val nextRawFile = FileUtil.createTempAudioFile(".raw", SERVICE_RAW_SUB_DIR) ?: run {
            lastStartFailedOnOutput = true
            return false
        }
        // raw 流在这里同步打开而不是留给采集线程：返回 true 必须意味着输出链路
        // 已真正就绪——上层拿到 true 就会把系统类来源持久化为"已确认"，若文件其实
        // 还没打开、稍后异步失败，一次没成功的会话就污染了下次 Dialog 的默认来源。
        val output = try {
            FileOutputStream(nextRawFile)
        } catch (error: IOException) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Unable to open raw audio file", error)
            lastStartFailedOnOutput = true
            FileUtil.deleteFile(nextRawFile.absolutePath)
            return false
        }

        try {
            systemRecord?.startRecording()
            microphoneRecord?.startRecording()
        } catch (error: Throwable) {
            stopRecordQuietly(microphoneRecord)
            stopRecordQuietly(systemRecord)
            FileUtil.closeStream(output)
            FileUtil.deleteFile(nextRawFile.absolutePath)
            if (BuildConfig.DEBUG) Log.e(TAG, "Unable to start audio capture", error)
            return false
        }

        rawFile = nextRawFile
        listening = true
        captureThread = CaptureThread(
            mode = inputMode,
            rate = sampleRate,
            channels = outputChannels,
            output = output,
            microphone = microphoneRecord,
            system = systemRecord
        ).also { it.start() }
        return true
    }

    @Synchronized
    fun startRecording(): File? {
        if (!listening || recording) return outputFile
        val nextOutput = AttachmentHelper.createAttachmentFile(AttachmentHelper.AUDIO) ?: return null
        outputFile = nextOutput
        recordingSampleRate = sampleRate
        recordingChannels = outputChannels
        if (gravityTrackEnabled) gravityTrack.start()
        recording = true
        return nextOutput
    }

    @Synchronized
    fun stopListening(saveFile: Boolean): File? {
        recording = false
        gravityTrack.stop()
        stopCaptureThread()
        if (!saveFile) {
            // 预览停止：raw 只是空占位（recording=false 从不写入），立即清掉。
            rawFile?.let { FileUtil.deleteFile(it.absolutePath) }
            rawFile = null
            return outputFile
        }
        return if (saveToWaveFile()) outputFile else null
    }

    @Synchronized
    fun restartListening(): Boolean {
        stopListening(saveFile = false)
        return startListening()
    }

    @Synchronized
    fun release(clearConsumers: Boolean = true) {
        stopListening(saveFile = false)
        releaseAudioRecords()
        if (clearConsumers) {
            fableSolReceivers.clear()
            listener = null
        }
    }

    fun isListening(): Boolean = listening

    fun isRecording(): Boolean = recording

    fun currentMode(): AudioInputMode = inputMode

    fun getSavedFile(): File? = outputFile

    fun isAecEnabled(): Boolean = aecEnabled

    fun linkFableSol(receiver: FableSolFrameReceiver) {
        if (!fableSolReceivers.contains(receiver)) fableSolReceivers.add(receiver)
    }

    fun unlinkFableSol(receiver: FableSolFrameReceiver) {
        fableSolReceivers.remove(receiver)
    }

    fun setGravityTrackEnabled(enabled: Boolean) {
        gravityTrackEnabled = enabled
    }

    fun offerGravitySample(x: Float, y: Float, z: Float) {
        gravityTrack.offer(x, y, z)
        lastGravitySample = floatArrayOf(x, y, z)
    }

    fun lastGravitySample(): FloatArray? = lastGravitySample

    private fun recordsReady(): Boolean {
        val micReady = !inputMode.requiresMicrophone ||
            microphoneRecord?.state == AudioRecord.STATE_INITIALIZED
        val systemReady = !inputMode.requiresSystemAudio ||
            systemRecord?.state == AudioRecord.STATE_INITIALIZED
        return micReady && systemReady
    }

    private fun createMicrophoneRecord(rate: Int, enableEchoCancellation: Boolean): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return null

        for (source in microphoneSources(enableEchoCancellation)) {
            val record = try {
                AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes, CAPTURE_FRAMES * BYTES_PER_SAMPLE * INTERNAL_BUFFER_MULTIPLIER)
                )
            } catch (_: Throwable) {
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                configurePreprocessing(record.audioSessionId, enableEchoCancellation)
                return record
            }
            record.release()
        }
        return null
    }

    private fun createSystemRecord(rate: Int, projection: MediaProjection): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val minBytes = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return null

        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(
                        minBytes,
                        CAPTURE_FRAMES * BYTES_PER_STEREO_FRAME * INTERNAL_BUFFER_MULTIPLIER
                    )
                )
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (error: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to initialize playback capture", error)
            null
        }
    }

    private fun microphoneSources(enableEchoCancellation: Boolean): IntArray {
        if (!enableEchoCancellation) {
            // 与旧 AudioRecorder 的 PREFER_MIC=true 保持一致，不改变纯麦克风录音音色。
            return intArrayOf(MediaRecorder.AudioSource.MIC)
        }
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.UNPROCESSED
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }
    }

    private fun configurePreprocessing(sessionId: Int, enableEchoCancellation: Boolean) {
        releaseEffects()
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.also { it.setEnabled(false) }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.also { it.setEnabled(false) }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.also { effect ->
                    val requested = effect.setEnabled(enableEchoCancellation) == AudioEffect.SUCCESS
                    aecEnabled = enableEchoCancellation && requested && effect.enabled
                }
            }
        } catch (error: Throwable) {
            aecEnabled = false
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to configure audio preprocessing", error)
        }
    }

    private fun releaseEffects() {
        agc?.release()
        ns?.release()
        aec?.release()
        agc = null
        ns = null
        aec = null
        aecEnabled = false
    }

    private fun releaseAudioRecords() {
        releaseEffects()
        microphoneRecord?.release()
        systemRecord?.release()
        microphoneRecord = null
        systemRecord = null
    }

    private fun stopCaptureThread() {
        val thread = captureThread
        listening = false
        thread?.requestStop()
        stopRecordQuietly(microphoneRecord)
        stopRecordQuietly(systemRecord)
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_THREAD_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (captureThread === thread) captureThread = null
    }

    private fun stopRecordQuietly(record: AudioRecord?) {
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (_: Throwable) {
        }
    }

    private fun saveToWaveFile(): Boolean {
        val source = rawFile ?: return false
        val destination = outputFile ?: return false
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        var saved = false
        lastFinalizeNoSpace = false
        try {
            input = FileInputStream(source)
            output = FileOutputStream(destination)
            val audioLength = input.channel.size()
            val bytesPerFrame = BYTES_PER_SAMPLE * recordingChannels
            val duration = if (bytesPerFrame > 0 && recordingSampleRate > 0) {
                audioLength.toDouble() / (bytesPerFrame.toDouble() * recordingSampleRate)
            } else {
                0.0
            }
            val gravityChunk = gravityTrack.buildChunk(duration)
            output.write(
                PcmWaveHeader.create(
                    audioLength,
                    recordingSampleRate,
                    recordingChannels,
                    gravityChunk?.size ?: 0
                )
            )
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            if (gravityChunk != null) output.write(gravityChunk)
            output.flush()
            saved = true
        } catch (error: Exception) {
            // 不只 IOException：RIFF 超限的显式拒绝抛 IllegalArgumentException，漏接会让
            // 失败清理与完成回报都不执行，快照永远停在 STOPPED+busy。
            Log.e(TAG, "Unable to finalize WAV", error)
            lastFinalizeNoSpace = generateSequence(error as Throwable) { it.cause }
                .any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC }
        } finally {
            FileUtil.closeStream(input)
            FileUtil.closeStream(output)
        }
        if (!saved) {
            // 用户裁定（2026-08-16）：封装失败（含磁盘写满）不保留数据，半成品 WAV 与
            // raw 一并删除，由上层给出空间不足的针对性提示。
            FileUtil.deleteFile(destination.absolutePath)
            outputFile = null
            FileUtil.deleteFile(source.absolutePath)
            rawFile = null
        } else {
            // WAV 已封装，raw 立即失去价值；及时删除把"双倍空间"的驻留窗口从
            // 用户拍板前的整段时间缩到复制瞬间。
            FileUtil.deleteFile(source.absolutePath)
            rawFile = null
        }
        return saved
    }

    private inner class CaptureThread(
        private val mode: AudioInputMode,
        private val rate: Int,
        private val channels: Int,
        /** 由 [startListening] 同步打开——线程启动即保证输出链路就绪。 */
        private val output: FileOutputStream,
        private val microphone: AudioRecord?,
        private val system: AudioRecord?
    ) : Thread("AudioCapture") {

        @Volatile
        private var shouldRun = true

        private val analyzer = FableSolRealtimeAnalyzer(rate, FableSolCaptureProfile.PHONE_CAPTURE_V1)
        private var systemSilentFrames = 0L
        private var systemSilenceReported = false
        private var recordedDataBytes = 0L
        private var sizeLimitReported = false

        fun requestStop() {
            shouldRun = false
        }

        override fun run() {
            val microphoneSamples = ShortArray(CAPTURE_FRAMES)
            val systemSamples = ShortArray(CAPTURE_FRAMES * 2)
            val finalSamples = ShortArray(CAPTURE_FRAMES * channels)
            val monoSamples = ShortArray(CAPTURE_FRAMES)
            val finalBytes = ByteArray(CAPTURE_FRAMES * channels * BYTES_PER_SAMPLE)
            val monoDoubles = DoubleArray(CAPTURE_FRAMES)
            var failure: Pair<CaptureSource, Int>? = null

            try {
                while (shouldRun) {
                    val frames = when (mode) {
                        AudioInputMode.MICROPHONE -> {
                            val read = microphone!!.read(
                                microphoneSamples,
                                0,
                                CAPTURE_FRAMES,
                                AudioRecord.READ_BLOCKING
                            )
                            if (read < 0) {
                                lastInputFaulted = true
                                failure = CaptureSource.MICROPHONE to read
                                0
                            } else {
                                System.arraycopy(microphoneSamples, 0, finalSamples, 0, read)
                                read
                            }
                        }

                        AudioInputMode.SYSTEM -> {
                            val read = system!!.read(
                                systemSamples,
                                0,
                                CAPTURE_FRAMES * 2,
                                AudioRecord.READ_BLOCKING
                            )
                            if (read < 0) {
                                lastInputFaulted = true
                                failure = CaptureSource.SYSTEM to read
                                0
                            } else {
                                val evenSamples = read - read % 2
                                System.arraycopy(systemSamples, 0, finalSamples, 0, evenSamples)
                                evenSamples / 2
                            }
                        }

                        AudioInputMode.SYSTEM_AND_MICROPHONE -> {
                            val systemRead = system!!.read(
                                systemSamples,
                                0,
                                CAPTURE_FRAMES * 2,
                                AudioRecord.READ_BLOCKING
                            )
                            if (systemRead < 0) {
                                lastInputFaulted = true
                                failure = CaptureSource.SYSTEM to systemRead
                                0
                            } else {
                                val systemFrames = (systemRead - systemRead % 2) / 2
                                val micRead = microphone!!.read(
                                    microphoneSamples,
                                    0,
                                    systemFrames,
                                    AudioRecord.READ_BLOCKING
                                )
                                if (micRead < 0) {
                                    lastInputFaulted = true
                                    failure = CaptureSource.MICROPHONE to micRead
                                    0
                                } else {
                                    Pcm16AudioMixer.mixSystemAndMicrophone(
                                        systemSamples,
                                        systemFrames,
                                        microphoneSamples,
                                        micRead,
                                        finalSamples
                                    )
                                }
                            }
                        }
                    }

                    if (!shouldRun || failure != null) break
                    if (frames <= 0) continue

                    if (mode.requiresSystemAudio) inspectSystemSignal(systemSamples, frames)

                    val finalSampleCount = frames * channels
                    val finalByteCount = Pcm16AudioMixer.writeLittleEndian(
                        finalSamples,
                        finalSampleCount,
                        finalBytes
                    )
                    if (recording) {
                        try {
                            output.write(finalBytes, 0, finalByteCount)
                        } catch (error: IOException) {
                            Log.e(TAG, "Unable to write raw audio", error)
                            failure = CaptureSource.OUTPUT to FILE_WRITE_ERROR
                            break
                        }
                        recordedDataBytes += finalByteCount
                        if (!sizeLimitReported && AudioRecordingLimits.reached(recordedDataBytes)) {
                            sizeLimitReported = true
                            if (captureThread === this) listener?.onRecordingSizeLimitReached(mode)
                        }
                    }

                    val monoCount = if (channels == 1) {
                        System.arraycopy(finalSamples, 0, monoSamples, 0, frames)
                        frames
                    } else {
                        Pcm16AudioMixer.downmixStereoToMono(finalSamples, frames, monoSamples)
                    }
                    for (index in 0 until monoCount) {
                        monoDoubles[index] = monoSamples[index] / 32768.0
                    }
                    fableSolFrontEndTuning.applyTo(analyzer)
                    val (featureFrames, events) = analyzer.feed(monoDoubles, monoCount)
                    if (featureFrames.isNotEmpty() || events.isNotEmpty()) {
                        for (receiver in fableSolReceivers) {
                            receiver.onAudioFrames(featureFrames, events)
                        }
                    }
                }
            } finally {
                FileUtil.closeStream(output)
                clearSelfCaptureState()
            }

            if (shouldRun) {
                failure?.let { listener?.onCaptureFailure(mode, it.first, it.second) }
            }
        }

        /**
         * 无锁清理：只有 [captureThread] 仍指向本线程时才有资格清（stopCaptureThread 抢先清过
         * 就跳过）。不取引擎锁——stopListening 持锁 join 本线程，取锁会让 join 永远等满超时。
         */
        private fun clearSelfCaptureState() {
            if (captureThread === this) {
                listening = false
                captureThread = null
            }
        }

        private fun inspectSystemSignal(samples: ShortArray, frames: Int) {
            var signal = false
            val sampleCount = minOf(frames * 2, samples.size)
            for (index in 0 until sampleCount) {
                if (abs(samples[index].toInt()) > SYSTEM_SIGNAL_THRESHOLD_PCM) {
                    signal = true
                    break
                }
            }
            if (signal) {
                systemSilentFrames = 0L
                if (systemSilenceReported) {
                    systemSilenceReported = false
                    // 身份自查：已被替换/停止的旧线程不得再发实时状态——旧预览的迟到
                    // silent=true 会污染新预览的快照，且新线程只在自己报过静音后才会
                    // 报恢复，污染一旦写入就无人纠正。
                    if (captureThread === this) listener?.onSystemSilenceChanged(mode, false)
                }
            } else {
                systemSilentFrames += frames
                if (!systemSilenceReported && systemSilentFrames >= rate.toLong() * SYSTEM_SILENCE_SECONDS) {
                    systemSilenceReported = true
                    if (captureThread === this) listener?.onSystemSilenceChanged(mode, true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AudioCaptureEngine"
        // 与旧 AudioRecorder（FableSol 调参工具）的 temp/audio_raw 隔离：raw 文件名只有
        // 秒级时间戳，共用目录会同名相撞，会话收尾的整目录删除也会误删对方在用的文件。
        private const val SERVICE_RAW_SUB_DIR = "audio_raw_service"
        const val SERVICE_RAW_TEMP_PATH_SEGMENT = "/audio_raw_service"
        private val SAMPLE_RATE_CANDIDATES = intArrayOf(48_000, 44_100)
        private const val CAPTURE_FRAMES = 512
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val INTERNAL_BUFFER_MULTIPLIER = 4
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val STOP_THREAD_JOIN_MS = 600L
        private const val SYSTEM_SILENCE_SECONDS = 6L
        private const val SYSTEM_SIGNAL_THRESHOLD_PCM = 8
        private const val FILE_WRITE_ERROR = -10_001
    }
}
