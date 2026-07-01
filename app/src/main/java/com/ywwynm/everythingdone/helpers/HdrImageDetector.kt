package com.ywwynm.everythingdone.helpers

import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 检测一张图片是否携带 **HDR** 增益图（UltraHDR / gain map），供详情附件网格显示 HDR 标识。见 ADR-0006。
 *
 * 判定依赖 `Bitmap.hasGainmap()`，仅 **API 34+** 可用；更低版本本就无法解码 / 显示 HDR，一律视为非 HDR。
 * 为省成本，解码时按较大 `inSampleSize` 降采样（增益图标志在降采样后仍保留）。结果按文件签名缓存。
 *
 * 注意：[detect] 会解码图片，**不得在主线程调用**；[peekCached] 只查缓存、可在主线程调用。
 */
object HdrImageDetector {

    private const val TAG = "HdrImageDetector"
    private const val TARGET_LONG_EDGE = 384

    private val cache = ConcurrentHashMap<String, Boolean>()

    /** 只有 API 34+ 且扩展名可能带增益图（jpg/jpeg/heic/heif）才值得检测。 */
    @JvmStatic
    fun isCandidate(pathName: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (pathName.isNullOrEmpty()) return false
        val dot = pathName.lastIndexOf('.')
        if (dot < 0 || dot == pathName.length - 1) return false
        val postfix = pathName.substring(dot + 1).lowercase()
        return postfix == "jpg" || postfix == "jpeg" || postfix == "heic" || postfix == "heif"
    }

    private fun keyOf(pathName: String, file: File): String =
        pathName + "|" + file.length() + "|" + file.lastModified()

    /** 只查缓存、不解码：未检测过返回 null。 */
    @JvmStatic
    fun peekCached(pathName: String?): Boolean? {
        if (!isCandidate(pathName)) return false
        val file = File(pathName!!)
        if (!file.exists() || !file.isFile) return false
        return cache[keyOf(pathName, file)]
    }

    /** 检测是否 HDR（带增益图）。命中缓存直接返回；否则降采样解码判断。**须在后台线程调用。** */
    @JvmStatic
    fun detect(pathName: String?): Boolean {
        if (!isCandidate(pathName)) return false
        val file = File(pathName!!)
        if (!file.exists() || !file.isFile) return false
        val key = keyOf(pathName, file)
        cache[key]?.let { return it }
        val result = try {
            decodeHasGainmap(pathName)
        } catch (e: Throwable) {
            Log.e(TAG, "detect failed for $pathName: ${e.message}")
            false
        }
        cache[key] = result
        return result
    }

    private fun decodeHasGainmap(pathName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pathName, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge > 0 && longEdge / sample > TARGET_LONG_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(pathName, opts) ?: return false
        val hdr = bmp.hasGainmap()
        bmp.recycle()
        return hdr
    }
}
