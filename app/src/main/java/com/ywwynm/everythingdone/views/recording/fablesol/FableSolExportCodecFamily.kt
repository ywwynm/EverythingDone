package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/**
 * 用户可选的视频编码器族。
 *
 * 与 [FableSolExportHdrFormat] 是两条独立的轴：格式决定信号与元数据，编码器族决定用哪套
 * 压缩标准。两者并非自由组合——HDR 各格式的阶梯里根本没有 AVC，杜比视界只走
 * `video/dolby-vision` 一个 MIME，所以可行组合必须由 [FableSolExportCapabilityMatrix]
 * 按实测结论给出，而不是让界面把三个轴当成互不相干的下拉框摆出来。
 *
 * 杜比视界归入 [HEVC]：它的基层就是 HEVC，编码器也是同一颗，只是换了 MIME 让厂商组件
 * 额外产出元数据层。
 */
internal enum class FableSolExportCodecFamily(
    /** 固定英文标识；进能力缓存、档位名与文件名，不得随 locale 改变。 */
    val stableLabel: String
) {

    HEVC("HEVC"),
    AV1("AV1"),
    AVC("H.264");

    companion object {

        fun of(mime: String): FableSolExportCodecFamily? = when {
            mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> HEVC
            mime.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) -> HEVC
            mime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> AV1
            mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> AVC
            else -> null
        }

        fun fromStableLabel(label: String?): FableSolExportCodecFamily? =
            label?.let { value -> entries.firstOrNull { it.stableLabel == value } }

        /**
         * 这个编码器实现是不是纯软件的。
         *
         * 判据优先用 API 29 起的 `isSoftwareOnly()`；更早的系统上退回名字前缀，那是 Android
         * 一直沿用的约定（`OMX.google.*` 与 `c2.android.*` 都是 AOSP 的软件实现）。
         *
         * 这件事必须知道：本项目导出的画布接近两百万像素，软件编码器编一段几十秒的录音要
         * 花的时间与硬件编码器差一到两个数量级。自动档因此**不使用**软件编码器，只有用户
         * 明确选中该编码器族时才走这条路。
         */
        fun isSoftwareOnly(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return try {
                    info.isSoftwareOnly
                } catch (ignored: Throwable) {
                    isSoftwareName(info.name)
                }
            }
            return isSoftwareName(info.name)
        }

        private fun isSoftwareName(name: String): Boolean =
            name.startsWith("OMX.google.", ignoreCase = true) ||
                name.startsWith("c2.android.", ignoreCase = true)
    }
}
