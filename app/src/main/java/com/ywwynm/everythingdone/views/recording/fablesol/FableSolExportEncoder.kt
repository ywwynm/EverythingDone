package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 视频 + 音频编码与封装。
 *
 * 档位在渲染开始之前定好（fablesol-video-export D9）——它同时决定 shader 分支和编码器
 * input surface 的 EGL 色彩空间，所以顺序必须是：探测 → 定档 → 建编码器 → 建 EGLSurface
 * → 开渲。任何一步失败由调用方沿阶梯换下一档重试。
 */
internal class FableSolExportEncoder(
    private val widthPx: Int,
    private val heightPx: Int,
    private val frameRate: Int,
    private val tier: FableSolExportTier,
    private val options: FableSolExportOptions,
    private val audioSampleRate: Int,
    private val muxer: MediaMuxer,
    /** PQ 静态元数据用的峰值亮度（尼特）；非 PQ 档位忽略。 */
    private val peakNits: Double = FableSolExportTransfer.SDR_WHITE_NITS,
    /**
     * 漫反射白（尼特），用来给 MaxFALL 一个说得通的值。
     *
     * 这个数现在用户可调（200–800），再把 MaxFALL 写死成 203 就与画面对不上了——水体与
     * 卡片的大面积亮度本来就落在漫反射白这一档，帧平均亮度以它为准才是诚实的。
     */
    private val diffuseWhiteNits: Double = FableSolExportTransfer.SDR_WHITE_NITS
) {

    // 必须在 init 的保护范围内逐个创建。若把两个实例写成抛异常的属性初始化器，第二个创建
    // 失败时 init 根本不会执行，先创建的编码器和调用方已经建好的 muxer 都无法清理。
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var audioSamplesFed = 0L
    private var audioInputDone = false
    private var audioOutputDone = false
    private var videoOutputDone = false
    private var videoStarted = false
    private var audioStarted = false
    private var muxerReleased = false
    private var released = false
    var hasVideoOutputFormat = false
        private set
    val hasStartedMuxer: Boolean
        get() = muxing
    /** 两条轨都拿到 format 之前不能 start muxer，先把已编码的样本攒着。 */
    private val pending = ArrayList<PendingSample>(32)

    private var codecInputSurface: Surface? = null
    val inputSurface: Surface
        get() = checkNotNull(codecInputSurface) { "Video input surface is not available" }

    /**
     * true 时走**字节缓冲输入**而不是 input surface。
     *
     * 只有 HDR10+ 需要：它的动态元数据必须逐帧通过 `PARAMETER_KEY_HDR10_PLUS_INFO` 提供，
     * 而该参数在 surface 输入模式下被系统明确禁止。代价是 RGB→YUV 不再由编码器代劳，
     * 得由 [FableSolExportP010Bridge] 交出 P010。
     */
    private val byteBufferInput: Boolean = tier.hdrFormat?.usesByteBufferInput == true

    /** 字节缓冲模式下编码器要求的行距与平面高度；start() 之后才知道。 */
    private var inputStride = widthPx * 2
    private var inputSliceHeight = heightPx
    private var lastVideoPresentationTimeUs = 0L

    /** 字节缓冲模式下，编出来的码流里是否真的带上了 HDR10+ 的动态元数据。 */
    var hdr10PlusSeiSeen = false
        private set

    init {
        try {
            // 按**具体编码器名字**创建，而不是 createEncoderByType：后者返回系统首选实现，
            // 未必就是探测时确认支持该 profile / 尺寸 / 帧率的那一个。
            videoCodec = MediaCodec.createByCodecName(tier.codecName)
            audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME)
            configureCodecs()
        } catch (error: Throwable) {
            // 构造函数抛出时对象不可达，调用方拿不到引用也就无从释放——两个 MediaCodec、
            // input surface 和 muxer 会一起泄漏，连着试几档就可能耗尽硬件编码器实例。
            release()
            throw error
        }
    }

    private fun configureCodecs() {
        val video = checkNotNull(videoCodec)
        val audio = checkNotNull(audioCodec)
        val videoFormat = MediaFormat.createVideoFormat(tier.videoMime, widthPx, heightPx).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (byteBufferInput) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                }
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, options.keyframeIntervalSeconds)
            if (tier.profile != 0) {
                // Android 要求 profile 与 level 成对设置；只写 profile 允许 configure 成功后
                // 静默切成别的 profile。
                check(tier.level != 0) { "Missing level for ${tier.label}" }
                setInteger(MediaFormat.KEY_PROFILE, tier.profile)
                setInteger(MediaFormat.KEY_LEVEL, tier.level)
            }
            setInteger(MediaFormat.KEY_COLOR_STANDARD, tier.transfer.mediaFormatStandard)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, tier.transfer.mediaFormatTransfer)
            if (tier.hdrFormat?.writesStaticMetadata == true) {
                // PQ 系（HDR10 / HDR10+）要静态母版元数据，播放端才知道按多高的峰值还原。
                // 杜比视界不写：它的基层是 HLG，元数据层由编码器自己生成。
                setByteBuffer(
                    MediaFormat.KEY_HDR_STATIC_INFO,
                    FableSolExportTransfer.hdr10StaticInfo(peakNits, diffuseWhiteNits)
                )
            }
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            // 码率必须夹到本编码器实际支持的区间：超界的值会让 configure() 直接抛。
            val bitrate = tier.clampBitrate(options.bitrateBps(frameRate))
            val quality = options.resolvedQuality(tier)
            when {
                quality != null && Build.VERSION.SDK_INT >= 28 -> {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    )
                    setInteger(MediaFormat.KEY_QUALITY, quality)
                    // CQ 档位下仍给一个码率提示，部分厂商实现拿它当上界。
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }
                // 面板上写的是「恒定码率」，那就真给 CBR；设备不支持才退到 VBR。
                tier.supportsCbr -> {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }
                else -> {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }
            }
        }
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (!byteBufferInput) {
            // 立刻登记所有权：随后音频 configure 失败时 release() 才能释放这张 surface。
            codecInputSurface = video.createInputSurface()
        }

        val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, audioSampleRate, 1).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_BYTES)
        }
        audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        val video = checkNotNull(videoCodec)
        val audio = checkNotNull(audioCodec)
        video.start()
        videoStarted = true
        if (byteBufferInput) {
            // 行距与平面高度是编码器说了算的，start() 之后才在 inputFormat 里给出；
            // 按宽高硬算会在需要对齐的实现上错位成花屏。
            val input = video.inputFormat
            // **0 要当成"没告诉我"，不能当成真值。** Android 明确允许厂商回报 0，而 0 会让
            // 色度平面的起始偏移和入队长度一起变成 0——画面直接废掉，还不会报错。
            inputStride = input.intOrNull(MediaFormat.KEY_STRIDE)
                ?.takeIf { it > 0 } ?: (widthPx * 2)
            inputSliceHeight = input.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)
                ?.takeIf { it > 0 } ?: heightPx
        }
        audio.start()
        audioStarted = true
    }

    /**
     * 字节缓冲模式下提交一帧，并（如果给了）把这一帧的 HDR10+ 动态元数据一并附上。
     *
     * `setParameters` 必须**先于** `queueInputBuffer`：文档的措辞是"设置到下一个入队的输入
     * 缓冲上"，顺序反了元数据就落到再下一帧去了。
     */
    fun queueVideoFrame(
        presentationTimeUs: Long,
        hdr10PlusInfo: ByteBuffer?,
        fill: (ByteBuffer, Int, Int) -> Int
    ) {
        val video = checkNotNull(videoCodec)
        check(byteBufferInput) { "queueVideoFrame requires byte-buffer input" }
        var index = video.dequeueInputBuffer(TIMEOUT_US)
        while (index < 0) {
            drain(endOfStream = false)
            index = video.dequeueInputBuffer(TIMEOUT_US)
        }
        hdr10PlusInfo?.let { info ->
            val bytes = ByteArray(info.remaining())
            info.duplicate().get(bytes)
            video.setParameters(
                android.os.Bundle().apply {
                    putByteArray(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO, bytes)
                }
            )
        }
        val buffer = checkNotNull(video.getInputBuffer(index))
        val written = fill(buffer, inputStride, inputSliceHeight)
        video.queueInputBuffer(index, 0, written, presentationTimeUs, 0)
        lastVideoPresentationTimeUs = presentationTimeUs
    }

    /** 字节缓冲模式下补一个流结束标记；surface 模式由 signalEndOfInputStream 负责。 */
    private fun queueVideoEndOfStream(presentationTimeUs: Long) {
        val video = videoCodec ?: return
        var index = video.dequeueInputBuffer(TIMEOUT_US)
        var attempts = 0
        while (index < 0 && attempts < END_OF_STREAM_ATTEMPTS) {
            drain(endOfStream = false)
            index = video.dequeueInputBuffer(TIMEOUT_US)
            attempts++
        }
        if (index < 0) return
        video.queueInputBuffer(
            index, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
    }

    /** 顺序喂入单声道 16-bit PCM；PTS 由累计样本数推出，与视频共用同一条时间轴。 */
    fun feedAudio(pcm: ShortArray, count: Int) {
        val audio = checkNotNull(audioCodec)
        var offset = 0
        while (offset < count) {
            val index = audio.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer: ByteBuffer = checkNotNull(audio.getInputBuffer(index))
            buffer.clear()
            val capacitySamples = buffer.capacity() / 2
            val chunk = minOf(capacitySamples, count - offset)
            for (i in 0 until chunk) {
                val value = pcm[offset + i].toInt()
                buffer.put((value and 0xff).toByte())
                buffer.put(((value shr 8) and 0xff).toByte())
            }
            val presentationTimeUs = audioSamplesFed * 1_000_000L / audioSampleRate
            audio.queueInputBuffer(index, 0, chunk * 2, presentationTimeUs, 0)
            audioSamplesFed += chunk
            offset += chunk
            drain(endOfStream = false)
        }
    }

    private fun signalAudioEnd(
        isCancelled: () -> Boolean,
        timeoutMs: Long
    ): Boolean {
        if (audioInputDone) return true
        val audio = checkNotNull(audioCodec)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isCancelled()) return false
            val index = audio.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val presentationTimeUs = audioSamplesFed * 1_000_000L / audioSampleRate
            audio.queueInputBuffer(
                index, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            audioInputDone = true
            return true
        }
        error("Audio encoder never accepted the end-of-stream buffer")
    }

    /**
     * 收尾：**必须一直排空到两条轨都真的报出 EOS**。
     *
     * `dequeueOutputBuffer` 返回 `INFO_TRY_AGAIN_LATER` 只代表这一次 10ms 等待超时，不代表
     * 输出结束——之前那版一遇到"本轮没产出"就 break，慢一点的硬编会因此丢掉尾帧、丢掉音频
     * 尾部，甚至留下一个没写完 moov 的 MP4。这里改成只受总时限约束。
     */
    fun finish(
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = FINISH_TIMEOUT_MS
    ): Boolean {
        val video = checkNotNull(videoCodec)
        if (!signalAudioEnd(isCancelled, timeoutMs)) return false
        if (byteBufferInput) {
            // 字节缓冲模式没有 input surface，signalEndOfInputStream 会直接抛。
            queueVideoEndOfStream(lastVideoPresentationTimeUs + 1L)
        } else {
            video.signalEndOfInputStream()
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!videoOutputDone || !audioOutputDone) {
            if (isCancelled()) return false
            drain(endOfStream = true)
            if (SystemClock.elapsedRealtime() > deadline) {
                error("Encoder did not reach end of stream within ${timeoutMs}ms")
            }
        }
        check(muxing) { "Muxer never started: one of the tracks produced no output" }
        finalizeMuxer()
        return true
    }

    fun drain(endOfStream: Boolean) {
        val video = checkNotNull(videoCodec)
        val audio = checkNotNull(audioCodec)
        if (!videoOutputDone) {
            while (true) {
                val index = video.dequeueOutputBuffer(
                    videoInfo, if (endOfStream) TIMEOUT_US else 0L
                )
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(videoTrack < 0) { "Video format changed twice" }
                    val outputFormat = video.outputFormat
                    validateVideoOutputFormat(outputFormat)
                    videoTrack = muxer.addTrack(outputFormat)
                    hasVideoOutputFormat = true
                    maybeStartMuxer()
                    continue
                }
                if (index < 0) continue
                writeSample(videoTrack, video.getOutputBuffer(index), videoInfo)
                video.releaseOutputBuffer(index, false)
                if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    videoOutputDone = true
                    break
                }
            }
        }
        if (!audioOutputDone) {
            while (true) {
                val index = audio.dequeueOutputBuffer(
                    audioInfo, if (endOfStream) TIMEOUT_US else 0L
                )
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(audioTrack < 0) { "Audio format changed twice" }
                    audioTrack = muxer.addTrack(audio.outputFormat)
                    maybeStartMuxer()
                    continue
                }
                if (index < 0) continue
                writeSample(audioTrack, audio.getOutputBuffer(index), audioInfo)
                audio.releaseOutputBuffer(index, false)
                if (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    audioOutputDone = true
                    break
                }
            }
        }
    }

    private fun writeSample(track: Int, buffer: ByteBuffer?, info: MediaCodec.BufferInfo) {
        if (buffer == null) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (info.size <= 0) return
        if (byteBufferInput && track == videoTrack && !hdr10PlusSeiSeen) {
            // HDR10+ 唯一作数的证据就是码流里那段 SEI——输出格式回报的 profile 本来就是
            // Main10，拿它判断只会误杀。这里在样本写出去之前顺手确认一次。
            hdr10PlusSeiSeen = FableSolExportHdr10PlusMetadata.containsSei(
                buffer, info.offset, info.size
            )
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        if (muxing) {
            muxer.writeSampleData(track, buffer, info)
            return
        }
        // 两条轨都就绪前先攒着；样本数量很少（各自第一批）。
        val copy = ByteBuffer.allocate(info.size)
        copy.put(buffer)
        copy.flip()
        pending.add(PendingSample(track, copy, info.presentationTimeUs, info.flags))
    }

    private fun maybeStartMuxer() {
        if (muxing || videoTrack < 0 || audioTrack < 0) return
        muxer.start()
        muxing = true
        val info = MediaCodec.BufferInfo()
        for (sample in pending) {
            info.set(0, sample.data.remaining(), sample.presentationTimeUs, sample.flags)
            muxer.writeSampleData(sample.track, sample.data, info)
        }
        pending.clear()
    }

    /**
     * `configure()` 成功不代表编码器接受了请求：Android 允许 profile 静默切换。HDR 若被
     * 换成 8-bit 或丢掉 BT.2020/HLG 标记，继续发布会得到一份信号解释完全错误的视频。
     */
    private fun validateVideoOutputFormat(format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME)
        check(mime?.equals(tier.videoMime, ignoreCase = true) == true) {
            "Encoder changed MIME from ${tier.videoMime} to $mime"
        }
        check(format.intOrNull(MediaFormat.KEY_WIDTH) == widthPx &&
            format.intOrNull(MediaFormat.KEY_HEIGHT) == heightPx
        ) {
            "Encoder changed size from ${widthPx}x$heightPx to " +
                "${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                format.intOrNull(MediaFormat.KEY_HEIGHT)
        }
        validateFullFrameCrop(format)
        if (tier.profile != 0 && !tier.eightBit) {
            val actualProfile = format.intOrNull(MediaFormat.KEY_PROFILE)
            check(actualProfile != null && tier.acceptsTenBitProfile(actualProfile)) {
                "Encoder changed profile ${tier.profile} to $actualProfile"
            }
        }
        val expectedStandard = tier.transfer.mediaFormatStandard
        val expectedTransfer = tier.transfer.mediaFormatTransfer
        preserveOrInstallColorKey(
            format, MediaFormat.KEY_COLOR_STANDARD, expectedStandard
        )
        preserveOrInstallColorKey(
            format, MediaFormat.KEY_COLOR_TRANSFER, expectedTransfer
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_RANGE,
            if (byteBufferInput) {
                // 字节缓冲模式下 RGB→YUV 是**我们**做的，样本就是有限范围。此时编码器只是
                // 透传，它回报什么都不改变已经写进去的像素——采纳它的值只会让容器标记与
                // 样本冲突（发灰、黑位抬高）。谁做的转换谁是权威：这一模式下是我们。
                MediaFormat.COLOR_RANGE_LIMITED
            } else {
                FableSolExportColorRange.resolveForMuxer(
                    format.intOrNull(MediaFormat.KEY_COLOR_RANGE)
                )
            }
        )
    }

    /**
     * 部分厂商编码器会在输出格式里附带单边 crop rectangle。Android 将 right/bottom 定义为
     * 最后一个有效像素的包含式坐标；若它们没有覆盖完整输入画面，MediaMuxer 会把这个裁切
     * 交给播放器或分享平台，最终表现为只少右侧/底部背景。输入 Surface 已按编码尺寸完整
     * 重绘，因此这里只接受“没有 crop keys”或“明确覆盖整个画面”两种结果。
     */
    private fun validateFullFrameCrop(format: MediaFormat) {
        val hasHorizontalCrop =
            format.containsKey(CROP_LEFT_KEY) || format.containsKey(CROP_RIGHT_KEY)
        if (hasHorizontalCrop) {
            val left = format.intOrNull(CROP_LEFT_KEY)
            val right = format.intOrNull(CROP_RIGHT_KEY)
            check(left == 0 && right == widthPx - 1) {
                "Encoder cropped horizontal frame to [$left, $right] of $widthPx"
            }
        }

        val hasVerticalCrop =
            format.containsKey(CROP_TOP_KEY) || format.containsKey(CROP_BOTTOM_KEY)
        if (hasVerticalCrop) {
            val top = format.intOrNull(CROP_TOP_KEY)
            val bottom = format.intOrNull(CROP_BOTTOM_KEY)
            check(top == 0 && bottom == heightPx - 1) {
                "Encoder cropped vertical frame to [$top, $bottom] of $heightPx"
            }
        }
    }

    private fun preserveOrInstallColorKey(
        format: MediaFormat,
        key: String,
        expected: Int
    ) {
        val actual = format.intOrNull(key)
        check(actual == null || actual == expected) {
            "Encoder changed $key from $expected to $actual"
        }
        // 部分编码器不把输入色彩键回显到 outputFormat。信号本身由导出 shader/EGL 确定；
        // 把缺失键补进交给 MediaMuxer 的 track format，确保 MP4 容器携带对应标记。
        format.setInteger(key, expected)
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) {
            try {
                getInteger(key)
            } catch (ignored: Throwable) {
                null
            }
        } else {
            null
        }

    /**
     * `MediaStore.IS_PENDING=0` 或媒体扫描只能发生在这里成功返回之后。`muxer.stop()` 会写完
     * MP4 的索引/moov；它失败就必须把整次导出判为失败，不能在 release() 里吞掉。
     */
    private fun finalizeMuxer() {
        if (muxerReleased) return
        var failure: Throwable? = null
        if (muxing) {
            try {
                muxer.stop()
            } catch (error: Throwable) {
                failure = error
            } finally {
                muxing = false
            }
        }
        try {
            muxer.release()
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure.addSuppressed(error)
            }
        } finally {
            muxerReleased = true
        }
        failure?.let { throw it }
    }

    fun release() {
        if (released) return
        released = true
        try {
            if (videoStarted) videoCodec?.stop()
        } catch (ignored: Throwable) {
        }
        try {
            if (audioStarted) audioCodec?.stop()
        } catch (ignored: Throwable) {
        }
        try {
            // input surface 由 createInputSurface() 交出所有权，必须显式还回去。
            codecInputSurface?.release()
        } catch (ignored: Throwable) {
        }
        codecInputSurface = null
        try {
            videoCodec?.release()
        } catch (ignored: Throwable) {
        }
        videoCodec = null
        try {
            audioCodec?.release()
        } catch (ignored: Throwable) {
        }
        audioCodec = null
        try {
            finalizeMuxer()
        } catch (ignored: Throwable) {
            // 失败路径只做尽力清理；成功路径已在 finish() 中让异常向上传播。
        }
        pending.clear()
    }

    private class PendingSample(
        val track: Int,
        val data: ByteBuffer,
        val presentationTimeUs: Long,
        val flags: Int
    )

    companion object {
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        /** crop keys 在 API 33 才公开成常量，但对应格式键从早期系统起就可能由 codec 返回。 */
        private const val CROP_LEFT_KEY = "crop-left"
        private const val CROP_RIGHT_KEY = "crop-right"
        private const val CROP_TOP_KEY = "crop-top"
        private const val CROP_BOTTOM_KEY = "crop-bottom"
        /** AAC-LC 192 kbps（D11）：单声道下远超透明，相对视频的量级可忽略。 */
        private const val AUDIO_BITRATE_BPS = 192_000
        private const val AUDIO_MAX_INPUT_BYTES = 32 * 1024
        private const val TIMEOUT_US = 10_000L
        /** 收尾总时限：硬编排空通常一秒内完成，给足余量后仍卡住就是真出问题了。 */
        private const val FINISH_TIMEOUT_MS = 30_000L
        /** 字节缓冲模式收尾时，为拿到一个输入缓冲最多让排空循环转几轮。 */
        private const val END_OF_STREAM_ATTEMPTS = 64
    }
}

/**
 * 交给 MediaMuxer 的色彩范围由**编码器**说了算，不是我们。
 *
 * 色域（BT.2020）与传递函数（PQ / HLG）是我们画出来的像素本身的属性——EGL 表面的色彩
 * 空间加导出 shader 已经把它们钉死，编码器改不了其含义，所以那两个键不一致就是这一档
 * 真的用不了。**色彩范围不同**：它描述的是编码器自己做的 RGB→YUV 转换选了哪一档，编码
 * 器才是权威。
 *
 * 之前这里和另外两个键一样按"不一致就抛"处理，代价是三星 S23 Ultra 上高通编码器一律
 * 回报 full range（1）而我们申请 limited（2），于是**每一档 HDR 候选都抛
 * IllegalStateException，整机 HDR 判成不可用**——色彩空间、Main10 编码器一样不缺，纯粹
 * 被这道校验挡死。正确做法是采纳编码器报的值写进轨道格式，让容器标记与码流一致。
 */
internal object FableSolExportColorRange {

    /** 编码器报了就采纳它的；没报才补上我们请求的 limited，保证容器一定带标记。 */
    fun resolveForMuxer(encoderReported: Int?): Int =
        encoderReported ?: MediaFormat.COLOR_RANGE_LIMITED
}

/**
 * 一个**具体可用**的编码档位：绑定到某一个编码器实现，而不只是一个 MIME。
 *
 * 按 MIME 创建拿到的是系统首选实现，未必就是探测时确认支持该 profile / 尺寸 / 帧率的
 * 那一个（Android 文档明确不保证）。因此这里连编码器名字一起记下来。
 */
internal data class FableSolExportTier(
    val codecName: String,
    val videoMime: String,
    val profile: Int,
    val level: Int,
    /** 该次尝试要输出的传递函数；与 codec/profile 是两条独立的轴。 */
    val transfer: FableSolExportTransfer,
    /** 本档属于哪种用户可选的 HDR 格式；null 表示 SDR。 */
    val hdrFormat: FableSolExportHdrFormat?,
    /** 8-bit 档位必须在 shader 里重新启用抖动（D9）。 */
    val eightBit: Boolean,
    val supportsCbr: Boolean,
    val qualityRange: Range<Int>?,
    val bitrateRange: Range<Int>?,
    val encodedWidthPx: Int,
    val encodedHeightPx: Int,
    val label: String
) {

    val hdr: Boolean get() = transfer.isHdr

    /** 将内部稳定档位名转换为当前 locale 的用户可见名称。 */
    fun displayLabel(context: Context): String =
        FableSolExportHdrFormat.localizeStableLabels(context, label)

    fun clampBitrate(value: Int): Int {
        val range = bitrateRange ?: return value
        return value.coerceIn(range.lower, range.upper)
    }

    @SuppressLint("InlinedApi")
    fun acceptsTenBitProfile(actualProfile: Int): Boolean {
        if (actualProfile == profile) return true
        // HDR10+ / 杜比视界不接受"等价替换"：HDR10+ 若被静默降成 Main10，产物就只是
        // HDR10 换了个名字挂在界面上，等于骗用户。这两档必须原样回报。
        if (hdrFormat?.requiresExactProfile == true) return false
        // **Main10 必须在这张表里**，否则申请 HDR10+ 时会被自己挡死：HDR10+ 申请的是
        // Main10HDR10Plus（8192），而码流的真实 profile 本来就是 Main10（2）——HEVC 层面
        // 没有"HDR10+ profile"这种东西，8192 是 Android 框架层的合成常量。少了这一项，
        // 一个真正带上了 HDR10+ SEI 的产物照样会被判成"编码器降了档"。
        // 反方向不会因此放松：HDR10 / HLG 申请的就是 Main10，命中的是上面那行相等判断。
        return when (videoMime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC ->
                actualProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    actualProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    actualProfile ==
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_AV1 ->
                actualProfile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                    actualProfile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                    actualProfile ==
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            else -> false
        }
    }

    companion object {

        /**
         * 按 D9 的阶梯列出**所有**可用档位，顺序即优先级。调用方逐个尝试：创建编码器或
         * 建 EGL 链路失败就换下一个，全部失败才算导出失败。
         */
        /** @param format null 表示 SDR——SDR 不是一种 HDR 格式。 */
        fun candidatesForMode(
            format: FableSolExportHdrFormat?,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            preferConstantQuality: Boolean = true
        ): List<FableSolExportTier> {
            val result = ArrayList<FableSolExportTier>(4)
            val ladder = format?.codecEntries ?: SDR_LADDER
            for (entry in ladder) {
                collect(
                    entry, format, widthPx, heightPx, frameRate, result, preferConstantQuality
                )
            }
            return result
        }

        private fun collect(
            entry: FableSolExportCodecEntry,
            format: FableSolExportHdrFormat?,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            target: MutableList<FableSolExportTier>,
            preferConstantQuality: Boolean
        ) {
            // MediaMuxer 的 MP4 容器到 API 34 才正式支持封装 AV1；更早的系统上即便厂商
            // 提前给了 AV1 编码器，也会在 addTrack 时失败，而那已经在降级循环之外了。
            if (entry.mime == MediaFormat.MIMETYPE_VIDEO_AV1 && Build.VERSION.SDK_INT < 34) return
            val transfer = format?.transfer ?: FableSolExportTransfer.SDR
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val batch = ArrayList<FableSolExportTier>(2)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(entry.mime, ignoreCase = true) }) continue
                val capabilities = try {
                    info.getCapabilitiesForType(entry.mime)
                } catch (ignored: Throwable) {
                    continue
                }
                val advertised = capabilities.profileLevels
                    .filter { it.profile == entry.profile }
                if (entry.profile != 0 && advertised.isEmpty()) continue
                // 这里曾经用 FEATURE_HlgEditing 给 HLG 档再加一道筛。它必须去掉：
                // 三星 S23 Ultra 的高通编码器一个都不广告这个能力位，于是 API 35 上 HLG
                // 的候选被整批筛光，诊断里只剩一句"没有编码器广告支持这个 profile"——而
                // 这台机器的 HEVC Main10 编码器一整排都在，EGL 的 HLG 色彩空间也有。
                //
                // 根本原因是这个能力位问的不是我们要的事：它描述的是「HLG 编辑」这一套
                // 转码用例，而我们只是把一张已经编码好的 HLG 画面交给 Main10 编码器。
                // 既然每一档最后都要真编一帧才算数，就没有必要再拿一个语义不对的广告位
                // 提前否决——真编不出来自然会被淘汰，编得出来就不该拦。
                val video = capabilities.videoCapabilities ?: continue
                // 宽高同时满足具体编码器能力与 64px 分享兼容边界。只把中性画框向外补齐，
                // 卡片和 density 不变；微信等二次转码链路因而无需从右侧/底部截去编码块余数。
                val encodedWidth = alignForEncoder(widthPx, video.widthAlignment)
                val encodedHeight = alignForEncoder(heightPx, video.heightAlignment)
                val supported = try {
                    video.areSizeAndRateSupported(
                        encodedWidth, encodedHeight, frameRate.toDouble()
                    )
                } catch (ignored: Throwable) {
                    false
                }
                if (!supported) continue
                // level 必须在**对齐之后**才算：64px 分享兼容对齐会把画布撑大，像素率跟着
                // 变，算早了可能刚好落在阶梯的错误一档上。
                val profileLevel = if (entry.profile != 0) {
                    if (format?.needsDolbyVisionLevel == true) {
                        // 杜比视界的 level 是一条像素率阶梯，必须按实际画布与帧率现算，
                        // 再取编码器广告里**刚好够用**的那一档；直接给最高档会超出它的能力。
                        val required = FableSolExportHdrFormat.dolbyVisionLevel(
                            encodedWidth, encodedHeight, frameRate
                        )
                        advertised.filter { it.level >= required }.minByOrNull { it.level }
                            ?: advertised.maxByOrNull { it.level }
                    } else {
                        advertised.maxByOrNull { it.level }
                    }
                } else {
                    null
                }
                val encoder = capabilities.encoderCapabilities
                val supportsCq = encoder?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                ) ?: false
                val supportsCbr = encoder?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) ?: false
                val qualityRange = if (supportsCq && Build.VERSION.SDK_INT >= 28) {
                    try {
                        encoder?.qualityRange?.takeIf { it.upper > it.lower }
                    } catch (ignored: Throwable) {
                        null
                    }
                } else {
                    null
                }
                batch.add(
                    FableSolExportTier(
                        codecName = info.name,
                        videoMime = entry.mime,
                        profile = entry.profile,
                        level = profileLevel?.level ?: 0,
                        transfer = transfer,
                        hdrFormat = format,
                        eightBit = entry.eightBit,
                        supportsCbr = supportsCbr,
                        qualityRange = qualityRange,
                        bitrateRange = try {
                            video.bitrateRange
                        } catch (ignored: Throwable) {
                            null
                        },
                        encodedWidthPx = encodedWidth,
                        encodedHeightPx = encodedHeight,
                        // 档位名带上格式：HDR10 与 HLG 用的是同一个 HEVC Main10 编码器，
                        // 不写格式的话完成提示里两者一模一样，看不出自动档最后落到了哪种。
                        label = format?.let {
                            "${it.stableLabel} ${entry.label} (${info.name})"
                        }
                            ?: "${entry.label} (${info.name})"
                    )
                )
            }
            // 同一档内，先排能满足用户所选编码模式的编码器，避免首个编码器不支持就静默换模式。
            batch.sortByDescending {
                if (preferConstantQuality) it.qualityRange != null else it.supportsCbr
            }
            target.addAll(batch)
        }

        internal fun alignForEncoder(value: Int, alignment: Int): Int {
            val codecAlignment = alignment.coerceAtLeast(1)
            val combinedAlignment = leastCommonMultiple(
                codecAlignment, SHARE_COMPATIBILITY_ALIGNMENT_PX
            )
            val remainder = value % combinedAlignment
            return if (remainder == 0) {
                value
            } else {
                value + combinedAlignment - remainder
            }
        }

        private fun leastCommonMultiple(first: Int, second: Int): Int {
            var a = first
            var b = second
            while (b != 0) {
                val remainder = a % b
                a = b
                b = remainder
            }
            return first / a * second
        }

        /** 微信等二次转码链路按 64px 编码块处理时，不把余数从右侧或底部截掉。 */
        private const val SHARE_COMPATIBILITY_ALIGNMENT_PX = 64

        // HDR 各格式的阶梯归 FableSolExportHdrFormat 自己持有：档位与格式一一对应，
        // 分开放两处只会让"HDR10+ 该用哪个 profile"这类问题散落在两个文件里。
        private val SDR_LADDER = listOf(
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                eightBit = false,
                label = "HEVC Main10 SDR"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                eightBit = true,
                label = "HEVC Main SDR"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                eightBit = true,
                label = "H.264 High SDR"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                eightBit = true,
                label = "H.264 Main SDR"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                eightBit = true,
                label = "H.264 Baseline SDR"
            )
        )
    }
}
