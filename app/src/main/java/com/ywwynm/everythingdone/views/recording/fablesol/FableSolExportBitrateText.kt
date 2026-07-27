package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.Locale

/**
 * 把码率写成人看的数。
 *
 * 之所以单独放一处：完成对话框与通知两边都要用，而且**恒定质量档下这个数只能事后算**——
 * 那个档位里 `KEY_BIT_RATE` 只是提示，事前给数字反而误导；产物落盘之后用文件大小除以时长
 * 才是真实码率。两边必须给出同一个说法。
 */
internal object FableSolExportBitrateText {

    fun of(bitrateBps: Long): String {
        if (bitrateBps <= 0L) return "—"
        val mbps = bitrateBps / 1_000_000.0
        return if (mbps >= 10.0) {
            String.format(Locale.US, "%.0f Mbps", mbps)
        } else {
            String.format(Locale.US, "%.1f Mbps", mbps)
        }
    }
}
