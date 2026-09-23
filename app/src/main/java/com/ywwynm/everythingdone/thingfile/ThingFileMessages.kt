package com.ywwynm.everythingdone.thingfile

import android.content.Context

import androidx.annotation.StringRes

import com.ywwynm.everythingdone.R

/**
 * 记事文件各环节报错时给用户看的文字。
 *
 * 原因一律取字符串资源（英文界面不能出现中文）；系统异常自带的消息只作为括号里的补充，
 * 它多为英文，但能帮用户和我们分辨具体是哪一步失败。
 */
object ThingFileMessages {

    @JvmStatic
    fun of(context: Context, @StringRes reason: Int, detail: String? = null): String {
        val base = context.getString(reason)
        if (detail.isNullOrBlank()) {
            return base
        }
        return context.getString(R.string.thing_file_error_with_detail, base, detail)
    }

    /** 异常链上任何一层是「设备空间不足」。 */
    @JvmStatic
    fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left")) {
                return true
            }
            t = t.cause
        }
        return false
    }
}
