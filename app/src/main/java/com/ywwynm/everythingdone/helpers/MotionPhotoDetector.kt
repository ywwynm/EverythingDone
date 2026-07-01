package com.ywwynm.everythingdone.helpers

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 检测一张图片附件是否为 **Motion Photo**(动态照片 / 实况照片),并定位其内嵌视频的字节区间。见
 * [ADR-0014](../../../../../../../docs/adr/0014-motion-photo-as-image-capability.md)。
 *
 * 策略(经验式,绕开 `MicroVideoOffset` 方向歧义):在文件里扫描 MP4 的 `ftyp` 盒子头,对每个候选
 * 偏移用 [MediaMetadataRetriever] 打开并要求 `HAS_VIDEO == "yes"`,取第一个通过校验者。
 * - 对 JPEG:内嵌视频直接附在主图之后,其 `ftyp` 在文件中段到尾部。
 * - 对 HEIC:容器自身也以 `....ftyp....` 开头(offset 0),但它 `HAS_VIDEO=no`,会被过滤掉;
 *   真正的内嵌视频(在 `mpvd`/`sefd` box 内)仍是带 `ftyp` 的 MP4,会被扫到并通过校验。
 * - 普通照片:尾部没有合法 MP4,扫不到通过校验的候选 → 判为非 Motion Photo。
 *
 * v1 实测范围 OPPO + 三星;`ftyp` 扫描厂商无关,小米/Pixel 很可能顺带可用但不声明。
 *
 * 注意:[detect] 会读取整个文件,**不得在主线程调用**。结果按"路径+大小+修改时间"缓存。
 */
object MotionPhotoDetector {

    private const val TAG = "MotionPhotoDetector"
    private const val SCAN_BUFFER_SIZE = 64 * 1024
    private const val OVERLAP = 3 // 让跨缓冲区边界的 "ftyp" 也能被扫到

    // 'f','t','y','p'
    private val FTYP = byteArrayOf(0x66, 0x74, 0x79, 0x70)

    data class MotionPhotoInfo(
        val isMotionPhoto: Boolean,
        val videoOffset: Long,
        val videoLength: Long
    ) {
        companion object {
            @JvmField
            val NONE = MotionPhotoInfo(false, -1L, -1L)
        }
    }

    // 结果缓存:key = "路径|大小|修改时间"。条目极小,无需淘汰。
    private val cache = ConcurrentHashMap<String, MotionPhotoInfo>()

    @JvmStatic
    fun isMotionPhoto(pathName: String?): Boolean = detect(pathName).isMotionPhoto

    /**
     * 只查缓存、**不扫描文件**:主线程绑定时用来快速判断"这张图此前是否已被检测为 Motion Photo"。
     * 未检测过返回 null(交由后台 [detect] 补齐)。只做一次轻量 File stat(与适配器算 loadKey 一致)。
     */
    @JvmStatic
    fun peekCached(pathName: String?): MotionPhotoInfo? {
        if (!AttachmentHelper.isMotionPhotoCandidate(pathName)) return null
        val file = File(pathName!!)
        if (!file.exists() || !file.isFile) return null
        val key = pathName + "|" + file.length() + "|" + file.lastModified()
        return cache[key]
    }

    /**
     * 检测并定位内嵌视频。命中缓存直接返回;否则读取文件扫描 + 校验。**须在后台线程调用。**
     */
    @JvmStatic
    fun detect(pathName: String?): MotionPhotoInfo {
        if (!AttachmentHelper.isMotionPhotoCandidate(pathName)) return MotionPhotoInfo.NONE
        val file = File(pathName!!)
        if (!file.exists() || !file.isFile) return MotionPhotoInfo.NONE

        val key = pathName + "|" + file.length() + "|" + file.lastModified()
        cache[key]?.let { return it }

        val info = try {
            val offset = findEmbeddedVideoOffset(file)
            if (offset > 0) {
                MotionPhotoInfo(true, offset, file.length() - offset)
            } else {
                MotionPhotoInfo.NONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect failed for $pathName: ${e.message}", e)
            MotionPhotoInfo.NONE
        }
        cache[key] = info
        return info
    }

    /**
     * 把内嵌视频段抠到 [dst](供派生 GIF 复用现有视频管线);裸字节拷贝,不重编码。成功返回 true。
     */
    @JvmStatic
    fun extractEmbeddedVideo(srcPath: String?, offset: Long, length: Long, dst: File): Boolean {
        if (srcPath.isNullOrEmpty() || offset <= 0 || length <= 0) return false
        return try {
            RandomAccessFile(srcPath, "r").use { raf ->
                raf.seek(offset)
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(SCAN_BUFFER_SIZE)
                    var remaining = length
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buf.size.toLong()).toInt()
                        val read = raf.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
            }
            dst.exists() && dst.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "extractEmbeddedVideo failed: ${e.message}", e)
            if (dst.exists()) dst.delete()
            false
        }
    }

    /** 全文扫描 "ftyp",对每个候选盒子起点做 HAS_VIDEO 校验,返回第一个通过者的字节偏移;找不到返回 -1。 */
    private fun findEmbeddedVideoOffset(file: File): Long {
        val len = file.length()
        if (len < 16) return -1L
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(SCAN_BUFFER_SIZE)
            var pos = 0L
            while (pos < len) {
                raf.seek(pos)
                val read = raf.read(buf)
                if (read <= 0) break
                var i = 0
                while (i <= read - 4) {
                    if (buf[i] == FTYP[0] && buf[i + 1] == FTYP[1] &&
                        buf[i + 2] == FTYP[2] && buf[i + 3] == FTYP[3]
                    ) {
                        // MP4 box = [4字节 size][ftyp]...,故视频盒子起点在 "ftyp" 前 4 字节
                        val boxStart = pos + i - 4
                        if (boxStart > 0 && isValidEmbeddedVideo(file, boxStart, len - boxStart)) {
                            return boxStart
                        }
                    }
                    i++
                }
                if (read < SCAN_BUFFER_SIZE) break
                pos += (read - OVERLAP)
            }
        }
        return -1L
    }

    /** 用 [MediaMetadataRetriever] 从 [offset] 起打开 [length] 字节,判断是不是含视频轨的有效 MP4。 */
    private fun isValidEmbeddedVideo(file: File, offset: Long, length: Long): Boolean {
        if (offset <= 0 || length <= 0) return false
        val mmr = MediaMetadataRetriever()
        return try {
            FileInputStream(file).use { fis ->
                mmr.setDataSource(fis.fd, offset, length)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            }
        } catch (e: Exception) {
            false
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }
}
