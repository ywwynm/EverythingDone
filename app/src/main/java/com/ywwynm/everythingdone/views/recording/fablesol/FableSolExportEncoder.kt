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
     * 漫反射白（尼特）。它是 PQ 绝对亮度的缩放锚点，把归一化统计换算成尼特要用。
     *
     * 这个数用户可调（200～800），所以静态元数据里的每一个亮度字段都必须跟着它走。
     */
    private val diffuseWhiteNits: Double = FableSolExportTransfer.SDR_WHITE_NITS,
    /**
     * 全片静态亮度统计（D85～D90）。
     *
     * 默认是 D90 的理论回退——`MaxCLL` 取理论峰值、`MaxFALL` 写未知。**不再把漫反射白当成
     * MaxFALL**：漫反射白是缩放锚点，不保证是每帧平均 `maxRGB` 的上界，拿它顶替可能低报。
     */
    private val luminance: FableSolExportLuminanceStats =
        FableSolExportLuminanceStats.theoretical(1.0),
    /**
     * 本次实际下发的码控形态（D145、D167）。
     *
     * 与"用户选了什么"是两回事：CQ 有纯 CQ 与 CQ+码率提示两种**同模式**形态，目标码率在
     * 编码器不支持 VBR 时会落到 CBR。默认按当前档位能力解析，短探测在走 D167 阶梯时显式
     * 指定——探测通过的是哪一种形态，正式导出就必须用哪一种。
     */
    private val form: FableSolExportRateControlForm =
        FableSolExportRateControlForm.resolve(options, tier),
    /**
     * 是否下发 `KEY_COMPLEXITY = upper`（D149）。短探测的复杂度阶梯确认该编码器不接受
     * 最高值时为假：省略该键、用厂商默认继续原格式，不判失败。
     */
    private val applyHighComplexity: Boolean = true,
    /**
     * 是否下发 `KEY_MAX_B_FRAMES = 1`（D148）。编码器初始化在申请 B 帧时失败过时为假：
     * 以 0 个 B 帧完成原格式导出，不切换格式也不判失败。
     */
    private val applyBFrames: Boolean = true
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
     * HDR10+ 必须如此：它的动态元数据只能逐帧通过 `PARAMETER_KEY_HDR10_PLUS_INFO` 提供，
     * 而该参数在 surface 输入模式下被系统明确禁止。D158 之后所有 10-bit 档位都优先走这条路，
     * 由 [FableSolExportP010Bridge] 交出 P010——色度位置、降采样相位、闭环修正与码值量化
     * 握在应用手里，同一份画面在不同设备上才会得到同一种转换。
     */
    private val byteBufferInput: Boolean = tier.usesAppP010

    /** 字节缓冲模式下编码器输入缓冲的实际排布；start() 之后才知道。 */
    private var inputLayout = FableSolExportP010Layout.of(widthPx, heightPx)
    private var inputLayoutRefined = false
    private var lastVideoPresentationTimeUs = 0L

    /**
     * 从实际码流读到的 4:2:0 色度位置（D154、D170）。
     *
     * 正式导出用不上它——相位必须在第一帧渲染之前定下来，而输出格式要到编码开始之后才到。
     * 它的用途是**能力探测**：短探测把这一结论存进可行组合表，下一次正式导出据此选相位。
     */
    var videoChromaSiting: FableSolExportChromaSiting.Result? = null
        private set

    /**
     * 字节缓冲模式下，携带 ST 2094-40 SEI 的视频样本数（D91 第 4 条，2026-07-30 修订）。
     *
     * 逐样本计数而不是命中即停：输入侧每帧都注入，覆盖不完整意味着编码器丢了部分帧的
     * SEI——发布门禁只要求"确实携带"（>0），覆盖率进诊断，部分覆盖另在完成信息如实说明，
     * 不改报失败。
     */
    var hdr10PlusSeiSamples = 0L
        private set

    /** 字节缓冲模式下，编出来的码流里是否真的带上了 HDR10+ 的动态元数据。 */
    val hdr10PlusSeiSeen: Boolean get() = hdr10PlusSeiSamples > 0L

    /**
     * 真正写进容器的视频样本数（不含 codec-config）。
     *
     * **"拿到了输出格式并走到了 EOS"不等于"编出了东西"。** `INFO_OUTPUT_FORMAT_CHANGED` 一来
     * 就能 `addTrack` 并启动 muxer，随后即便一个实际样本都没有，`finish()` 依然会成功返回，
     * 产物则是一个 0 字节的文件。华为平板上 `OMX.hisi.video.encoder.hevc` 正是如此：10 位
     * 输入表面拿不到带 `EGL_RECORDABLE_ANDROID` 的 config，编码器不报错也不产出，SDR 与 HDR
     * 的每一档 HEVC 都导出成 0 字节，只有 8 位的 H.264 有数据（2026-07-28）。
     */
    var videoSamplesWritten = 0L
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

    /**
     * CQ 兼容形态要附带的码率提示（D167 第 2 条）。
     *
     * 取的是 D147 对**当前解析候选**的自动推导值，与 VBR 用的是同一个模型：它只是给那些
     * configure 必须带码率键的编码器一个合理的数，不是把这一档改判成 VBR。
     */
    private fun hintBitrateBps(): Int = FableSolExportBitrateModel.autoBitrateBps(
        widthPx = tier.encodedWidthPx,
        heightPx = tier.encodedHeightPx,
        frameRate = frameRate,
        family = tier.family,
        tenBit = !tier.eightBit,
        hdr = tier.transfer.isHdr
    )

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
                //
                // **configure 阶段的注入必须始终保留**（D166）：`MediaMuxer` 把
                // KEY_HDR_STATIC_INFO 落盘成容器级 mdcv/clli box 是较新 Android 才有的行为，
                // 旧系统上实际承载的是编码器按这里的注入生成的码流 SEI。
                setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticInfo())
            }
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            // 离线文件生成，不要求编码器按播放帧率实时完成：声明为非实时、尽力而为，
            // 好让最高复杂度在需要时慢慢编（D150）。**绝不设置 KEY_OPERATING_RATE**——
            // 那是要求硬件在现实时间里达到某个吞吐，与这里的意图正好相反。
            setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_NON_REALTIME)
            // 码率必须夹到本编码器实际支持的区间：超界的值会让 configure() 直接抛。
            val bitrate = tier.bitrateBps?.let { tier.clampBitrate(it) }
            when (form) {
                FableSolExportRateControlForm.CONSTANT_QUALITY,
                FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT -> {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    )
                    val quality = checkNotNull(options.resolvedQuality(tier))
                    setInteger(MediaFormat.KEY_QUALITY, quality)
                    FableSolExportCqQualityGuard.maxQp(tier, quality)?.let { maxQp ->
                        setInteger(MediaFormat.KEY_VIDEO_QP_MAX, maxQp)
                    }
                    // 默认**不**同时下发码率：Android 明确说同时设置质量与码率行为未定义
                    // （D145）。个别 OMX 系编码器 configure 时必须带码率键，那条兼容形态由
                    // 短探测按 D167 的同模式阶梯选出来，两处用的是同一个 form。
                    if (form == FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT) {
                        setInteger(
                            MediaFormat.KEY_BIT_RATE,
                            bitrate ?: tier.clampBitrate(hintBitrateBps())
                        )
                    }
                }
                // 离线文件导出没有固定瞬时带宽的要求：目标码率档一律 VBR，由编码器在复杂
                // 水体、高光和高速运动帧上多分配码字。CBR 只在实际编码器不支持 VBR 时作为
                // 内部后备，并须在完成信息里如实显示（D145）。
                FableSolExportRateControlForm.CONSTANT_BITRATE -> {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, checkNotNull(bitrate))
                }
                FableSolExportRateControlForm.VARIABLE_BITRATE -> {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, checkNotNull(bitrate))
                    // 复杂帧质量保护只作用于 VBR（D151）：CQ 已经由质量值直接表达目标，
                    // CBR 再加质量下限则会破坏它本身的固定码率约束。
                    if (options.complexFrameGuardEnabled && tier.supportsQpBounds) {
                        setInteger(MediaFormat.KEY_VIDEO_QP_MAX, QP_MAX_GUARD)
                    }
                }
            }
            // B 帧是**用户明确接受帧重排**的选项，默认关闭（D148）。API 29 起可以主动请求；
            // `1` 是"任意两个 I/P 之间最多 1 个连续 B 帧"的上限，不是每个 GOP 只有一个。
            // [applyBFrames] 为假表示该请求在本候选上被拒绝过，按 D148 以 0 个 B 帧继续
            // 原格式导出。
            if (Build.VERSION.SDK_INT >= 29 && tier.supportsBFrames) {
                setInteger(
                    MediaFormat.KEY_MAX_B_FRAMES,
                    if (options.bFramesEnabled && applyBFrames) 1 else 0
                )
            } else if (Build.VERSION.SDK_INT < 29) {
                // API 26～28 没有 KEY_MAX_B_FRAMES：按 D148 采用可用的低延迟约束——
                // KEY_LATENCY = 1（延迟不超过 1 帧，蕴含无帧重排），阻止编码器自行产生
                // B 帧后让 MediaMuxer 遇到乱序时间戳。这是标准可选键，编码器不支持时按
                // 平台契约忽略，不会使 configure 失败。B 帧开关在这些版本按 D148 不适用。
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            // 高复杂度默认开启（D149）。关闭时**省略**这个键而不是下发下限：关闭表示"不额外
            // 要求最高复杂度"，保留厂商默认，不是主动要求最低画质。[applyHighComplexity]
            // 为假表示短探测按 D149 的阶梯确认该编码器不接受最高值，同样省略该键。
            if (options.highComplexityEnabled && applyHighComplexity) {
                tier.complexityRange?.let {
                    setInteger(MediaFormat.KEY_COMPLEXITY, it.upper)
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
            // 行距、平面高度与 crop 原点都是编码器说了算的，start() 之后才在 inputFormat 里
            // 给出；按宽高硬算会在需要对齐的实现上错位成花屏。0 与缺失的处理见
            // [FableSolExportP010Layout]。
            val input = video.inputFormat
            inputLayout = FableSolExportP010Layout.of(
                widthPx = widthPx,
                heightPx = heightPx,
                reportedStride = input.intOrNull(MediaFormat.KEY_STRIDE),
                reportedSliceHeight = input.intOrNull(MediaFormat.KEY_SLICE_HEIGHT),
                cropLeft = input.intOrNull(CROP_LEFT_KEY) ?: 0,
                cropTop = input.intOrNull(CROP_TOP_KEY) ?: 0
            )
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
        fill: (ByteBuffer, FableSolExportP010Layout) -> Int
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
        refineLayout(video, index)
        val buffer = checkNotNull(video.getInputBuffer(index))
        val written = fill(buffer, inputLayout)
        video.queueInputBuffer(index, 0, written, presentationTimeUs, 0)
        lastVideoPresentationTimeUs = presentationTimeUs
    }

    /**
     * 用 `getInputImage()` 报出的平面参数校正排布，只做一次。
     *
     * `Image` 的平面行距比 `KEY_STRIDE` 更权威：后者是整帧一个数，而 P010 的色度平面行距
     * 并不保证等于亮度行距。像素步长则是**门禁**：标准 P010 是半平面交错（Y 每样本 2 字节、
     * Cb/Cr 各 4 字节），报出别的排布说明这个编码器给的根本不是我们要写的那种 P010——此时
     * 判本候选失败，交由候选阶梯退到同格式 Surface（D158 第 5 条），而不是照写一帧花屏。
     */
    private fun refineLayout(video: MediaCodec, index: Int) {
        if (inputLayoutRefined) return
        inputLayoutRefined = true
        val image = try {
            video.getInputImage(index)
        } catch (ignored: Throwable) {
            null
        } ?: return
        // **不 close 这个 Image。** 紧接着的 getInputBuffer(index) 按 MediaCodec 的契约本来
        // 就会让它失效；而部分 AOSP 版本的 MediaImage.close() 会去 free 底层直接缓冲，那正是
        // 我们马上要写入的那块内存。
        val planes = image.planes
        if (planes.size < 3) return
        check(planes[0].pixelStride == FableSolExportP010Layout.LUMA_PIXEL_STRIDE) {
            "P010 luma pixel stride is ${planes[0].pixelStride}, expected " +
                FableSolExportP010Layout.LUMA_PIXEL_STRIDE
        }
        check(
            planes[1].pixelStride == FableSolExportP010Layout.CHROMA_PIXEL_STRIDE &&
                planes[2].pixelStride == FableSolExportP010Layout.CHROMA_PIXEL_STRIDE
        ) {
            "P010 chroma is not semi-planar: pixel strides " +
                "${planes[1].pixelStride}/${planes[2].pixelStride}"
        }
        inputLayout = inputLayout.withPlaneRowStrides(
            luma = planes[0].rowStride,
            chroma = planes[1].rowStride
        )
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
                    ensureStaticMetadata(outputFormat)
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
        if (track == videoTrack) videoSamplesWritten++
        if (byteBufferInput && track == videoTrack) {
            // HDR10+ 唯一作数的证据就是码流里那段 SEI——输出格式回报的 profile 本来就是
            // Main10，拿它判断只会误杀。逐样本计数（不命中即停）：覆盖率要进诊断与完成
            // 信息，扫描成本在内存带宽量级，可忽略。
            if (FableSolExportHdr10PlusMetadata.containsSei(buffer, info.offset, info.size)) {
                hdr10PlusSeiSamples++
            }
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
    /** 本次要写入的 25 字节 ST 2086/CTA-861.3 静态描述符；应用是它唯一的权威（D91）。 */
    private fun staticInfo(): ByteBuffer = FableSolExportTransfer.hdr10StaticInfo(
        peakNits = peakNits,
        diffuseWhiteNits = diffuseWhiteNits,
        luminance = luminance
    )

    /**
     * 本次**应当**写进产物的静态描述符；非 PQ 档位为 null。
     *
     * 短探测的回读核对读这一份，而不是自己另算一遍（D166）：两处各算各的，只要有一处的
     * 参数漏传，核对就会稳定地判所有设备失败，而那种错误看起来完全像是设备问题。
     */
    fun expectedStaticInfo(): ByteBuffer? =
        if (tier.hdrFormat?.writesStaticMetadata == true) staticInfo() else null

    /**
     * 编码器输出格式没回报静态元数据时，把应用生成的描述符补进交给 muxer 的轨格式（D91 第 1 条）。
     *
     * 回报了**不同**内容时不静默采纳，也不在这里推翻候选：正式导出的门禁是注入、有效样本与
     * 真实错误（D166），逐字段核对定位在短探测产物上。这里只保证"应用生成的那一份"确实有
     * 机会落进容器。
     */
    private fun ensureStaticMetadata(format: MediaFormat) {
        if (tier.hdrFormat?.writesStaticMetadata != true) return
        val reported = try {
            format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)
        } catch (ignored: Throwable) {
            null
        }
        if (reported != null && reported.remaining() >= STATIC_INFO_BYTES) return
        try {
            format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticInfo())
        } catch (ignored: Throwable) {
            // 少数实现的 outputFormat 不接受写入。configure 阶段的注入仍然有效，码流 SEI
            // 是这条路上的实际承载（D166）。
        }
    }

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
        // 码流实际声明的色度位置（D154、D170）。解析只读 csd-0，失败一律当"未声明"，不影响
        // 本次导出成败——它服务的是下一次导出的相位选择。
        videoChromaSiting = try {
            FableSolExportChromaSiting.parse(tier.videoMime, format.getByteBuffer(CSD_KEY))
        } catch (ignored: Throwable) {
            FableSolExportChromaSiting.Result.UNDECLARED
        }
        if (tier.profile != 0 && !tier.eightBit) {
            // **没回报不等于被改掉。** 不少编码器（尤其是 OMX 系）压根不把 KEY_PROFILE 写进
            // outputFormat，此前这里把 null 一律判成"降了档"，于是一台明明已经产出 10-bit
            // HDR 输出的华为平板被自己的校验否掉，报的是 `changed profile 2 to null`
            // （2026-07-28）。与色彩键的处理保持一致：缺失就是没有信息，只有回报了**别的
            // 值**才算真的换了东西。
            //
            // 放过 null 不会让 HDR10+ 或杜比视界蒙混过关：前者的判据本来就是码流里那段 SEI，
            // 后者换的是 MIME，而传递函数另有一道校验。
            val actualProfile = format.intOrNull(MediaFormat.KEY_PROFILE)
            check(actualProfile == null || tier.acceptsTenBitProfile(actualProfile)) {
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
        /** HEVC 的 VPS/SPS/PPS 与 AV1 的 av1C 都在这个键里；色度位置从它解析。 */
        private const val CSD_KEY = "csd-0"
        /** AAC-LC 192 kbps（D11）：单声道下远超透明，相对视频的量级可忽略。 */
        private const val AUDIO_BITRATE_BPS = 192_000
        private const val AUDIO_MAX_INPUT_BYTES = 32 * 1024

        /** `KEY_PRIORITY` 的非实时、尽力而为档（D150）。 */
        const val PRIORITY_NON_REALTIME = 1

        /**
         * VBR 复杂帧质量保护的 QP 上限（D151）。
         *
         * QP 越高量化越重；40 是 Android 分享编码文档给出的推荐上限，用来挡住复杂水体与
         * 高速高光在码率压力下被压到更差的 QP，代价是实际码率可能高于目标。
         */
        const val QP_MAX_GUARD = 40
        private const val TIMEOUT_US = 10_000L
        /** 收尾总时限：硬编排空通常一秒内完成，给足余量后仍卡住就是真出问题了。 */
        private const val FINISH_TIMEOUT_MS = 30_000L
        /** 字节缓冲模式收尾时，为拿到一个输入缓冲最多让排空循环转几轮。 */
        private const val END_OF_STREAM_ATTEMPTS = 64
        /** CTA-861.3 Static Metadata Descriptor ID 0 的长度。 */
        const val STATIC_INFO_BYTES = 25
    }
}

/**
 * AOSP 软件 AV1 在 HDR 最高 CQ 档位下的量化保护。
 *
 * Android 的公开质量区间只能表达编码器自定义的相对档位。AOSP
 * `c2.android.av1.encoder` 把最高值 100 映射为 libaom CQ 15；在 OPPO PLZ110 的
 * 1152×1472、10-bit HDR 渐变实测中，该档仍会在水体产生可见块状量化。追加 QP 上限 8
 * 后，编码器报告的 I/P/B 最大 QP 为 8/11/14，水体块状量化消失，且输出仍可完整解码。
 * 编码器同时明确广告 QP bounds，因此只对这一项、只在用户采用最高质量值时补充该上限。
 *
 * 其它编码器、SDR、非最高质量值或未广告 QP bounds 的路径没有同一份证据，不应用该限制。
 */
internal object FableSolExportCqQualityGuard {

    const val AOSP_AV1_ENCODER = "c2.android.av1.encoder"
    const val AOSP_AV1_HDR_MAX_QP = 8

    fun maxQp(tier: FableSolExportTier, resolvedQuality: Int): Int? {
        val range = tier.qualityRange ?: return null
        return maxQp(
            codecName = tier.codecName,
            videoMime = tier.videoMime,
            family = tier.family,
            softwareOnly = tier.softwareOnly,
            hdr = tier.transfer.isHdr,
            supportsQpBounds = tier.supportsQpBounds,
            maximumQuality = resolvedQuality == range.upper
        )
    }

    internal fun maxQp(
        codecName: String,
        videoMime: String,
        family: FableSolExportCodecFamily,
        softwareOnly: Boolean,
        hdr: Boolean,
        supportsQpBounds: Boolean,
        maximumQuality: Boolean
    ): Int? {
        if (!maximumQuality) return null
        if (codecName != AOSP_AV1_ENCODER) return null
        if (videoMime != MediaFormat.MIMETYPE_VIDEO_AV1) return null
        if (family != FableSolExportCodecFamily.AV1) return null
        if (!softwareOnly || !hdr || !supportsQpBounds) return null
        return AOSP_AV1_HDR_MAX_QP
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
    /** 本档属于哪个用户可选的编码器族。 */
    val family: FableSolExportCodecFamily,
    /**
     * 这个编码器实现是不是纯软件的。
     *
     * 本项目的导出画布接近两百万像素，软件编码与硬件编码的耗时可相差一到两个数量级。
     * 因此软件实现排在硬件实现之后，并作为公开规格字段明确显示；从硬件切换为软件前必须
     * 获得用户确认（D179）。
     */
    val softwareOnly: Boolean,
    /** 8-bit 档位必须在 shader 里重新启用抖动（D9）。 */
    val eightBit: Boolean,
    /**
     * 交给编码器的输入通路（D158）。
     *
     * 它是同一档位下的**子候选**，不是另一种输出格式：10-bit 优先应用自有 P010，失败后退到
     * 同格式、同 Profile、同编码器族的 Surface。HDR10+ 只有 P010 一种（动态元数据在 surface
     * 输入模式下无法提交），8-bit 只有 Surface 一种。
     */
    val inputPath: FableSolExportInputPath = FableSolExportInputPath.SURFACE,
    val supportsCbr: Boolean,
    /** 离线导出的目标码率模式优先 VBR；不支持时才退到 CBR（D145）。 */
    val supportsVbr: Boolean,
    val qualityRange: Range<Int>?,
    val bitrateRange: Range<Int>?,
    /**
     * 本档**已解析**的目标码率（bps）；CQ 模式为 null。
     *
     * 码率必须在候选生成时定下来，不能等到 configure：D147 的自动值依赖对齐后的实际宽高、
     * 帧率、编码器族与位深，而这些正是在这里才全部齐备的。Level 的码率分量读的也是这一份，
     * 探测与正式导出因此不可能算出两个不同的数。
     */
    val bitrateBps: Int? = null,
    /** 仅 HEVC 有意义：本档是否用到 High Tier（D152）。 */
    val highTier: Boolean = false,
    /** 实际编码器公开的复杂度区间；null 表示未公开，此时省略 `KEY_COMPLEXITY`（D149）。 */
    val complexityRange: Range<Int>? = null,
    /** 实际编码器是否声明 `FEATURE_QpBounds`（D151）。 */
    val supportsQpBounds: Boolean = false,
    val encodedWidthPx: Int,
    val encodedHeightPx: Int,
    val label: String,
    /**
     * 阶梯项本身的名字，例如 “HEVC Main10”“HEVC Main SDR”“H.264 High SDR”。
     *
     * 与 [label] 的区别是不带格式前缀与编码器实现名。能力报告需要它：同一个编码器族里
     * 10-bit 与 8-bit 是两个阶梯项，只写 “HEVC” 就分不出这台机器到底是 10-bit 编不了，
     * 还是仅仅 HDR 信号编不了。
     */
    val profileLabel: String = label
) {

    val hdr: Boolean get() = transfer.isHdr

    /** 本档是否由应用自己生成 P010 字节缓冲。 */
    val usesAppP010: Boolean get() = inputPath == FableSolExportInputPath.APP_P010

    /**
     * 本档能不能请求 B 帧（D148 的格式适用范围）。
     *
     * - H.264 High 与 Main 可以；Baseline 的语法里就没有 B 片。
     * - 所有 HEVC 路径可以，含杜比视界 8.4——它换的是 MIME，基层仍是 HEVC。
     * - **AV1 不套用 `KEY_MAX_B_FRAMES`**：它的复合预测与参考帧结构是另一套模型，
     *   由 AV1 编码器自行决定，下发这个键既无意义也可能被拒。
     */
    val supportsBFrames: Boolean
        get() = when (family) {
            FableSolExportCodecFamily.AV1 -> false
            FableSolExportCodecFamily.AVC ->
                profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh ||
                    profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            FableSolExportCodecFamily.HEVC -> true
        }

    /**
     * 本档是否需要 EGL 提供对应的 BT.2020 色彩空间扩展。
     *
     * 这是**输入通路**的属性，不是格式的属性。走应用自有 P010 时画面进的是我们自己的离屏
     * framebuffer，传递函数由导出 shader 亲自编码，交给编码器的已经是 P010 字节；那张 1×1
     * 的 pbuffer 只用来持有 GL 上下文，压根没打色彩空间属性。按格式一刀切会把"有 P010
     * 编码能力、却没有对应 EGL 窗口扩展"的设备整格式降到 SDR——与 D30 的判据相悖。
     */
    val requiresEglColorSpace: Boolean get() = transfer.isHdr && !usesAppP010

    /** CQ 自定义原值的归属签名（D146）；与 [FableSolExportResolvedCandidate] 同源。 */
    val qualitySignature: String
        get() = fableSolExportQualitySignature(codecName, hdrFormat, !eightBit, inputPath)

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
        /**
         * @param format null 表示 SDR——SDR 不是一种 HDR 格式。
         * @param tenBit 只保留该位深的候选；null 表示不限（位深不是本次求值的轴）。
         * @param family 只保留这个编码器族的候选；null 表示不限。
         * @param allowSoftware false 时排除纯软件编码器。
         *
         * 返回顺序即 D53 修订 + D161 定义的公开规格建议顺序：**先全部硬件实现、再全部软件
         * 实现**，每种实现类型内部按 `HEVC → AV1 → AVC`。同一公开规格内有多个具体组件时，
         * 保持阶梯里的 Profile 优先级，最后才按"是否满足用户所选码控模式"打破平局。
         *
         * 位深属于输出规格，由调用方作为独立的轴给出（D160）：自动位深先穷尽同规格 10-bit
         * 候选，再进入 8-bit；严格位深只生成对应位深的候选，不跨位深后备。
         */
        fun candidatesForMode(
            format: FableSolExportHdrFormat?,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            tenBit: Boolean? = null,
            preferConstantQuality: Boolean = true,
            family: FableSolExportCodecFamily? = null,
            allowSoftware: Boolean = true,
            /** 用户拖过滑杆之后的绝对目标码率；null 表示自动推导（D147）。 */
            customBitrateMbps: Float? = null,
            /** B 帧影响 Level 的参考结构约束（D152）。 */
            bFrames: Boolean = false,
            /**
             * 复杂帧质量保护开关（D151）。开着时 VBR 的实际码率可以超过目标，Level 的
             * 码率定档按 D152 乘保守余量；默认 false 即旧行为，真实调用方必须显式传
             * `options.complexFrameGuardEnabled`。
             */
            complexFrameGuard: Boolean = false
        ): List<FableSolExportTier> {
            val ladder = ladderFor(format)
            val rateControl = if (preferConstantQuality) {
                FableSolExportRateControl.CONSTANT_QUALITY
            } else {
                FableSolExportRateControl.TARGET_BITRATE
            }
            val collected = ArrayList<Pair<Int, FableSolExportTier>>(4)
            for ((index, entry) in ladder.withIndex()) {
                if (family != null && entry.family != family) continue
                if (tenBit != null && entry.eightBit == tenBit) continue
                val batch = ArrayList<FableSolExportTier>(2)
                collect(
                    entry, format, widthPx, heightPx, frameRate, batch, allowSoftware,
                    rateControl, customBitrateMbps, bFrames, complexFrameGuard
                )
                for (tier in batch) collected += index to tier
            }
            return collected.sortedWith(
                compareBy(
                    // 1. 硬件实现优先于软件实现（同规格内，D53 修订）。
                    { (_, tier) -> tier.softwareOnly },
                    // 2. 编码器族 HEVC → AV1 → AVC（枚举声明顺序，D161）。
                    { (_, tier) -> tier.family.ordinal },
                    // 3. 同族内保持阶梯里的 Profile 优先级（例如 H.264 High → Main → Baseline）。
                    { (index, _) -> index },
                    // 4. 同族同 Profile 有多个实现时，优先能满足用户所选码控模式的那个，
                    //    避免首个编码器不支持就静默换模式。
                    { (_, tier) ->
                        val matches = if (preferConstantQuality) {
                            tier.qualityRange != null
                        } else {
                            tier.supportsVbr || tier.supportsCbr
                        }
                        if (matches) 0 else 1
                    },
                    // 5. 输入通路是同一档位内的**子候选**（D158 第 5 条）：应用自有 P010 优先，
                    //    失败后退到同格式、同 Profile、同编码器族的 Surface。它排在最后，因此
                    //    永远不会把某个编码器的 Surface 提到另一个编码器的 P010 前面。
                    { (_, tier) -> if (tier.usesAppP010) 0 else 1 }
                )
            ).map { it.second }
        }

        /** 该输出格式的编码阶梯；顺序即优先级。SDR 不属于任何 HDR 格式，单独一张表。 */
        fun ladderFor(format: FableSolExportHdrFormat?): List<FableSolExportCodecEntry> =
            format?.codecEntries ?: SDR_LADDER

        /** 该输出格式**结构上**可能用到的编码器族，去重后保持阶梯顺序。 */
        fun familiesFor(format: FableSolExportHdrFormat?): List<FableSolExportCodecFamily> =
            ladderFor(format).map { it.family }.distinct()

        /** 该输出格式在给定位深下**结构上**是否存在阶梯项。 */
        fun supportsBitDepth(format: FableSolExportHdrFormat?, tenBit: Boolean): Boolean =
            ladderFor(format).any { it.eightBit != tenBit }

        private fun collect(
            entry: FableSolExportCodecEntry,
            format: FableSolExportHdrFormat?,
            widthPx: Int,
            heightPx: Int,
            frameRate: Int,
            target: MutableList<FableSolExportTier>,
            allowSoftware: Boolean,
            rateControl: FableSolExportRateControl,
            /** null 表示用户没动过滑杆，按 D147 走自动推导。 */
            customBitrateMbps: Float?,
            bFrames: Boolean,
            complexFrameGuard: Boolean
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
                val softwareOnly = FableSolExportCodecFamily.isSoftwareOnly(info)
                if (softwareOnly && !allowSoftware) continue
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
                val encoder = capabilities.encoderCapabilities
                val supportsCq = encoder?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                ) ?: false
                val supportsCbr = encoder?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) ?: false
                val supportsVbr = encoder?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) ?: true
                val qualityRange = if (supportsCq && Build.VERSION.SDK_INT >= 28) {
                    try {
                        encoder?.qualityRange?.takeIf { it.upper > it.lower }
                    } catch (ignored: Throwable) {
                        null
                    }
                } else {
                    null
                }
                // 编码模式是用户意图，必须由当前格式、编码器、位深、尺寸和帧率的同一个精确
                // 档位满足。不能因为设备上的另一条编码路径支持 CQ，就让本档静默改用 VBR。
                if (
                    rateControl == FableSolExportRateControl.CONSTANT_QUALITY &&
                    qualityRange == null
                ) {
                    continue
                }
                if (
                    rateControl == FableSolExportRateControl.TARGET_BITRATE &&
                    !supportsVbr && !supportsCbr
                ) {
                    continue
                }
                // 输入通路是同一档位下的子候选（D158）。8-bit 继续走 Surface 与 D9 的抖动；
                // 10-bit 只要编码器**公开列出**标准 P010 色彩格式就优先应用自有 P010，再以
                // 同格式 Surface 兜底。HDR10+ 没有兜底：它的动态元数据只能逐帧交给字节缓冲，
                // 用 Surface 冒充同格式后备等于发布一个名不副实的产物。
                val listsP010 = try {
                    capabilities.colorFormats.any { it == COLOR_FORMAT_YUV_P010 }
                } catch (ignored: Throwable) {
                    false
                }
                val p010Required = format?.usesByteBufferInput == true
                val inputPaths = when {
                    entry.eightBit -> listOf(FableSolExportInputPath.SURFACE)
                    p010Required && listsP010 -> listOf(FableSolExportInputPath.APP_P010)
                    p010Required -> emptyList()
                    listsP010 -> listOf(
                        FableSolExportInputPath.APP_P010, FableSolExportInputPath.SURFACE
                    )
                    else -> listOf(FableSolExportInputPath.SURFACE)
                }
                val bitrateRange = try {
                    video.bitrateRange
                } catch (ignored: Throwable) {
                    null
                }
                // **码率先于 Level 解析**：D152 的最低充分档要拿解析后的码率算，而 D147 的
                // 自动值又依赖对齐后的实际宽高、帧率、族与位深——它们到这一步才全部齐备。
                // CQ 模式下 bitrate 为 null，Level 因此只按尺寸与像素率定档（D168）。
                val constantQuality =
                    rateControl == FableSolExportRateControl.CONSTANT_QUALITY
                val resolvedBitrate = if (constantQuality && qualityRange != null) {
                    null
                } else {
                    val value = customBitrateMbps?.let {
                        FableSolExportBitrateModel.customBitrateBps(it)
                    } ?: FableSolExportBitrateModel.autoBitrateBps(
                        widthPx = encodedWidth,
                        heightPx = encodedHeight,
                        frameRate = frameRate,
                        family = entry.family,
                        tenBit = !entry.eightBit,
                        hdr = transfer.isHdr
                    )
                    bitrateRange?.let { value.coerceIn(it.lower, it.upper) } ?: value
                }
                // QP 保护只作用于 VBR（D151）；它是否会跟着本候选跑，要在定档前就知道——
                // 保护挡住压缩引起的码率上浮，正是 Level 码率余量（D152）要吃下的那一段。
                val supportsQpBounds = Build.VERSION.SDK_INT >= 31 && try {
                    capabilities.isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds
                    )
                } catch (ignored: Throwable) {
                    false
                }
                // level 必须在**对齐之后**才算：64px 分享兼容对齐会把画布撑大，像素率跟着
                // 变，算早了可能刚好落在阶梯的错误一档上。
                val selection = if (entry.profile == 0) {
                    null
                } else if (format?.needsDolbyVisionLevel == true) {
                    // 杜比视界的 level 是一条独立的像素率阶梯，不在 AVC/HEVC/AV1 的标准表里；
                    // D152 要求把它纳入同一套"取刚好够用的一档"语义，算法一致、表不同。
                    val required = FableSolExportHdrFormat.dolbyVisionLevel(
                        encodedWidth, encodedHeight, frameRate
                    )
                    val level = advertised.filter { it.level >= required }
                        .minByOrNull { it.level }
                        ?: advertised.maxByOrNull { it.level }
                    level?.let { FableSolExportLevel.Selection(it.level, highTier = false) }
                } else {
                    FableSolExportLevel.select(
                        family = entry.family,
                        advertised = advertised.map { it.level },
                        widthPx = encodedWidth,
                        heightPx = encodedHeight,
                        frameRate = frameRate,
                        profile = entry.profile,
                        bitrateBps = resolvedBitrate,
                        bFrames = bFrames,
                        // 与运行时 qpGuardRequested 同一谓词：保护开启、形态会落 VBR、编码器
                        // 声明 QpBounds。CQ 候选的 bitrateBps 为 null，这个标志天然无效。
                        qpGuard = complexFrameGuard && supportsVbr && supportsQpBounds
                    )
                }
                val complexityRange = try {
                    encoder?.complexityRange?.takeIf { it.upper > it.lower }
                } catch (ignored: Throwable) {
                    null
                }
                for (inputPath in inputPaths) batch.add(
                    FableSolExportTier(
                        codecName = info.name,
                        videoMime = entry.mime,
                        profile = entry.profile,
                        level = selection?.level ?: 0,
                        transfer = transfer,
                        hdrFormat = format,
                        family = entry.family,
                        softwareOnly = softwareOnly,
                        eightBit = entry.eightBit,
                        inputPath = inputPath,
                        supportsCbr = supportsCbr,
                        supportsVbr = supportsVbr,
                        qualityRange = qualityRange,
                        bitrateRange = bitrateRange,
                        bitrateBps = resolvedBitrate,
                        highTier = selection?.highTier == true,
                        complexityRange = complexityRange,
                        supportsQpBounds = supportsQpBounds,
                        encodedWidthPx = encodedWidth,
                        encodedHeightPx = encodedHeight,
                        // 档位名带上格式：HDR10 与 HLG 用的是同一个 HEVC Main10 编码器，
                        // 不写格式的话完成提示里两者一模一样，看不出自动档最后落到了哪种。
                        // 输入通路同理——同一个编码器的 P010 与 Surface 是两条真正不同的
                        // 通路，诊断里必须分得出是哪一条失败了。
                        label = buildString {
                            if (format != null) append(format.stableLabel).append(' ')
                            append(entry.label).append(" (").append(info.name).append(')')
                            if (inputPath == FableSolExportInputPath.APP_P010) {
                                append(" [P010]")
                            }
                        },
                        profileLabel = entry.label
                    )
                )
            }
            // 公开规格及其内部候选的完整排序（硬件先于软件、族顺序、Profile 顺序、码控模式
            // 匹配）统一在
            // candidatesForMode 里做：那里才看得见全部阶梯项，才排得出 D161 要求的
            // “硬件 HEVC → 硬件 AV1 → 硬件 AVC → 软件 HEVC → 软件 AV1 → 软件 AVC”。
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

        /**
         * `MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010`。
         *
         * 直接写数值而不是引用常量：该常量到 API 33 才加入，而 D158 第 2 条明确要求**不以
         * Android 版本单独作硬门禁**——较早系统若实际列出同一个标准值并通过短探测，同样
         * 允许使用。只认这一个标准值，不认厂商私有的 P010 变体：后者的平面排布规则不同，
         * 按标准 P010 写进去就是花屏。
         */
        internal const val COLOR_FORMAT_YUV_P010 = 54

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
            // AV1 此前只在 HDR 阶梯里出现，SDR 完全没有——于是"用户想选 AV1"这件事在关掉
            // HDR 之后根本无从谈起。补上之后 SDR 也成了三个编码器族齐全的一档。
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                eightBit = false,
                label = "AV1 Main10 SDR"
            ),
            FableSolExportCodecEntry(
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
                eightBit = true,
                label = "AV1 Main8 SDR"
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
