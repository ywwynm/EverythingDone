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
}
