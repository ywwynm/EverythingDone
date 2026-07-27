package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * 从屏幕自身的能力推一个漫反射白的默认值。
 *
 * ### 为什么要推
 *
 * PQ 是绝对亮度，漫反射白写多少就渲染多亮。写死 203 尼特（BT.2408 给暗室监视器定的数）在
 * 今天的手机上明显偏暗——这些屏幕的 HDR 峰值动辄一两千尼特。屏幕越亮，背景就该坐得越高，
 * 这个数不该是个常量。
 *
 * ### 取"峰值的四分之一"
 *
 * 与 HDR 强度上限（9.6）无关地固定取四分之一，理由是这两件事要分开：
 *
 * - **漫反射白**决定水体与卡片看起来够不够亮，对标的是这块屏幕能有多亮；
 * - **HDR 强度**决定高光比水体亮多少，那是作者意图，不该被屏幕反过来改写。
 *
 * 若按"让高光正好落在屏幕峰值"去反推（峰值 ÷ 9.6），一块 2600 尼特的屏幕只会得到 271 尼特，
 * 比现在还暗——那等于让屏幕越好、画面越暗，方向就反了。四分之一意味着背景舒服地亮着，
 * 高光冲出屏幕上限的那部分交给 HDR10+ 的色调映射曲线去压，这正是"动态感"的来源。
 *
 * ### 只作默认值
 *
 * 读的是屏幕的**能力**（`getDesiredMaxLuminance`），不是它此刻的亮度设置。后者会让同一段
 * 音频在不同亮度下导出成不同亮度的文件——产物的绝对亮度不该被导出那一刻的屏幕状态挟持
 * （fablesol-video-export D5 已经为此拒绝过读 `hdrSdrRatio`）。用户随时可以在设置里改。
 */
internal object FableSolExportDisplayLuminance {

    /**
     * @return 推荐的漫反射白（尼特）。读不到或读到不可信的值时返回
     *   [FableSolExportOptions.DEFAULT_PQ_WHITE_NITS]。
     */
    fun autoWhiteNits(context: Context): Float {
        val peak = panelPeakNits(context) ?: return FableSolExportOptions.DEFAULT_PQ_WHITE_NITS
        return (peak / BACKGROUND_HEADROOM).coerceIn(
            FableSolExportOptions.MIN_PQ_WHITE_NITS,
            FableSolExportOptions.MAX_PQ_WHITE_NITS
        )
    }

    /**
     * 屏幕声明的 HDR 内容峰值亮度（尼特）；读不到或明显不可信时返回 null。
     *
     * `getDesiredMaxLuminance` 在 API 34 被标记为过时——原因正是"厂商填的值不可靠"，所以
     * 这里必须自己夹一道合理区间，而不是照单全收。它同时也进诊断行，好让"自动值是怎么来的"
     * 可以被核对。
     */
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun panelPeakNits(context: Context): Float? = try {
        val manager = context.applicationContext
            .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
        val reported = display?.hdrCapabilities?.desiredMaxLuminance
        if (reported != null && reported.isFinite() &&
            reported >= MIN_PLAUSIBLE_PEAK_NITS && reported <= MAX_PLAUSIBLE_PEAK_NITS
        ) {
            reported
        } else {
            null
        }
    } catch (ignored: Throwable) {
        null
    }

    /** 背景坐在屏幕峰值的四分之一处。 */
    const val BACKGROUND_HEADROOM = 4f

    // 低于这个数说明厂商填的是占位值（常见 0 或 100）；高于这个数是明显的胡填。
    private const val MIN_PLAUSIBLE_PEAK_NITS = 300f
    private const val MAX_PLAUSIBLE_PEAK_NITS = 10000f
}
