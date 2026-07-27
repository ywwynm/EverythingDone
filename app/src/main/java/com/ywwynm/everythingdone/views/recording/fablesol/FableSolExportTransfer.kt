package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 导出信号的传递函数。它与"编码档位"（codec / profile / 尺寸 / 帧率）是**两条互相独立的
 * 轴**：同一个 HEVC Main10 编码器既可以出 HLG，也可以出 PQ。
 *
 * 为什么必须两条都支持，而不是只认 HLG：
 *
 * - **余量**。HLG 按 BT.2408 把漫反射白定在信号 0.75，其上只剩约 3.77 倍；而用户 HDR 强度
 *   上限是 9.6，超出的部分只能压缩。PQ 是绝对亮度、上限 10000 尼特，9.6 倍漫反射白约
 *   1949 尼特，放得下且不用压。
 * - **可用性**。`EGL_EXT_gl_colorspace_bt2020_pq` 比 `..._bt2020_hlg` 出现得更早、支持面
 *   更广。此前整条 HDR 通路只认 HLG 这一个扩展，只有 PQ 的设备（三星那一系的 HDR10+
 *   生态正是 PQ）会被判成"不支持 HDR"，而它其实完全够格。
 */
internal enum class FableSolExportTransfer {

    /** BT.709，非 HDR。 */
    SDR,

    /** BT.2020 + HLG。 */
    HLG,

    /** BT.2020 + PQ（ST.2084），即 HDR10。 */
    PQ;

    val isHdr: Boolean get() = this != SDR

    /** EGL window surface 的色彩空间常量；SDR 不打色彩空间属性。 */
    val eglColorSpace: Int?
        get() = when (this) {
            SDR -> null
            HLG -> EGL_GL_COLORSPACE_BT2020_HLG_EXT
            PQ -> EGL_GL_COLORSPACE_BT2020_PQ_EXT
        }

    val eglExtension: String?
        get() = when (this) {
            SDR -> null
            HLG -> EXTENSION_BT2020_HLG
            PQ -> EXTENSION_BT2020_PQ
        }

    val mediaFormatTransfer: Int
        get() = when (this) {
            SDR -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            HLG -> MediaFormat.COLOR_TRANSFER_HLG
            PQ -> MediaFormat.COLOR_TRANSFER_ST2084
        }

    val mediaFormatStandard: Int
        get() = if (isHdr) {
            MediaFormat.COLOR_STANDARD_BT2020
        } else {
            MediaFormat.COLOR_STANDARD_BT709
        }

    /** 用户可见的短标签，进档位名与完成态提示。 */
    val label: String
        get() = when (this) {
            SDR -> "SDR"
            HLG -> "HLG"
            PQ -> "HDR10"
        }

    companion object {

        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        const val EXTENSION_BT2020_HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"
        const val EXTENSION_BT2020_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"

        /**
         * SDR 参考白的绝对亮度（BT.2408 建议 203 尼特）。PQ 是绝对亮度曲线，必须先定这个
         * 锚点，"1.0 倍白"才有确定的物理含义。
         */
        const val SDR_WHITE_NITS = 203.0

        /** PQ 的信号上限。 */
        const val PQ_MAX_NITS = 10000.0

        /**
         * HDR10 的静态母版元数据（CTA-861.3 Static Metadata Descriptor ID 0，25 字节）。
         *
         * 我们的峰值是**解析可算**的（HDR 强度 × [SDR_WHITE_NITS]），不像实拍内容那样只能
         * 估，所以 MaxCLL 给的是准确值而不是保守猜测。
         */
        fun hdr10StaticInfo(
            peakNits: Double,
            frameAverageNits: Double = SDR_WHITE_NITS
        ): ByteBuffer {
            val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(0)                       // descriptor id
            putPrimary(buffer, 0.708, 0.292)    // BT.2020 red
            putPrimary(buffer, 0.170, 0.797)    // BT.2020 green
            putPrimary(buffer, 0.131, 0.046)    // BT.2020 blue
            putPrimary(buffer, 0.3127, 0.3290)  // D65 white point
            buffer.putShort(peakNits.roundToInt().coerceIn(1, 10000).toShort())
            buffer.putShort(1)                  // 最小亮度，单位 0.0001 尼特
            buffer.putShort(peakNits.roundToInt().coerceIn(1, 65535).toShort()) // MaxCLL
            // MaxFALL：水面的平均画面亮度远低于峰值，取漫反射白这一档是诚实且保守的估计。
            // 漫反射白已经可调（200–800），所以这里必须跟着走，不能再写死 203。
            buffer.putShort(frameAverageNits.roundToInt().coerceIn(1, 65535).toShort())
            buffer.rewind()
            return buffer
        }

        private fun putPrimary(buffer: ByteBuffer, x: Double, y: Double) {
            buffer.putShort((x / 0.00002).roundToInt().toShort())
            buffer.putShort((y / 0.00002).roundToInt().toShort())
        }
    }
}
