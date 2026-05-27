@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.DisplayMetrics
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R

import java.text.NumberFormat
import java.util.Locale

/**
 * Created by ywwynm on 2015/8/3.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Helper for localization
 */
object LocaleUtil {

    const val TAG: String = $$"EverythingDone$LocaleUtil"
    const val LANGUAGE_CODE_FOLLOW_SYSTEM: String = "follow system"

    private const val LANGUAGE_TAG_FOLLOW_SYSTEM: String = ""

    @JvmStatic
    fun getSystemLocale(context: Context?): Locale? {
        return context!!.resources.configuration.getLocales().get(0)
    }

    @JvmStatic
    fun setAppLocale(configuration: Configuration?, locale: Locale?) {
        configuration!!.setLocale(locale)
    }

    @JvmStatic
    fun isChinese(context: Context?): Boolean {
        return isSimplifiedChinese(context) || isTraditionalChinese(context)
    }

    @JvmStatic
    fun isSimplifiedChinese(context: Context?): Boolean {
        return getSystemLocale(context)!!.language
                .equals(Locale.SIMPLIFIED_CHINESE.language)
    }

    @JvmStatic
    fun isTraditionalChinese(context: Context?): Boolean {
        return getSystemLocale(context)!!.language
                .equals(Locale.TRADITIONAL_CHINESE.language)
    }

    @JvmStatic
    fun getLanguageDescription(languageCode: String?): String? {
        val res: Resources = App.getApp()!!.resources
        val lanCodes: Array<String?> = res.getStringArray(R.array.language_codes)
        var index = 0
        for (i in 0 until lanCodes.size) {
            if (sameLanguageCode(lanCodes[i], languageCode)) {
                index = i
                break
            }
        }
        return res.getStringArray(R.array.languages)[index]
    }

    @JvmStatic
    fun changeLanguage() {
        val app: App = App.getApp() ?: return
        syncAppCompatLocalesFromStorage(app, false)
    }

    @JvmStatic
    fun changeLanguage(language: String, countryOrDistinct: String) {
        var lang: String = language
        var country: String = countryOrDistinct
        if (LANGUAGE_CODE_FOLLOW_SYSTEM == lang) {
            lang = Locale.getDefault().language
            country = Locale.getDefault().country
        }
        val resources: Resources = App.getApp()!!.resources
        val dm: DisplayMetrics = resources.displayMetrics
        val configuration: Configuration = resources.configuration
        setAppLocale(configuration, Locale(lang, country))
        resources.updateConfiguration(configuration, dm)
    }

    @JvmStatic
    fun attachBaseContext(base: Context): Context {
        syncAppCompatLocalesFromStorage(base, true)
        return getContextForLanguage(base)
    }

    @JvmStatic
    fun syncAppCompatLocalesFromStorage(context: Context, beforeActivityOnCreate: Boolean) {
        if (beforeActivityOnCreate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val tag: String = getStoredLanguageTag(context)
        val locales: LocaleListCompat = LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != locales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    @JvmStatic
    fun applyStoredLanguageToAppCompat(context: Context) {
        syncAppCompatLocalesFromStorage(context, false)
    }

    @JvmStatic
    fun getStoredLanguageTag(context: Context): String {
        return toLanguageTag(getStoredLanguageCode(context))
    }

    @JvmStatic
    fun getStoredLanguageCode(context: Context): String {
        return context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(Def.Meta.KEY_LANGUAGE_CODE, LANGUAGE_CODE_FOLLOW_SYSTEM + "_")!!
    }

    @JvmStatic
    fun sameLanguageCode(code1: String?, code2: String?): Boolean {
        return toLanguageTag(code1) == toLanguageTag(code2)
    }

    @JvmStatic
    fun getContextForLanguage(context: Context): Context {
        val locale: Locale = getStoredLocale(context) ?: return context
        Locale.setDefault(locale)

        // Override only locale fields. Copying the full Configuration freezes
        // uiMode and can block follow-system dark mode changes from reaching
        // the wrapped Activity context.
        val configuration = Configuration()
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }

    private fun getStoredLocale(context: Context): Locale? {
        val tag: String = getStoredLanguageTag(context)
        if (tag == LANGUAGE_TAG_FOLLOW_SYSTEM) {
            return null
        }
        return Locale.forLanguageTag(tag)
    }

    private fun toLanguageTag(languageCode: String?): String {
        if (languageCode == null) {
            return LANGUAGE_TAG_FOLLOW_SYSTEM
        }
        val trimmed: String = languageCode.trim()
        if (trimmed.isEmpty() || trimmed == LANGUAGE_CODE_FOLLOW_SYSTEM
                || trimmed == LANGUAGE_CODE_FOLLOW_SYSTEM + "_") {
            return LANGUAGE_TAG_FOLLOW_SYSTEM
        }

        val normalized: String = trimmed.replace('_', '-')
        val parts: List<String> = normalized.split("-")
                .filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return LANGUAGE_TAG_FOLLOW_SYSTEM
        }
        if (parts.size == 1) {
            return parts[0].lowercase(Locale.US)
        }

        val b = StringBuilder(parts[0].lowercase(Locale.US))
        for (i in 1 until parts.size) {
            val part = parts[i]
            b.append('-')
            b.append(if (part.length == 2) part.uppercase(Locale.US) else part)
        }
        return b.toString()
    }

    @JvmStatic
    fun getTimesStr(context: Context?, times: Int): String? {
        return getTimesStr(context, times, true)
    }

    @JvmStatic
    fun getTimesStr(context: Context?, times: Int, shouldHasGapStr: Boolean): String? {
        val timesStr: String = context!!.getString(R.string.times)
        return if (isChinese(context)) {
            times.toString() + (if (shouldHasGapStr) " " else "") + timesStr
        } else {
            when (times) {
                0 -> "0 time"
                1 -> "once"
                2 -> "twice"
                else -> times.toString() + " " + timesStr + "s"
            }
        }
    }

    @JvmStatic
    fun getPercentStr(num1: Int, num2: Int): String {
        if (num2 == 0) {
            return "0 %"
        } else {
            val nf: NumberFormat = NumberFormat.getPercentInstance()
            nf.setMaximumFractionDigits(2)
            val str: String = nf.format((num1.toFloat() / num2).toDouble())
            return str.substring(0, str.length - 1) + " %"
        }
    }

}
