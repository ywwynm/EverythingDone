package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import com.ywwynm.everythingdone.helpers.DetailCropPreviewLoader
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 使用生产加载器验证线程、缓存失效、取消与未交付位图释放，不访问用户媒体。 */
internal class DetailCropPreviewChecks(private val probe: Instrumentation, private val directory: File) {
    private fun main(action: () -> Unit) = probe.runOnMainSync(action)
    fun run() {
        val file = File(directory, "preview-loader-fixture.bin").apply { writeBytes(byteArrayOf(1)) }
        val source = ThingCardMediaHelper.toMediaSource("0${file.absolutePath}")!!
        val loader = DetailCropPreviewLoader()
        val decoded = AtomicInteger()
        val checks = JSONObject()
        fun decode(frame: Long): DetailCropPreviewLoader.Preview {
            check(Looper.myLooper() != Looper.getMainLooper())
            decoded.incrementAndGet()
            return DetailCropPreviewLoader.Preview(source.typePathName, frame,
                Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888), 1234L)
        }
        fun load(frame: Long): DetailCropPreviewLoader.Preview {
            val latch = CountDownLatch(1)
            var result: Result<DetailCropPreviewLoader.Preview?>? = null
            main { loader.load(source, frame, { decode(frame) }) {
                check(Looper.myLooper() == Looper.getMainLooper())
                result = it; latch.countDown()
            } }
            check(latch.await(5, TimeUnit.SECONDS))
            return result!!.getOrThrow()!!
        }
        try {
            val image = File(directory, "metadata-fixture.jpg")
            val pixels = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
            image.outputStream().use { pixels.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            pixels.recycle()
            android.media.ExifInterface(image.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            fun metadata(input: ThingCardMediaHelper.MediaSource): DetailCropPreviewLoader.Metadata {
                val done = CountDownLatch(1)
                var value: Result<DetailCropPreviewLoader.Metadata?>? = null
                main { loader.loadMetadata(input) {
                    check(Looper.myLooper() == Looper.getMainLooper()); value = it; done.countDown()
                } }
                check(done.await(5, TimeUnit.SECONDS)); return value!!.getOrThrow()!!
            }
            val imageSource = ThingCardMediaHelper.toMediaSource("0${image.path}")!!
            check(metadata(imageSource).let { it.width == 48 && it.height == 96 })
            android.media.ExifInterface(image.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
            image.setLastModified(image.lastModified() + 2000)
            check(metadata(imageSource).let { it.width == 96 && it.height == 48 })
            checks.put("metadata_exif_rotation_and_file_invalidation", true)
            val video = File(directory, "metadata-fixture.mp4")
            probe.targetContext.assets.open("detail-regression/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
            val videoMetadata = metadata(ThingCardMediaHelper.toMediaSource("1${video.path}")!!)
            check(videoMetadata.width > 0 && videoMetadata.height > 0 && videoMetadata.durationMs > 0)
            checks.put("video_metadata_without_frame_decode", true)
            image.delete(); video.delete()
            val first = load(0)
            check(load(0) === first && decoded.get() == 1)
            checks.put("worker_decode_main_delivery_and_cache", true)
            file.appendBytes(byteArrayOf(2))
            check(load(0) !== first && decoded.get() == 2)
            check(load(10).frameMs == 10L && decoded.get() == 3)
            checks.put("file_and_frame_invalidation", true)

            val started = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val lastReady = CountDownLatch(1)
            val unwanted = AtomicInteger()
            var discarded: Bitmap? = null
            main { loader.load(source, 20, {
                val preview = decode(20)
                discarded = preview.bitmap
                started.countDown(); check(unblock.await(5, TimeUnit.SECONDS)); preview
            }) { unwanted.incrementAndGet() } }
            check(started.await(5, TimeUnit.SECONDS))
            main {
                loader.load(source, 30, { unwanted.incrementAndGet(); decode(30) }) { unwanted.incrementAndGet() }
                loader.load(source, 40, { decode(40) }) { check(it.getOrThrow()!!.frameMs == 40L); lastReady.countDown() }
            }
            unblock.countDown()
            check(lastReady.await(5, TimeUnit.SECONDS))
            probe.waitForIdleSync()
            check(unwanted.get() == 0 && discarded?.isRecycled == true)
            checks.put("superseded_queue_skipped_and_unused_bitmap_recycled", true)

            val closingStarted = CountDownLatch(1)
            val closeUnblock = CountDownLatch(1)
            var closedBitmap: Bitmap? = null
            main { loader.load(source, 50, {
                val preview = decode(50); closedBitmap = preview.bitmap
                closingStarted.countDown(); check(closeUnblock.await(5, TimeUnit.SECONDS)); preview
            }) { unwanted.incrementAndGet() } }
            check(closingStarted.await(5, TimeUnit.SECONDS))
            main { loader.close() }
            closeUnblock.countDown()
            val deadline = SystemClock.uptimeMillis() + 5000
            while (closedBitmap?.isRecycled != true && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            check(closedBitmap?.isRecycled == true && unwanted.get() == 0)
            checks.put("close_discards_late_result", true)
        } finally {
            main { loader.close() }
            file.delete()
        }
        File(directory, "preview-loader-checks.json").writeText(checks.toString(2))
    }
}
