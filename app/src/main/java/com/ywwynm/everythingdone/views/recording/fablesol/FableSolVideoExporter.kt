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
    private val context: Context,
    private val request: Request,
    private val listener: Listener
) {

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
            val frameRate: Int,
            val frames: Int,
            val elapsedMs: Long
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
            val wantHdr = options.hdrEnabled &&
                strength > FableSolHdrPolicy.STRENGTH_OFF &&
                capability.linearSceneSupported &&
                capability.bt2020HlgSupported
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
            val attempts = FableSolExportAttemptPlan.ordered(
                wantHdr = wantHdr,
                requestedFrameRate = requestedFrameRate
            )
            for (attempt in attempts) {
                val candidates = FableSolExportTier.candidatesForMode(
                    hdr = attempt.hdr,
                    basePlan.canvasWidthPx,
                    basePlan.canvasHeightPx,
                    attempt.frameRate,
                    preferConstantQuality = options.constantQuality
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
                        lastFailure =
                            "${tier.label}: ${error.message ?: error.javaClass.simpleName}"
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
            Result.Failure(error.message ?: error.javaClass.simpleName)
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

            encoder = FableSolExportEncoder(
                plan.canvasWidthPx,
                plan.canvasHeightPx,
                frameRate,
                tier,
                options,
                sampleRate,
                request.sink.createMuxer()
            )
            egl = FableSolExportEgl(
                encoder.inputSurface,
                hdr = tier.hdr,
                tenBit = !tier.eightBit
            )
            encoder.start()

            clock = FableSolExportClock(context, plan, request.accentBackground)
            presenter = FableSolExportPresenter(
                context.assets,
                plan,
                clock,
                if (tier.hdr) {
                    FableSolExportPresenter.TRANSFER_HLG
                } else {
                    FableSolExportPresenter.TRANSFER_BT709
                },
                dither = tier.eightBit
            )
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

            val gravityTrack = FableSolGravityTrack.readFrom(File(request.audioPath))
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
                check(
                    egl.swapBuffers(
                        frameIndex.toLong() * 1_000_000_000L / frameRate
                    )
                ) {
                    "eglSwapBuffers failed at frame $frameIndex"
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

            // finish() 已完成并验证 muxer.stop()/release()；现在才允许关闭 PFD、清 pending 或
            // 触发 MediaScanner。发布失败仍属于整次候选失败。
            val committed = request.sink.commit(listener::isCancelled)
            // 旧系统的 MediaScanner 回调最多会等待数秒；这期间若发生系统超时，仍删除刚发布
            // 的文件，让即时 Failed 终态与磁盘结果保持一致。
            if (listener.isCancelled()) return Result.Cancelled
            if (!committed) {
                throw PublishFailure("Failed to publish the exported video")
            }
            published = true
            reportProgress(attemptStartedAt, frameIndex, frameIndex)
            return Result.Success(
                request.sink,
                tier.label,
                tier.hdr,
                frameRate,
                frameIndex,
                SystemClock.elapsedRealtime() - exportStartedAt
            )
        } finally {
            renderer?.setScenePresenter(null)
            safely { presenter?.release() }
            safely { renderer?.release() }
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
