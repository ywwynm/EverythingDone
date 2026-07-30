package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.AppearanceUtil

/**
 * 离线导出的外观 Context。
 *
 * 产物是“此刻这个界面的忠实记录”（fablesol-video-export D4），所以画框底色、卡片底色和
 * 时钟的 hostDark 必须与屏上完全一致。屏上那份来自对话框的 Context：主题
 * `EverythingDoneTheme.Dialog` 把 `android:colorBackground` 指到
 * `@color/app_chrome_surface_elevated`，配置由 AppCompat 按默认夜间模式覆写。
 *
 * 导出跑在 Service 里，两样都没有：
 *
 * - `<application>` 没有 `android:theme`，Application Context 的主题是**平台默认的**
 *   `Theme.DeviceDefault.Light.DarkActionBar`（targetSdk ≥ 21 的默认选择）。它的
 *   `colorBackground` 恒为浅色，与夜间资源无关——这正是深色模式下画框已经变黑、卡片却
 *   仍是白的原因。
 * - Service 的 `uiMode` 只跟系统走，读不到 AppCompat 对 Activity 的夜间覆写。
 *
 * 因此这里把两件事一次性补齐：按 [AppearanceUtil.isDarkModeApplied] 钉死夜间位，再套上
 * 对话框主题。之后 [FableSolExportSpec]、[FableSolExportClock] 与
 * [FableSolGlRenderer] 只要都拿这一个 Context，三者就不可能各判各的。
 */
internal object FableSolExportAppearance {

    /**
     * 本次导出实际生效的夜间位。
     *
     * 全片亮度预分析的缓存指纹要它（D92）：深浅两套外观的画框底色、卡片底色与时钟墨色都不同，
     * 帧平均亮度会跟着变，同一份录音在两种外观下不能共用一份统计。
     */
    fun isDark(base: Context): Boolean = AppearanceUtil.isDarkModeApplied(base)

    fun themedContext(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (AppearanceUtil.isDarkModeApplied(base)) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        return ContextThemeWrapper(
            base.createConfigurationContext(configuration),
            R.style.EverythingDoneTheme_Dialog
        )
    }
}
