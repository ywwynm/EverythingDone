package com.ywwynm.everythingdone.hdr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** APP5 的真实辅助图纹理；参考曲线只在独立 Surface 中使用，不伪造 Android Gainmap。 */
internal data class HdrApp5Preview(val width: Int, val height: Int, val pixels: ByteBuffer, val description: String) {
    companion object {
        const val REFERENCE_HEADROOM = 8f
        fun decode(file: File, base: Bitmap): HdrApp5Preview? {
            val started = System.nanoTime()
            val payload = HdrApp5.read(file) ?: return null
            val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            require(orientation in 0..8) { "app5_unsupported_orientation" }
            val transpose = orientation in 5..8
            val expectedWidth = if (transpose) payload.baseHeight else payload.baseWidth
            val expectedHeight = if (transpose) payload.baseWidth else payload.baseHeight
            require(HdrApp5.compatible(expectedWidth, expectedHeight, base.width, base.height)) { "app5_decoded_base_geometry_mismatch" }
            var sample = 1
            while (maxOf(payload.width, payload.height) / sample > HdrProbeImage.MAX_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val map = requireNotNull(BitmapFactory.decodeByteArray(payload.jpeg, 0, payload.jpeg.size, options)) { "app5_auxiliary_decode_failed" }
            try {
                require(HdrApp5.compatible(payload.width, payload.height, map.width, map.height)) { "app5_decoded_auxiliary_geometry_mismatch" }
                val width = if (transpose) map.height else map.width
                val height = if (transpose) map.width else map.height
                val colors = IntArray(map.width * map.height)
                map.getPixels(colors, 0, map.width, 0, 0, map.width, map.height)
                val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                val rgba = IntArray(width * height)
                var minimum = 255; var maximum = 0; var sum = 0L; var chromaMax = 0
                val histogram = IntArray(16)
                for (y in 0 until height) for (x in 0 until width) {
                    val c = colors[HdrApp5.pixelIndex(x, y, map.width, map.height, orientation)]
                    val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                    // JPEG 的 Y' 近似恢复；ICC 已在内存容器中移除，不做 EOTF 或色域转换。
                    val luma = (77 * r + 150 * g + 29 * b + 128) shr 8
                    minimum = minOf(minimum, luma); maximum = maxOf(maximum, luma); sum += luma
                    chromaMax = maxOf(chromaMax, maxOf(r, g, b) - minOf(r, g, b))
                    histogram[luma / 16]++
                    rgba[y * width + x] = luma * 0x010101 or (255 shl 24)
                }
                // 此变体按单通道参考增强。大幅彩色信号意味着不能沿用这条已验证的提取路径。
                require(chromaMax <= 8) { "app5_auxiliary_not_neutral_chroma:$chromaMax" }
                pixels.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(rgba)
                pixels.rewind()
                return HdrApp5Preview(width, height, pixels,
                    "${payload.description}\napp5Decode=ready; nativeGainmap=false; decodedAuxiliary=${width}x$height; " +
                        "sample=$sample; exifOrientation=$orientation; auxiliaryOrientation=inherited_from_base; " +
                        "lumaExtraction=rounded_BT601_from_RGB_without_ICC; lumaMin=$minimum; lumaMax=$maximum; " +
                        "lumaMean=${sum.toDouble() / colors.size}; chromaMax=$chromaMax; lumaHistogram16=${histogram.contentToString()}; " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000}\n" +
                        "reconstruction=experimental_reference_log_gain; ratio=selectedHeadroom^(luma/255); " +
                        "calibration=unverified; privateMetadataApplied=false; 仅用于 APP5 实验对照，未确认厂商曲线。")
            } finally { map.recycle() }
        }
    }
}
