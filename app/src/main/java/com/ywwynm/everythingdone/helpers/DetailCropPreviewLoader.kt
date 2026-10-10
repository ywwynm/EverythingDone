package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 单个详情页的裁剪预览。只缓存最近一个来源，解码不占用主线程。 */
class DetailCropPreviewLoader : AutoCloseable {
    data class Preview(val typePathName: String, val frameMs: Long, val bitmap: Bitmap, val durationMs: Long)
    data class Metadata(val typePathName: String, val width: Int, val height: Int, val durationMs: Long)
    private data class Key(val source: String, val frameMs: Long, val length: Long, val modified: Long)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    // 旧请求的原生解码不能立即取消，不能让下一次打开的尺寸读取排在它后面。
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    private var request: Any? = null
    private var work: Future<*>? = null
    private var metadataWork: Future<*>? = null
    private var cachedMetadata: Pair<Key, Metadata>? = null
    private var cached: Pair<Key, Preview>? = null
    private var closed = false

    /** 尺寸读取已校验过文件状态时，可直接交付缓存，避免再次打开还闪过加载占位。 */
    fun preparedPreview(source: String, frameMs: Long): Preview? {
        val metadataKey = cachedMetadata?.first ?: return null
        return cached?.takeIf {
            it.first == metadataKey.copy(frameMs = frameMs) && it.first.source == source && !it.second.bitmap.isRecycled
        }?.second
    }

    fun loadMetadata(source: ThingCardMediaHelper.MediaSource,
                     complete: (Result<Metadata?>) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed)
        cancel()
        val token = Any()
        request = token
        val previous = cachedMetadata
        metadataWork = metadataExecutor.submit {
            val result = runCatching {
                val file = File(source.pathName)
                val key = Key(source.typePathName, 0, file.length(), file.lastModified())
                key to (previous?.takeIf { it.first == key }?.second ?: readMetadata(source))
            }
            main.post {
                if (closed || request !== token) return@post
                request = null
                metadataWork = null
                cachedMetadata = result.getOrNull()?.let { (key, metadata) -> metadata?.let { key to it } }
                complete(result.map { it.second })
            }
        }
        return cancellation(token)
    }

    fun load(source: ThingCardMediaHelper.MediaSource, frameMs: Long,
             decode: () -> Preview?, complete: (Result<Preview?>) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed)
        cancel()
        val token = Any()
        request = token
        val previous = cached?.takeIf { it.first.source == source.typePathName && it.first.frameMs == frameMs }
        cached = previous
        work = executor.submit {
            val result = runCatching {
                val file = File(source.pathName)
                val key = Key(source.typePathName, frameMs, file.length(), file.lastModified())
                key to (previous?.takeIf { it.first == key && !it.second.bitmap.isRecycled }?.second
                    ?: decode()?.also { it.bitmap.prepareToDraw() })
            }
            main.post {
                val preview = result.getOrNull()?.second
                if (closed || request !== token) {
                    // 已交给 View 的位图可能仍被渲染线程引用，仅回收从未交付的解码结果。
                    if (preview != null && preview !== previous?.second) preview.bitmap.recycle()
                    return@post
                }
                request = null
                work = null
                if (preview != null) {
                    // 极大媒体只供当前编辑器使用，不额外长期占用详情页内存。
                    val cacheLimit = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)
                    cached = if (preview.bitmap.allocationByteCount <= cacheLimit)
                        result.getOrThrow().first to preview else null
                }
                complete(result.map { it.second })
            }
        }
        return cancellation(token)
    }

    private fun cancellation(token: Any): () -> Unit = {
        if (request === token) cancel()
    }

    fun cancel() {
        request = null
        // 已进入原生解码的任务不能强行中断；未开始的排队任务无需再解码。
        work?.cancel(false)
        work = null
        metadataWork?.cancel(false)
        metadataWork = null
    }

    override fun close() {
        closed = true
        cancel()
        cached = null
        cachedMetadata = null
        executor.shutdown()
        metadataExecutor.shutdown()
    }

    private fun readMetadata(source: ThingCardMediaHelper.MediaSource): Metadata? {
        if (source.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.pathName)
                var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) width = height.also { height = width }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                return if (width > 0 && height > 0) Metadata(source.typePathName, width, height, duration) else null
            } finally {
                retriever.release()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.pathName, bounds)
        var width = bounds.outWidth
        var height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        // 与现有 BitmapUtil 的旋转规则一致，不在本次性能修改中改变镜像图片的呈现。
        val rotation = try {
            ExifInterface(source.pathName).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        if (rotation == ExifInterface.ORIENTATION_ROTATE_90 || rotation == ExifInterface.ORIENTATION_ROTATE_270) {
            width = height.also { height = width }
        }
        return Metadata(source.typePathName, width, height, 0)
    }
}
