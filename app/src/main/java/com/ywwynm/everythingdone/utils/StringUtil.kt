package com.ywwynm.everythingdone.utils

import android.text.TextUtils

import java.util.Locale

/**
 * Created by ywwynm on 2016/10/20.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * some utils to operate String
 */
object StringUtil {

    const val TAG: String = "StringUtil"

    @JvmStatic
    fun upperFirst(s: String?): String {
        if (TextUtils.isEmpty(s)) {
            return ""
        }
        val first: String = s!![0].toString()
        return first.uppercase(Locale.getDefault()) + s.substring(1, s.length)
    }

    @JvmStatic
    fun lowerFirst(s: String?): String {
        if (TextUtils.isEmpty(s)) {
            return ""
        }
        val first: String = s!![0].toString()
        return first.lowercase(Locale.getDefault()) + s.substring(1, s.length)
    }

    @JvmStatic
    fun replaceChineseBrackets(s: String?): String {
        if (TextUtils.isEmpty(s)) {
            return ""
        }
        return s!!.replace("（".toRegex(), "(").replace("）".toRegex(), ")")
    }

}
