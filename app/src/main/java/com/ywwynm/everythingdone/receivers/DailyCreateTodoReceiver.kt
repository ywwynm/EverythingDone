package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.AlarmHelper

/**
 * Created by ywwynm on 2017/4/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A broadcast receiver that will open DetailActivity and create a reminder for user to write
 * their TODOs everyday.
 */
open class DailyCreateTodoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Phase 7: prefer ThingBackground for GRADIENT support.
        val openIntent: Intent = DetailActivity.getOpenIntentForCreate(context, TAG,
                if (App.newThingBackground != null)
                        App.newThingBackground
                else com.ywwynm.everythingdone.model.ThingBackground.pure(App.newThingColor))
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(openIntent)
        AlarmHelper.tryToCreateDailyTodoAlarm(context)
    }

    companion object {
        const val TAG: String = "DailyCreateTodoReceiver"
    }
}
