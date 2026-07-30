package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * HLG super-white 的真实编码—解码 P010 回环验证（fablesol-video-export D138～D140）。
 *
 * ```text
 * 已知 limited-range P010 测试图 → 正式候选的编码器配置 → 临时容器
 * → 支持该 MIME 的 P010 解码器 → CPU 可读 P010 重建样本 → 逐分量安全区间
 * ```
 *
 * D139 明确排除了几种看起来能用的证据：编码器接受了 P010、产出了非空样本、回报了 HLG
 * 格式、Surface 解码后截图、8-bit YUV 输出、RGB 显示读回——它们各自都可能在中途裁切、量化
 * 或色调映射掉 100% 以上的信号。唯一算数的是 CPU 直接读到的 10-bit 重建码值。
 *
 * 验证不通过**不是** HLG 编码失败：按 D135 继续导出名义范围 HLG，不回退 SDR、不弹失败。
 */
internal object FableSolExportHlgVerification {

    /** 一次验证的结论。[safe] 为 null 表示无法验证，按名义范围导出。 */
    data class Outcome(
        val safe: FableSolExportHlgDeviceRange.SafeCodes?,
        /** 稳定的诊断原因标识；成功时是 `ok`。不本地化，展示时再翻。 */
        val reason: String,
        /** 实际使用的解码器；没有可用解码器时为空。 */
        val decoderName: String
    ) {
        val verified: Boolean get() = safe != null
    }

    // ---- 缓存（D138）----

    private const val PREFS_NAME = "fablesol_export_hlg_range"
    private const val MAX_ENTRIES = 24

    /**
     * 结论的完整签名。
     *
     * D138 逐项列出了它必须覆盖的东西，核心是"同一套设置的首次导出不能因为后台探测完成时机
     * 不同而随机采用不同画质路径"。因此**不得**退化成一个与编码器和输入路径无关的全局布尔值。
     */
    fun signature(
        tier: FableSolExportTier,
        options: FableSolExportOptions,
        frameRate: Int,
        decoderName: String,
        /**
         * 本次实际下发的码控形态（D167）。纯 CQ 与 CQ+码率提示是同一用户模式的两种
         * `MediaFormat` 形态，编码器可能只接受其一——两种形态的结论不得共享（D139）。
         */
        form: FableSolExportRateControlForm
    ): String = listOf(
        FableSolExportHlgLoopback.CONTRACT_VERSION.toString(),
        Build.FINGERPRINT,
        tier.codecName,
        tier.videoMime,
        tier.profile.toString(),
        tier.transfer.name,
        tier.inputPath.stableId,
        options.rateControl.stableId,
        tier.encodedWidthPx.toString(),
        tier.encodedHeightPx.toString(),
        frameRate.toString(),
        decoderName.ifEmpty { "none" },
        form.stableId
    ).joinToString("|")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 设置页预测用的只读查询（D135）：组签名、查缓存，**绝不触发回环**。
     *
     * @return 已缓存的结论；null 表示该签名尚未验证，设置页据此维持"首次导出会先做一次
     *   短验证"的说明（D138），不预告落点。
     */
    fun cachedPrediction(
        context: Context,
        tier: FableSolExportTier,
        options: FableSolExportOptions,
        frameRate: Int,
        form: FableSolExportRateControlForm
    ): Outcome? {
        val decoder = selectDecoderName(tier.videoMime)
        return cached(
            context, signature(tier, options, frameRate, decoder.orEmpty(), form)
        )
    }

    /** 已有结论时直接复用，不再跑一次回环（D138）。 */
    fun cached(context: Context, signature: String): Outcome? {
        val encoded = prefs(context).getString(signature, null) ?: return null
        val parts = encoded.split(';')
        if (parts.size != 3) return null
        val safe = if (parts[0] == "nominal") {
            null
        } else {
            FableSolExportHlgDeviceRange.SafeCodes.decode(parts[0]) ?: return null
        }
        return Outcome(safe, parts[1], parts[2])
    }

    private fun store(context: Context, signature: String, outcome: Outcome) {
        val preferences = prefs(context)
        val entries = preferences.all
        val editor = preferences.edit()
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(signature)) {
            entries.keys.take(entries.size - MAX_ENTRIES + 1).forEach { editor.remove(it) }
        }
        editor.putString(
            signature,
            "${outcome.safe?.encode() ?: "nominal"};${outcome.reason};${outcome.decoderName}"
        )
        editor.apply()
    }

    /**
     * 设备诊断用：已缓存的全部回环结论。
     *
     * 结论本身按签名区分编码器与输入通路，因此报告也逐条列出——"这台设备支不支持
     * super-white"不是一个可回答的问题（D140），能回答的只有"这条通路上量到了多少"。
     */
    fun diagnosticLines(context: Context): List<String> = try {
        prefs(context).all.entries
            .mapNotNull { (signature, value) ->
                val fields = signature.split('|')
                // 13 个字段是含码控形态的当前签名格式；旧格式条目已经不会被任何新签名命中，
                // 继续展示只会把过时结论混进报告。
                if (fields.size < 13 || value !is String) return@mapNotNull null
                val outcome = cached(context, signature) ?: return@mapNotNull null
                val encoder = fields[2]
                val path = fields[6]
                val decoder = fields[11]
                val form = fields[12]
                val conclusion = outcome.safe?.let {
                    "扩展（Y′ ≤ ${it.lumaMaxCode}，Cb ${it.cbMinCode}～${it.cbMaxCode}，" +
                        "Cr ${it.crMinCode}～${it.crMaxCode}）"
                } ?: "名义（${outcome.reason}）"
                "  · $encoder / $path / $form → $conclusion，解码器 $decoder"
            }
            .sorted()
    } catch (ignored: Throwable) {
        emptyList()
    }

    // ---- 验证 ----

    /**
     * 取得本候选的 super-white 结论；缓存命中时不做任何编解码。
     *
     * 必须在正式动画渲染开始之前调用（D138）：换信号范围会改变量化边界与肩部容量，渲染到
     * 一半再换等于把已经编好的帧作废。
     */
    fun resolve(
        context: Context,
        tier: FableSolExportTier,
        options: FableSolExportOptions,
        frameRate: Int,
        /** 本次实际下发的码控形态；必须与正式导出同源（D139"相同的……编码模式"）。 */
        form: FableSolExportRateControlForm
    ): Outcome {
        val decoder = selectDecoderName(tier.videoMime)
        val signature = signature(tier, options, frameRate, decoder.orEmpty(), form)
        cached(context, signature)?.let { return it }
        val outcome = if (decoder == null) {
            Outcome(null, REASON_NO_DECODER, "")
        } else {
            runLoopback(context, tier, options, frameRate, decoder, form)
        }
        // 只缓存**确定性**结论。D139 把解码侧失败、输出非 P010、标记缺失与实测区间定为可
        // 缓存的"无法验证/已验证"；D138 对"受控异常或超时"的措辞是"**本次**继续使用名义
        // 范围"——临时文件建不出来、编码超时这类环境性失败按签名永久锁死，等于把一次偶发
        // 当成设备事实。编码侧的确定性拒绝（configure 不接受）失败得很快，不缓存的重试代价
        // 只有几十毫秒，不值得为省它冒缓存错误结论的险。
        if (outcome.reason !in TRANSIENT_REASONS) {
            store(context, signature, outcome)
        }
        return outcome
    }

    const val REASON_OK = "ok"
    const val REASON_NO_DECODER = "no-p010-decoder"
    const val REASON_ENCODE_FAILED = "encode-failed"
    const val REASON_DECODE_FAILED = "decode-failed"
    const val REASON_NOT_P010 = "output-not-p010"
    const val REASON_COLOR_TAGS = "colour-tags-mismatch"
    const val REASON_GEOMETRY = "canvas-too-small"
    const val REASON_COLLAPSED = "extended-codes-collapsed"
    const val REASON_NO_EXTENSION = "no-extension-available"
    const val REASON_TEMPORARY_FILE = "temporary-file"

    /** 环境性失败：只影响本次（D138），不写入按签名的持久缓存。 */
    private val TRANSIENT_REASONS = setOf(REASON_ENCODE_FAILED, REASON_TEMPORARY_FILE)

    /**
     * 只挑**明确公开** `COLOR_FormatYUVP010` 的解码器。
     *
     * Android CDD 要求声明该颜色格式的解码器提供 CPU 可读的 P010；反过来，没有声明的解码器
     * 即便能播，也可能只经由 Surface 输出，那条路按 D139 不构成证据。
     */
    fun selectDecoderName(mime: String): String? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .firstOrNull { info ->
                try {
                    info.getCapabilitiesForType(mime).colorFormats.any {
                        it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                    }
                } catch (ignored: Throwable) {
                    false
                }
            }
            ?.name
    } catch (ignored: Throwable) {
        null
    }

    private fun runLoopback(
        context: Context,
        tier: FableSolExportTier,
        options: FableSolExportOptions,
        frameRate: Int,
        decoderName: String,
        form: FableSolExportRateControlForm
    ): Outcome {
        val temporary = try {
            File.createTempFile("fablesol-hlg-range-", ".mp4", context.cacheDir)
        } catch (error: Throwable) {
            return Outcome(null, REASON_TEMPORARY_FILE, decoderName)
        }
        return try {
            val patches = FableSolExportHlgLoopback.patches()
            if (!encodeTestImage(tier, options, frameRate, temporary, patches, form)) {
                return Outcome(null, REASON_ENCODE_FAILED, decoderName)
            }
            decodeAndMeasure(temporary, decoderName, patches)
        } catch (error: Throwable) {
            Outcome(null, REASON_ENCODE_FAILED, decoderName)
        } finally {
            temporary.delete()
        }
    }

    // ---- 编码侧 ----

    /**
     * 把测试图按正式候选的配置编成一帧。
     *
     * 用的是 [FableSolExportEncoder] 而不是另起一套 `MediaCodec` 配置：D139 要求"与正式候选
     * 相同的编码器名称、MIME、Profile、编码模式、输入 P010 排布和色彩标记"，另写一份配置迟早
     * 会与正式路径分叉，那样验证的就不是正式路径。
     */
    private fun encodeTestImage(
        tier: FableSolExportTier,
        options: FableSolExportOptions,
        frameRate: Int,
        target: File,
        patches: List<FableSolExportHlgLoopback.Patch>,
        /** 与正式导出同一份码控形态（D139、D167）：探一种形态、正式用另一种等于没验证。 */
        form: FableSolExportRateControlForm
    ): Boolean {
        var encoder: FableSolExportEncoder? = null
        return try {
            val muxer = MediaMuxer(
                target.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val active = FableSolExportEncoder(
                widthPx = tier.encodedWidthPx,
                heightPx = tier.encodedHeightPx,
                frameRate = frameRate,
                tier = tier,
                options = options,
                audioSampleRate = LOOPBACK_AUDIO_SAMPLE_RATE,
                muxer = muxer,
                peakNits = FableSolHdrPolicy.MAX_STRENGTH *
                    FableSolExportTransfer.SDR_WHITE_NITS,
                luminance = FableSolExportLuminanceStats.theoretical(
                    FableSolHdrPolicy.MAX_STRENGTH.toDouble()
                ),
                form = form
            )
            encoder = active
            active.start()
            active.queueVideoFrame(0L, null) { buffer, layout ->
                writeTestImage(buffer, layout, patches)
            }
            val silence = ShortArray(LOOPBACK_AUDIO_SAMPLES)
            active.feedAudio(silence, silence.size)
            active.finish(timeoutMs = LOOPBACK_TIMEOUT_MS) && active.videoSamplesWritten > 0L
        } catch (error: Throwable) {
            false
        } finally {
            try {
                encoder?.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 按编码器回报的实际排布写 P010；行距、平面高度、crop 一概不假定（D159）。 */
    private fun writeTestImage(
        buffer: ByteBuffer,
        layout: FableSolExportP010Layout,
        patches: List<FableSolExportHlgLoopback.Patch>
    ): Int {
        buffer.clear()
        val width = layout.widthPx
        val height = layout.heightPx
        // 背景填中性中位灰：色块之间隔开的这一圈本身不参与统计，但它决定边界的邻域内容，
        // 用极端值会让相邻色块的边界污染范围更大。
        for (y in 0 until height) {
            var offset = layout.lumaOffset + y * layout.lumaRowStride
            for (x in 0 until width) {
                putCode(buffer, offset, FableSolExportHlgLoopback.MID_LUMA_CODE)
                offset += FableSolExportP010Layout.BYTES_PER_SAMPLE
            }
        }
        for (y in 0 until height / 2) {
            var offset = layout.chromaOffset + y * layout.chromaRowStride
            for (x in 0 until width / 2) {
                putCode(buffer, offset, FableSolExportHlgLoopback.NEUTRAL_CHROMA_CODE)
                putCode(
                    buffer,
                    offset + FableSolExportP010Layout.BYTES_PER_SAMPLE,
                    FableSolExportHlgLoopback.NEUTRAL_CHROMA_CODE
                )
                offset += 2 * FableSolExportP010Layout.BYTES_PER_SAMPLE
            }
        }
        patches.forEachIndexed { index, patch ->
            val rect = FableSolExportHlgLoopback.patchRect(index, patches.size, width, height)
            for (y in rect.top until rect.bottom) {
                var offset = layout.lumaOffset + y * layout.lumaRowStride +
                    rect.left * FableSolExportP010Layout.BYTES_PER_SAMPLE
                for (x in rect.left until rect.right) {
                    putCode(buffer, offset, patch.lumaCode)
                    offset += FableSolExportP010Layout.BYTES_PER_SAMPLE
                }
            }
            for (y in rect.top / 2 until rect.bottom / 2) {
                var offset = layout.chromaOffset + y * layout.chromaRowStride +
                    rect.left * FableSolExportP010Layout.BYTES_PER_SAMPLE
                for (x in rect.left / 2 until rect.right / 2) {
                    putCode(buffer, offset, patch.cbCode)
                    putCode(
                        buffer,
                        offset + FableSolExportP010Layout.BYTES_PER_SAMPLE,
                        patch.crCode
                    )
                    offset += 2 * FableSolExportP010Layout.BYTES_PER_SAMPLE
                }
            }
        }
        return layout.frameBytes
    }

    /** P010：10 位有效值放在 16 位字的高位，小端两个字节。 */
    private fun putCode(buffer: ByteBuffer, offset: Int, code: Int) {
        val word = (code.coerceIn(0, 1023) shl 6) and 0xFFFF
        buffer.put(offset, (word and 0xFF).toByte())
        buffer.put(offset + 1, ((word ushr 8) and 0xFF).toByte())
    }

    // ---- 解码侧 ----

    private fun decodeAndMeasure(
        source: File,
        decoderName: String,
        patches: List<FableSolExportHlgLoopback.Patch>
    ): Outcome {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    trackIndex = index
                    trackFormat = format
                    break
                }
            }
            val format = trackFormat ?: return Outcome(null, REASON_DECODE_FAILED, decoderName)
            // D139 要求像素回环与色彩标记同时通过。标记缺失同样不算通过：容器没说这是
            // BT.2020/HLG/limited range 时，"扩展码值被保留"这件事没有可解释的载体。
            if (!colorTagsMatch(format)) {
                return Outcome(null, REASON_COLOR_TAGS, decoderName)
            }
            extractor.selectTrack(trackIndex)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            )
            val active = MediaCodec.createByCodecName(decoderName)
            decoder = active
            active.configure(format, null, null, 0)
            active.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.nanoTime() + LOOPBACK_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    val index = active.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = active.getInputBuffer(index)
                        val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (read <= 0) {
                            active.queueInputBuffer(
                                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            active.queueInputBuffer(
                                index, 0, read, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIndex = active.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outcome = measure(active, outIndex, patches, decoderName)
                        active.releaseOutputBuffer(outIndex, false)
                        return outcome
                    }
                    active.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!outputIsP010(active.outputFormat)) {
                        return Outcome(null, REASON_NOT_P010, decoderName)
                    }
                }
            }
            return Outcome(null, REASON_DECODE_FAILED, decoderName)
        } catch (error: Throwable) {
            return Outcome(null, REASON_DECODE_FAILED, decoderName)
        } finally {
            try {
                decoder?.stop()
            } catch (ignored: Throwable) {
            }
            try {
                decoder?.release()
            } catch (ignored: Throwable) {
            }
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 容器必须如实携带 BT.2020 primaries/matrix、HLG transfer 与 limited range（D139）。 */
    private fun colorTagsMatch(format: MediaFormat): Boolean {
        val standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        val range = format.intOrNull(MediaFormat.KEY_COLOR_RANGE)
        return standard == MediaFormat.COLOR_STANDARD_BT2020 &&
            transfer == MediaFormat.COLOR_TRANSFER_HLG &&
            range == MediaFormat.COLOR_RANGE_LIMITED
    }

    private fun outputIsP010(format: MediaFormat): Boolean =
        format.intOrNull(MediaFormat.KEY_COLOR_FORMAT) ==
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010

    private fun MediaFormat.intOrNull(key: String): Int? = try {
        if (containsKey(key)) getInteger(key) else null
    } catch (ignored: Throwable) {
        null
    }

    /**
     * 从 CPU 可读的 P010 输出量出每块色块的三个分量中位值。
     *
     * 一切几何都读 `Image` 自己报的 crop 与平面参数，不假定紧密排布（D139）。
     */
    private fun measure(
        decoder: MediaCodec,
        outputIndex: Int,
        patches: List<FableSolExportHlgLoopback.Patch>,
        decoderName: String
    ): Outcome {
        val image = try {
            decoder.getOutputImage(outputIndex)
        } catch (ignored: Throwable) {
            null
        } ?: return Outcome(null, REASON_NOT_P010, decoderName)
        return try {
            if (image.format != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010) {
                return Outcome(null, REASON_NOT_P010, decoderName)
            }
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            val planes = image.planes
            if (planes.size < 3 || width <= 0 || height <= 0) {
                return Outcome(null, REASON_NOT_P010, decoderName)
            }
            val readings = ArrayList<FableSolExportHlgLoopback.Reading>(patches.size)
            patches.forEachIndexed { index, patch ->
                val rect = FableSolExportHlgLoopback.patchRect(
                    index, patches.size, width, height
                )
                val interior = FableSolExportHlgLoopback.interiorRect(rect)
                    ?: return Outcome(null, REASON_GEOMETRY, decoderName)
                readings.add(
                    FableSolExportHlgLoopback.Reading(
                        patch = patch,
                        lumaMedian = median(
                            planes[0], crop.left, crop.top, interior, chroma = false
                        ),
                        cbMedian = median(
                            planes[1], crop.left, crop.top, interior, chroma = true
                        ),
                        crMedian = median(
                            planes[2], crop.left, crop.top, interior, chroma = true
                        )
                    )
                )
            }
            val safe = FableSolExportHlgLoopback.deriveSafeCodes(readings)
                ?: return Outcome(null, REASON_COLLAPSED, decoderName)
            if (!safe.extended) {
                // 三条轴都停在名义端点：链路本身是好的，只是这台设备的编解码不保留扩展码值。
                Outcome(null, REASON_NO_EXTENSION, decoderName)
            } else {
                Outcome(safe, REASON_OK, decoderName)
            }
        } finally {
            try {
                image.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 统计区内的中位码值。
     *
     * 取中位而不是均值：有损编码在块边界留下的少量异常样本对均值有杠杆，对中位没有；D139 也
     * 正是按"各区域的重建中位值"定的判据。样本按固定步长抽取，几千个点足以定出中位，不必把
     * 整块几十万个样本都读一遍。
     */
    private fun median(
        plane: android.media.Image.Plane,
        cropLeft: Int,
        cropTop: Int,
        interior: FableSolExportHlgLoopback.Rect,
        chroma: Boolean
    ): Double {
        val shift = if (chroma) 1 else 0
        val left = (cropLeft + interior.left) shr shift
        val top = (cropTop + interior.top) shr shift
        val right = (cropLeft + interior.right) shr shift
        val bottom = (cropTop + interior.bottom) shr shift
        val stepX = ((right - left) / SAMPLE_SIDE).coerceAtLeast(1)
        val stepY = ((bottom - top) / SAMPLE_SIDE).coerceAtLeast(1)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val values = ArrayList<Int>()
        var y = top
        while (y < bottom) {
            val rowOffset = y * rowStride
            var x = left
            while (x < right) {
                val offset = rowOffset + x * pixelStride
                if (offset + 1 < buffer.limit()) {
                    val lo = buffer.get(offset).toInt() and 0xFF
                    val hi = buffer.get(offset + 1).toInt() and 0xFF
                    values.add(((hi shl 8) or lo) ushr 6)
                }
                x += stepX
            }
            y += stepY
        }
        if (values.isEmpty()) return -1.0
        values.sort()
        val middle = values.size / 2
        return if (values.size % 2 == 1) {
            values[middle].toDouble()
        } else {
            (values[middle - 1] + values[middle]) / 2.0
        }
    }

    private const val SAMPLE_SIDE = 48
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val LOOPBACK_TIMEOUT_MS = 6_000L
    private const val LOOPBACK_AUDIO_SAMPLE_RATE = 48_000
    private const val LOOPBACK_AUDIO_SAMPLES = 1_024
}
