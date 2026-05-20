package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

/**
 * Created by ywwynm on 2016/9/15.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Receiver that receives [Intent.ACTION_USER_PRESENT] broadcast.
 */
open class UserPresentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
            Log.i(TAG, "Screen is on, EverythingDone is responding...")

            val appContext: Context = context.getApplicationContext()
            Thread(object : Runnable {
                override fun run() {
                    AlarmHelper.createAllAlarms(appContext, false)
                    Log.i(TAG, "Alarms set.")

                    SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext)
                    Log.i(TAG, "Quick Create Notification created.")

                    Log.i(TAG, "Everything Done after screen was on.")
                }
            }).start()

        }
    }

    companion object {
        const val TAG: String = "UserPresentReceiver"
    }
}
