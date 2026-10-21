package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.ywwynm.everythingdone.BuildConfig
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPoolAdapter
import com.bumptech.glide.load.ImageHeaderParser.ImageType
import com.bumptech.glide.load.resource.bitmap.DefaultImageHeaderParser
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** 仅供展开动画使用的完整构图，不能作为正常图片或 HDR 输出的来源。 */
internal object MediaPreviewThumbnail {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "media-preview-thumbnail") }
    internal var diagnosticCopy: ((Int, Int, Long) -> Unit)? = null
    private val staticWebp = LruCache<Source, Boolean>(128)
    data class Source(val path: String, val length: Long, val modified: Long)
    data class Preview(val bitmap: Bitmap, val fromDetail: Boolean)
    private data class Cached(val source: Source, val bitmap: Bitmap)
    private val cache = object : LruCache<String, Cached>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached) = value.bitmap.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Cached, newValue: Cached?) {
            oldValue.bitmap.recycle()
        }
    }

    fun source(path: String) = File(path).let { Source(path, it.length(), it.lastModified()) }

    /** WebP 扩展名同时包含静态和动画；复用 Glide 的短文件头解析，不能把动画交给静图请求。 */
    fun isStaticImage(path: String): Boolean {
        if (!AttachmentHelper.isAnimatedImageCandidate(path)) return true
        if (!path.endsWith(".webp", ignoreCase = true)) return false
        val identity = source(path)
        staticWebp.get(identity)?.let { return it }
        val result = try {
            File(path).inputStream().buffered(32).use { DefaultImageHeaderParser().getType(it) }
                .let { it == ImageType.WEBP || it == ImageType.WEBP_A }
        } catch (_: Exception) { false }
        staticWebp.put(identity, result)
        return result
    }

    /** 只接收裁剪前的既有解码结果；缓存和查看器均持有独立副本，不依赖 Glide 位图生命周期。 */
    fun remember(identity: Source, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE || identity != source(identity.path)) return
        val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        synchronized(cache) {
            cache.get(identity.path)?.takeIf { it.source == identity && it.bitmap.width >= width && it.bitmap.height >= height }
                ?.let { return }
        }
        var copy: Bitmap? = null
        val started = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        try {
            val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            copy = created
            Canvas(created).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            synchronized(cache) {
                if (identity == source(identity.path)) {
                    cache.put(identity.path, Cached(identity, created))
                    copy = null
                }
            }
        } catch (_: RuntimeException) {
            // 轻量预览失败不能影响详情显示或最终原图请求。
        } catch (_: OutOfMemoryError) {
            synchronized(cache) { cache.evictAll() }
        } finally {
            copy?.recycle()
            if (BuildConfig.DEBUG) diagnosticCopy?.invoke(bitmap.width, bitmap.height, (System.nanoTime() - started) / 1000)
        }
    }

    fun clear() = synchronized(cache) { cache.evictAll() }

    fun prepare(path: String): CompletableFuture<Preview?> = CompletableFuture.supplyAsync({
        val identity = source(path)
        val copied = try {
            synchronized(cache) {
                cache.get(path)?.takeIf { it.source == identity }?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (_: OutOfMemoryError) { null }
        copied?.let { Preview(it, true) } ?: decode(path)?.let { Preview(it, false) }
    }, io)

    /** 后台调用；返回独立位图，没有可用预览时继续原来的图片请求。 */
    fun decode(path: String): Bitmap? = try {
        val file = File(path)
        val jpeg = file.inputStream().use { it.read() == 0xff && it.read() == 0xd8 }
        if (!jpeg) null else decodeJpeg(file)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decodeJpeg(file: File): Bitmap? {
        val exif = ExifInterface(file)
        val bytes = exif.thumbnail ?: return null
        if (bytes.size > 256 * 1024) return null
        val preview = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, preview)
        // 防止异常元数据触发大分配，也不拿极小图或经过独立裁剪的旧缩略图展开。
        if (minOf(preview.outWidth, preview.outHeight) < 96 ||
            maxOf(preview.outWidth, preview.outHeight) > 1024) return null
        val original = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, original)
        if (original.outWidth <= 0 || original.outHeight <= 0) return null
        val aspectRatio = original.outWidth.toDouble() / original.outHeight
        if (abs(preview.outWidth.toDouble() / preview.outHeight / aspectRatio - 1) > .01) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return try {
            TransformationUtils.rotateImageExif(BitmapPoolAdapter(), bitmap, orientation).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}
