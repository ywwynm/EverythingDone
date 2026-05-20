package com.ywwynm.everythingdone.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver
import com.ywwynm.everythingdone.utils.DateTimeUtil

import java.util.Calendar

/**
 * Created by ywwynm on 2016/3/13.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * utils for auto notify function
 */
object AutoNotifyHelper {

    const val TAG: String = "AutoNotifyHelper"

    @JvmField
    var AUTO_NOTIFY_TIMES: IntArray = intArrayOf(
            15, 30, 1, 2, 6, 1, 3, 1
    )
    @JvmField
    var AUTO_NOTIFY_TYPES: IntArray = intArrayOf(
            Calendar.MINUTE, Calendar.MINUTE, Calendar.HOUR_OF_DAY,
            Calendar.HOUR_OF_DAY, Calendar.HOUR_OF_DAY, Calendar.DATE,
            Calendar.DATE, Calendar.WEEK_OF_YEAR
    )

    init {
        if (BuildConfig.DEBUG) {
            AUTO_NOTIFY_TIMES = intArrayOf(
                    10, 15, 30, 1, 2, 6, 1, 3, 1
            )
            AUTO_NOTIFY_TYPES = intArrayOf(
                    Calendar.SECOND, Calendar.MINUTE, Calendar.MINUTE, Calendar.HOUR_OF_DAY,
                    Calendar.HOUR_OF_DAY, Calendar.HOUR_OF_DAY, Calendar.DATE,
                    Calendar.DATE, Calendar.WEEK_OF_YEAR
            )
        }
    }

    @JvmStatic
    fun createAutoNotify(thing: Thing?, context: Context?) {
        if (!shouldCreateAutoNotify(thing, context)) {
            return
        }
        val id: Long = thing!!.id
        val alarmManager: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent: Intent = Intent(context, AutoNotifyReceiver::class.java)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time: Long = DateTimeUtil.getActualTimeAfterSomeTime(getAutoNotifyPreferences(context))
        AlarmHelper.setExactAllowWhileIdleSafe(alarmManager, time, pendingIntent)
    }

    private fun shouldCreateAutoNotify(thing: Thing?, context: Context?): Boolean {
        val typeTime: IntArray = getAutoNotifyPreferences(context)
        if (typeTime[1] == 0) {
            return false
        }
        val thingType: Int = thing!!.type
        if (thingType == Thing.GOAL) {
            return false
        }

        val id: Long = thing.id
        val twoTimesTime: Int = typeTime[1] * 2
        val limitTime: Long = DateTimeUtil.getActualTimeAfterSomeTime(typeTime[0], twoTimesTime)
        if (thingType == Thing.REMINDER) {
            val reminder: Reminder = ReminderDAO.getInstance(context)!!.getReminderById(id)!!
            val time: Long = reminder.notifyTime
            return time >= limitTime
        } else if (thingType == Thing.HABIT) {
            val habit: Habit = HabitDAO.getInstance(context)!!.getHabitById(id)!!
            val time: Long = habit.firstTime
            return time >= limitTime
        }
        return true
    }

    private fun getAutoNotifyPreferences(context: Context?): IntArray {
        val index: Int = getAutoNotifyPreferencesIndex(context)
        val ret: IntArray = IntArray(2)
        if (index == 0) {
            ret[1] = 0
        } else {
            ret[0] = AUTO_NOTIFY_TYPES[index - 1]
            ret[1] = AUTO_NOTIFY_TIMES[index - 1]
        }
        return ret
    }

    private fun getAutoNotifyPreferencesIndex(context: Context?): Int {
        val preferences: SharedPreferences = context!!.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getInt(Def.Meta.KEY_AUTO_NOTIFY, 0)
    }

}
