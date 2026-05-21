package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.model.Thing

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class DailyUpdateHabitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        updateHabits(context)
        sendBroadcastToMainUI(context)
        AlarmHelper.createDailyUpdateHabitAlarm(context)
    }

    private fun updateHabits(context: Context) {
        val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
        val habitDAO: HabitDAO = HabitDAO.getInstance(context)!!
        val cursor: Cursor = thingDAO.getThingsCursor(
                "type=" + Thing.HABIT + " and state=" + Thing.UNDERWAY)!!
        while (cursor.moveToNext()) {
            val id: Long = cursor.getLong(0)
            habitDAO.dailyUpdate(id)
            AppWidgetHelper.updateSingleThingAppWidgets(context, id)
        }
        AppWidgetHelper.updateThingsListAppWidgetsForType(context, Thing.HABIT)
        cursor.close()
    }

    private fun sendBroadcastToMainUI(context: Context) {
        App.setJustNotifyAll(true)
        val broadcastIntent = Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE,
                Def.Communication.RESULT_JUST_NOTIFY_DATASET_CHANGED)
        context.sendBroadcast(broadcastIntent)
    }

    companion object {
        const val TAG: String = "DailyUpdateHabitReceiver"
    }
}
