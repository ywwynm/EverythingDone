package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.media.MediaMuxer
import android.opengl.GLES30
import android.os.Build
import com.ywwynm.everythingdone.BuildConfig
import java.io.File

/**
 * HDR 导出能力的实际探测。
 *
 * 不能只相信 codec 广告的 Main10 profile：厂商实现可能到 configure、10-bit HLG
 * EGL window surface、start 或输出格式阶段才拒绝。这里复用正式导出的编码器、EGL 和
 * 输出格式校验，编码一帧黑场与一小段静音；完整成功才允许设置页打开 HDR 开关。
 *
 * 结果按探测实现、App 版本和系统 build 缓存。成功结果随该签名长期有效；失败结果缓存
 * 24 小时，避免临时的 codec 资源占用既造成每次开 Dialog 都重试，又永久误判设备不支持。
 */
internal object FableSolHdrExportCapability {

    @Volatile
    private var processCache: CachedResult? = null

    /**
     * 只读进程内结果，不触发 SharedPreferences 磁盘读取。设置页可据此立即恢复本进程已经
     * 得出的状态；首次进程启动后的持久化读取仍放在后台线程。
     */
    fun peekCachedResult(): Boolean? {
        val cached = processCache ?: return null
        return cached.takeIf { it.isValid(cacheSignature(), System.currentTimeMillis()) }?.supported
    }

    /**
     * 同一进程内串行解析，避免 Dialog 重建时两组硬件编码器互相抢占而产生假阴性。
     * 先读进程/持久化缓存；只有缓存缺失、签名失效或失败结果过期时才真正编码。
     */
    @Synchronized
    fun probe(context: Context): Boolean {
        val now = System.currentTimeMillis()
        processCache
            ?.takeIf { it.isValid(cacheSignature(), now) }
            ?.let { return it.supported }
        readPersisted(context)
            ?.takeIf { it.isValid(cacheSignature(), now) }
            ?.let {
                processCache = it
                return it.supported
            }

        val supported = try {
            probeInternal(context)
        } catch (ignored: Throwable) {
            false
        }
        val resolved = CachedResult(
            signature = cacheSignature(),
            supported = supported,
            checkedAtMs = now
        )
        processCache = resolved
        persist(context, resolved)
        return supported
    }

    private fun probeInternal(context: Context): Boolean {
        val eglCapability = FableSolExportEgl.probe()
        if (!eglCapability.linearSceneSupported || !eglCapability.bt2020HlgSupported) {
            return false
        }

        // 能力结论不能随用户当前 CQ/码率偏好漂移。固定用正式默认 CBR 参数，分别尝试
        // 120/60fps；正式导出仍会按用户设置自行走完整降级阶梯。
        val options = FableSolExportOptions(
            frameRateCap = FableSolExportOptions.FRAME_RATE_HIGH,
            constantQuality = false,
            qualityValue = FableSolExportOptions.UNSET_QUALITY,
            bitrateMbps = FableSolExportOptions.DEFAULT_BITRATE_MBPS,
            keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS,
            hdrEnabled = true
        )
        val basePlan = FableSolExportSpec.plan(
            context,
            FableSolExportSpec.MAX_CARD_WIDTH_DP
        )
        val hdrAttempts = FableSolExportAttemptPlan.ordered(
            wantHdr = true,
            requestedFrameRate = FableSolExportOptions.FRAME_RATE_HIGH
        ).takeWhile { it.hdr }

        for (attempt in hdrAttempts) {
            val candidates = FableSolExportTier.candidatesForMode(
                hdr = true,
                widthPx = basePlan.canvasWidthPx,
                heightPx = basePlan.canvasHeightPx,
                frameRate = attempt.frameRate,
                preferConstantQuality = options.constantQuality
            )
            for (tier in candidates) {
                if (probeCandidate(context, options, attempt.frameRate, tier)) {
                    return true
                }
            }
        }
        return false
    }

    private fun probeCandidate(
        context: Context,
        options: FableSolExportOptions,
        frameRate: Int,
        tier: FableSolExportTier
    ): Boolean {
        val temporary = try {
            File.createTempFile("fablesol-hdr-probe-", ".mp4", context.cacheDir)
        } catch (ignored: Throwable) {
            return false
        }
        var encoder: FableSolExportEncoder? = null
        var egl: FableSolExportEgl? = null
        return try {
            val muxer = MediaMuxer(
                temporary.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            encoder = FableSolExportEncoder(
                widthPx = tier.encodedWidthPx,
                heightPx = tier.encodedHeightPx,
                frameRate = frameRate,
                tier = tier,
                options = options,
                audioSampleRate = PROBE_AUDIO_SAMPLE_RATE,
                muxer = muxer
            )
            egl = FableSolExportEgl(
                encoder.inputSurface,
                hdr = true,
                tenBit = true
            )
            encoder.start()

            // 能力探测只验证 HDR 编码链，不编译整套水体 shader 或分配完整场景缓冲；
            // FP16 扩展已经由 FableSolExportEgl.probe() 门控，正式导出仍会验证实际 scene targets。
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, tier.encodedWidthPx, tier.encodedHeightPx)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            check(egl.swapBuffers(0L)) { "HDR probe eglSwapBuffers failed" }

            val silence = ShortArray(PROBE_AUDIO_SAMPLES)
            encoder.feedAudio(silence, silence.size)
            encoder.finish(timeoutMs = PROBE_TIMEOUT_MS)
        } catch (ignored: Throwable) {
            false
        } finally {
            try {
                egl?.release()
            } catch (ignored: Throwable) {
            }
            try {
                encoder?.release()
            } catch (ignored: Throwable) {
            }
            temporary.delete()
        }
    }

    private fun readPersisted(context: Context): CachedResult? {
        return try {
            val preferences = context.applicationContext.getSharedPreferences(
                CACHE_PREFERENCES,
                Context.MODE_PRIVATE
            )
            val signature = preferences.getString(KEY_SIGNATURE, null)
            if (!preferences.contains(KEY_SUPPORTED) || signature == null) {
                null
            } else {
                CachedResult(
                    signature = signature,
                    supported = preferences.getBoolean(KEY_SUPPORTED, false),
                    checkedAtMs = preferences.getLong(KEY_CHECKED_AT_MS, 0L)
                )
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun persist(context: Context, result: CachedResult) {
        try {
            context.applicationContext.getSharedPreferences(
                CACHE_PREFERENCES,
                Context.MODE_PRIVATE
            ).edit()
                .putString(KEY_SIGNATURE, result.signature)
                .putBoolean(KEY_SUPPORTED, result.supported)
                .putLong(KEY_CHECKED_AT_MS, result.checkedAtMs)
                .apply()
        } catch (ignored: Throwable) {
            // 缓存写入失败不改变本次实际探测结论；本进程仍会复用 processCache。
        }
    }

    private fun cacheSignature(): String =
        "$PROBE_CONTRACT_VERSION|${BuildConfig.VERSION_CODE}|${Build.VERSION.SDK_INT}|" +
            Build.FINGERPRINT

    private data class CachedResult(
        val signature: String,
        val supported: Boolean,
        val checkedAtMs: Long
    ) {
        fun isValid(expectedSignature: String, nowMs: Long): Boolean {
            if (signature != expectedSignature) return false
            if (supported) return true
            val ageMs = nowMs - checkedAtMs
            return ageMs in 0..NEGATIVE_RESULT_TTL_MS
        }
    }

    private const val PROBE_AUDIO_SAMPLE_RATE = 48_000
    private const val PROBE_AUDIO_SAMPLES = 2048
    private const val PROBE_TIMEOUT_MS = 3_000L
    private const val PROBE_CONTRACT_VERSION = 1
    private const val NEGATIVE_RESULT_TTL_MS = 24L * 60L * 60L * 1_000L

    private const val CACHE_PREFERENCES = "fablesol_hdr_export_capability"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_SUPPORTED = "supported"
    private const val KEY_CHECKED_AT_MS = "checked_at_ms"
}
