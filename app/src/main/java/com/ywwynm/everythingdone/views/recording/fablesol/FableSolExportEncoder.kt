package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
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
    private val muxer: MediaMuxer
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
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
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
            if (tier.hdr) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            } else {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
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
        // 立刻登记所有权：随后音频 configure 失败时 release() 才能释放这张 surface。
        codecInputSurface = video.createInputSurface()

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
        audio.start()
        audioStarted = true
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
        video.signalEndOfInputStream()
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
        val expectedStandard = if (tier.hdr) {
            MediaFormat.COLOR_STANDARD_BT2020
        } else {
            MediaFormat.COLOR_STANDARD_BT709
        }
        val expectedTransfer = if (tier.hdr) {
            MediaFormat.COLOR_TRANSFER_HLG
        } else {
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        }
        preserveOrInstallColorKey(
            format, MediaFormat.KEY_COLOR_STANDARD, expectedStandard
        )
        preserveOrInstallColorKey(
            format, MediaFormat.KEY_COLOR_TRANSFER, expectedTransfer
        )
        preserveOrInstallColorKey(
            format, MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED
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
    }
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
    val hdr: Boolean,
    /** 8-bit 档位必须在 shader 里重新启用抖动（D9）。 */
    val eightBit: Boolean,
    val supportsCbr: Boolean,
    val qualityRange: Range<Int>?,
    val bitrateRange: Range<Int>?,
    val encodedWidthPx: Int,
    val encodedHeightPx: Int,
    val label: String
) {

    fun clampBitrate(value: Int): Int {
        val range = bitrateRange ?: return value
        return value.coerceIn(range.lower, range.upper)
    }

    @SuppressLint("InlinedApi")
    fun acceptsTenBitProfile(actualProfile: Int): Boolean {
        if (actualProfile == profile) return true
        return when (videoMime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC ->
                actualProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    actualProfile ==
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_AV1 ->
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
        fun candidates(
            wantHdr: Boolean,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            /** 用户选的是恒定质量还是恒定码率；同一档里优先排能满足该模式的编码器。 */
            preferConstantQuality: Boolean = true
        ): List<FableSolExportTier> {
            val result = ArrayList<FableSolExportTier>(4)
            if (wantHdr) {
                for (entry in HDR_LADDER) {
                    collect(entry, widthPx, heightPx, frameRate, result, preferConstantQuality)
                }
            }
            for (entry in SDR_LADDER) {
                collect(entry, widthPx, heightPx, frameRate, result, preferConstantQuality)
            }
            return result
        }

        /**
         * 只列出一种信号类型的候选。正式导出用它把“HDR 的 120→60”放在任何 SDR 尝试之前；
         * 设置页的一帧能力探测也复用同一候选集合。
         */
        fun candidatesForMode(
            hdr: Boolean,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            preferConstantQuality: Boolean = true
        ): List<FableSolExportTier> {
            val result = ArrayList<FableSolExportTier>(4)
            val ladder = if (hdr) HDR_LADDER else SDR_LADDER
            for (entry in ladder) {
                collect(entry, widthPx, heightPx, frameRate, result, preferConstantQuality)
            }
            return result
        }

        private class LadderEntry(
            val mime: String,
            val profile: Int,
            val hdr: Boolean,
            val eightBit: Boolean,
            val label: String
        )

        private fun collect(
            entry: LadderEntry,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            target: MutableList<FableSolExportTier>,
            preferConstantQuality: Boolean
        ) {
            // MediaMuxer 的 MP4 容器到 API 34 才正式支持封装 AV1；更早的系统上即便厂商
            // 提前给了 AV1 编码器，也会在 addTrack 时失败，而那已经在降级循环之外了。
            if (entry.mime == MediaFormat.MIMETYPE_VIDEO_AV1 && Build.VERSION.SDK_INT < 34) return
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
                val profileLevel = if (entry.profile != 0) {
                    capabilities.profileLevels
                        .filter { it.profile == entry.profile }
                        .maxByOrNull { it.level }
                        ?: continue
                } else {
                    null
                }
                // HDR 档另加一道：Main10 只说明能编 10-bit，不代表接受 HLG 输入。
                // 但 FEATURE_HlgEditing 是 **API 35** 才加入的能力位——在 API 34 上查它
                // 会一律返回 false，把所有 HDR 候选静默筛光、悄悄降成 SDR。所以只在
                // API 35+ 才作为过滤条件，更早的系统交给 configure/EGL 的降级重试兜底。
                if (entry.hdr && Build.VERSION.SDK_INT >= 35) {
                    val hlgOk = try {
                        capabilities.isFeatureSupported(
                            MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing
                        )
                    } catch (ignored: Throwable) {
                        false
                    }
                    if (!hlgOk) continue
                }
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
                        hdr = entry.hdr,
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
                        label = "${entry.label} (${info.name})"
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

        @SuppressLint("InlinedApi")
        private val HDR_LADDER = listOf(
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                hdr = true,
                eightBit = false,
                label = "HEVC Main10 HLG"
            ),
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                hdr = true,
                eightBit = false,
                label = "AV1 Main10 HLG"
            )
        )

        private val SDR_LADDER = listOf(
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                hdr = false,
                eightBit = false,
                label = "HEVC Main10 SDR"
            ),
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                hdr = false,
                eightBit = true,
                label = "HEVC Main SDR"
            ),
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                hdr = false,
                eightBit = true,
                label = "H.264 High SDR"
            ),
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                hdr = false,
                eightBit = true,
                label = "H.264 Main SDR"
            ),
            LadderEntry(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                hdr = false,
                eightBit = true,
                label = "H.264 Baseline SDR"
            )
        )
    }
}
