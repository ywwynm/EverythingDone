package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.HabitRecord
import com.ywwynm.everythingdone.model.HabitReminder
import com.ywwynm.everythingdone.utils.DateTimeUtil

import java.time.ZonedDateTime
import java.time.temporal.WeekFields

import java.util.ArrayList
import java.util.Calendar

/**
 * Created by ywwynm on 2016/1/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * dao layer between model [Habit] and table "habits".
 */
open class HabitDAO private constructor(context: Context?) {

    private var mContext: Context? = context!!.applicationContext
    private var mHabitReminderId: Long = -1
    private var mHabitRecordId: Long = 0

    private var db: SQLiteDatabase? = null

    init {
        val helper = DBHelper(context)
        db = helper.writableDatabase
        updateMaxHabitReminderRecordId()
    }

    private fun updateMaxHabitReminderRecordId() {
        mHabitReminderId = -1
        val c: Cursor = db!!.query(Def.Database.TABLE_HABIT_REMINDERS,
                null, null, null, null, null, "id desc")
        if (c.moveToFirst()) {
            mHabitReminderId = c.getLong(0)
        }
        c.close()
        val c2: Cursor = db!!.query(Def.Database.TABLE_HABIT_RECORDS,
                null, null, null, null, null, "id desc")
        if (c2.moveToFirst()) {
            mHabitRecordId = c2.getLong(0)
        }
        c2.close()
    }

    open fun getAllHabits(): List<Habit?>? {
        val habits: MutableList<Habit?> = ArrayList()
        val cursor: Cursor = db!!.query(Def.Database.TABLE_HABITS, null, null, null, null, null, null)
        while (cursor.moveToNext()) {
            habits.add(Habit(cursor))
        }
        cursor.close()
        return habits
    }

    open fun getAllHabitReminders(): List<HabitReminder?>? {
        val hrs: MutableList<HabitReminder?> = ArrayList()
        val cursor: Cursor = db!!.query(Def.Database.TABLE_HABIT_REMINDERS, null, null, null, null, null, null)
        while (cursor.moveToNext()) {
            hrs.add(HabitReminder(cursor))
        }
        cursor.close()
        return hrs
    }

    open fun getAllHabitRecords(): List<HabitRecord?>? {
        val hrs: MutableList<HabitRecord?> = ArrayList()
        val cursor: Cursor = db!!.query(Def.Database.TABLE_HABIT_RECORDS, null, null, null, null, null, null)
        while (cursor.moveToNext()) {
            hrs.add(HabitRecord(cursor))
        }
        cursor.close()
        return hrs
    }

    open fun getHabitById(id: Long): Habit? {
        val c: Cursor = db!!.query(Def.Database.TABLE_HABITS, null,
            "id=$id", null, null, null, null)
        var habit: Habit? = null
        if (c.moveToFirst()) {
            habit = Habit(c)
            habit.habitReminders = getHabitRemindersByHabitId(id)
            habit.habitRecords = getHabitRecordsByHabitId(id)
        }
        c.close()
        return habit
    }

    open fun getHabitReminderById(id: Long): HabitReminder? {
        var habitReminder: HabitReminder? = null
        val c: Cursor = db!!.query(Def.Database.TABLE_HABIT_REMINDERS, null,
            "id=$id", null, null, null, null)
        if (c.moveToFirst()) {
            habitReminder = HabitReminder(c)
        }
        c.close()
        return habitReminder
    }

    open fun getHabitRemindersByHabitId(habitId: Long): List<HabitReminder?>? {
        val habitReminders: MutableList<HabitReminder?> = ArrayList()
        val c: Cursor = db!!.query(Def.Database.TABLE_HABIT_REMINDERS, null,
            "habit_id=$habitId", null, null, null, null)
        while (c.moveToNext()) {
            habitReminders.add(HabitReminder(c))
        }
        c.close()
        return habitReminders
    }

    open fun getHabitRecordsByHabitId(habitId: Long): List<HabitRecord?>? {
        val habitRecords: MutableList<HabitRecord?> = ArrayList()
        val condition: String = Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS + "=" + habitId +
                " and (" +
                    Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FINISHED +
                        " or " +
                    Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FAKE_FINISHED +
                ")"
        val c: Cursor = db!!.query(Def.Database.TABLE_HABIT_RECORDS, null,
                condition, null, null, null,
                Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS + " asc")
        while (c.moveToNext()) {
            habitRecords.add(HabitRecord(c))
        }
        c.close()
        return habitRecords
    }

    open fun createHabit(habit: Habit?) {
        db!!.beginTransaction()
        try {
            val id: Long = habit!!.id
            val values = ContentValues()
            values.put(Def.Database.COLUMN_ID_HABITS, id)
            values.put(Def.Database.COLUMN_TYPE_HABITS, habit.type)
            values.put(Def.Database.COLUMN_REMINDED_TIMES_HABITS, habit.remindedTimes)
            values.put(Def.Database.COLUMN_DETAIL_HABITS, habit.detail)
            values.put(Def.Database.COLUMN_RECORD_HABITS, habit.record)
            values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS, habit.intervalInfo)
            values.put(Def.Database.COLUMN_CREATE_TIME_HABITS, habit.createTime)
            values.put(Def.Database.COLUMN_FIRST_TIME_HABITS, habit.firstTime)
            db!!.insert(Def.Database.TABLE_HABITS, null, values)

            for (habitReminder in habit.habitReminders!!) {
                createHabitReminder(habitReminder)
            }
            db!!.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db!!.endTransaction()
        }
    }

    open fun createHabitReminder(habitReminder: HabitReminder?) {
        mHabitReminderId++
        val notifyTime: Long = habitReminder!!.notifyTime
        val values = ContentValues()
        values.put(Def.Database.COLUMN_ID_HABIT_REMINDERS, mHabitReminderId)
        values.put(Def.Database.COLUMN_HABIT_ID_HABIT_REMINDERS, habitReminder.habitId)
        values.put(Def.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS, notifyTime)
        db!!.insert(Def.Database.TABLE_HABIT_REMINDERS, null, values)
        AlarmHelper.setHabitReminderAlarm(mContext, mHabitReminderId, notifyTime)
    }

    open fun createHabitRecord(habitRecord: HabitRecord?): HabitRecord? {
        mHabitRecordId++
        val values: ContentValues = getContentValuesFromHabitRecord(habitRecord, true)
        db!!.insert(Def.Database.TABLE_HABIT_RECORDS, null, values)
        habitRecord!!.id = mHabitRecordId
        return habitRecord
    }

    private fun getContentValuesFromHabitRecord(habitRecord: HabitRecord?, putId: Boolean): ContentValues {
        val values = ContentValues()
        if (putId) {
            values.put(Def.Database.COLUMN_ID_HABIT_RECORDS, mHabitRecordId)
        }
        values.put(Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS, habitRecord!!.habitId)
        values.put(Def.Database.COLUMN_HR_ID_HABIT_RECORDS, habitRecord.habitReminderId)
        values.put(Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS, habitRecord.recordTime)
        values.put(Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS, habitRecord.recordYear)
        values.put(Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS, habitRecord.recordMonth)
        values.put(Def.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS, habitRecord.recordWeek)
        values.put(Def.Database.COLUMN_RECORD_DAY_HABIT_RECORDS, habitRecord.recordDay)
        values.put(Def.Database.COLUMN_TYPE_HABIT_RECORDS, habitRecord.type)
        return values
    }

    // added on 2017/3/2, version should be 1.3.6(38)
    open fun pause(habitId: Long) {
        val habit: Habit? = getHabitById(habitId)
        if (habit == null || habit.isPaused()) return
        addHabitIntervalInfo(habitId, System.currentTimeMillis().toString() + ",")
    }

    open fun resume(habitId: Long) {
        val habit: Habit? = getHabitById(habitId)
        if (habit == null || !habit.isPaused()) return
        addHabitIntervalInfo(habitId, System.currentTimeMillis().toString() + ";")
    }
    // added end

    open fun isPaused(habitId: Long): Boolean {
        val cursor: Cursor = db!!.query(
                Def.Database.TABLE_HABITS,
                arrayOf<String?>(Def.Database.COLUMN_INTERVAL_INFO_HABITS),
                Def.Database.COLUMN_ID_HABITS + "=" + habitId,
                null, null, null, null
        )
        var paused = false
        if (cursor.moveToFirst()) {
            val intervalInfo: String = cursor.getString(0)
            paused = intervalInfo.endsWith(",")
        }
        cursor.close()
        return paused
    }

    open fun dailyUpdate(habitId: Long) {
        val habit: Habit = getHabitById(habitId)!!
        val record: String = habit.record!!
        val recordedTimes: Int = record.length
        val remindedTimes: Int = habit.remindedTimes
        if (recordedTimes < remindedTimes) {
            val type: Int = habit.type
            val start: Long = System.currentTimeMillis() - 86400000
            val gap: Int = DateTimeUtil.calculateTimeGap(start, System.currentTimeMillis(), type)
            if (gap != 0) {
                val sb: StringBuilder = StringBuilder(record)
                for (i in recordedTimes until remindedTimes) {
                    sb.append("0")
                }
                updateRecordOfHabit(habitId, sb.toString())
            }
        }
    }

    open fun finishOneTime(habit: Habit?): HabitRecord? {
        var record: String = habit!!.record!!
        val recordedTimes: Int = record.length
        val remindedTimes: Int = habit.remindedTimes
        val habitId: Long = habit.id
        val habitReminderId: Long

        if (recordedTimes >= remindedTimes) {
            // finish this habit once before notification
            val closest: HabitReminder = habit.getClosestHabitReminder()!!
            updateHabitReminderToNext(closest.id)
            habitReminderId = closest.id
        } else {
            val finalOne: HabitReminder = habit.getFinalHabitReminder()!!
            val finalTime: Long = finalOne.notifyTime
            val type: Int = habit.type
            val finalLastTime: Long = DateTimeUtil.getHabitReminderTime(type, finalTime, -1)
            val gap: Int = DateTimeUtil.calculateTimeGap(
                    finalLastTime, System.currentTimeMillis(), type)
            if (gap == 0) {
                // Reminded this T
                habitReminderId = finalOne.id
            } else {
                // Haven't reminded this T yet.
                // User want to finish this habit once before notification.
                val closest: HabitReminder = habit.getClosestHabitReminder()!!
                updateHabitReminderToNext(closest.id)
                habitReminderId = closest.id

                // At the same time, this means that user didn't finish enough times last T.
                // More clearly, user didn't finish last time last T.
                record += "0"
            }
        }
        updateRecordOfHabit(habitId, record + "1")
        return createHabitRecord(HabitRecord(habitId, habitReminderId))
    }

    open fun undoFinishOneTime(habitRecord: HabitRecord?) {
        val hrId: Long = habitRecord!!.id
        val habitId: Long = habitRecord.habitId
        val habit: Habit = getHabitById(habitId)!!
        val record: String = habit.record!!
        val len: Int = record.length
        if (len - 1 >= habit.remindedTimes) {
            updateHabitReminderToLast(habit.getFinalHabitReminder())
        }

        updateRecordOfHabit(habitId, record.substring(0, len - 1))
        deleteHabitRecord(hrId)
    }

    open fun getFinishedTimesThisT(habit: Habit?): Int {
        val habitId: Long = habit!!.id
        val type: Int = habit.type
        return when (type) {
            Calendar.DATE -> getFinishedTimesToday(habitId)
            Calendar.WEEK_OF_YEAR -> getFinishedTimesThisWeek(habitId)
            Calendar.MONTH -> getFinishedTimesThisMonth(habitId)
            Calendar.YEAR -> getFinishedTimesThisYear(habitId)
            else -> 0
        }
    }

    private fun getFinishedHabitRecordCursor(habitId: Long, limitCount: Int): Cursor {
        val condition: String = Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS + "=" + habitId +
                " and (" +
                Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FINISHED +
                " or " +
                Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FAKE_FINISHED +
                ")"
        val orderBy: String = Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS + " desc"
        return db!!.query(Def.Database.TABLE_HABIT_RECORDS, null,
                condition, null, null, null, orderBy, limitCount.toString())
    }

    private fun getFinishedTimesToday(habitId: Long): Int {
        val dt: ZonedDateTime  = ZonedDateTime.now()
        val curYear: Int  = dt.year
        val curMonth: Int = dt.monthValue
        val curDay: Int   = dt.dayOfMonth
        var times = 0
        val c: Cursor = getFinishedHabitRecordCursor(habitId, 4)
        while (c.moveToNext()) {
            val year: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS))
            val month: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS))
            val day: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_DAY_HABIT_RECORDS))
            if (year == curYear && month == curMonth && day == curDay) {
                times++
            } else break
        }
        c.close()
        return times
    }

    private fun getFinishedTimesThisWeek(habitId: Long): Int {
        val dt: ZonedDateTime = ZonedDateTime.now()
        val curYear: Int = dt.year
        val curWeek: Int = dt.get(WeekFields.ISO.weekOfWeekBasedYear())
        var times = 0
        val c: Cursor = getFinishedHabitRecordCursor(habitId, 7)
        while (c.moveToNext()) {
            val year: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS))
            val week: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS))
            if (year == curYear && week == curWeek) {
                times++
            } else break
        }
        c.close()
        return times
    }

    private fun getFinishedTimesThisMonth(habitId: Long): Int {
        val dt: ZonedDateTime = ZonedDateTime.now()
        val curYear: Int  = dt.year
        val curMonth: Int = dt.monthValue
        var times = 0
        val c: Cursor = getFinishedHabitRecordCursor(habitId, 31)
        while (c.moveToNext()) {
            val year: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS))
            val month: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS))
            if (year == curYear && month == curMonth) {
                times++
            } else break
        }
        c.close()
        return times
    }

    private fun getFinishedTimesThisYear(habitId: Long): Int {
        val dt: ZonedDateTime = ZonedDateTime.now()
        val curYear: Int = dt.year
        var times = 0
        val c: Cursor = getFinishedHabitRecordCursor(habitId, 12)
        while (c.moveToNext()) {
            val year: Int = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS))
            if (year == curYear) {
                times++
            } else break
        }
        c.close()
        return times
    }

    /**
     * Update all data of a habit(habitReminders, habitRecords and alarms) to latest and
     * correct state.
     * This method will be often called for these reasons:
     * 1. User finished a habit once  thus we should update the habit to next alarm;
     * 2. Habit Notification appeared thus we should update the habit to next alarm;
     * 3. We just want to set every alarms(of course including alarms for Habit) again to ensure
     *    that they can still ring. see [AlarmHelper.createAllAlarms]
     *    for more details.
     *
     * @param id id of the habit
     * @param updateRemindedTimes
     * @param forceToUpdateRemindedTimes
     */
    open fun updateHabitToLatest(
            id: Long, updateRemindedTimes: Boolean, forceToUpdateRemindedTimes: Boolean) {
        val habit: Habit = getHabitById(id)
            ?: // This may happen if the universe boom, so we should consider it strictly.
            return

        val recordTimes: Int = habit.record!!.length
        if (updateRemindedTimes && forceToUpdateRemindedTimes) {
            // This will prevent this habit from finishing in this T if it was notified but
            // user didn't finish it at once.
            updateHabitRemindedTimes(id, recordTimes.toLong())
        }

        var habitReminders: List<HabitReminder?> = habit.habitReminders!!
        val hrIds: MutableList<Long?> = ArrayList()
        for (habitReminder in habitReminders) {
            hrIds.add(habitReminder!!.id)
        }

        habit.initHabitReminders() // habitReminders have become latest.
        // You may see that Habit#initHabitReminders() will also set member firstTime again,
        // but this makes no change here because we don't update habit to database.

        habitReminders = habit.habitReminders!!
        val hrIdsSize: Int = hrIds.size
        if (hrIdsSize != habitReminders.size) {
            /**
             * it seems that this cannot happen but it did happen according to a user log.
             * I've tried to solve this problem by ensuring old habit is deleted successfully
             * when updating a habit in DetailActivity.
             * See [com.ywwynm.everythingdone.activities.DetailActivity.setOrUpdateHabit]
             * and [deleteHabit] for more details.
             * Maybe I didn't actually solve it. Let's see if there are more logs about that.
             */
            return
        }
        for (i in 0 until hrIdsSize) {
            val newTime: Long = habitReminders[i]!!.notifyTime
            updateHabitReminder(hrIds[i]!!, newTime)
        }

        // 将已经提前完成的habitReminder更新至新的周期里
        val habitType: Int = habit.type
        val habitRecordsThisT: List<HabitRecord?> = habit.getHabitRecordsThisT()!!
        for (habitRecord in habitRecordsThisT) {
            val hr: HabitReminder? = getHabitReminderById(habitRecord!!.habitReminderId)
            if (hr != null && DateTimeUtil.calculateTimeGap(
                    System.currentTimeMillis(), hr.notifyTime, habitType) == 0) {
                updateHabitReminderToNext(hr.id)
            }
        }

        if (updateRemindedTimes && !forceToUpdateRemindedTimes) {
            val remindedTimes: Int = habit.remindedTimes
            if (recordTimes < remindedTimes) {
                val minTime: Long = habit.getMinHabitReminderTime()
                val maxTime: Long = habit.getFinalHabitReminder()!!.notifyTime
                val maxLastTime: Long = DateTimeUtil.getHabitReminderTime(habitType, maxTime, -1)
                val curTime: Long = System.currentTimeMillis()
                if (curTime in (maxLastTime + 1)..<minTime) {
                    if (DateTimeUtil.calculateTimeGap(maxLastTime, curTime, habitType) != 0) {
                        updateHabitRemindedTimes(id, recordTimes.toLong())
                    }
                    // else 用户还能"补"掉这一次未完成的情况，因此不更新remindedTimes
                } else {
                    updateHabitRemindedTimes(id, recordTimes.toLong())
                }
            } else if (recordTimes > remindedTimes) {
                updateHabitRemindedTimes(id, recordTimes.toLong())
            }
        }
    }

    @Throws(Exception::class)
    open fun changeHabitRecordsByUser(habit: Habit?, recordBefore: String?, recordAfter: String?) {
        var h: Habit? = habit
        val arrBefore: CharArray = recordBefore!!.toCharArray()
        val arrAfter: CharArray  = recordAfter!!.toCharArray()
        val habitId: Long = h!!.id
        h = getHabitById(habitId)
        val len: Int = arrBefore.size
        if (len == 0) return
        val recordEndWith0: Boolean = arrBefore[len - 1] == '0'
        val habitRecords: List<HabitRecord?> = getHabitRecordsByHabitId(habitId)!!
        for (i in len - 1 downTo 0) {
            if (arrBefore[i] != arrAfter[i]) {
                if (arrBefore[i] == '0') {
                    createFakeFinishedHabitRecord(
                            h, len, len - i, recordEndWith0)
                } else {
                    val indexFromLast: Int = indexFromLast(recordBefore, arrBefore[i], i)
                    cancelFinishHabitRecord(habitRecords, indexFromLast)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun createFakeFinishedHabitRecord(habit: Habit?, recordTimes: Int, indexFromLast: Int, recordEndWith0: Boolean) {
        val habitId: Long = habit!!.id
        val habitReminders: List<HabitReminder?> = getHabitRemindersByHabitId(habitId)!!
        val timesEachT: Int = habitReminders.size

        val curTime: Long = System.currentTimeMillis()
        var nextRemindIndex: Int = timesEachT // 找出下一个提醒时刻所对应的下标，如果为timesEachT，则说明下一个提醒时刻在下个周期
        for (i in 0 until timesEachT) if (habitReminders[i]!!.notifyTime < curTime) {
            nextRemindIndex = i
            break
        }
        var backFrom: Int // 从哪一个HabitReminder开始回溯，找到我们需要伪造HabitRecord的对应的HabitReminder
        var preVary = 0
        if (nextRemindIndex == 0) {
            backFrom = timesEachT - 1
        } else if (!recordEndWith0 && recordTimes == habit.remindedTimes) {
            backFrom = nextRemindIndex - 1
        } else {
            backFrom = nextRemindIndex - 2
            if (backFrom < 0) {
                backFrom += timesEachT
            }
            preVary--
        }

        var indexToPreVary: Int = backFrom + 1
        if (indexToPreVary >= timesEachT) {
            indexToPreVary -= timesEachT
        }
        var hr: HabitReminder = habitReminders[indexToPreVary]!!
        val habitType: Int = habit.type
        hr.notifyTime = DateTimeUtil.getHabitReminderTime(habitType, hr.notifyTime, preVary)

        var i: Int = backFrom
        var j = 1
        while (j <= indexFromLast) {
            hr = habitReminders[i]!!
            hr.notifyTime = DateTimeUtil.getHabitReminderTime(habitType, hr.notifyTime, -1)
            i--
            if (i < 0) {
                i += timesEachT
            }
            j++
        }
        i++
        if (i >= timesEachT) {
            i -= timesEachT
        }
        hr = habitReminders[i]!!
        val fakeFinishedHabitRecord = HabitRecord(
                habitId, hr.id, hr.notifyTime + 6000)
        fakeFinishedHabitRecord.type = HabitRecord.TYPE_FAKE_FINISHED
        createHabitRecord(fakeFinishedHabitRecord)
    }

    /**
     * 给定一个位置的某一字符，得到它是字符串从后往前数的第几个该字符
     * 比如：字符串是011011010，c为1，index为2，那么c就是从后往前数的第4个1
     */
    private fun indexFromLast(src: String?, c: Char, index: Int): Int {
        var lastN = 0
        val arr: CharArray = src!!.toCharArray()
        for (i in arr.size - 1 downTo index) if (arr[i] == c) lastN++
        return lastN
    }

    private fun cancelFinishHabitRecord(habitRecords: List<HabitRecord?>?, indexFromLast: Int) {
        val size: Int = habitRecords!!.size
        for (j in size - 1 downTo 0) {
            if (size - j == indexFromLast) {
                val hr: HabitRecord = habitRecords[j]!!
                @HabitRecord.Type val type: Int = hr.type
                if (type == HabitRecord.TYPE_FINISHED) {
                    hr.type = HabitRecord.TYPE_CANCEL_FINISHED
                } else if (type == HabitRecord.TYPE_FAKE_FINISHED) {
                    hr.type = HabitRecord.TYPE_FAKE_CANCEL_FINISHED
                }
                updateHabitRecord(hr)
            }
        }
    }

    open fun updateHabitRecord(updatedHabitRecord: HabitRecord?) {
        val values: ContentValues = getContentValuesFromHabitRecord(updatedHabitRecord, false)
        db!!.update(Def.Database.TABLE_HABIT_RECORDS, values,
                Def.Database.COLUMN_ID_HABIT_RECORDS + "=" + updatedHabitRecord!!.id, null)
    }

    open fun updateRecordOfHabit(id: Long, record: String?) {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_RECORD_HABITS, record)
        db!!.update(Def.Database.TABLE_HABITS, values, "id=$id", null)
    }

    open fun updateHabitRemindedTimes(id: Long, remindedTimes: Long) {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_REMINDED_TIMES_HABITS, remindedTimes)
        db!!.update(Def.Database.TABLE_HABITS, values, "id=$id", null)
    }

    open fun addHabitIntervalInfo(id: Long, intervalInfoToAdd: String?) {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS,
                getHabitById(id)!!.intervalInfo + intervalInfoToAdd)
        db!!.update(Def.Database.TABLE_HABITS, values, "id=$id", null)
    }

    open fun removeLastHabitIntervalInfo(id: Long) {
        var interval: String = getHabitById(id)!!.intervalInfo!!
        interval = interval.substring(0,
                interval.lastIndexOf(if (interval.endsWith(";")) "," else ";") + 1)
        val values = ContentValues()
        values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS, interval)
        db!!.update(Def.Database.TABLE_HABITS, values, "id=$id", null)
    }

    open fun updateHabitReminder(hrId: Long, notifyTime: Long) {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS, notifyTime)
        db!!.update(Def.Database.TABLE_HABIT_REMINDERS, values, "id=$hrId", null)
        AlarmHelper.setHabitReminderAlarm(mContext, hrId, notifyTime)
    }

    open fun updateHabitReminderToNext(hrId: Long) {
        val habitReminder: HabitReminder = getHabitReminderById(hrId)!!
        val habit: Habit = getHabitById(habitReminder.habitId)!!
        val type: Int = habit.type
        var time: Long = habitReminder.notifyTime

        // do one time before loop if user finish a habit for 1 time in advance
        time = DateTimeUtil.getHabitReminderTime(type, time, 1)
        while (time < System.currentTimeMillis()) {
            time = DateTimeUtil.getHabitReminderTime(type, time, 1)
        }
        updateHabitReminder(hrId, time)
    }

    open fun updateHabitReminderToLast(habitReminder: HabitReminder?) {
        val habit: Habit = getHabitById(habitReminder!!.habitId)!!
        val type: Int = habit.type
        val time: Long = habitReminder.notifyTime
        updateHabitReminder(habitReminder.id,
                DateTimeUtil.getHabitReminderTime(type, time, -1))
    }

    open fun updateHabit(updatedHabit: Habit?): Boolean {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_TYPE_HABITS, updatedHabit!!.type)
        values.put(Def.Database.COLUMN_REMINDED_TIMES_HABITS, updatedHabit.remindedTimes)
        values.put(Def.Database.COLUMN_DETAIL_HABITS, updatedHabit.detail)
        values.put(Def.Database.COLUMN_RECORD_HABITS, updatedHabit.record)
        values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS, updatedHabit.intervalInfo)
        return db!!.update(Def.Database.TABLE_HABITS, values,
                Def.Database.COLUMN_ID_HABITS + "=" + updatedHabit.id, null) == 1
    }

    open fun deleteHabit(id: Long): Boolean {
        db!!.beginTransaction()
        try {
            db!!.delete(Def.Database.TABLE_HABITS, "id=$id", null)
            deleteHabitReminders(id)
            deleteHabitRecords(id)
            updateMaxHabitReminderRecordId()
            db!!.setTransactionSuccessful()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            db!!.endTransaction()
        }
    }

    open fun deleteHabitReminders(habitId: Long) {
        val habitReminders: List<HabitReminder?> = getHabitRemindersByHabitId(habitId)!!
        for (habitReminder in habitReminders) {
            AlarmHelper.deleteHabitReminderAlarm(mContext, habitReminder!!.id)
        }
        db!!.delete(Def.Database.TABLE_HABIT_REMINDERS, "habit_id=$habitId", null)
    }

    open fun deleteHabitRecords(habitId: Long) {
        db!!.delete(Def.Database.TABLE_HABIT_RECORDS, "habit_id=$habitId", null)
    }

    open fun deleteHabitRecord(hrId: Long) {
        db!!.delete(Def.Database.TABLE_HABIT_RECORDS, "id=$hrId", null)
    }

    companion object {
        const val TAG: String = "HabitDAO"

        @JvmField
        var sHabitDAO: HabitDAO? = null

        @JvmStatic
        fun getInstance(context: Context?): HabitDAO? {
            if (sHabitDAO == null) {
                synchronized(ReminderDAO::class.java) {
                    if (sHabitDAO == null) {
                        sHabitDAO = HabitDAO(context!!.applicationContext)
                    }
                }
            }
            return sHabitDAO
        }
    }
}
