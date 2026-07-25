package com.ywwynm.everythingdone.views.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolCaptureProfile
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrameReceiver
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolFrontEndTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolRealtimeAnalyzer
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning

import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * 音频附件播放器：解码文件 → AudioTrack 播放 → 把同一段 PCM 喂给
 * [FableSolRealtimeAnalyzer]，产出的 frames/events 分发给 [FableSolFrameReceiver]。
 *
 * 分析链与录音完全一致：同一个实时分析器、同一套采集域标定
 * （[FableSolCaptureProfile.PHONE_CAPTURE_V1]）、同样 512 样本一批推进，
 * 区别只在 PCM 来自文件而不是麦克风。不做任何离线/整曲分析。
 *
 * **喂入进度以已播出的采样位置为准**（[AudioTrack.getPlaybackHeadPosition]），
 * 不是解码进度：AudioTrack 缓冲里通常压着上百毫秒的音频，按解码进度喂会让水面
 * 整体早于声音。已写入但尚未播出的样本先存在环形缓冲里，播到哪儿喂到哪儿。
 */
class FableSolAudioFilePlayer(private val appContext: Context) {

    interface Listener {
        /** 解码器就绪，[durationMs] 为音轨时长（无法读出时为 0）。 */
        fun onPrepared(durationMs: Int)
        fun onPlayingChanged(playing: Boolean)
        fun onPositionChanged(positionMs: Int)
        /** 自然播放到结尾（seek 到结尾不算）。 */
        fun onCompleted()
        fun onFailed(message: String)
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mFableSolReceivers: MutableList<FableSolFrameReceiver> = ArrayList()
    private val mFrontEndTuning = FableSolFrontEndTuning().also { tuning ->
        FableSolTuning.applyFrontEndStored(appContext, tuning)
    }

    private var mListener: Listener? = null
    private var mThread: PlaybackThread? = null

    @Volatile private var mPositionMs: Int = 0
    @Volatile private var mDurationMs: Int = 0
    @Volatile private var mPlaying: Boolean = false

    /** 与 [AudioRecorder.linkFableSol] 同义：链路只在有接收器时运行。 */
    fun linkFableSol(receiver: FableSolFrameReceiver) {
        mFableSolReceivers.add(receiver)
    }

    fun setListener(listener: Listener?) {
        mListener = listener
    }

    /** 打开并（可选）立即播放一个音频文件；会先停掉上一条播放线程。 */
    fun open(path: String, autoPlay: Boolean) {
        stopCurrentThread()
        mPositionMs = 0
        mDurationMs = 0
        mPlaying = false
        val thread = PlaybackThread(path, !autoPlay)
        mThread = thread
        thread.start()
    }

    fun play() {
        val thread = mThread ?: return
        thread.setPaused(false)
    }

    fun pause() {
        val thread = mThread ?: return
        thread.setPaused(true)
    }

    fun isPlaying(): Boolean = mPlaying

    fun positionMs(): Int = mPositionMs

    fun durationMs(): Int = mDurationMs

    fun seekTo(positionMs: Int) {
        val thread = mThread ?: return
        val clamped = positionMs.coerceAtLeast(0)
        // 让 UI 立刻跟手：真实位置在解码线程完成 flush 后再覆盖。
        mPositionMs = clamped
        thread.requestSeek(clamped * 1000L)
    }

    /** 停止播放并释放解码器/AudioTrack；调用后本实例不再回调。 */
    fun release() {
        mListener = null
        stopCurrentThread()
        mFableSolReceivers.clear()
    }

    private fun stopCurrentThread() {
        val thread = mThread ?: return
        mThread = null
        thread.quit()
    }

    private fun postPlaying(thread: PlaybackThread, playing: Boolean) {
        mPlaying = playing
        mMainHandler.post {
            if (mThread === thread) mListener?.onPlayingChanged(playing)
        }
    }

    private inner class PlaybackThread(
        private val path: String,
        startPaused: Boolean
    ) : Thread("FableSolAudioPlayback") {

        private val lock = Object()
        @Volatile private var shouldRun = true
        @Volatile private var paused = startPaused
        @Volatile private var seekRequestUs = NO_SEEK

        private var extractor: MediaExtractor? = null
        private var codec: MediaCodec? = null
        private var audioTrack: AudioTrack? = null
        private var analyzer: FableSolRealtimeAnalyzer? = null

        private var sampleRate = 0
        private var channelCount = 0
        private var frameBytes = 2

        /** 已播出样本 → 分析器的环形缓冲；三个游标都以「上次 flush 之后」计。 */
        private var ring = DoubleArray(0)
        private var ringWrite = 0L
        private var ringRead = 0L
        private var flushBaseFrames = 0L

        private var monoChunk = DoubleArray(0)
        private var byteChunk = ByteArray(0)
        private var feedBuffer = DoubleArray(FEED_FRAMES)

        private var lastPositionPostMs = 0L
        /** 收尾停滞判定：播放头上次读数与它保持不变的起始时刻。 */
        private var drainLastPlayedFrames = -1L
        private var drainStallSinceMs = 0L

        fun quit() {
            shouldRun = false
            synchronized(lock) {
                paused = false
                lock.notifyAll()
            }
            // AudioTrack 的写入用非阻塞模式，线程最多在一轮 8ms 的等待里，
            // 这里 join 是为了保证 release 之后不再有回调与硬件占用。
            try {
                join(THREAD_JOIN_MS)
            } catch (_: InterruptedException) {
                currentThread().interrupt()
            }
        }

        fun setPaused(value: Boolean) {
            synchronized(lock) {
                if (paused == value) return
                paused = value
                lock.notifyAll()
            }
        }

        fun requestSeek(positionUs: Long) {
            synchronized(lock) {
                seekRequestUs = positionUs
                lock.notifyAll()
            }
        }

        override fun run() {
            try {
                if (!openDecoder()) return
                decodeLoop()
            } catch (e: Exception) {
                Log.w(TAG, "playback failed: $path", e)
                fail(e.message ?: e.javaClass.simpleName)
            } finally {
                releaseCodec()
            }
        }

        private fun openDecoder(): Boolean {
            val mediaExtractor = MediaExtractor()
            extractor = mediaExtractor
            mediaExtractor.setDataSource(path)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until mediaExtractor.trackCount) {
                val format = mediaExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = format
                    break
                }
            }
            val format = trackFormat
            if (trackIndex < 0 || format == null) {
                fail("no audio track")
                return false
            }
            mediaExtractor.selectTrack(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            mDurationMs = (durationUs / 1000L).toInt().coerceAtLeast(0)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val mediaCodec = MediaCodec.createDecoderByType(mime)
            codec = mediaCodec
            mediaCodec.configure(format, null, null, 0)
            mediaCodec.start()

            val duration = mDurationMs
            postToMain { mListener?.onPrepared(duration) }
            return true
        }

        private fun decodeLoop() {
            val mediaExtractor = extractor ?: return
            val mediaCodec = codec ?: return
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (shouldRun) {
                if (consumeSeekRequest()) {
                    sawInputEos = false
                    sawOutputEos = false
                }
                if (!waitWhilePaused()) break

                if (!sawInputEos) {
                    val inputIndex = mediaCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputIndex)
                        val read = if (inputBuffer == null) -1 else {
                            inputBuffer.clear()
                            mediaExtractor.readSampleData(inputBuffer, 0)
                        }
                        if (read < 0) {
                            mediaCodec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            mediaCodec.queueInputBuffer(
                                inputIndex, 0, read, mediaExtractor.sampleTime, 0
                            )
                            mediaExtractor.advance()
                        }
                    }
                }

                if (sawOutputEos) {
                    if (drainToEnd()) {
                        if (shouldRun) complete()
                        return
                    }
                    continue
                }

                when (val outputIndex = mediaCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureOutput(mediaCodec.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> pumpAnalyzer()
                    else -> {
                        if (outputIndex >= 0) {
                            if (audioTrack == null) ensureOutput(mediaCodec.outputFormat)
                            if (info.size > 0) {
                                val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                                if (outputBuffer != null) {
                                    if (byteChunk.size < info.size) byteChunk = ByteArray(info.size)
                                    outputBuffer.position(info.offset)
                                    outputBuffer.get(byteChunk, 0, info.size)
                                    outputBuffer.clear()
                                    if (!renderChunk(info.size)) {
                                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                                        return
                                    }
                                }
                            }
                            mediaCodec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }
        }

        /** 输出格式就绪：按解码器实际报的采样率/声道数建 AudioTrack 与分析器。 */
        private fun ensureOutput(outputFormat: MediaFormat) {
            if (audioTrack != null) return
            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            frameBytes = 2 * channelCount

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .apply {
                    when (channelCount) {
                        1 -> setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        2 -> setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        else -> setChannelIndexMask((1 shl channelCount) - 1)
                    }
                }
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameBytes * 2048)
            val bufferBytes = minBytes * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track

            // 环形缓冲要装得下 AudioTrack 里排队的全部帧，取 4 倍缓冲与 1s 的大者。
            val bufferFrames = bufferBytes / frameBytes
            ring = DoubleArray(max(bufferFrames * 4, sampleRate))
            ringWrite = 0L
            ringRead = 0L

            analyzer = FableSolRealtimeAnalyzer(sampleRate, FableSolCaptureProfile.PHONE_CAPTURE_V1)
            if (!paused) {
                track.play()
                postPlaying(this, true)
            }
        }

        /**
         * 把一块解码出的 PCM 交给 AudioTrack，并把对应的单声道样本压入环形缓冲。
         * 写入用非阻塞模式：暂停/退出时线程要能立刻响应，不能卡在 write 里。
         */
        private fun renderChunk(size: Int): Boolean {
            val track = audioTrack ?: return true
            val totalFrames = size / frameBytes
            if (totalFrames <= 0) return true
            if (monoChunk.size < totalFrames) monoChunk = DoubleArray(totalFrames)
            var byteIndex = 0
            for (frame in 0 until totalFrames) {
                var sum = 0.0
                for (channel in 0 until channelCount) {
                    val value = ((byteChunk[byteIndex].toInt() and 0xff) or
                            (byteChunk[byteIndex + 1].toInt() shl 8)).toShort().toInt()
                    sum += value / 32768.0
                    byteIndex += 2
                }
                monoChunk[frame] = sum / channelCount
            }

            var writtenBytes = 0
            var pushedFrames = 0
            while (writtenBytes < size && shouldRun) {
                if (!waitWhilePaused()) return false
                if (seekRequestUs != NO_SEEK) return true  // 丢掉这块，seek 会重建游标
                val written = track.write(
                    byteChunk, writtenBytes, size - writtenBytes, AudioTrack.WRITE_NON_BLOCKING
                )
                if (written < 0) {
                    fail("AudioTrack.write=$written")
                    return false
                }
                writtenBytes += written
                val acceptedFrames = writtenBytes / frameBytes
                if (acceptedFrames > pushedFrames) {
                    pushMono(monoChunk, pushedFrames, acceptedFrames - pushedFrames)
                    pushedFrames = acceptedFrames
                }
                pumpAnalyzer()
                if (written == 0) {
                    // 缓冲已满：让出一小段时间，同时保持 seek/暂停/退出的响应。
                    synchronized(lock) {
                        if (shouldRun && !paused && seekRequestUs == NO_SEEK) {
                            try {
                                lock.wait(BUFFER_FULL_WAIT_MS)
                            } catch (_: InterruptedException) {
                                currentThread().interrupt()
                            }
                        }
                    }
                }
            }
            return true
        }

        private fun pushMono(source: DoubleArray, offset: Int, count: Int) {
            if (ring.isEmpty() || count <= 0) return
            // 正常路径下环形缓冲远大于 AudioTrack 队列，不会被写满；真发生了就丢最旧的，
            // 宁可让分析器少看一段，也不能把未播出的样本覆盖成错位数据。
            val overflow = (ringWrite + count) - ringRead - ring.size
            if (overflow > 0) {
                ringRead += overflow
                if (BuildConfig.DEBUG) Log.w(TAG, "pcm ring overflow: dropped $overflow samples")
            }
            for (i in 0 until count) {
                ring[((ringWrite + i) % ring.size).toInt()] = source[offset + i]
            }
            ringWrite += count
        }

        /** 按已播出的位置把样本喂给分析器，并顺带上报播放进度。 */
        private fun pumpAnalyzer() {
            val track = audioTrack ?: return
            val currentAnalyzer = analyzer ?: return
            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val target = min(playedFrames, ringWrite)
            while (ringRead < target) {
                val count = min((target - ringRead).toInt(), FEED_FRAMES)
                for (i in 0 until count) {
                    feedBuffer[i] = ring[((ringRead + i) % ring.size).toInt()]
                }
                ringRead += count
                mFrontEndTuning.applyTo(currentAnalyzer)
                val (frames, events) = currentAnalyzer.feed(feedBuffer, count)
                if (frames.isNotEmpty() || events.isNotEmpty()) {
                    for (r in mFableSolReceivers.indices) {
                        mFableSolReceivers[r].onAudioFrames(frames, events)
                    }
                }
            }
            reportPosition(flushBaseFrames + playedFrames)
        }

        private fun reportPosition(positionFrames: Long) {
            if (sampleRate <= 0) return
            val positionMs = (positionFrames * 1000L / sampleRate).toInt()
            mPositionMs = if (mDurationMs > 0) positionMs.coerceAtMost(mDurationMs) else positionMs
            val now = SystemClock.uptimeMillis()
            if (now - lastPositionPostMs < POSITION_POST_INTERVAL_MS) return
            lastPositionPostMs = now
            val value = mPositionMs
            postToMain { mListener?.onPositionChanged(value) }
        }

        /** 处理挂起的 seek 请求；返回 true 表示已经 flush 过、EOS 状态需要重置。 */
        private fun consumeSeekRequest(): Boolean {
            val targetUs: Long
            synchronized(lock) {
                if (seekRequestUs == NO_SEEK) return false
                targetUs = seekRequestUs
                seekRequestUs = NO_SEEK
            }
            val mediaExtractor = extractor ?: return false
            val mediaCodec = codec ?: return false
            val track = audioTrack
            track?.pause()
            track?.flush()
            mediaExtractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            mediaCodec.flush()
            ringWrite = 0L
            ringRead = 0L
            resetDrainStall()
            // 实际落点以解封装器为准：SEEK_TO_CLOSEST_SYNC 未必正好停在请求的时刻，
            // 用请求值当基准会让之后的进度整体偏移。
            val landedUs = mediaExtractor.sampleTime.let { if (it >= 0L) it else targetUs }
            flushBaseFrames = if (sampleRate > 0) landedUs * sampleRate / 1_000_000L else 0L
            if (sampleRate > 0) {
                mPositionMs = (flushBaseFrames * 1000L / sampleRate).toInt()
            }
            if (!paused) track?.play()
            return true
        }

        /**
         * EOS 之后把 AudioTrack 里剩下的帧放完；返回 true 表示已经播完。
         *
         * 不能死等 `playbackHeadPosition` 追平写入量：末尾不足一个 HAL 缓冲的残帧未必会被
         * 播出并计入播放头，尤其是在结尾附近暂停过一次之后。那样这里会永远等下去——
         * 播放不结束、按钮一直停在"暂停"图标，只有反复点播放/暂停才被踢动几帧。
         * 因此加停滞判定：播放头在非暂停状态下连续 [DRAIN_STALL_MS] 没有前进就按放完处理，
         * 并把环形缓冲里剩下的样本补给分析器，水面不会在最后一截突然断掉。
         */
        private fun drainToEnd(): Boolean {
            val track = audioTrack ?: return true
            pumpAnalyzer()
            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (playedFrames >= ringWrite) return true
            val now = SystemClock.uptimeMillis()
            if (playedFrames != drainLastPlayedFrames) {
                drainLastPlayedFrames = playedFrames
                drainStallSinceMs = now
            } else if (drainStallSinceMs != 0L && now - drainStallSinceMs >= DRAIN_STALL_MS) {
                feedRemainingToAnalyzer()
                return true
            }
            synchronized(lock) {
                if (shouldRun && !paused && seekRequestUs == NO_SEEK) {
                    try {
                        lock.wait(BUFFER_FULL_WAIT_MS)
                    } catch (_: InterruptedException) {
                        currentThread().interrupt()
                    }
                }
            }
            return !shouldRun
        }

        /**
         * 清掉收尾停滞计时。暂停期间播放头本来就不动，恢复后必须从头计时，
         * 否则一次几秒的暂停会让 [drainToEnd] 在恢复的那一刻就误判成"已经放完"。
         */
        private fun resetDrainStall() {
            drainLastPlayedFrames = -1L
            drainStallSinceMs = 0L
        }

        /** 把环形缓冲里还没喂过的样本一次性交给分析器（只在收尾时调用）。 */
        private fun feedRemainingToAnalyzer() {
            val currentAnalyzer = analyzer ?: return
            while (ringRead < ringWrite) {
                val count = min((ringWrite - ringRead).toInt(), FEED_FRAMES)
                for (i in 0 until count) {
                    feedBuffer[i] = ring[((ringRead + i) % ring.size).toInt()]
                }
                ringRead += count
                mFrontEndTuning.applyTo(currentAnalyzer)
                val (frames, events) = currentAnalyzer.feed(feedBuffer, count)
                if (frames.isNotEmpty() || events.isNotEmpty()) {
                    for (r in mFableSolReceivers.indices) {
                        mFableSolReceivers[r].onAudioFrames(frames, events)
                    }
                }
            }
        }

        /** 暂停期间阻塞在这里；返回 false 表示线程该退出了。 */
        private fun waitWhilePaused(): Boolean {
            if (paused) {
                audioTrack?.pause()
                postPlaying(this, false)
                synchronized(lock) {
                    while (paused && shouldRun && seekRequestUs == NO_SEEK) {
                        try {
                            lock.wait()
                        } catch (_: InterruptedException) {
                            currentThread().interrupt()
                            return false
                        }
                    }
                }
                if (!shouldRun) return false
            }
            // 不变式：只要不是暂停态，AudioTrack 就必须处于 PLAYING。按硬件实际状态判断
            // 而不是按 mPlaying 记账——记账一旦与硬件不同步（例如收尾附近的暂停/继续），
            // 写入会永远返回 0、线程空转，表现就是"按钮停在暂停图标但没有声音"。
            val track = audioTrack
            if (!paused && track != null) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    resetDrainStall()
                }
                if (!mPlaying) postPlaying(this, true)
            }
            return shouldRun
        }

        private fun complete() {
            mPlaying = false
            if (mDurationMs > 0) mPositionMs = mDurationMs
            postToMain {
                mListener?.onPositionChanged(mPositionMs)
                mListener?.onPlayingChanged(false)
                mListener?.onCompleted()
            }
        }

        private fun fail(message: String) {
            mPlaying = false
            postToMain { mListener?.onFailed(message) }
        }

        private fun releaseCodec() {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.release()
            } catch (_: Exception) {
            }
            audioTrack = null
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            extractor = null
            analyzer = null
            if (mPlaying) {
                mPlaying = false
                postToMain { mListener?.onPlayingChanged(false) }
            }
        }

        private fun postToMain(action: () -> Unit) {
            mMainHandler.post {
                if (mThread === this) action()
            }
        }
    }

    companion object {
        private const val TAG = "FableSolAudioFilePlayer"

        /** 与录音链一致的观测批次：512 样本一批推进分析器。 */
        private const val FEED_FRAMES = 512
        private const val DEQUEUE_TIMEOUT_US = 4000L
        private const val BUFFER_FULL_WAIT_MS = 8L
        /** 收尾时播放头停滞多久算"已经放完"（见 drainToEnd）。 */
        private const val DRAIN_STALL_MS = 320L
        private const val POSITION_POST_INTERVAL_MS = 48L
        private const val THREAD_JOIN_MS = 200L
        private const val NO_SEEK = -1L
    }
}
