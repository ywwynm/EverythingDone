package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.SystemClock
import com.ywwynm.everythingdone.model.ThingBackground
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 一次 FableSol 可视化视频导出。
 *
 * 每一个编码候选都是完整事务：重新打开音频、创建 codec/muxer/EGL、初始化渲染器、编码到
 * EOS、成功执行 `MediaMuxer.stop()`，最后才发布文件。任一步失败都会清理半成品并从零尝试
 * 下一档，避免“configure 成功但首帧、输出格式或 muxer.addTrack 失败”直接终止整个导出。
 */
internal class FableSolVideoExporter(
    baseContext: Context,
    private val request: Request,
    private val listener: Listener
) {

    /**
     * 全流程只认这一个 Context。Service 传进来的是 Application Context——它的主题是平台
     * 默认的浅色主题、配置也读不到应用自己的夜间模式，直接用会导出一张与屏上不同外观的
     * 卡片（见 [FableSolExportAppearance]）。
     */
    private val context: Context = FableSolExportAppearance.themedContext(baseContext)

    internal data class Request(
        val audioPath: String,
        val sink: FableSolExportSink,
        val waterBackground: ThingBackground,
        val accentBackground: ThingBackground,
        val cardWidthDp: Double
    )

    internal interface Listener {
        fun onProgress(framesDone: Int, framesTotal: Int, etaMs: Long)
        fun isCancelled(): Boolean
    }

    internal sealed class Result {
        data class Success(
            val sink: FableSolExportSink,
            val tierLabel: String,
            val hdr: Boolean,
            /** 当前 locale 的用户可见格式名，例如“HDR10+”“杜比视界 8.4”“SDR”。 */
            val formatLabel: String,
            /** 实际使用的编码器族，例如“HEVC”“AV1”“H.264”。 */
            val codecLabel: String,
            /** 实际使用的编码器实现是否为纯软件；完成态要标出来。 */
            val softwareCodec: Boolean,
            val frameRate: Int,
            val frames: Int,
            val elapsedMs: Long,
            /** 漫反射白（尼特）；0 表示这次不是 PQ 系，完成态不显示这一行。 */
            val pqWhiteNits: Double = 0.0,
            /** 峰值 = 漫反射白 × HDR 强度。 */
            val peakNits: Double = 0.0,
            /** 高光起点百分位；0 表示不是 HDR10+，该项不适用。 */
            val highlightStartPercent: Int = 0
        ) : Result()

        object Cancelled : Result()
        data class OutOfSpace(val estimatedBytes: Long) : Result()
        data class Failure(val message: String) : Result()
    }

    fun run(): Result {
        val exportStartedAt = SystemClock.elapsedRealtime()
        return try {
            val capability = FableSolExportEgl.probe()
            val options = FableSolExportOptions.read(context)
            val strength = FableSolTuning.hdrStrength(context)
            // 走字节缓冲的档不依赖 EGL 的窗口色彩空间扩展，所以那一项不能作为总门禁。
            val wantHdr = options.hdrEnabled &&
                strength > FableSolHdrPolicy.STRENGTH_OFF &&
                capability.linearSceneSupported
            val basePlan = FableSolExportSpec.plan(context, request.cardWidthDp)
            val requestedFrameRate = options.frameRateCap.coerceIn(
                FableSolExportOptions.FRAME_RATE_BASE,
                FableSolExportOptions.FRAME_RATE_HIGH
            )

            val metadata = readAudioMetadata()
            val durationSeconds = metadata.durationUs / 1_000_000.0
            val spaceResult = checkInitialSpace(
                options, requestedFrameRate, durationSeconds
            )
            if (spaceResult != null) return spaceResult

            var lastFailure: String? = null
            var foundCandidate = false
            // 用户可以钉死某一种格式；默认「自动」按 AUTO_ORDER 的规格/画质能力顺序尝试。
            // 这里只按 EGL 能出哪种色彩空间做粗筛，不查探测缓存：缓存可能过期，而降级
            // 阶梯本来就会把真正编不出来的档一个个淘汰掉。
            val availableTransfers = capability.availableHdrTransfers()
            val hdrFormats = if (wantHdr) {
                FableSolExportHdrFormat.AUTO_ORDER
                    .filter {
                        !it.requiresEglColorSpace || availableTransfers.contains(it.transfer)
                    }
                    .filter { options.hdrFormat.allows(it) }
            } else {
                emptyList()
            }
            val attempts = FableSolExportAttemptPlan.ordered(
                hdrFormats = hdrFormats,
                requestedFrameRate = requestedFrameRate
            )
            for (attempt in attempts) {
                val candidates = FableSolExportTier.candidatesForMode(
                    format = attempt.format,
                    basePlan.canvasWidthPx,
                    basePlan.canvasHeightPx,
                    attempt.frameRate,
                    preferConstantQuality = options.constantQuality,
                    // 用户钉死某个编码器族时只走那一族；自动档不使用软件编码器——本项目的
                    // 画布接近两百万像素，让软件编码作为静默退路等于在用户毫不知情的情况下
                    // 把一次导出拖长几十倍（三星 Z Fold4 上实际落到过软件 AV1 的 60fps）。
                    family = options.codec.family,
                    allowSoftware = options.codec.allowsSoftware
                )
                for (tier in candidates) {
                    foundCandidate = true
                    if (listener.isCancelled()) return Result.Cancelled
                    val plan = basePlan.withCanvasSize(
                        tier.encodedWidthPx, tier.encodedHeightPx
                    )
                    try {
                        return runAttempt(
                            exportStartedAt = exportStartedAt,
                            capability = capability,
                            options = options,
                            strength = strength,
                            durationSeconds = durationSeconds,
                            frameRate = attempt.frameRate,
                            tier = tier,
                            plan = plan
                        )
                    } catch (outOfSpace: OutOfSpaceFailure) {
                        return Result.OutOfSpace(outOfSpace.requiredBytes)
                    } catch (publish: PublishFailure) {
                        // 发布失败与 codec 档位无关，重新渲染整段只会重复同一个错误。
                        return Result.Failure(
                            publish.message ?: "Failed to publish the exported video"
                        )
                    } catch (error: Exception) {
                        val detail = FableSolExportHdrFormat.localizeStableLabels(
                            context,
                            error.message ?: error.javaClass.simpleName
                        )
                        lastFailure =
                            "${tier.displayLabel(context)}：$detail"
                    }
                }
            }
            Result.Failure(
                lastFailure ?: if (foundCandidate) {
                    "All encoder attempts failed"
                } else {
                    "No encoder supports at least " +
                        "${basePlan.canvasWidthPx}x${basePlan.canvasHeightPx}"
                }
            )
        } catch (error: Throwable) {
            Result.Failure(
                FableSolExportHdrFormat.localizeStableLabels(
                    context,
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private fun runAttempt(
        exportStartedAt: Long,
        capability: FableSolExportEgl.Capability,
        options: FableSolExportOptions,
        strength: Float,
        durationSeconds: Double,
        frameRate: Int,
        tier: FableSolExportTier,
        plan: FableSolExportPlan
    ): Result {
        val attemptStartedAt = SystemClock.elapsedRealtime()
        var audio: FableSolExportAudioSource? = null
        var encoder: FableSolExportEncoder? = null
        var egl: FableSolExportEgl? = null
        var renderer: FableSolGlRenderer? = null
        var presenter: FableSolExportPresenter? = null
        var clock: FableSolExportClock? = null
        var bridge: FableSolExportP010Bridge? = null
        // 曲线是有状态的：膝点要跨帧做快起慢落的平滑，所以每次尝试各持一条。
        var hdrCurve: FableSolExportHdr10PlusCurve? = null
        var published = false
        try {
            audio = FableSolExportAudioSource(request.audioPath)
            val sampleRate = audio.sampleRate
            val totalFrames = if (durationSeconds > 0.0) {
                ceil(durationSeconds * frameRate).toInt().coerceAtLeast(1)
            } else {
                Int.MAX_VALUE
            }
            listener.onProgress(0, totalFrames, -1L)

            // 文件名里要带格式，而格式是降级阶梯定下来才知道的；sink 在建 muxer 那一刻
            // 才真正落名，所以这里补标签正好赶得上。
            // PQ 是绝对亮度，漫反射白钉在哪儿由用户定；HLG 是相对亮度，用不到这个数。
            val whiteNits = if (tier.transfer == FableSolExportTransfer.PQ) {
                options.pqWhiteNits.toDouble()
            } else {
                FableSolExportTransfer.SDR_WHITE_NITS
            }
            val peakNits = strength * whiteNits
            request.sink.tagFormat(
                tier.hdrFormat?.fileTag ?: FableSolExportHdrFormat.SDR_LABEL
            )
            encoder = FableSolExportEncoder(
                plan.canvasWidthPx,
                plan.canvasHeightPx,
                frameRate,
                tier,
                options,
                sampleRate,
                request.sink.createMuxer(),
                peakNits = peakNits,
                diffuseWhiteNits = whiteNits
            )
            // HDR10+ 走字节缓冲输入：它的动态元数据只能逐帧交给编码器，而那个接口在
            // surface 输入模式下被系统禁止。此时没有 input surface，EGL 走离屏。
            val byteBuffer = tier.hdrFormat?.usesByteBufferInput == true
            egl = FableSolExportEgl(
                if (byteBuffer) null else encoder.inputSurface,
                transfer = tier.transfer,
                tenBit = !tier.eightBit
            )
            encoder.start()
            if (byteBuffer) {
                bridge = FableSolExportP010Bridge(
                    context.assets, plan.canvasWidthPx, plan.canvasHeightPx
                )
                hdrCurve = FableSolExportHdr10PlusCurve(
                    masteringPeakNits = peakNits,
                    targetNits = FableSolExportHdr10PlusCurve.targetNitsFor(
                        masteringPeakNits = peakNits,
                        whiteNits = whiteNits,
                        panelPeakNits = FableSolExportDisplayLuminance.panelPeakNits(context)
                    ),
                    highlightStartPercent = options.highlightStartPercent
                )
            }

            clock = FableSolExportClock(context, plan, request.accentBackground)
            presenter = FableSolExportPresenter(
                context.assets,
                plan,
                clock,
                FableSolExportPresenter.shaderTransfer(tier.transfer),
                dither = tier.eightBit,
                whiteNits = whiteNits.toFloat()
            )
            bridge?.let { presenter.targetFramebufferId = it.presentFramebufferId }
            renderer = FableSolGlRenderer(context, plan.density)
            renderer.setOfflineTimebase(true)
            renderer.setScenePresenter(presenter)
            renderer.initialize(capability.linearSceneSupported)
            renderer.resize(plan.cardWidthPx, plan.cardHeightPx)
            // resize() 才会真正创建 FP16 scene targets。渲染器若静默退到 RGBA8，HLG 档必须
            // 失败并走 SDR 重渲染，不能把 8-bit SDR 场景标成 HDR 成功。
            check(!tier.hdr || renderer.isHdrContentEnabled()) {
                "FP16 scene targets are unavailable"
            }
            renderer.setThingBackground(request.waterBackground)
            renderer.setPresentationAlpha(1f)
            renderer.primeHdrForExport(
                if (tier.hdr) strength else FableSolHdrPolicy.STRENGTH_OFF
            )
            renderer.primeFrameTime(TIMEBASE_ORIGIN_NANOS)
            renderer.setOfflineFixedDt(1.0 / frameRate)

            // 用户关掉倾斜时连读都不读：与"这份录音本来就没有轨迹"走同一条竖直渲染路径，
            // 不需要第二种表达方式。
            val gravityTrack = if (options.tiltEnabled) {
                FableSolGravityTrack.readFrom(File(request.audioPath))
            } else {
                null
            }
            val gravity = FloatArray(3)
            val analyzer = FableSolRealtimeAnalyzer(
                sampleRate,
                FableSolCaptureProfile.PHONE_CAPTURE_V1
            )
            FableSolFrontEndTuning().also { tuning ->
                FableSolTuning.applyFrontEndStored(context, tuning)
                tuning.applyTo(analyzer)
            }
            analyzer.skipStartupGate()

            val pcm = ShortArray(ANALYZER_BATCH)
            val mono = DoubleArray(ANALYZER_BATCH)
            var samplesFed = 0L
            var audioExhausted = false
            var frameIndex = 0

            while (frameIndex < totalFrames) {
                if (listener.isCancelled()) return Result.Cancelled
                checkDynamicSpace(options, frameIndex)

                // 第 i 帧只消费 i/fps 之前的音频，不读取未来样本。
                val targetSamples = frameIndex.toLong() * sampleRate / frameRate
                while (!audioExhausted && samplesFed < targetSamples) {
                    val want = minOf(
                        ANALYZER_BATCH.toLong(), targetSamples - samplesFed
                    ).toInt()
                    val read = audio.read(pcm, want)
                    if (read <= 0) {
                        audioExhausted = true
                        break
                    }
                    for (index in 0 until read) {
                        mono[index] = pcm[index] / 32768.0
                    }
                    val (frames, events) = analyzer.feed(mono, read)
                    if (frames.isNotEmpty() || events.isNotEmpty()) {
                        renderer.onAudioFrames(frames, events)
                    }
                    encoder.feedAudio(pcm, read)
                    samplesFed += read
                }
                if (audioExhausted && samplesFed <= targetSamples) break

                if (gravityTrack != null) {
                    gravityTrack.sampleAt(frameIndex.toDouble() / frameRate, gravity)
                } else {
                    gravity[0] = 0f
                    gravity[1] = 1f
                    gravity[2] = 0f
                }
                renderer.setGravity(gravity[0], gravity[1], gravity[2])

                presenter.elapsedMs = frameIndex.toLong() * 1000L / frameRate
                renderer.render(
                    TIMEBASE_ORIGIN_NANOS +
                        (frameIndex + 1).toLong() * 1_000_000_000L / frameRate
                )
                val presentationTimeUs = frameIndex.toLong() * 1_000_000L / frameRate
                val activeBridge = bridge
                if (activeBridge == null) {
                    check(egl.swapBuffers(presentationTimeUs * 1_000L)) {
                        "eglSwapBuffers failed at frame $frameIndex"
                    }
                } else {
                    // 转换 + 逐帧统计都在 GPU 上；元数据必须在入队之前设好。
                    activeBridge.convert()
                    val stats = activeBridge.stats()
                    val payload = FableSolExportHdr10PlusMetadata.payload(
                        stats,
                        hdrCurve?.next(stats, 1.0 / frameRate)
                    )
                    encoder.queueVideoFrame(presentationTimeUs, payload) { buffer, stride, slice ->
                        activeBridge.writeInto(buffer, stride, slice)
                    }
                }
                // 输出格式验证与 muxer.addTrack 都在 drain 内；它们失败会清理并重试下一档。
                encoder.drain(endOfStream = false)
                if (
                    frameIndex >= frameRate * MUXER_START_DEADLINE_SECONDS &&
                    !encoder.hasStartedMuxer
                ) {
                    error("Encoder produced no usable track format")
                }

                frameIndex++
                if (frameIndex % PROGRESS_INTERVAL_FRAMES == 0) {
                    reportProgress(attemptStartedAt, frameIndex, totalFrames)
                }
            }

            if (listener.isCancelled()) return Result.Cancelled
            while (!audioExhausted) {
                val read = audio.read(pcm, ANALYZER_BATCH)
                if (read <= 0) {
                    audioExhausted = true
                    break
                }
                encoder.feedAudio(pcm, read)
                if (listener.isCancelled()) return Result.Cancelled
            }
            checkDynamicSpace(options, frameIndex, force = true)
            if (!encoder.finish(listener::isCancelled)) return Result.Cancelled
            if (listener.isCancelled()) return Result.Cancelled
            // **发布前的最后一道门**：探测通过不代表这一次真的写进去了。编码器若在运行中
            // 丢掉动态元数据，我们就会发布一个文件名与完成提示都标着 HDR10+、实际却是普通
            // Main10 的产物——那等于骗用户。这里抛出去，降级阶梯会换下一档（HDR10）重来。
            check(!byteBuffer || encoder.hdr10PlusSeiSeen) {
                "HDR10+ metadata never reached the bitstream"
            }
            // **同一道门的另一半：编码器有没有真的产出东西。** `INFO_OUTPUT_FORMAT_CHANGED`
            // 一来就能 addTrack 并启动 muxer，随后一个样本都没有，`finish()` 照样成功返回，
            // 产物是 0 字节。华为平板上每一档 HEVC 都这样（10 位输入表面拿不到带 recordable
            // 的 config），只有 8 位的 H.264 有数据（2026-07-28）。抛出去让阶梯换下一档。
            check(encoder.videoSamplesWritten > 0L) {
                "Encoder produced no video samples for ${tier.label}"
            }

            // finish() 已完成并验证 muxer.stop()/release()；现在才允许关闭 PFD、清 pending 或
            // 触发 MediaScanner。发布失败仍属于整次候选失败。
            val committed = request.sink.commit(listener::isCancelled)
            // 旧系统的 MediaScanner 回调最多会等待数秒；这期间若发生系统超时，仍删除刚发布
            // 的文件，让即时 Failed 终态与磁盘结果保持一致。
            if (listener.isCancelled()) return Result.Cancelled
            if (!committed) {
                throw PublishFailure("Failed to publish the exported video")
            }
            // 兜底：样本计数正常但落盘仍是空文件时，绝不能把它留在图库里。这一步失败也算
            // 本候选失败，阶梯会换下一档重来。
            if (request.sink.fileSizeBytes() <= 0L) {
                error("Published file is empty for ${tier.label}")
            }
            published = true
            reportProgress(attemptStartedAt, frameIndex, frameIndex)
            return Result.Success(
                request.sink,
                tier.displayLabel(context),
                tier.hdr,
                tier.hdrFormat?.displayName(context) ?: FableSolExportHdrFormat.SDR_LABEL,
                // 位深要写出来：10 位 HEVC 的分享兼容性明显差于 8 位。
                tier.family.stableLabel + if (tier.eightBit) " 8-bit" else " 10-bit",
                tier.softwareOnly,
                frameRate,
                frameIndex,
                SystemClock.elapsedRealtime() - exportStartedAt,
                // 只在真正生效时才带出去：HLG 系没有绝对锚点，非 HDR10+ 没有色调映射曲线。
                pqWhiteNits = if (tier.transfer == FableSolExportTransfer.PQ) whiteNits else 0.0,
                peakNits = if (tier.transfer == FableSolExportTransfer.PQ) peakNits else 0.0,
                highlightStartPercent =
                    if (tier.hdrFormat == FableSolExportHdrFormat.HDR10_PLUS) {
                        options.highlightStartPercent
                    } else {
                        0
                    }
            )
        } finally {
            renderer?.setScenePresenter(null)
            safely { presenter?.release() }
            safely { renderer?.release() }
            // bridge 的 GL 资源必须在 EGL 上下文还在的时候释放。
            safely { bridge?.release() }
            safely { clock?.release() }
            safely { egl?.release() }
            safely { encoder?.release() }
            safely { audio?.release() }
            if (!published) safely { request.sink.discard() }
        }
    }

    private fun readAudioMetadata(): AudioMetadata {
        var source: FableSolExportAudioSource? = null
        return try {
            source = FableSolExportAudioSource(request.audioPath)
            AudioMetadata(source.durationUs.coerceAtLeast(0L))
        } finally {
            source?.release()
        }
    }

    private fun checkInitialSpace(
        options: FableSolExportOptions,
        frameRate: Int,
        durationSeconds: Double
    ): Result.OutOfSpace? {
        if (options.constantQuality || durationSeconds <= 0.0) {
            return if (
                FableSolExportSink.hasMinimumFreeSpace(MIN_FREE_SPACE_BYTES)
            ) {
                null
            } else {
                Result.OutOfSpace(MIN_FREE_SPACE_BYTES)
            }
        }
        val combinedBitrate = options.bitrateBps(frameRate).toLong() +
            AUDIO_BITRATE_BPS
        val estimated = (durationSeconds * combinedBitrate / 8.0).toLong() +
            ESTIMATE_HEADROOM_BYTES
        return if (FableSolExportSink.hasRoomFor(estimated)) {
            null
        } else {
            Result.OutOfSpace(estimated)
        }
    }

    private fun checkDynamicSpace(
        options: FableSolExportOptions,
        frameIndex: Int,
        force: Boolean = false
    ) {
        if (!options.constantQuality) return
        if (!force && frameIndex % SPACE_CHECK_INTERVAL_FRAMES != 0) return
        if (!FableSolExportSink.hasMinimumFreeSpace(MIN_FREE_SPACE_BYTES)) {
            throw OutOfSpaceFailure(MIN_FREE_SPACE_BYTES)
        }
    }

    private fun reportProgress(startedAt: Long, done: Int, total: Int) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val eta = if (done > 0 && total > 0 && total != Int.MAX_VALUE) {
            ((elapsed.toDouble() / done) * (total - done)).roundToInt().toLong()
        } else {
            -1L
        }
        listener.onProgress(done, total, eta)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
        }
    }

    private data class AudioMetadata(val durationUs: Long)

    private class OutOfSpaceFailure(val requiredBytes: Long) : RuntimeException()
    private class PublishFailure(message: String) : RuntimeException(message)

    private companion object {
        const val ANALYZER_BATCH = 512
        const val TIMEBASE_ORIGIN_NANOS = 1_000_000_000L
        const val PROGRESS_INTERVAL_FRAMES = 30
        const val SPACE_CHECK_INTERVAL_FRAMES = 30
        const val MUXER_START_DEADLINE_SECONDS = 2
        const val AUDIO_BITRATE_BPS = 192_000L
        const val ESTIMATE_HEADROOM_BYTES = 4L * 1024L * 1024L
        const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
    }
}
