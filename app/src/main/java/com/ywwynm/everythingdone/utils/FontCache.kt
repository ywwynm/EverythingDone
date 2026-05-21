package com.ywwynm.everythingdone.utils

import android.content.Context
import android.graphics.Typeface

import java.util.HashMap

/**
 * Created by ywwynm on 2016/3/16.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * cache fonts to prevent memory leak
 */
object FontCache {

    const val TAG: String = "FontCache"

    private val fontCache: HashMap<String?, Typeface?> = HashMap()

    @JvmStatic
    fun get(name: String?, context: Context?): Typeface? {
        var tf: Typeface? = fontCache[name]
        if (tf == null) {
            tf = Typeface.createFromAsset(context!!.assets, name)
            fontCache[name] = tf
        }
        return tf
    }

}
