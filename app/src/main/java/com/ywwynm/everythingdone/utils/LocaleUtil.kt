@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics

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

    const val TAG: String = "EverythingDone\$LocaleUtil"

    @JvmStatic
    fun getSystemLocale(context: Context?): Locale? {
        return context!!.getResources().getConfiguration().getLocales().get(0)
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
        return getSystemLocale(context)!!.getLanguage()
                .equals(Locale.SIMPLIFIED_CHINESE.getLanguage())
    }

    @JvmStatic
    fun isTraditionalChinese(context: Context?): Boolean {
        return getSystemLocale(context)!!.getLanguage()
                .equals(Locale.TRADITIONAL_CHINESE.getLanguage())
    }

    const val LANGUAGE_CODE_FOLLOW_SYSTEM: String = "follow system"

    @JvmStatic
    fun getLanguageDescription(languageCode: String?): String? {
        val res: Resources = App.getApp()!!.getResources()
        val lanCodes: Array<String?> = res.getStringArray(R.array.language_codes)
        var index: Int = 0
        for (i in 0 until lanCodes.size) {
            if (lanCodes[i].equals(languageCode)) {
                index = i
                break
            }
        }
        return res.getStringArray(R.array.languages)[index]
    }

    @JvmStatic
    fun changeLanguage() {
        val languageCode: String = FrequentSettings.getString(
                Def.Meta.KEY_LANGUAGE_CODE, LANGUAGE_CODE_FOLLOW_SYSTEM + "_")!!
        var lanCon = languageCode.split("_".toRegex()).toTypedArray()
        if (lanCon.size == 1) {
            val lan = lanCon[0]
            lanCon = arrayOf(lan, "")
        }
        changeLanguage(lanCon[0], lanCon[1])
    }

    @JvmStatic
    fun changeLanguage(language: String, countryOrDistinct: String) {
        var lang: String = language
        var country: String = countryOrDistinct
        if (LANGUAGE_CODE_FOLLOW_SYSTEM.equals(lang)) {
            lang = Locale.getDefault().getLanguage()
            country = Locale.getDefault().getCountry()
        }
        val resources: Resources = App.getApp()!!.getResources()
        val dm: DisplayMetrics = resources.getDisplayMetrics()
        val configuration: Configuration = resources.getConfiguration()
        setAppLocale(configuration, Locale(lang, country))
        resources.updateConfiguration(configuration, dm)
    }

    @JvmStatic
    fun getTimesStr(context: Context?, times: Int): String? {
        return getTimesStr(context, times, true)
    }

    @JvmStatic
    fun getTimesStr(context: Context?, times: Int, shouldHasGapStr: Boolean): String? {
        val timesStr: String = context!!.getString(R.string.times)
        if (isChinese(context)) {
            return times.toString() + (if (shouldHasGapStr) " " else "") + timesStr
        } else {
            if (times == 0) {
                return "0 time"
            } else if (times == 1) {
                return "once"
            } else if (times == 2) {
                return "twice"
            } else {
                return times.toString() + " " + timesStr + "s"
            }
        }
    }

    @JvmStatic
    fun getPercentStr(num1: Int, num2: Int): String? {
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
