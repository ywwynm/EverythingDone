package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.waynejo.androidndkgif.GifEncoder
import com.ywwynm.everythingdone.services.VideoCoverPreviewWorker
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 从视频派生一个循环 GIF 预览（Thing Card Video Preview），供 Thing Card 封面在
 * 开启 Cover Autoplay 时逐帧播放。派生 GIF 复用既有的 Animated Playback 管线
 * （GifDrawable + MediaCropTransformation + Glide 生命周期）。见 ADR-0012 与
 * docs/features/animated-video-cover/。
 *
 * 设计要点：
 * - 以 Thing Card Video Frame（videoFrameMs）为循环起点，向后 [DURATION_MS] 毫秒、
 *   [FPS] 帧率、长边 [MAX_LONG_EDGE]、无限循环。
 * - Lazy：首次需要时后台生成；持久化到外部缓存，每个封面只生成一次。
 * - 缓存 key 不含裁切——裁切在显示期由 MediaCropTransformation 逐帧套用。
 * - 生成在 WorkManager 唯一工作里跑：持久化、扛进程被杀、可重试；按"视频+帧"定键、KEEP 去重，
 *   封面中途从视频改成别的图片也不取消、跑完留作备用。
 *
 * 存储位置：优先用 externalCacheDir（Android/data/<pkg>/cache/video-cover-previews/，
 * 真机可浏览、便于排查），不可用时退回内部 cacheDir。两者都是缓存语义。
 *
 * 诊断日志：写到 files/debug_logs/[LOG_FILE]，记录分支命中与生成成功/失败原因。
 */
object VideoCoverPreviewManager {

    // —— 可后调的内部 tuning 常量 ——
    // 原生 NDK 编码很快、瓶颈转为取帧(~90ms/帧)，故总帧数是生成耗时主因；用户确认通畅后提画质。
    private const val DURATION_MS = 3000L                   // 单次循环时长 3s
    private const val FRAME_DELAY_MS = 40L                  // 25fps = 4 厘秒，GIF 厘秒延迟整除干净
    private const val MAX_LONG_EDGE = 720
    private const val CACHE_MAX_BYTES = 1024L * 1024L * 1024L   // 1GB LRU 上限
    private const val DIR_NAME = "video-cover-previews"
    private const val LOG_FILE = "video-cover-preview.log"
    /** 孤儿 .gif.tmp 的清理年龄阈值：远大于一次生成耗时(~10–30s)，确保不会误删正在写入的 tmp。 */
    private const val STALE_TMP_MS = 10L * 60L * 1000L
    /** 生成参数/逻辑变化时 +1，使旧缓存 key 失效。 */
    private const val VERSION = 10

    // 生成迁到 WorkManager（见 services.VideoCoverPreviewWorker）；本执行器只用于轻量清理 IO。
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoCoverPreviewIO").apply { priority = Thread.MIN_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** key -> 该 key 生成完成后的"即时换装"回调（主线程触发，仅进程内有效）。 */
    private val callbacks = HashMap<String, MutableList<(File) -> Unit>>()

    /** 诊断日志去重，避免滚动期重复 bind 刷屏。 */
    private val loggedOnce = Collections.synchronizedSet(HashSet<String>())

    /** 已加载失败过的派生 GIF（损坏/不可解码）key：每 key 会话内只删一次，自愈式重生成一次，
     *  之后不再删，避免坏编码器输出导致"删→重生成→又坏"死循环。 */
    private val badPreviewKeys = Collections.synchronizedSet(HashSet<String>())

    /** 诊断日志总开关：默认关闭；排查时改 true 即可全部恢复（写入 files/debug_logs/[LOG_FILE]）。 */
    private const val DEBUG_LOG = false

    /** 受 [DEBUG_LOG] 门控的诊断日志；Worker 等同进程组件也走它，统一一处开关。 */
    fun debugLog(msg: String) {
        if (DEBUG_LOG) DebugFileLogger.log(LOG_FILE, msg)
    }

    private fun videoPrefix(videoPath: String): String =
        Integer.toHexString(videoPath.hashCode())

    private fun cacheKey(videoPath: String, sizeBytes: Long, lastModified: Long, frameMs: Long): String =
        "${videoPrefix(videoPath)}_${sizeBytes}_${lastModified}_${frameMs}_v$VERSION"

    private fun previewDir(context: Context): File {
        val app = context.applicationContext
        val base = app.externalCacheDir ?: app.cacheDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun previewFileFor(context: Context, videoPath: String, videoFrameMs: Long?): File? {
        val f = File(videoPath)
        if (!f.exists()) return null
        val key = cacheKey(videoPath, f.length(), f.lastModified(), videoFrameMs ?: 0L)
        return File(previewDir(context), "$key.gif")
    }

    /** 已就绪的派生预览文件，未生成/无效则返回 null。可在主线程调用（仅做文件存在性判断）。 */
    fun getReadyPreview(context: Context, videoPath: String, videoFrameMs: Long?): File? {
        val out = previewFileFor(context, videoPath, videoFrameMs) ?: return null
        return if (out.exists() && out.length() > 0L) out else null
    }

    /** 诊断：记录一次视频封面绑定的判定输入（按 key 去重，仅记一次）。 */
    fun logBindDecision(
        context: Context,
        videoPath: String,
        videoFrameMs: Long?,
        autoplayEnabled: Boolean,
        ready: Boolean
    ) {
        if (!DEBUG_LOG) return   // UI 绑定热路径：日志关闭时彻底零开销（不做磁盘 stat）
        val name = File(videoPath).name
        val exists = File(videoPath).exists()
        val out = previewFileFor(context, videoPath, videoFrameMs)
        if (!loggedOnce.add("bind:${out?.name ?: videoPath}")) return
        debugLog(
            "BIND name=$name frameMs=$videoFrameMs autoplay=$autoplayEnabled " +
                    "videoExists=$exists ready=$ready out=${out?.name} dir=${previewDir(context).absolutePath}"
        )
    }

    /**
     * 请求生成派生预览。若已就绪立即在主线程回调；否则登记"即时换装"回调，并入队一个唯一的
     * WorkManager 工作（按 key 去重、持久化、扛进程被杀）。生成完成由 Worker 调 [notifyGenerated]。
     */
    fun requestPreview(
        context: Context,
        videoPath: String,
        videoFrameMs: Long?,
        onReady: (File) -> Unit
    ) {
        val appContext = context.applicationContext
        val out = previewFileFor(appContext, videoPath, videoFrameMs) ?: run {
            debugLog("REQUEST skip (video missing) path=$videoPath")
            return
        }
        if (out.exists() && out.length() > 0L) {
            mainHandler.post { onReady(out) }
            return
        }
        val key = out.name
        synchronized(callbacks) {
            callbacks.getOrPut(key) { mutableListOf() }.add(onReady)
        }
        enqueueGeneration(appContext, videoPath, videoFrameMs, key)
    }

    private fun enqueueGeneration(
        appContext: Context,
        videoPath: String,
        videoFrameMs: Long?,
        key: String
    ) {
        val data = Data.Builder()
            .putString(VideoCoverPreviewWorker.KEY_VIDEO_PATH, videoPath)
            .putLong(VideoCoverPreviewWorker.KEY_VIDEO_FRAME_MS, videoFrameMs ?: -1L)
            .putString(VideoCoverPreviewWorker.KEY_OUTPUT_NAME, key)
            .build()
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequest.Builder(VideoCoverPreviewWorker::class.java)
            .setInputData(data)
            .addTag(VideoCoverPreviewWorker.TAG)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request)
        debugLog("ENQUEUE name=${File(videoPath).name} frameMs=$videoFrameMs work=$key")
    }

    /** 由 Worker 在后台线程调用：阻塞生成。已就绪直接返回；否则生成并执行 LRU 上限。 */
    fun generateBlocking(context: Context, videoPath: String, videoFrameMs: Long?): File? {
        val out = previewFileFor(context, videoPath, videoFrameMs) ?: return null
        if (out.exists() && out.length() > 0L) return out
        enforceLruCap(previewDir(context))   // 生成前先腾空间（目录超限/设备低存储兜底）
        val result = generate(videoPath, videoFrameMs, out)
        if (result != null) enforceLruCap(previewDir(context))
        return result
    }

    /** 由 Worker 在生成成功后调用：触发该 key 登记的即时换装回调（仅进程内有效）。 */
    fun notifyGenerated(key: String, file: File) {
        val cbs = synchronized(callbacks) { callbacks.remove(key) } ?: return
        if (cbs.isEmpty()) return
        mainHandler.post { cbs.forEach { it(file) } }
    }

    /**
     * 由 Worker 在终态失败（视频已删 / 重试用尽）时调用：丢弃该 key 登记的回调而**不触发**
     * （UI 保持静态 Thing Card Video Frame 回退），防止 ImageView/holder 闭包泄漏。
     */
    fun notifyGenerationFailed(key: String) {
        val cbs = synchronized(callbacks) { callbacks.remove(key) }
        if (!cbs.isNullOrEmpty()) {
            debugLog("GEN failed-final, dropped ${cbs.size} callbacks key=$key")
        }
    }

    /**
     * 派生 GIF 文件存在却被 Glide 加载失败（损坏/不可解码）时由加载方调用：每个 key 在本会话内
     * 只删一次该坏文件，让它有一次自愈式重生成机会；之后不再删，避免坏编码器输出造成死循环。
     * 显示侧应同时回退到视频静态帧（见 BaseThingsAdapter 的 `.error()` 回退）。
     */
    fun onPreviewLoadFailed(file: File) {
        if (badPreviewKeys.add(file.name)) {
            debugLog("PREVIEW load-failed, delete for one self-heal: ${file.name}")
            runCatching { file.delete() }
        }
    }

    /** 删除某视频的所有派生预览（任意帧/版本）。用于附件删除 / 改封面来源后的即时清理。 */
    fun deletePreviewsForVideo(context: Context, videoPath: String) {
        val appContext = context.applicationContext
        ioExecutor.submit {
            val prefix = videoPrefix(videoPath) + "_"
            previewDir(appContext).listFiles { f -> f.isFile && f.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }
    }

    // —— 生成 ——

    private fun generate(videoPath: String, videoFrameMs: Long?, out: File): File? {
        // 只清理本 key 自己的残留 tmp（同 key 由 WorkManager KEEP 保证唯一、不并发，安全）。
        // 绝不扫整目录删别的 .gif.tmp——并发的其它 Worker 可能正在写它们的 tmp。
        // 孤儿 tmp 由 enforceLruCap 按年龄清理（见 STALE_TMP_MS）。
        val tmp = File(out.absolutePath + ".tmp")
        if (tmp.exists()) tmp.delete()
        val retriever = MediaMetadataRetriever()
        var encoder: GifEncoder? = null
        var needClose = false
        var framesEncoded = 0
        var decodeMs = 0L
        var encodeMs = 0L
        var targetW = 0
        var targetH = 0
        val startedAt = SystemClock.elapsedRealtime()
        try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                debugLog("GEN abort name=${File(videoPath).name} reason=duration<=0 ($durationMs)")
                return null
            }

            // 从用户选定的 Thing Card Video Frame 开始截取；第 0 帧恒等于该帧、不前移，
            // 保证"静态帧 → 动画"无跳变。末尾不足设定时长就短循环（不凑满、不绕回）。
            var startMs = (videoFrameMs ?: 0L).coerceAtLeast(0L)
            if (startMs >= durationMs) startMs = 0L
            val windowMs = min(DURATION_MS, durationMs - startMs)
            val frameCount = max(1, (windowMs / FRAME_DELAY_MS).toInt())
            debugLog(
                "GEN start name=${File(videoPath).name} durationMs=$durationMs startMs=$startMs frameCount=$frameCount longEdge=$MAX_LONG_EDGE"
            )

            for (i in 0 until frameCount) {
                val tUs = (startMs + i * FRAME_DELAY_MS) * 1000L
                debugLog("f$i decoding tMs=${tUs / 1000}")
                val d0 = SystemClock.elapsedRealtime()
                val frame = decodeFrame(retriever, tUs)
                val dt = SystemClock.elapsedRealtime() - d0
                decodeMs += dt
                if (frame == null) {
                    debugLog("f$i decoded dt=${dt}ms NULL")
                    continue
                }
                debugLog("f$i decoded dt=${dt}ms ${frame.width}x${frame.height}")

                val scaled = scaleToLongEdge(frame, MAX_LONG_EDGE)
                if (encoder == null) {
                    targetW = scaled.width
                    targetH = scaled.height
                    encoder = GifEncoder()
                    encoder!!.init(
                        targetW, targetH, tmp.absolutePath,
                        GifEncoder.EncodingType.ENCODING_TYPE_STABLE_HIGH_MEMORY
                    )
                    needClose = true
                    debugLog("encoder init ${targetW}x$targetH file=${tmp.name}")
                }
                // 原生编码器要求 ARGB_8888，且每帧尺寸与 init 一致。
                val sized = if (scaled.width == targetW && scaled.height == targetH) {
                    scaled
                } else {
                    Bitmap.createScaledBitmap(scaled, targetW, targetH, true)
                }
                val argb = if (sized.config == Bitmap.Config.ARGB_8888) {
                    sized
                } else {
                    sized.copy(Bitmap.Config.ARGB_8888, false)
                }

                val e0 = SystemClock.elapsedRealtime()
                encoder!!.encodeFrame(argb, FRAME_DELAY_MS.toInt())
                val et = SystemClock.elapsedRealtime() - e0
                encodeMs += et
                framesEncoded++

                if (argb !== sized) argb.recycle()
                if (sized !== scaled) sized.recycle()
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                debugLog("f$i encoded et=${et}ms cum d=$decodeMs e=$encodeMs")
            }
            if (needClose) {
                encoder?.close()
                needClose = false
            }

            if (framesEncoded == 0 || !tmp.exists() || tmp.length() == 0L) {
                debugLog(
                    "GEN abort name=${File(videoPath).name} reason=no-frames encoded=$framesEncoded decodeMs=$decodeMs"
                )
                tmp.delete()
                return null
            }
            if (out.exists()) out.delete()
            return if (tmp.renameTo(out)) {
                debugLog(
                    "GEN done name=${File(videoPath).name} frames=$framesEncoded ${targetW}x$targetH " +
                            "totalMs=${SystemClock.elapsedRealtime() - startedAt} decodeMs=$decodeMs encodeMs=$encodeMs bytes=${out.length()}"
                )
                out
            } else {
                debugLog("GEN abort name=${File(videoPath).name} reason=rename-failed")
                tmp.delete(); null
            }
        } catch (t: Throwable) {
            debugLog("GEN exception name=${File(videoPath).name} err=${t.javaClass.simpleName}:${t.message}")
            if (needClose) {
                try { encoder?.close() } catch (_: Throwable) {}
            }
            tmp.delete()
            return null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeFrame(retriever: MediaMetadataRetriever, tUs: Long): Bitmap? {
        // 用最兼容的 getFrameAtTime（自己降采样）。getScaledFrameAtTime 在部分机型上会卡死
        // 且不返回 null，导致 fallback 不触发，故弃用。见 docs/features/animated-video-cover。
        return try {
            retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleToLongEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val ratio = maxEdge.toFloat() / longEdge
        val nw = max(1, (bmp.width * ratio).roundToInt())
        val nh = max(1, (bmp.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    private fun enforceLruCap(dir: File) {
        // 顺带清理孤儿 .gif.tmp（崩溃/被杀留下的）：只删足够旧的，绝不碰近期/正在写入的 tmp。
        val now = System.currentTimeMillis()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gif.tmp") }?.forEach { f ->
            if (now - f.lastModified() > STALE_TMP_MS) f.delete()
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gif") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= CACHE_MAX_BYTES) return
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
