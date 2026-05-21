package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper

/**
 * Created by qiizhang on 2016/8/26.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Broadcast used to response to locale change
 */
open class LocaleChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Def.Communication.BROADCAST_ACTION_RESP_LOCALE_CHANGE == intent.action) {
            AppWidgetHelper.updateAllAppWidgets(context)

            if (BuildConfig.DEBUG) {
                Log.i(TAG, "App language has changed, app widgets are updated for that.")
            }
        }
    }

    companion object {
        const val TAG: String = "LocaleChangeReceiver"
    }
}
