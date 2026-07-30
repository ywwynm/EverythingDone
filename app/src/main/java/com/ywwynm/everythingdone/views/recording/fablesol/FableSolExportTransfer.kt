package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
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

        /** ST 2086 虚拟母版的最低亮度，单位 0.0001 尼特（D89）。 */
        const val MASTERING_MIN_LUMINANCE_UNITS = 1

        /**
         * 写进 ST 2086 MDCV 的**声明母版峰值**（尼特）。
         *
         * 只服务静态元数据这一处。HDR10+ 曲线的横轴是**场景 V8 百分位**（D177、D180），
         * 不读本值——把它当成曲线的归一化母版是 D176 已被推翻的做法，不得恢复。
         */
        fun masteringPeakNits(
            peakNits: Double,
            diffuseWhiteNits: Double,
            luminance: FableSolExportLuminanceStats
        ): Int = ceil(peakNits).toInt()
            .coerceAtLeast(luminance.maxContentLightLevel(diffuseWhiteNits))
            .coerceIn(1, PQ_MAX_NITS.toInt())

        /**
         * HDR10 的静态母版元数据（CTA-861.3 Static Metadata Descriptor ID 0，25 字节）。
         *
         * 四组字段各司其职，不能互相顶替（D89）：
         *
         * - **primaries + 白点 + 亮度范围**描述虚拟母版**显示色容积**。编码容器仍是 BT.2020，
         *   母版 primaries 用 **P3-D65**（D88）：两者说的是不同的事——BT.2020 决定码值怎么
         *   解释，P3-D65 描述承载创作意图的母版显示器。FableSol 的身份色是 Rec.709 子集，
         *   完整落在 P3 之内，因此这不是虚报，也不做 Rec.709→P3 的创作扩色。
         * - **MaxCLL** 是全片实际出现的最高 `maxRGB`；
         * - **MaxFALL** 是全片实际出现的最高帧平均 `maxRGB`，未知时写 0（D90）。
         *
         * 母版最高亮度取 `ceil(漫反射白 × HDR 强度)`，并至少覆盖实测 MaxCLL——预分析可能因
         * 数值边界测出略高的值，母版色容积不能反而装不下自己的内容。不按 1000/2000/4000
         * 监视器档位取整：FableSol 没有实际调色监视器，任意抬高只会让"只看 MDCV、忽略
         * CLLI"的播放端做不必要的强压缩。
         */
        fun hdr10StaticInfo(
            peakNits: Double,
            diffuseWhiteNits: Double,
            luminance: FableSolExportLuminanceStats
        ): ByteBuffer {
            val maxContent = luminance.maxContentLightLevel(diffuseWhiteNits)
            val masteringMax = masteringPeakNits(peakNits, diffuseWhiteNits, luminance)
            val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(0)                       // descriptor id
            putPrimary(buffer, 0.680, 0.320)    // Display P3 red（D88）
            putPrimary(buffer, 0.265, 0.690)    // Display P3 green
            putPrimary(buffer, 0.150, 0.060)    // Display P3 blue
            putPrimary(buffer, 0.3127, 0.3290)  // D65 white point（D87）
            buffer.putShort(masteringMax.toShort())
            buffer.putShort(MASTERING_MIN_LUMINANCE_UNITS.toShort())
            buffer.putShort(maxContent.toShort())
            buffer.putShort(
                luminance.maxFrameAverageLightLevel(diffuseWhiteNits).toShort()
            )
            buffer.rewind()
            return buffer
        }

        private fun putPrimary(buffer: ByteBuffer, x: Double, y: Double) {
            buffer.putShort((x / 0.00002).roundToInt().toShort())
            buffer.putShort((y / 0.00002).roundToInt().toShort())
        }
    }
}
