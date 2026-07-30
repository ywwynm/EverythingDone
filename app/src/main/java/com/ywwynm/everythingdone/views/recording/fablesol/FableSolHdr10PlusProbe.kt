package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 独立验证字节缓冲输入路径能否生成包含 ST 2094-40 动态元数据的 HDR10+ 码流。
 *
 * 背景。我们的正式导出走 surface 输入（GL 直接画进 `MediaCodec.createInputSurface()`），
 * 而 HDR10+ 的动态元数据（ST 2094-40）是内嵌 SEI，在 surface 模式下**应用无法提供**——
 * `PARAMETER_KEY_HDR10_PLUS_INFO` 的文档明确写着该参数不适用于 surface 输入模式。三星
 * S23 Ultra 上的实测结果与此一致：编码器收下配置却把 profile 从 `Main10HDR10Plus`（8192）
 * 降回 `Main10`（2）。
 *
 * 但**字节缓冲输入是另一回事**：那正是该参数唯一被允许使用的模式，也是 AOSP CTS 里
 * HDR10+ 编码用例采用的模式。所以"设备做不到"与"我们这条链路做不到"是两个命题，前者
 * 还没有被验证过。这个探测就是去验证前者。
 *
 * 它**完全独立于导出管线**：不建 EGL、不碰渲染器、不写文件，只喂一帧 P010 到编码器再看
 * 输出格式回报的 profile。目的是在投入"自己做 RGB→P010 转换 + 自己算 ST 2094-40 统计"
 * 这一整套之前，先确认那套东西值不值得做。
 */
@SuppressLint("InlinedApi")
internal object FableSolHdr10PlusProbe {

    /**
     * @param codecName 广告了 HDR10+ profile 的编码器；null 表示一个都没有。
     * @param p010Supported 该编码器是否接受 `COLOR_FormatYUVP010` 字节缓冲输入。
     * @param withoutMetadata 只切字节缓冲、不喂元数据时输出格式回报的 profile。
     * @param withMetadata 同时喂一份 ST 2094-40 元数据时的结果。
     */
    class Result(
        val codecName: String?,
        val p010Supported: Boolean,
        val withoutMetadata: String,
        val withMetadata: String
    )

    fun run(widthPx: Int, heightPx: Int, frameRate: Int): Result {
        // P010 的字节缓冲常量与元数据参数都是 API 31 才有的；更早的系统没有这条路可谈。
        if (Build.VERSION.SDK_INT < 31) {
            return Result(null, false, "Android 12 以下不支持该公开接口", "—")
        }
        val info = findEncoder()
            ?: return Result(null, false, "未发现声明 HDR10+ Profile 的编码器", "—")
        val capabilities = try {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        } catch (error: Throwable) {
            return Result(
                info.name,
                false,
                "读取编码器能力失败（${error.javaClass.simpleName}）",
                "—"
            )
        }
        val p010 = capabilities.colorFormats.contains(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
        )
        if (!p010) {
            return Result(info.name, false, "编码器不支持 P010 字节缓冲输入", "—")
        }
        val level = capabilities.profileLevels
            .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus }
            .maxByOrNull { it.level }
            ?.level ?: 0
        // 分别验证基础 P010 编码与逐帧提交 ST 2094-40 的条件，以区分编码输入能力和
        // 动态元数据提交能力。
        val without = attempt(info.name, level, widthPx, heightPx, frameRate, metadata = null)
        val with = attempt(
            info.name, level, widthPx, heightPx, frameRate, metadata = hdr10PlusPayload()
        )
        return Result(info.name, true, without, with)
    }

    private fun findEncoder(): MediaCodecInfo? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder &&
                info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    .profileLevels.any {
                        it.profile ==
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
        }
    } catch (ignored: Throwable) {
        null
    }

    private fun attempt(
        codecName: String,
        level: Int,
        widthPx: Int,
        heightPx: Int,
        frameRate: Int,
        metadata: ByteBuffer?
    ): String {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(codecName)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC, widthPx, heightPx
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                )
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                )
                if (level != 0) setInteger(MediaFormat.KEY_LEVEL, level)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
                setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                // 探测只关心"这台机器能不能把 ST 2094-40 SEI 写进码流"，内容亮度无从测起，
                // 因此用 D90 的理论回退填静态元数据：结构完整、数值有效，且不冒充实测。
                setByteBuffer(
                    MediaFormat.KEY_HDR_STATIC_INFO,
                    FableSolExportTransfer.hdr10StaticInfo(
                        peakNits = FableSolHdrPolicy.MAX_STRENGTH *
                            FableSolExportTransfer.SDR_WHITE_NITS,
                        diffuseWhiteNits = FableSolExportTransfer.SDR_WHITE_NITS,
                        luminance = FableSolExportLuminanceStats.theoretical(
                            FableSolHdrPolicy.MAX_STRENGTH.toDouble()
                        )
                    )
                )
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val inputFormat = codec.inputFormat
            val stride = inputFormat.intOr(MediaFormat.KEY_STRIDE, widthPx * 2)
            val sliceHeight = inputFormat.intOr(MediaFormat.KEY_SLICE_HEIGHT, heightPx)

            metadata?.let {
                codec.setParameters(
                    android.os.Bundle().apply {
                        putByteArray(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO, it.array())
                    }
                )
            }

            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return "未在限定时间内取得编码器输入缓冲"
            val buffer = codec.getInputBuffer(index) ?: return "编码器未提供输入缓冲"
            val written = fillFlatP010(buffer, stride, sliceHeight, heightPx)
            codec.queueInputBuffer(index, 0, written, 0L, 0)

            val endIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (endIndex >= 0) {
                codec.queueInputBuffer(
                    endIndex, 0, 0, FRAME_DURATION_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drainForProfile(codec)
        } catch (error: Throwable) {
            "${error.javaClass.simpleName}: ${error.message ?: "未提供详细信息"}"
        } finally {
            try {
                codec?.stop()
            } catch (ignored: Throwable) {
            }
            try {
                codec?.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 判据是**码流里有没有那段 SEI**，不是输出格式回报的 profile。
     *
     * 这一点上我先前判错过。HEVC 层面根本没有"HDR10+ profile"这种东西——码流的
     * `general_profile_idc` 就是 Main10（2），HDR10+ 只是额外多一段 T.35 SEI。Android 的
     * `HEVCProfileMain10HDR10Plus`（8192）是框架层拼出来的合成常量，编码器把输出格式回报成
     * 2 完全可能只是在陈述码流的真实 profile，而不是"拒绝了 HDR10+"。
     *
     * 所以直接在输出字节里找那段 SEI 的签名：`B5 00 3C 00 01`（T.35 国家码 + 杜比之外的
     * HDR10+ 厂商码 + application_identifier）。这五个字节里没有连续两个 0x00，因此不会被
     * 防竞争字节（0x03）打断，可以直接按字节匹配。
     */
    private fun drainForProfile(codec: MediaCodec): String {
        val bufferInfo = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
        var reportedProfile: Int? = null
        var sawOutput = false
        while (System.nanoTime() < deadline) {
            when (val out = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    reportedProfile = codec.outputFormat.intOr(MediaFormat.KEY_PROFILE, 0)
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (out >= 0) {
                    sawOutput = true
                    val buffer = codec.getOutputBuffer(out)
                    val hasSei = buffer != null && containsHdr10PlusSei(buffer, bufferInfo)
                    codec.releaseOutputBuffer(out, false)
                    if (hasSei) {
                        return "检测到 HDR10+ ST 2094-40 SEI（输出 Profile：$reportedProfile）"
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return when {
            !sawOutput -> "在限定时间内未检测到编码输出"
            else -> "未检测到 HDR10+ ST 2094-40 SEI（输出 Profile：$reportedProfile）"
        }
    }

    /** 在一个输出缓冲里找 HDR10+ 的 T.35 SEI 签名。 */
    private fun containsHdr10PlusSei(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Boolean {
        val bytes = ByteArray(info.size)
        val duplicate = buffer.duplicate()
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)
        duplicate.get(bytes)
        outer@ for (start in 0..bytes.size - SEI_SIGNATURE.size) {
            for (offset in SEI_SIGNATURE.indices) {
                if (bytes[start + offset] != SEI_SIGNATURE[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * 填一帧平场 P010：Y 与 CbCr 都是 10-bit 中值，样本按 16-bit 小端存放、有效位在**高位**。
     * 探测只关心编码器认不认这条通路，画面内容无关紧要。
     */
    private fun fillFlatP010(
        buffer: ByteBuffer,
        stride: Int,
        sliceHeight: Int,
        heightPx: Int
    ): Int {
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val sample = (MID_10_BIT shl 6).toShort()
        val samplesPerRow = stride / 2
        val chromaRows = (heightPx + 1) / 2
        val shorts = buffer.asShortBuffer()
        val total = samplesPerRow * (sliceHeight + chromaRows)
        val count = minOf(total, shorts.capacity())
        for (i in 0 until count) shorts.put(i, sample)
        return count * 2
    }

    /**
     * 探测用的载荷：直接复用正式通路那一份写入器，只是统计量用占位值。
     *
     * **不再自带一份 ST 2094-40 写入器**——两份实现迟早会漂移，而这东西错一位后面全部错位，
     * 极难反查。探测要验证的是"设备认不认这条路"，用的载荷必须和正式导出是同一份代码。
     */
    internal fun hdr10PlusPayload(): ByteBuffer {
        val stats = FableSolHdr10PlusStats.placeholder(
            PEAK_NITS.toDouble() / FableSolExportTransfer.PQ_MAX_NITS
        )
        val curve = FableSolExportHdr10PlusCurve(
            sourcePeakNits = FableSolExportHdr10PlusCurve.sourcePeakNits(stats)
        ).shapeForScene(stats)
        return FableSolExportHdr10PlusMetadata.payload(
            stats, curve, FableSolExportHdr10PlusCurve.DEFAULT_TARGET_NITS
        )
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int = try {
        if (containsKey(key)) getInteger(key) else fallback
    } catch (ignored: Throwable) {
        fallback
    }

    /** T.35 国家码 0xB5 + terminal_provider_code 0x003C + oriented_code 高位 0x00 0x01。 */
    private val SEI_SIGNATURE = byteArrayOf(
        0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01
    )

    private const val MID_10_BIT = 512
    private const val TIMEOUT_US = 20_000L
    private const val FRAME_DURATION_US = 8_333L
    private const val DRAIN_TIMEOUT_NANOS = 3_000_000_000L

    /** 我们的峰值：HDR 强度上限 × 203 尼特，取整。 */
    private const val PEAK_NITS = 1949
}
