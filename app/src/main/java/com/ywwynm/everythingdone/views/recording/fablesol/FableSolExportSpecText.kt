package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import com.ywwynm.everythingdone.R
import java.util.Locale

/**
 * 完成态要补的那一行色彩规格。
 *
 * 单独放一处是因为**完成对话框与通知必须给出同一个说法**——两边各写一遍，迟早会漂移成
 * 两个版本。
 *
 * 三个数只在真正生效时才出现：漫反射白与峰值只对 PQ 系格式成立（HLG 系是相对亮度，没有
 * 绝对锚点），高光起点更是只对 HDR10+ 成立（只有它带色调映射曲线）。不生效还写出来，
 * 等于告诉用户一个不影响产物的数。
 */
internal object FableSolExportSpecText {

    /**
     * @param whiteNits 漫反射白；≤0 表示这次导出不是 PQ 系，整行不出现。
     * @param peakNits 峰值 = 漫反射白 × HDR 强度。
     * @param highlightStartPercent 高光起点百分位；≤0 表示不是 HDR10+，该项不出现。
     * @return 可直接拼在完成文案后面的一段（含换行）；不适用时为空串。
     */
    fun detail(
        context: Context,
        whiteNits: Double,
        peakNits: Double,
        highlightStartPercent: Int
    ): String {
        if (whiteNits <= 0.0) return ""
        val builder = StringBuilder(
            context.getString(
                R.string.fablesol_export_detail_hdr,
                String.format(Locale.US, "%.0f", whiteNits),
                String.format(Locale.US, "%.0f", peakNits)
            )
        )
        if (highlightStartPercent > 0) {
            builder.append(
                context.getString(
                    R.string.fablesol_export_detail_highlight,
                    highlightStartPercent
                )
            )
        }
        return builder.toString()
    }
}
