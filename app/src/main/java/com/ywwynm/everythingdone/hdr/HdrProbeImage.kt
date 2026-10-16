package com.ywwynm.everythingdone.hdr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.function.DoubleUnaryOperator
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RequiresApi(34)
internal class HdrProbeImage(val file: File, val bitmap: Bitmap, val base: Bitmap,
                             val description: String, private val app5: HdrApp5Preview?, val app5Diagnostics: String) {
    val hasHdrSurfaceData: Boolean get() = bitmap.hasGainmap() || app5 != null
    val usesApp5: Boolean get() = !bitmap.hasGainmap() && app5 != null
    companion object {
        const val MAX_EDGE = 1280

        fun supportsCompactBase(base: Bitmap): Boolean = base.config == Bitmap.Config.ARGB_8888 && !base.hasAlpha() &&
            (base.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB) || base.colorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3))

        private class FullSizeSkipped : RuntimeException()

        fun inspectFullSize(file: File): String {
            var allocation = ""
            var decoded: Bitmap? = null
            var gain: Bitmap? = null
            val started = System.nanoTime()
            return try {
                decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val runtime = Runtime.getRuntime()
                    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
                    val (allowed, details) = HdrFullSizeBudget.decision(info.size.width, info.size.height, available)
                    allocation = "mime=${info.mimeType}; $details; allocator=SOFTWARE; targetSize=original"
                    if (maxOf(info.size.width, info.size.height) <= MAX_EDGE) {
                        allocation += "; reason=preview_is_already_full_size"
                        throw FullSizeSkipped()
                    }
                    if (!allowed) { allocation += "; reason=memory_budget"; throw FullSizeSkipped() }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    // 不设置 targetSize／采样率，对照缩放前的系统解码结果。
                }
                gain = decoded.gainmap?.gainmapContents
                "status=decoded; $allocation\n${describe(decoded)}\nelapsedMs=${(System.nanoTime() - started) / 1_000_000}"
            } catch (_: FullSizeSkipped) {
                "status=skipped; $allocation; 跳过不等于原图不含 HDR。"
            } catch (_: OutOfMemoryError) {
                "status=failed; reason=OutOfMemoryError; $allocation"
            } catch (failure: Exception) {
                "status=failed; exception=${failure.javaClass.simpleName}; $allocation"
            } finally { gain?.recycle(); decoded?.recycle() }
        }

        fun builtin(file: File) {
            val base = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
            val gain = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(base)
            val gainCanvas = Canvas(gain)
            val paint = Paint()
            canvas.drawColor(Color.rgb(24, 24, 24))
            gainCanvas.drawColor(Color.BLACK)
            // 三行的 SDR 基图相同；上行无增益，中行 2 倍，下行 4 倍。
            val colors = intArrayOf(Color.WHITE, Color.rgb(190, 190, 190),
                Color.rgb(255, 190, 80), Color.rgb(80, 170, 255))
            for (row in 0..2) for (column in colors.indices) {
                val left = 38f + column * 230f
                val top = 35f + row * 210f
                paint.color = colors[column]
                canvas.drawRect(left, top, left + 190f, top + 150f, paint)
                val level = intArrayOf(0, 128, 255)[row]
                paint.color = Color.rgb(level, level, level)
                gainCanvas.drawRect(left, top, left + 190f, top + 150f, paint)
            }
            base.gainmap = Gainmap(gain).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(4f, 4f, 4f)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(0f, 0f, 0f)
                setEpsilonHdr(0f, 0f, 0f)
                minDisplayRatioForHdrTransition = 1f
                displayRatioForFullHdr = 4f
            }
            try {
                file.outputStream().use { check(base.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally { base.recycle(); gain.recycle() }
        }

        fun decode(file: File, maxEdge: Int = MAX_EDGE, hashSource: Boolean = true,
                   maxPixels: Long = Long.MAX_VALUE): HdrProbeImage {
            require(maxEdge > 0 && maxPixels > 0)
            var header = ""
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                header = "mime=${info.mimeType}; encodedSize=${info.size}; headerColorSpace=${info.colorSpace}"
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val scale = minOf(1f, maxEdge.toFloat() / maxOf(info.size.width, info.size.height),
                    sqrt(maxPixels.toDouble() / (info.size.width.toLong() * info.size.height)).toFloat())
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).roundToInt()),
                    maxOf(1, (info.size.height * scale).roundToInt()))
            }
            return fromOwnedBitmap(file, bitmap, header, hashSource)
        }

        /** 主线程取得独立像素及增益副本后才交给后台，避免 Glide 回收／复用期间读到旧内存。 */
        fun copyForViewer(source: Bitmap): Bitmap {
            check(source.config != Bitmap.Config.HARDWARE) { "viewer_hardware_bitmap" }
            val bitmap = checkNotNull(source.copy(source.config ?: Bitmap.Config.ARGB_8888, true))
            // Bitmap.copy 的增益资源所有权不能当作深拷贝承诺；先断开，再显式复制。
            bitmap.gainmap = null
            var gainBitmap: Bitmap? = null
            try {
                source.gainmap?.let { gain ->
                    gainBitmap = checkNotNull(gain.gainmapContents.copy(gain.gainmapContents.config ?: Bitmap.Config.ARGB_8888, false))
                    bitmap.gainmap = Gainmap(gainBitmap!!).apply {
                        gain.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
                        gain.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
                        gain.gamma.let { setGamma(it[0], it[1], it[2]) }
                        gain.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
                        gain.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
                        minDisplayRatioForHdrTransition = gain.minDisplayRatioForHdrTransition
                        displayRatioForFullHdr = gain.displayRatioForFullHdr
                        if (Build.VERSION.SDK_INT >= 36) {
                            gainmapDirection = gain.gainmapDirection
                            alternativeImagePrimaries = gain.alternativeImagePrimaries
                        }
                    }
                }
                return bitmap
            } catch (failure: Throwable) {
                gainBitmap?.recycle(); bitmap.recycle()
                throw failure
            }
        }

        fun recycleOwnedBitmap(bitmap: Bitmap) {
            bitmap.gainmap?.gainmapContents?.recycle()
            bitmap.recycle()
        }

        /** 消费独立位图的所有权，成功由返回对象释放，失败也会在此释放。 */
        fun fromOwnedBitmap(file: File, bitmap: Bitmap, header: String = "source=PhotoView_owned_snapshot",
                            hashSource: Boolean = false): HdrProbeImage {
            var ownedBase: Bitmap? = null
            try {
                val base = checkNotNull(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)).also { ownedBase = it }
                base.gainmap = null
                var app5: HdrApp5Preview? = null
                val app5Diagnostics = if (bitmap.hasGainmap()) "app5Decode=skipped; reason=native_gainmap_preferred"
                else try {
                    app5 = HdrApp5Preview.decode(file, base)
                    app5?.description ?: "app5Decode=not_found; hdrSurfaceSource=none"
                } catch (failure: Exception) {
                    "app5Decode=rejected; exception=${failure.javaClass.simpleName}; " +
                        "reason=${failure.message?.takeIf { it.startsWith("app5_") } ?: "decoder_error"}; SDR 基图保留。"
                } catch (_: OutOfMemoryError) {
                    "app5Decode=rejected; reason=memory_budget; SDR 基图保留。"
                }
                val digest = if (hashSource) MessageDigest.getInstance("SHA-256") else null
                if (digest != null) file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                return HdrProbeImage(file, bitmap, base,
                    "$header\nbytes=${file.length()}; sha256=${digest?.digest()?.joinToString("") { "%02x".format(it) } ?: "skipped_in_viewer"}\n" +
                        describe(bitmap), app5, app5Diagnostics)
            } catch (failure: Throwable) {
                ownedBase?.recycle()
                recycleOwnedBitmap(bitmap)
                throw failure
            }
        }

        fun describe(bitmap: Bitmap): String = buildString {
            append("decoded=${bitmap.width}x${bitmap.height}; config=${bitmap.config}; " +
                "colorSpace=${bitmap.colorSpace}; hasGainmap=${bitmap.hasGainmap()}")
            if (bitmap.hasGainmap()) bitmap.gainmap?.let { gain ->
                append("\ngainmap=${gain.gainmapContents.width}x${gain.gainmapContents.height}; " +
                    "config=${gain.gainmapContents.config}; colorSpace=${gain.gainmapContents.colorSpace}")
                append("\nratioMin=${gain.ratioMin.contentToString()}; ratioMax=${gain.ratioMax.contentToString()}")
                append("\ngamma=${gain.gamma.contentToString()}; epsilonSdr=${gain.epsilonSdr.contentToString()}; " +
                    "epsilonHdr=${gain.epsilonHdr.contentToString()}")
                append("\ntransition=${gain.minDisplayRatioForHdrTransition}; fullHdr=${gain.displayRatioForFullHdr}")
                if (Build.VERSION.SDK_INT >= 36) {
                    append("\nISO metadata=" + runCatching {
                        "direction=${gain.gainmapDirection}; alternativePrimaries=${gain.alternativeImagePrimaries}"
                    }.getOrElse { "unavailable: $it" })
                }
            }
        }
    }

    /** 只释放本次独立解码拥有的位图，不接管 Glide／PhotoView 的资源。 */
    fun close() {
        bitmap.gainmap?.gainmapContents?.recycle()
        base.recycle()
        bitmap.recycle()
    }

    /** 显式线性化与色域转换，避免 getPixels() 将基图提前压成 8 位 sRGB。 */
    fun gpuData(pq: Boolean, sdr: Boolean, compactBase: Boolean = false): HdrGpuData {
        val gain = bitmap.gainmap
        val app5Gain = if (sdr || gain != null) null else app5
        if (!sdr) check(gain != null || app5Gain != null) { "图片没有可用的标准 Gainmap 或受支持的 APP5 附加图" }
        val baseSpace = base.colorSpace as? ColorSpace.Rgb ?: error("基图不是 RGB 色彩空间")
        var mathSpace = baseSpace
        if (Build.VERSION.SDK_INT >= 36 && gain != null) {
            // 新格式无法确认方向时明确失败，防止把 HDR 基图再次增亮。
            check(gain.gainmapDirection == Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR) {
                "独立 Surface 暂不重建以 HDR 为基图的反向 gainmap"
            }
            mathSpace = gain.alternativeImagePrimaries as? ColorSpace.Rgb ?: baseSpace
        }
        fun linear(rgb: ColorSpace.Rgb) = ColorSpace.Rgb("Probe linear ${rgb.name}",
            rgb.primaries, rgb.whitePoint, DoubleUnaryOperator { it }, DoubleUnaryOperator { it }, -65504f, 65504f)
        val linearMath = linear(mathSpace)
        val targetSpace = linear(ColorSpace.get(if (pq) ColorSpace.Named.BT2020 else ColorSpace.Named.SRGB) as ColorSpace.Rgb)
        // 自定义 DoubleUnaryOperator 色彩空间没有原生 ICC 参数，不能用于 Bitmap / Canvas。
        // 原生绘制使用系统线性扩展 sRGB，gainmap 要求的基色转换在着色器里完成。
        // sRGB 与 Display P3 使用相同 EOTF。硬件 sRGB 纹理在过滤前线性化，保留原基色，
        // 避免整幅 8 位基图在 CPU 上转换、复制为两倍大小的 FP16；其它格式沿用原路径。
        val srgbTexture = compactBase && supportsCompactBase(base)
        val textureSpace = if (srgbTexture) linear(baseSpace)
            else ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) as ColorSpace.Rgb
        fun transform(source: ColorSpace.Rgb, destination: ColorSpace.Rgb): FloatArray {
            val connector = ColorSpace.connect(source, destination, ColorSpace.RenderIntent.RELATIVE)
            return FloatArray(9).also { matrix ->
                for (column in 0..2) {
                    val basis = FloatArray(3) { if (it == column) 1f else 0f }
                    connector.transform(basis).copyInto(matrix, column * 3)
                }
            }
        }
        val baseToMath = transform(textureSpace, linearMath)
        val matrix = transform(linearMath, targetSpace)
        val pixels: ByteBuffer
        if (srgbTexture) {
            val raw = ByteBuffer.allocateDirect(base.byteCount).order(ByteOrder.nativeOrder())
            base.copyPixelsToBuffer(raw); raw.rewind()
            pixels = tightRows(raw, base.width * 4, base.rowBytes, base.height)
        } else {
            val fp16 = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.RGBA_F16, false, textureSpace)
            try {
                Canvas(fp16).drawBitmap(base, 0f, 0f, Paint())
                val raw = ByteBuffer.allocateDirect(fp16.byteCount).order(ByteOrder.nativeOrder())
                fp16.copyPixelsToBuffer(raw); raw.rewind()
                pixels = tightRows(raw, fp16.width * 8, fp16.rowBytes, fp16.height)
            } finally { fp16.recycle() }
        }
        val map = gain?.gainmapContents
        val mapWidth = map?.width ?: app5Gain?.width ?: 1
        val mapHeight = map?.height ?: app5Gain?.height ?: 1
        var gains = app5Gain?.pixels?.duplicate()?.order(ByteOrder.nativeOrder())
        if (app5Gain == null && map == null) gains = ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1))
        else if (map != null) {
            // 只读取原始增益通道，不能把增益纹理当颜色进行色彩空间转换。
            check(map.config == Bitmap.Config.ALPHA_8 || map.config == Bitmap.Config.ARGB_8888) {
                "暂不支持此 gainmap 存储格式：${map.config}"
            }
            val raw = ByteBuffer.allocateDirect(map.byteCount).order(ByteOrder.nativeOrder())
            map.copyPixelsToBuffer(raw)
            raw.rewind()
            if (map.config == Bitmap.Config.ARGB_8888) {
                // 采样器只读取 RGB，alpha 无须逐像素改写。直接批量复制原始增益字节。
                gains = tightRows(raw, map.width * 4, map.rowBytes, map.height)
            } else {
                val alpha = ByteArray(map.byteCount); raw.get(alpha)
                val rgba = IntArray(mapWidth * mapHeight)
                for (y in 0 until mapHeight) for (x in 0 until mapWidth) {
                    val value = alpha[y * map.rowBytes + x].toInt() and 255
                    rgba[y * mapWidth + x] = value * 0x010101 or (255 shl 24)
                }
                gains = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                gains.asIntBuffer().put(rgba)
            }
        }
        val gainPixels = checkNotNull(gains).apply { rewind() }
        // 8 是测试页余量选项的上界，绝非从未知私有字段推断出的照片最大增益。
        val referenceMaximum = if (app5Gain != null) HdrApp5Preview.REFERENCE_HEADROOM else 1f
        return HdrGpuData(base.width, base.height, pixels, mapWidth, mapHeight, gainPixels, baseToMath, matrix,
            gain?.ratioMin ?: floatArrayOf(1f, 1f, 1f), gain?.ratioMax ?: FloatArray(3) { referenceMaximum },
            gain?.gamma ?: floatArrayOf(1f, 1f, 1f), gain?.epsilonSdr ?: floatArrayOf(0f, 0f, 0f),
            gain?.epsilonHdr ?: floatArrayOf(0f, 0f, 0f), gain?.minDisplayRatioForHdrTransition ?: 1f,
            gain?.displayRatioForFullHdr ?: referenceMaximum, "baseUpload=${if (srgbTexture) "SRGB8" else "LINEAR_FP16"}; " +
                "baseBytes=${pixels.remaining()}; textureSpace=${textureSpace.name}; linearMath=${linearMath.name}; " +
                "output=${targetSpace.name}; baseToMath=${baseToMath.contentToString()}; mathToOutput=${matrix.contentToString()}; " +
                "hdrSurfaceSource=${if (sdr) "SDR" else if (app5Gain != null) "APP5_EXPERIMENTAL" else "ANDROID_GAINMAP"}" +
                (if (app5Gain != null) "\n${app5Gain.description}" else ""), srgbTexture)
    }

    private fun tightRows(raw: ByteBuffer, widthBytes: Int, stride: Int, height: Int): ByteBuffer {
        if (widthBytes == stride) return raw
        return ByteBuffer.allocateDirect(widthBytes * height).order(ByteOrder.nativeOrder()).apply {
            for (y in 0 until height) {
                raw.limit(y * stride + widthBytes).position(y * stride)
                put(raw)
                raw.clear()
            }
            rewind()
        }
    }
}

internal data class HdrGpuData(val width: Int, val height: Int, val base: ByteBuffer,
    val gainWidth: Int, val gainHeight: Int, val gain: ByteBuffer, val baseToMath: FloatArray, val matrix: FloatArray,
    val ratioMin: FloatArray, val ratioMax: FloatArray, val gamma: FloatArray,
    val epsilonSdr: FloatArray, val epsilonHdr: FloatArray, val minimum: Float, val full: Float,
    val description: String, val srgbTexture: Boolean = false)
