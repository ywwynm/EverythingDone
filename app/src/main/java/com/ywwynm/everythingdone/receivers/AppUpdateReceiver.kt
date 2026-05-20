package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class AppUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.i(TAG, "EverythingDone updated.")

            val appContext: Context = context.getApplicationContext()
            Thread(object : Runnable {
                override fun run() {
                    AlarmHelper.createAllAlarms(appContext, false)
                    Log.i(TAG, "Alarms set.")

                    SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext)
                    Log.i(TAG, "Quick Create Notification created.")

                    SystemNotificationUtil.tryToCreateThingOngoingNotification(appContext)

                    AppWidgetHelper.updateAllAppWidgets(appContext)
                    Log.i(TAG, "App widgets updated.")

                    Log.i(TAG, "Everything Done after app updated.")
                }
            }).start()

        }
    }

    companion object {
        const val TAG: String = "AppUpdateReceiver"
    }
}
