package com.ywwynm.everythingdone.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import androidx.core.util.Pair
import android.util.Log

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver
import com.ywwynm.everythingdone.receivers.DailyCreateTodoReceiver
import com.ywwynm.everythingdone.receivers.DailyUpdateHabitReceiver
import com.ywwynm.everythingdone.receivers.HabitReceiver
import com.ywwynm.everythingdone.receivers.ReminderReceiver

import java.time.ZonedDateTime

/**
 * Created by ywwynm on 2016/3/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * utils for creating/canceling alarms.
 */
object AlarmHelper {

    const val TAG: String = "AlarmHelper"

    @JvmStatic
    fun setReminderAlarm(context: Context?, id: Long, notifyTime: Long) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent: Intent = Intent(context, ReminderReceiver::class.java)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
                context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        scheduleUserVisibleAlarm(context, am, notifyTime, pendingIntent)
    }

    @JvmStatic
    fun deleteReminderAlarm(context: Context?, id: Long) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent: Intent = Intent(context, ReminderReceiver::class.java)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pendingIntent)
    }

    @JvmStatic
    fun setHabitReminderAlarm(context: Context?, id: Long, notifyTime: Long) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent: Intent = Intent(context, HabitReceiver::class.java)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        scheduleUserVisibleAlarm(context, am, notifyTime, pendingIntent)
    }

    @JvmStatic
    fun deleteHabitReminderAlarm(context: Context?, id: Long) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent: Intent = Intent(context, HabitReceiver::class.java)
        intent.putExtra(Def.Communication.KEY_ID, id)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pendingIntent)
    }

    @JvmStatic
    fun cancelAlarms(context: Context?, thingIds: List<Long?>?, reminderIds: List<Long?>?,
                     habitReminderIds: List<Long?>?) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (thingId in thingIds!!) {
            val intent: Intent = Intent(context, AutoNotifyReceiver::class.java)
            intent.putExtra(Def.Communication.KEY_ID, thingId)
            val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, thingId!!.toInt(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pendingIntent)
        }
        for (reminderId in reminderIds!!) {
            val intent: Intent = Intent(context, ReminderReceiver::class.java)
            intent.putExtra(Def.Communication.KEY_ID, reminderId)
            val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, reminderId!!.toInt(),
                    intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pendingIntent)
        }
        for (habitReminderId in habitReminderIds!!) {
            val intent: Intent = Intent(context, HabitReceiver::class.java)
            intent.putExtra(Def.Communication.KEY_ID, habitReminderId)
            val pendingIntent: PendingIntent = PendingIntent.getBroadcast(context, habitReminderId!!.toInt(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pendingIntent)
        }
    }

    /**
     * Set all alarms again to ensure that they can still ring.
     * At first we only do this job after device reboot, app update and restore data, which are all
     * normal behaviors. However, we need to do this under more situations now, like after screen on,
     * for some third-party roms those who changed behaviors of system components like
     * [android.app.AlarmManager] and those who are willing to kill background apps in a
     * force-stop-like way to "improve Android user experience". Yes, I'm talking about Huawei EMUI
     * devices(maybe Xiaomi MIUI devices and Samsung devices are also interesting subjects, but
     * Huawei is most stupid).
     * @param context
     * @param updateHabitRemindedTimes
     */
    @JvmStatic
    fun createAllAlarms(
            context: Context?, updateHabitRemindedTimes: Boolean) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
        val reminderDAO: ReminderDAO = ReminderDAO.getInstance(context)!!
        val habitDAO: HabitDAO = HabitDAO.getInstance(context)!!
        val cursor: Cursor = thingDAO.getThingsCursor("state=" + Thing.UNDERWAY)!!
        while (cursor.moveToNext()) {
            val id: Long = cursor.getLong(
                    cursor.getColumnIndex(Def.Database.COLUMN_ID_THINGS))
            @Thing.Type val type: Int = cursor.getInt(
                    cursor.getColumnIndex(Def.Database.COLUMN_TYPE_THINGS))
            val state: Int = cursor.getInt(
                    cursor.getColumnIndex(Def.Database.COLUMN_STATE_THINGS))
            if (state != Thing.UNDERWAY) continue
            if (Thing.isReminderType(type)) {
                val reminder: Reminder? = reminderDAO.getReminderById(id)
                if (reminder == null || reminder.state != Reminder.UNDERWAY) {
                    continue
                }
                val notifyTime: Long = reminder.notifyTime
                if (notifyTime < System.currentTimeMillis()) {
                    reminder.state = Reminder.EXPIRED
                    reminderDAO.update(reminder)
                } else {
                    val intent: Intent = Intent(context, ReminderReceiver::class.java)
                    intent.putExtra(Def.Communication.KEY_ID, id)
                    val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
                            context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    scheduleUserVisibleAlarm(context, am, notifyTime, pendingIntent)
                }
            } else if (type == Thing.HABIT) {
                // 直接将习惯的提醒时间更新到最新时刻
                // 当用户收到提醒但未完成一次，备份应用，并在下一个周期恢复时，该习惯将不会为这一次添加记录"0"
                habitDAO.updateHabitToLatest(
                        id, updateHabitRemindedTimes, false)
            }
        }
        cursor.close()

        createDailyUpdateHabitAlarm(context)
        tryToCreateDailyTodoAlarm(context)
    }

    @JvmStatic
    fun createDailyUpdateHabitAlarm(context: Context?) {
        val intent: Intent = Intent(context, DailyUpdateHabitReceiver::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dt: ZonedDateTime = ZonedDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
        val alarmManager: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setExactAllowWhileIdleSafe(alarmManager, dt.toInstant().toEpochMilli(), pendingIntent)
    }

    @JvmStatic
    fun tryToCreateDailyTodoAlarm(context: Context?) {
        val sp: SharedPreferences = context!!.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val index: Int = sp.getInt(Def.Meta.KEY_DAILY_TODO, 0)
        if (index == 0) {
            return
        }

        val dailyTodoPairs: List<Pair<Int, Int>?> = DailyTodoHelper.getDailyTodoTimePairs()!!
        val pair: Pair<Int, Int> = dailyTodoPairs.get(index)!!

        val intent: Intent = Intent(context, DailyCreateTodoReceiver::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        var dt: ZonedDateTime = ZonedDateTime.now().withHour(pair.first!!).withMinute(pair.second!!).withSecond(0).withNano(0)
        if (dt.toInstant().toEpochMilli() < System.currentTimeMillis()) {
            dt = dt.plusDays(1)
        }
        val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setExactAllowWhileIdleSafe(alarmManager, dt.toInstant().toEpochMilli(), pendingIntent)
        Log.d(TAG, "daily todo alarm is created")
    }

    @JvmStatic
    fun cancelDailyTodoAlarm(context: Context?) {
        val am: AlarmManager = context!!.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent: Intent = Intent(context, DailyCreateTodoReceiver::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pendingIntent)
    }

    /**
     * Schedule an alarm the user will see fire — reminders and habit reminders.
     * Uses [AlarmManager.setAlarmClock] so the system surfaces it as a
     * top-priority alarm (status-bar "next alarm" icon, exempt from Doze and
     * from most OEM battery savers). Falls back to
     * [AlarmManager.setExactAndAllowWhileIdle] on the rare device where
     * `setAlarmClock` is denied.
     */
    private fun scheduleUserVisibleAlarm(
            context: Context?, am: AlarmManager, notifyTime: Long, fireIntent: PendingIntent) {
        try {
            am.setAlarmClock(buildAlarmClockInfo(context, notifyTime), fireIntent)
        } catch (e: SecurityException) {
            Log.w(TAG, "setAlarmClock denied; falling back to setExactAndAllowWhileIdle", e)
            setExactAllowWhileIdleSafe(am, notifyTime, fireIntent)
        }
    }

    /**
     * Schedule an exact-and-allow-while-idle alarm that survives Android's
     * exact-alarm permission model.
     *
     * - API ≤ 30: `setExactAndAllowWhileIdle` is always available.
     * - API 31+ (Android 12): app must hold `SCHEDULE_EXACT_ALARM`;
     *   the user can revoke it at any time. We gate on
     *   [AlarmManager.canScheduleExactAlarms] and fall back to
     *   the inexact `setAndAllowWhileIdle` if it returns false —
     *   the alarm still fires, just within a window the system chooses.
     * - API 33+ (Android 13): `USE_EXACT_ALARM` permission grants
     *   this implicitly for calendar / clock-style apps (declared in
     *   manifest); `canScheduleExactAlarms()` returns true and we
     *   take the exact path.
     *
     * Wrapped in a try/catch as a final safety net because some OEM ROMs
     * (e.g. HarmonyOS) report `canScheduleExactAlarms = true` but
     * still throw [SecurityException] at the actual call site.
     */
    @JvmStatic
    fun setExactAllowWhileIdleSafe(
            am: AlarmManager?, triggerAtMillis: Long, operation: PendingIntent?) {
        if (am == null || operation == null) return
        val canExact: Boolean = android.os.Build.VERSION.SDK_INT <
                android.os.Build.VERSION_CODES.S ||
                am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "setExactAndAllowWhileIdle denied at call site; " +
                        "falling back to setAndAllowWhileIdle", e)
            }
        }
        // Inexact fallback — fires within a system-chosen window, not at the
        // millisecond. Acceptable for the daily-update / daily-todo cadence
        // and for reminders that already went through setAlarmClock first.
        am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }

    private fun buildAlarmClockInfo(
            context: Context?, triggerTime: Long): AlarmManager.AlarmClockInfo {
        val showIntent: Intent = Intent(context, ThingsActivity::class.java)
        showIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val showPi: PendingIntent = PendingIntent.getActivity(
                context, 0, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return AlarmManager.AlarmClockInfo(triggerTime, showPi)
    }

}
