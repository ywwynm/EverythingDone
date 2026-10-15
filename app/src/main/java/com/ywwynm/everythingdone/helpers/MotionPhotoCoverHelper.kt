package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.content.SharedPreferences
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * 把 **Motion Photo**(动态照片)的内嵌视频接进既有的"视频→派生 GIF"管线
 * ([VideoCoverPreviewManager]),从而在 Thing Card 封面与详情附件列表上播放派生 GIF。见 ADR-0014。
 *
 * 做法:检测内嵌视频区间 → 把它裸字节抠到一个**确定性缓存文件**(按图片身份+偏移定名) → 交给
 * [VideoCoverPreviewManager] 当作普通视频派生 GIF。抠出文件的 mtime 设为**源图 mtime**,使派生
 * GIF 的缓存 key 跨"抠出文件被 LRU 淘汰后重抠"保持稳定(GIF 不会因此重复生成)。
 *
 * 这样 [VideoCoverPreviewManager] 与封面/详情的动图加载路径都无需改动,只在"源是 Motion Photo"
 * 时多接一条分支。抠出的 MP4 缓存有独立 LRU 上限;派生 GIF 仍走 VideoCoverPreviewManager 的 1GB LRU。
 */
object MotionPhotoCoverHelper {

    private const val TAG = "MotionPhotoCoverHelper"
    private const val DIR_NAME = "motion-photo-videos"
    private const val CACHE_MAX_BYTES = 512L * 1024L * 1024L  // 抠出 MP4 的 LRU 上限
    private const val STALE_TMP_MS = 10L * 60L * 1000L

    // 单线程:检测(整文件扫描)与抠出串行化、低优先级,不与 UI 争资源。
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MotionPhotoExtract").apply { priority = Thread.MIN_PRIORITY }
    }

    private fun dir(context: Context): File {
        val app = context.applicationContext
        val base = app.externalCacheDir ?: app.cacheDir
        val d = File(base, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun extractedFileFor(
        context: Context, imagePath: String, info: MotionPhotoDetector.MotionPhotoInfo
    ): File {
        val f = File(imagePath)
        val key = Integer.toHexString(imagePath.hashCode()) +
                "_" + f.length() + "_" + f.lastModified() + "_" + info.videoOffset
        return File(dir(context), "$key.mp4")
    }

    /**
     * 已就绪的派生 GIF(内嵌视频已抠出且 GIF 已生成),否则 null。**主线程可调**:只查检测缓存 +
     * 文件存在性,不扫描、不生成。
     */
    @JvmStatic
    fun getReadyGif(context: Context, imagePath: String): File? {
        val info = MotionPhotoDetector.peekCached(imagePath) ?: return null
        if (!info.isMotionPhoto) return null
        val extracted = extractedFileFor(context, imagePath, info)
        if (!extracted.exists() || extracted.length() <= 0L) return null
        return VideoCoverPreviewManager.getReadyPreview(context, extracted.absolutePath, null)
    }

    /**
     * 后台:检测 → 抠出内嵌视频(必要时) → 交 [VideoCoverPreviewManager] 派生 GIF;就绪后由该管理器
     * 在主线程回调 [onReady]。非 Motion Photo 静默返回。
     */
    @JvmStatic
    fun requestGif(context: Context, imagePath: String, onReady: (File) -> Unit) {
        val appContext = context.applicationContext
        ioExecutor.submit {
            val extracted = prepareVideo(appContext, imagePath, false) {} ?: return@submit
            VideoCoverPreviewManager.requestPreview(appContext, extracted.absolutePath, null, onReady)
        }
    }

    /** 普通图片检测期间保持数量；确认动态照片后才显示准备状态。 */
    fun observeGeneration(
        context: Context,
        imagePath: String,
        retry: Boolean,
        onState: (CoverGenerationState) -> Unit,
        onReady: (File) -> Unit
    ): Closeable {
        val app = context.applicationContext
        val subscription = CoverGenerationSubscription()
        val failures = app.getSharedPreferences("motion_cover_preparation_failures", Context.MODE_PRIVATE)
        val source = File(imagePath)
        val failurePrefix = Integer.toHexString(imagePath.hashCode()) +
            "_" + source.length() + "_" + source.lastModified() + "_"
        var preparationFailed = false
        var generation: Closeable? = null
        if (MotionPhotoDetector.peekCached(imagePath)?.isMotionPhoto == true) {
            onState(CoverGenerationState.WAITING)
        }
        fun prepare(requestRetry: Boolean) {
            ioExecutor.execute {
                val video = prepareVideo(app, imagePath, requestRetry) { state ->
                    subscription.dispatch {
                        preparationFailed = state.phase == CoverGenerationState.Phase.FAILED
                        onState(state)
                    }
                } ?: return@execute
                // 卡片离屏或切换来源也不取消已开始的准备／生成。
                VideoCoverPreviewManager.ensurePreview(app, video.absolutePath, requestRetry)
                subscription.dispatch {
                    preparationFailed = false
                    generation?.close()
                    generation = VideoCoverPreviewManager.observePreview(
                        app, video.absolutePath, null, requestRetry, onState, onReady)
                }
            }
        }
        // 一个来源可同时出现在多张卡片；其它卡片发起重试后，准备失败的卡片也要跟进新任务。
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (preparationFailed && key?.startsWith(failurePrefix) == true && !prefs.getBoolean(key, false)) {
                preparationFailed = false
                prepare(false)
            }
        }
        failures.registerOnSharedPreferenceChangeListener(listener)
        subscription.onClose {
            failures.unregisterOnSharedPreferenceChangeListener(listener)
            generation?.close()
        }
        prepare(retry)
        return subscription
    }

    private fun prepareVideo(
        context: Context, imagePath: String, retry: Boolean,
        onState: (CoverGenerationState) -> Unit
    ): File? {
        val info = MotionPhotoDetector.detect(imagePath)
        if (!info.isMotionPhoto) {
            onState(CoverGenerationState.IDLE)
            return null
        }
        val extracted = extractedFileFor(context, imagePath, info)
        val failures = context.getSharedPreferences("motion_cover_preparation_failures", Context.MODE_PRIVATE)
        if (retry) failures.edit().remove(extracted.name).apply()
        if (extracted.exists() && extracted.length() > 0L) return extracted
        if (failures.getBoolean(extracted.name, false)) {
            onState(CoverGenerationState.FAILED)
            return null
        }
        onState(CoverGenerationState.PREPARING)
        enforceLruCap(dir(context))
        val tmp = File(extracted.absolutePath + ".tmp")
        if (tmp.exists()) tmp.delete()
        val ok = MotionPhotoDetector.extractEmbeddedVideo(
            imagePath, info.videoOffset, info.videoLength, tmp)
        if (ok) {
            tmp.setLastModified(File(imagePath).lastModified())
            if (extracted.exists()) extracted.delete()
            if (tmp.renameTo(extracted)) return extracted
        }
        tmp.delete()
        failures.edit().putBoolean(extracted.name, true).apply()
        onState(CoverGenerationState.FAILED)
        return null
    }

    private fun enforceLruCap(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4.tmp") }?.forEach { f ->
            if (now - f.lastModified() > STALE_TMP_MS) f.delete()
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= CACHE_MAX_BYTES) return
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
