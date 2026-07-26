package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * 按需解码音频文件，逐段吐出单声道 16-bit PCM。
 *
 * 导出必须是流式的：20 分钟 48kHz 单声道整段驻留内存要 115MB。这里只保留当前一块
 * 解码输出，读多少解多少。
 *
 * 与 [FableSolAudioFilePlayer] 的区别只是**没有 AudioTrack**——不播声音、也不按播放头
 * 对齐，纯粹按音频时间往下走（fablesol-video-export D7 的非实时驱动）。
 */
internal class FableSolExportAudioSource(path: String) {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var pending: ShortBuffer? = null
    private var inputDone = false
    private var outputDone = false

    var sampleRate: Int = 48000
        private set
    var channelCount: Int = 1
        private set
    var durationUs: Long = 0L
        private set

    init {
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            val trackFormat = checkNotNull(format) { "No audio track in $path" }
            extractor.selectTrack(trackIndex)
            sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }
            durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType(mime)
            // 先登记所有权；configure/start 任一步失败，catch 才有引用可释放。
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /**
     * 读出至多 [count] 个单声道样本写进 [dest]；返回实际样本数，0 表示已到结尾。
     * 多声道源在这里下混成单声道，与录音链的单声道输入保持一致。
     */
    fun read(dest: ShortArray, count: Int): Int {
        var written = 0
        while (written < count) {
            val buffer = pending
            if (buffer != null && buffer.hasRemaining()) {
                val take = minOf(count - written, buffer.remaining() / channelCount)
                if (take <= 0) {
                    pending = null
                    continue
                }
                for (i in 0 until take) {
                    var sum = 0
                    for (channel in 0 until channelCount) sum += buffer.get().toInt()
                    dest[written + i] = (sum / channelCount).toShort()
                }
                written += take
                if (!buffer.hasRemaining()) pending = null
                continue
            }
            if (outputDone) break
            if (!decodeMore()) break
        }
        return written
    }

    /** @return false 表示这一轮没能推进（已到结尾）。 */
    private fun decodeMore(): Boolean {
        val decoder = codec ?: return false
        if (!inputDone) {
            val index = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input: ByteBuffer? = decoder.getInputBuffer(index)
                val size = if (input == null) -1 else extractor.readSampleData(input, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(
                        index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val output = decoder.outputFormat
            if (output.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (output.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channelCount = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            }
            return true
        }
        if (index < 0) return !outputDone
        val output = decoder.getOutputBuffer(index)
        if (output != null && info.size > 0) {
            output.position(info.offset)
            output.limit(info.offset + info.size)
            val copy = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder())
            copy.put(output)
            copy.flip()
            pending = copy.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        decoder.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
        return true
    }

    fun release() {
        try {
            codec?.stop()
        } catch (ignored: Throwable) {
        }
        try {
            codec?.release()
        } catch (ignored: Throwable) {
        }
        codec = null
        try {
            extractor.release()
        } catch (ignored: Throwable) {
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
