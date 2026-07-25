package com.ywwynm.everythingdone

import android.content.Context
import android.content.SharedPreferences

import com.ywwynm.everythingdone.utils.LocaleUtil

import java.util.HashMap

/**
 * Created by ywwynm on 2017/1/17.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Store settings that will be seen frequently during app lifecycle, which will also influence
 * ui very constantly. Usually, these settings belong to settings for UI that can be seen under
 * ui category in [com.ywwynm.everythingdone.activities.SettingsActivity]
 */
object FrequentSettings {

    const val TAG: String = "FrequentSettings"

    private var settingsMap: HashMap<String?, Any?>? = null

    init {
        loadFromSharedPreferences()
    }

    private fun loadFromSharedPreferences() {
        val app: App = App.getApp()!!
        val sp: SharedPreferences = app.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        settingsMap = HashMap()

        val languageCode: String = sp.getString(Def.Meta.KEY_LANGUAGE_CODE,
                LocaleUtil.LANGUAGE_CODE_FOLLOW_SYSTEM + "_")!!
        settingsMap!![Def.Meta.KEY_LANGUAGE_CODE] = languageCode

        val toggleCliOtc: Boolean = sp.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, false)
        settingsMap!![Def.Meta.KEY_TOGGLE_CLI_OTC] = toggleCliOtc

        val simpleFCli: Boolean = sp.getBoolean(Def.Meta.KEY_SIMPLE_FCLI, false)
        settingsMap!![Def.Meta.KEY_SIMPLE_FCLI] = simpleFCli

        val autoLink: Boolean = sp.getBoolean(Def.Meta.KEY_AUTO_LINK, false)
        settingsMap!![Def.Meta.KEY_AUTO_LINK] = autoLink

        val twiceBack: Boolean = sp.getBoolean(Def.Meta.KEY_TWICE_BACK, false)
        settingsMap!![Def.Meta.KEY_TWICE_BACK] = twiceBack

        val followSystemDarkMode: Boolean = sp.getBoolean(
            Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, false
        )
        settingsMap!![Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE] = followSystemDarkMode

        val forceDarkMode: Boolean = sp.getBoolean(Def.Meta.KEY_FORCE_DARK_MODE, false)
        settingsMap!![Def.Meta.KEY_FORCE_DARK_MODE] = forceDarkMode

        val closeLater: Boolean = sp.getBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, false)
        settingsMap!![Def.Meta.KEY_CLOSE_NOTIFICATION_LATER] = closeLater

        val autoSaveEdits: Boolean = sp.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS, false)
        settingsMap!![Def.Meta.KEY_AUTO_SAVE_EDITS] = autoSaveEdits

        val curOngoingId: Long = sp.getLong(Def.Meta.KEY_ONGOING_THING_ID, -1L)
        settingsMap!![Def.Meta.KEY_ONGOING_THING_ID] = curOngoingId
    }

    @JvmStatic
    fun put(key: String?, value: Any?) {
        settingsMap!![key] = value
    }

    @JvmStatic
    fun getBoolean(key: String?): Boolean {
        return getBoolean(key, false)
    }

    @JvmStatic
    fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (settingsMap!!.containsKey(key)) {
            return settingsMap!![key] as Boolean
        } else {
            val value: Boolean = getBooleanFromSp(key, defValue)
            put(key, value)
            return value
        }
    }

    @JvmStatic
    fun getInt(key: String?, defValue: Int): Int {
        if (settingsMap!!.containsKey(key)) {
            return settingsMap!![key] as Int
        } else {
            val value: Int = getIntFromSp(key, defValue)
            put(key, value)
            return value
        }
    }

    @JvmStatic
    fun getLong(key: String?): Long {
        return getLong(key, -1L)
    }

    @JvmStatic
    fun getLong(key: String?, defValue: Long): Long {
        if (settingsMap!!.containsKey(key)) {
            return settingsMap!![key] as Long
        } else {
            val value: Long = getLongFromSp(key, defValue)
            put(key, value)
            return value
        }
    }

    @JvmStatic
    fun getString(key: String?, defValue: String?): String? {
        if (settingsMap!!.containsKey(key)) {
            return settingsMap!![key] as String?
        } else {
            val value: String? = getStringFromSp(key, defValue)
            put(key, value)
            return value
        }
    }

    private fun getSp(): SharedPreferences {
        return App.getApp()!!.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private fun getBooleanFromSp(key: String?, defValue: Boolean): Boolean {
        return getSp().getBoolean(key, defValue)
    }

    private fun getIntFromSp(key: String?, defValue: Int): Int {
        return getSp().getInt(key, defValue)
    }

    private fun getLongFromSp(key: String?, defValue: Long): Long {
        return getSp().getLong(key, defValue)
    }

    private fun getStringFromSp(key: String?, defValue: String?): String? {
        return getSp().getString(key, defValue)
    }

}
