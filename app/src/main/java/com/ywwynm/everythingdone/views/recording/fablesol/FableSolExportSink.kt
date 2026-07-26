package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.ContentValues
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 产物落地：`Movies/EverythingDone/`（fablesol-video-export D12）。
 *
 * GB 级文件不进应用私有目录——用户在系统的"应用存储"里只会看到应用占了几个 G，却无法
 * 单独删除某一个视频。走相册他用任何文件管理器都能处理。
 *
 * API 29+ 用 MediaStore + `IS_PENDING`，编码直接写进最终位置，失败就删条目；老系统写
 * 公共 Movies 目录再触发一次扫描。两条路都**不做整份文件的二次拷贝**。
 */
internal abstract class FableSolExportSink(val displayName: String) {

    abstract fun createMuxer(): MediaMuxer

    /**
     * 成功收尾：让产物对相册可见。**返回是否真的成功**——它是成功路径的一部分，不是
     * finally 里可以吞掉的收尾动作：提交失败意味着产物仍是 pending、相册里看不见，
     * 此时报"导出成功"就是在骗人。
     */
    abstract fun commit(isCancelled: () -> Boolean): Boolean
    /** 失败或取消：删掉半成品。 */
    abstract fun discard()
    /** 供完成后分享或"添加为附件"。 */
    abstract fun contentUri(): Uri?
    /** 成功提交后的实际文件大小；读取失败时返回 0。 */
    abstract fun fileSizeBytes(): Long
    /** 用户可识别的最终保存位置，至少包含公共 Movies 相对目录与文件名。 */
    abstract fun displayLocation(): String

    /**
     * 产物在文件系统上的真实路径；"添加为附件"需要它——本应用的附件模型是路径而不是 URI。
     * MediaStore 上取不到 `DATA` 列时返回 null，此时只提供分享。
     */
    abstract fun localPath(): String?

    companion object {

        private const val RELATIVE_DIRECTORY = "Movies/EverythingDone"
        private const val FILE_PROVIDER_AUTHORITY = "com.ywwynm.everythingdone"
        private const val MIME_TYPE = "video/mp4"

        fun create(context: Context, displayName: String): FableSolExportSink =
            if (Build.VERSION.SDK_INT >= 29) {
                MediaStoreSink(context, displayName)
            } else {
                LegacySink(context, displayName)
            }

        /** 预估体积（字节）× 1.2 与可用空间比较；导出前必查（D16）。 */
        @SuppressLint("UsableSpace")
        fun hasRoomFor(estimatedBytes: Long): Boolean {
            val root = Environment.getExternalStorageDirectory() ?: return true
            val usable = root.usableSpace
            if (usable <= 0L) return true
            val required = if (estimatedBytes > Long.MAX_VALUE / 12L) {
                Long.MAX_VALUE
            } else {
                estimatedBytes * 12L / 10L
            }
            return usable > required
        }

        /** CQ 无法预估最终码率，只能在开始前和编码途中守住一段实际剩余空间。 */
        @SuppressLint("UsableSpace")
        fun hasMinimumFreeSpace(requiredBytes: Long): Boolean {
            val root = Environment.getExternalStorageDirectory() ?: return true
            val usable = root.usableSpace
            return usable <= 0L || usable > requiredBytes
        }
    }

    private class MediaStoreSink(
        private val context: Context,
        displayName: String
    ) : FableSolExportSink(displayName) {

        private val resolver = context.contentResolver
        private var uri: Uri? = null
        private var descriptor: ParcelFileDescriptor? = null

        override fun createMuxer(): MediaMuxer {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIRECTORY)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val created = checkNotNull(
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ) { "MediaStore insert failed" }
            uri = created
            val pfd = checkNotNull(resolver.openFileDescriptor(created, "rw")) {
                "openFileDescriptor failed"
            }
            descriptor = pfd
            return MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        override fun commit(isCancelled: () -> Boolean): Boolean {
            if (isCancelled()) return false
            closeDescriptor()
            val target = uri ?: return false
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            return try {
                // 更新行数必须查：返回 0 同样意味着还挂在 IS_PENDING 上。
                resolver.update(target, values, null, null) > 0
            } catch (ignored: Throwable) {
                false
            }
        }

        override fun discard() {
            closeDescriptor()
            val target = uri ?: return
            try {
                resolver.delete(target, null, null)
            } catch (ignored: Throwable) {
            }
            uri = null
        }

        override fun contentUri(): Uri? = uri

        override fun fileSizeBytes(): Long {
            val target = uri ?: return 0L
            val queriedSize = try {
                resolver.query(
                    target, arrayOf(MediaStore.Video.Media.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
            } catch (ignored: Throwable) {
                0L
            }
            if (queriedSize > 0L) return queriedSize
            return try {
                resolver.openFileDescriptor(target, "r")?.use { descriptor ->
                    descriptor.statSize.coerceAtLeast(0L)
                } ?: 0L
            } catch (ignored: Throwable) {
                0L
            }
        }

        override fun displayLocation(): String {
            localPath()?.let { if (it.isNotEmpty()) return it }
            val target = uri
            if (target != null) {
                try {
                    resolver.query(
                        target,
                        arrayOf(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            MediaStore.Video.Media.DISPLAY_NAME
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val directory = cursor.getString(0)?.trimEnd('/').orEmpty()
                            val name = cursor.getString(1).orEmpty()
                            if (directory.isNotEmpty() && name.isNotEmpty()) {
                                return "$directory/$name"
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            return "$RELATIVE_DIRECTORY/$displayName"
        }

        override fun localPath(): String? {
            val target = uri ?: return null
            return try {
                resolver.query(
                    target, arrayOf(MediaStore.Video.Media.DATA), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun closeDescriptor() {
            try {
                descriptor?.close()
            } catch (ignored: Throwable) {
            }
            descriptor = null
        }
    }

    private class LegacySink(
        private val context: Context,
        displayName: String
    ) : FableSolExportSink(displayName) {

        /**
         * API 26–28 必须写公共 Movies：应用专属的 `getExternalFilesDir()` 不属于公共相册，
         * MediaScanner 也没有可靠契约把它变成持久图库产物，而且卸载应用时文件会被删除。
         * 权限在发起导出前申请；这里再次校验，避免任何绕过 Launcher 的调用静默改写私有目录。
         */
        private val directory: File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "EverythingDone"
        )
        private var file: File? = null
        private var ownsFile = false
        private var scannedUri: Uri? = null

        override fun createMuxer(): MediaMuxer {
            check(
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) { "Storage permission is required to publish video on Android 8/9" }
            check(file == null) { "Legacy export sink is already open" }
            check(directory.exists() || directory.mkdirs()) {
                "Cannot create ${directory.absolutePath}"
            }
            val target = reserveUniqueFile(directory, displayName)
            file = target
            ownsFile = true
            scannedUri = null
            return MediaMuxer(
                target.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        }

        override fun commit(isCancelled: () -> Boolean): Boolean {
            val target = file ?: return false
            if (!ownsFile || !target.isFile || target.length() <= 0L) return false
            val completed = CountDownLatch(1)
            var published: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(MIME_TYPE)
            ) { _, uri ->
                published = uri
                completed.countDown()
            }
            val deadline = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(MEDIA_SCAN_TIMEOUT_SECONDS)
            val callbackArrived = try {
                var arrived = false
                while (!arrived && System.nanoTime() < deadline) {
                    if (isCancelled()) return false
                    arrived = completed.await(
                        MEDIA_SCAN_POLL_MILLIS, TimeUnit.MILLISECONDS
                    )
                }
                arrived
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!callbackArrived || published == null) return false
            scannedUri = published
            return true
        }

        override fun discard() {
            val target = file
            if (ownsFile && target != null && target.exists()) target.delete()
            file = null
            ownsFile = false
            scannedUri = null
        }

        /**
         * 必须走 FileProvider：`Uri.fromFile()` 交给外部应用会在 API 24+ 抛
         * `FileUriExposedException`，而本项目 minSdk 26，这条路径是必崩的。
         * `file_provider_paths.xml` 的 `external-path path="."` 覆盖公共 Movies 目录。
         */
        override fun contentUri(): Uri? {
            scannedUri?.let { return it }
            val target = file ?: return null
            return try {
                FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, target)
            } catch (ignored: Throwable) {
                null
            }
        }

        override fun fileSizeBytes(): Long =
            file?.takeIf { it.exists() }?.length() ?: 0L

        override fun displayLocation(): String =
            file?.absolutePath ?: "$RELATIVE_DIRECTORY/$displayName"

        override fun localPath(): String? =
            file?.takeIf { it.exists() }?.absolutePath

        private fun reserveUniqueFile(directory: File, requestedName: String): File {
            val dot = requestedName.lastIndexOf('.')
            val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
            val extension = if (dot > 0) requestedName.substring(dot) else ".mp4"
            for (suffix in 0 until MAX_UNIQUE_SUFFIX) {
                val name = if (suffix == 0) {
                    "$base$extension"
                } else {
                    "${base}_$suffix$extension"
                }
                val candidate = File(directory, name)
                if (candidate.createNewFile()) return candidate
            }
            error("Cannot reserve a unique output name for $requestedName")
        }

        private companion object {
            const val MEDIA_SCAN_TIMEOUT_SECONDS = 60L
            const val MEDIA_SCAN_POLL_MILLIS = 250L
            const val MAX_UNIQUE_SUFFIX = 10_000
        }
    }
}
