package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.model.Reminder

import java.util.ArrayList

/**
 * Created by ywwynm on 2015/5/22.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Updated by ywwynm on 2015/9/6, from EverythingDoneDAO to [ReminderDAO].
 * DAO layer between model [Reminder] and table "reminders".
 */
open class ReminderDAO private constructor(context: Context?) {

    private var mContext: Context? = context!!.getApplicationContext()

    private var db: SQLiteDatabase? = null

    init {
        val helper: DBHelper = DBHelper(context)
        db = helper.getWritableDatabase()
    }

    open fun getAllReminders(): List<Reminder?>? {
        val reminders: MutableList<Reminder?> = ArrayList()
        val cursor: Cursor = db!!.query(Def.Database.TABLE_REMINDERS, null, null, null, null, null, null)
        while (cursor.moveToNext()) {
            reminders.add(Reminder(cursor))
        }
        cursor.close()
        return reminders
    }

    open fun getReminderById(id: Long): Reminder? {
        val cursor: Cursor = db!!.query(Def.Database.TABLE_REMINDERS, null,
                "id=" + id, null, null, null, null)
        var reminder: Reminder? = null
        if (cursor.moveToFirst()) {
            reminder = Reminder(cursor)
        }
        cursor.close()
        return reminder
    }

    open fun create(reminder: Reminder?) {
        if (reminder != null) {
            val id: Long = reminder.id
            val notifyTime: Long = reminder.notifyTime

            val values: ContentValues = ContentValues()
            values.put(Def.Database.COLUMN_ID_REMINDERS, id)
            values.put(Def.Database.COLUMN_NOTIFY_TIME_REMINDERS, notifyTime)
            values.put(Def.Database.COLUMN_STATE_REMINDERS, reminder.state)
            values.put(Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS, reminder.notifyMillis)
            values.put(Def.Database.COLUMN_CREATE_TIME_REMINDERS, System.currentTimeMillis())
            values.put(Def.Database.COLUMN_UPDATE_TIME_REMINDERS, System.currentTimeMillis())
            db!!.insert(Def.Database.TABLE_REMINDERS, null, values)

            AlarmHelper.setReminderAlarm(mContext, id, notifyTime)
        }
    }

    open fun update(updatedReminder: Reminder?) {
        if (updatedReminder != null) {
            val id: Long = updatedReminder.id
            val notifyTime: Long = updatedReminder.notifyTime

            val values: ContentValues = ContentValues()
            values.put(Def.Database.COLUMN_NOTIFY_TIME_REMINDERS, notifyTime)
            values.put(Def.Database.COLUMN_STATE_REMINDERS, updatedReminder.state)
            values.put(Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS, updatedReminder.notifyMillis)
            values.put(Def.Database.COLUMN_UPDATE_TIME_REMINDERS, updatedReminder.updateTime)
            db!!.update(Def.Database.TABLE_REMINDERS, values, "id=" + id, null)

            if (updatedReminder.state == Reminder.UNDERWAY) {
                AlarmHelper.setReminderAlarm(mContext, id, notifyTime)
            }
        }
    }

    open fun resetGoal(goal: Reminder?) {
        if (goal == null) return
        val millis: Long = goal.notifyMillis
        if (millis >= Reminder.GOAL_MILLIS) {
            val notifyTime: Long = System.currentTimeMillis() + millis
            goal.notifyTime = notifyTime
            goal.state = Reminder.UNDERWAY
            goal.updateTime = System.currentTimeMillis()
            update(goal)
        }
    }

    open fun delete(id: Long) {
        db!!.delete(Def.Database.TABLE_REMINDERS, "id=" + id, null)
        AlarmHelper.deleteReminderAlarm(mContext, id)
    }

    companion object {
        const val TAG: String = "ReminderDAO"

        @JvmField
        var sReminderDAO: ReminderDAO? = null

        @JvmStatic
        fun getInstance(context: Context?): ReminderDAO? {
            if (sReminderDAO == null) {
                synchronized(ReminderDAO::class.java) {
                    if (sReminderDAO == null) {
                        sReminderDAO = ReminderDAO(context!!.getApplicationContext())
                    }
                }
            }
            return sReminderDAO
        }
    }
}
