package com.ywwynm.everythingdone.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Pair

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Thing

/**
 * Created by ywwynm on 2016/8/25.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * habit widget action BroadcastReceiver
 */
@SuppressLint("LongLogTag")
open class HabitWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Def.Communication.WIDGET_ACTION_FINISH == intent.action) {
            val id: Long = intent.getLongExtra(Def.Communication.KEY_ID, -1)
            var position: Int = intent.getIntExtra(Def.Communication.KEY_POSITION, -1)

            for (dId in App.getRunningDetailActivities()) if (dId == id) {
                return
            }

            val pair: Pair<Thing, Int> = App.getThingAndPosition(context, id, position)!!
            val thing: Thing = pair.first ?: return
            position = pair.second!!

            val habit: Habit? = HabitDAO.getInstance(context)!!.getHabitById(id)
            if (habit == null) {
                RemoteActionHelper.correctIfNoHabit(context, thing, position, thing.type)
                return
            }

            val nmc: NotificationManagerCompat = NotificationManagerCompat.from(context)
            for (habitReminder in habit.habitReminders!!) {
                nmc.cancel(habitReminder!!.id.toInt())
            }

            RemoteActionHelper.finishHabitOnce(context, thing, position, -1L)
        }
    }

    companion object {
        const val TAG: String = "HabitWidgetActionReceiver"
    }
}
