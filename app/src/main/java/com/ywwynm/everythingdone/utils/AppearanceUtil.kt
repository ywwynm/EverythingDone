package com.ywwynm.everythingdone.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings

object AppearanceUtil {

    @JvmStatic
    fun applyDefaultNightMode() {
        AppCompatDelegate.setDefaultNightMode(getDefaultNightMode())
    }

    @JvmStatic
    fun getDefaultNightMode(): Int {
        return if (isFollowSystemDarkMode()) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        } else if (isForceDarkMode()) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
    }

    @JvmStatic
    fun isFollowSystemDarkMode(): Boolean {
        return FrequentSettings.getBoolean(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, false)
    }

    @JvmStatic
    fun isForceDarkMode(): Boolean {
        return FrequentSettings.getBoolean(Def.Meta.KEY_FORCE_DARK_MODE, false)
    }

    @JvmStatic
    fun isDarkMode(context: Context): Boolean {
        if (!isFollowSystemDarkMode() && isForceDarkMode()) {
            return true
        }
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 与 [getDefaultNightMode] 同源的暗色判定，**不依赖调用者 Context 自身的配置**。
     *
     * [isDarkMode] 对 Activity 是准的：AppCompat 已经按默认夜间模式覆写过它的配置。但
     * Service 与 Application Context 拿不到这份覆写，它们的 `uiMode` 只跟系统走——于是
     * “应用固定浅色、系统深色”会被判成深色，“应用固定深色、系统浅色”又只能靠
     * [isForceDarkMode] 那条短路兜住。后台工作（例如离线导出）要复现界面外观，必须按
     * 这里的口径判定。
     */
    @JvmStatic
    fun isDarkModeApplied(context: Context): Boolean {
        if (!isFollowSystemDarkMode()) {
            return isForceDarkMode()
        }
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }
}
