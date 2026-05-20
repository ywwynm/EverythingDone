package com.ywwynm.everythingdone.model

import android.content.Context
import android.database.Cursor
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections

/**
 * Created by ywwynm on 2016/1/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * model layer. related to table "habits".
 */
open class Habit(
    var id: Long,
    var type: Int,
    var remindedTimes: Int,
    /**
     * time of day:   6:30,9:25,16:40
     * day of week:   2,4,6 15:0
     * day of month:  6,16,26,31 12:30
     * month of year: 3,6,9 16 23:45
     */
    var detail: String?,
    var record: String?,
    var intervalInfo: String?,
    var createTime: Long,
    var firstTime: Long
) {

    var habitReminders: List<HabitReminder?>? = null
    var habitRecords: List<HabitRecord?>? = null

    constructor(c: Cursor?) : this(
        c!!.getLong(0), c.getInt(1), c.getInt(2),
        c.getString(3), c.getString(4), c.getString(5),
        c.getLong(6), c.getLong(7)
    )

    constructor(habit: Habit) : this(
        habit.id, habit.type, habit.remindedTimes,
        habit.detail, habit.record, habit.intervalInfo,
        habit.createTime, habit.firstTime
    )

    // added on 2017/3/2, version should be 1.3.6(38)
    open fun isPaused(): Boolean {
        return intervalInfo!!.endsWith(",")
    }

    open fun getMinHabitReminderTime(): Long {
        return getClosestHabitReminder()!!.notifyTime
    }

    open fun getFinishedTimes(): Int {
        var times = 0
        for (i in 0 until record!!.length) {
            if (record!![i] == '1') times++
        }
        return times
    }

    open fun initHabitReminders() {
        habitReminders = ArrayList()
        if (type == Calendar.DATE) {
            initHabitRemindersTimeOfDay()
        } else if (type == Calendar.WEEK_OF_YEAR) {
            initHabitRemindersDayOfWeek()
        } else if (type == Calendar.MONTH) {
            initHabitRemindersDayOfMonth()
        } else if (type == Calendar.YEAR) {
            initHabitRemindersMonthOfYear()
        }
        firstTime = getMinHabitReminderTime()
    }

    open fun getClosestHabitReminder(): HabitReminder? {
        var minTime = Long.MAX_VALUE
        var ret: HabitReminder? = null
        for (habitReminder in habitReminders!!) {
            val time = habitReminder!!.notifyTime
            if (time < minTime) {
                minTime = time
                ret = habitReminder
            }
        }
        return ret
    }

    open fun getFinalHabitReminder(): HabitReminder? {
        var maxTime = Long.MIN_VALUE
        var ret: HabitReminder? = null
        for (habitReminder in habitReminders!!) {
            val time = habitReminder!!.notifyTime
            if (time > maxTime) {
                maxTime = time
                ret = habitReminder
            }
        }
        return ret
    }

    open fun getHabitRecordsThisT(): List<HabitRecord?>? {
        val ret: ArrayList<HabitRecord?> = ArrayList()
        for (habitRecord in habitRecords!!) {
            val time = habitRecord!!.recordTime
            if (DateTimeUtil.calculateTimeGap(time, System.currentTimeMillis(), type) == 0) {
                ret.add(habitRecord)
            }
        }
        return ret
    }

    open fun getCompletionRate(): String? {
        val fTimes = getFinishedTimes()
        val total = record!!.length
        if (total == 0) {
            return "0 %"
        }
        val nf = NumberFormat.getPercentInstance()
        nf.setMaximumFractionDigits(2)
        val str = nf.format(fTimes.toFloat() / total)
        return str!!.substring(0, str.length - 1) + " %"
    }

    open fun getTotalT(): Int {
        val total = DateTimeUtil.calculateTimeGap(createTime, System.currentTimeMillis(), type)
        return total - getTotalIntervalT()
    }

    open fun getPersistInT(): Int {
        val size = habitRecords!!.size
        if (size == 0) {
            return 0
        }

        var time0 = habitRecords!![0]!!.recordTime
        var piT = 1
        for (i in 1 until size) {
            val time = habitRecords!![i]!!.recordTime
            if (DateTimeUtil.calculateTimeGap(time0, time, type) != 0) {
                time0 = time
                piT++
            }
        }

        piT -= getTotalIntervalT()
        return piT
    }

    open fun getTotalIntervalT(): Int {
        var total = 0
        if (intervalInfo!!.isEmpty()) return total
        val intervals = intervalInfo!!.split(";".toRegex()).toTypedArray()
        for (interval in intervals) {
            val times = interval.split(",".toRegex()).toTypedArray()
            val s = java.lang.Long.parseLong(times[0])
            var e = System.currentTimeMillis()
            if (times.size == 2) {
                e = java.lang.Long.parseLong(times[1])
            }
            total += DateTimeUtil.calculateTimeGap(s, e, type)
        }
        return total
    }

    open fun allowFinish(): Boolean {
        if (isPaused()) return false
        var dt = ZonedDateTime.now()
        val temporalField = DateTimeUtil.getTemporalFieldFor(type)
        var ct = dt.get(temporalField)
        var t: Int
        var nextTime: Long = 0
        for (habitReminder in habitReminders!!) {
            val time = habitReminder!!.notifyTime
            t = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).get(temporalField)
            if (ct == t) {
                return true
            } else {
                nextTime = time
            }
        }

        val ndt = Instant.ofEpochMilli(DateTimeUtil.getHabitReminderTime(type, nextTime, -1)).atZone(ZoneId.systemDefault())
        t = ndt.get(temporalField)
        // All notifications are set to next T but we are still in this T.
        ct = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).get(temporalField)
        return ct == t && record!!.length == remindedTimes - 1
    }

    /**
     * Check if user can finish Habit once now for a Habit reminder which was reminded at given time.
     */
    open fun allowFinish(notifyTime: Long): Boolean {
        if (isPaused()) return false
        val dt = ZonedDateTime.now()
        val temporalField = DateTimeUtil.getTemporalFieldFor(type)
        return dt.get(temporalField) == Instant.ofEpochMilli(notifyTime).atZone(ZoneId.systemDefault()).get(temporalField)
    }

    open fun getFinishedTimesThisTStr(context: Context?): String? {
        val timeTypeThisT = DateTimeUtil.getThisTStr(type, context)
        val finished = context!!.getString(R.string.finished)
        val timesThisT = HabitDAO.getInstance(context)!!.getFinishedTimesThisT(this)
        if (LocaleUtil.isChinese(context)) {
            val timesStr = context.getString(R.string.times)
            return timeTypeThisT + finished + " " + timesThisT + " " + timesStr
        } else {
            return finished + " " + LocaleUtil.getTimesStr(context, timesThisT) + " " + timeTypeThisT
        }
    }

    open fun getNextReminderDescription(context: Context?): String? {
        val nextTime = getClosestHabitReminder()!!.notifyTime
        return DateTimeUtil.getDateTimeStrAt(nextTime, context, true)
    }

    open fun getSummary(context: Context?): String? {
        val timeTypeEveryT = DateTimeUtil.getTimeTypeStr(type, context)
        val timesEveryT = habitReminders!!.size
        if (LocaleUtil.isChinese(context)) {
            val every = context!!.getString(R.string.every)
            val timesStr = context.getString(R.string.times)
            return every + timeTypeEveryT + " " + timesEveryT + " " + timesStr
        } else {
            return LocaleUtil.getTimesStr(context, timesEveryT) + " a " + timeTypeEveryT
        }
    }

    /**
     * Precondition: Habit should be in underway.
     */
    open fun getStateDescription(context: Context?): String? {
        if (isPaused()) {
            return context!!.getString(R.string.habit_paused)
        } else return ""
    }

    open fun getCelebrationText(context: Context?): String? {
        val sb = StringBuilder()
        val part1 = context!!.getString(R.string.celebration_habit_part_1)
        val piT = getPersistInT()
        sb.append(part1).append(" ").append(if (piT < 1) "<1" else piT.toString()).append(" ")
                .append(DateTimeUtil.getTimeTypeStr(type, context))
        if (piT > 1 && !LocaleUtil.isChinese(context)) {
            sb.append("s")
        }
        sb.append(context.getString(R.string.celebration_habit_part_2))
                .append(" ")
                .append(LocaleUtil.getTimesStr(context, getFinishedTimes()))
                .append(if (LocaleUtil.isChinese(context)) "" else " ")
                .append(context.getString(R.string.celebration_habit_part_3))
                .append("\n")
                .append(context.getString(R.string.celebration_habit_part_4))
        return sb.toString()
    }

    open fun getDoingEndLimitTime(): Long {
        val recordedTimes = record!!.length
        if (remindedTimes > recordedTimes) {
            return getMinHabitReminderTime()
        } else {
            if (habitReminders!!.size < 2) {
                // Once a month, you finish it in advance and now still want to do that thing?
                // Ok, you can do it until end of next month.
                var end = Long.MAX_VALUE
                var dt = ZonedDateTime.now()
                dt = dt.withHour(23).withMinute(59).withSecond(59).withNano(999_000_000)
                if (type == Calendar.DATE) {
                    end = dt.plusDays(1).toInstant().toEpochMilli()
                } else if (type == Calendar.WEEK_OF_YEAR) {
                    end = dt.plusWeeks(1).with(ChronoField.DAY_OF_WEEK, 7).toInstant().toEpochMilli()
                } else if (type == Calendar.MONTH) {
                    end = dt.plusMonths(1).withDayOfMonth(31).toInstant().toEpochMilli()
                } else if (type == Calendar.YEAR) {
                    end = dt.plusYears(1).withMonth(12).withDayOfMonth(31).toInstant().toEpochMilli()
                }
                return end
            }

            val newHrs: ArrayList<HabitReminder?> = ArrayList(habitReminders!!)
            Collections.sort(newHrs, Comparator<HabitReminder?> { hr1, hr2 ->
                val hrTime1 = hr1!!.notifyTime
                val hrTime2 = hr2!!.notifyTime
                if (hrTime1 == hrTime2) 0
                else if (hrTime1 < hrTime2) -1
                else 1
            })
            return newHrs[1]!!.notifyTime
        }
    }

    private fun initHabitRemindersTimeOfDay() {
        val times = detail!!.split(",".toRegex()).toTypedArray()
        var dt = ZonedDateTime.now()
        for (time in times) {
            val t = time.split(":".toRegex()).toTypedArray()
            val hour   = Integer.parseInt(t[0])
            val minute = Integer.parseInt(t[1])
            dt = dt.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            var remMillis = dt.toInstant().toEpochMilli()
            while (System.currentTimeMillis() >= remMillis) {
                remMillis += 86400000
            }
            (habitReminders as MutableList<HabitReminder?>).add(HabitReminder(0, id, remMillis))
            dt = ZonedDateTime.now()
        }
    }

    private fun initHabitRemindersDayOfWeek() {
        val dateTimes = detail!!.split(" ".toRegex()).toTypedArray()
        val times = dateTimes[1].split(":".toRegex()).toTypedArray()
        val hour = Integer.parseInt(times[0])
        val minute = Integer.parseInt(times[1])
        var dt = ZonedDateTime.now().withHour(hour)
                .withMinute(minute).withSecond(0).withNano(0)
        val days = dateTimes[0].split(",".toRegex()).toTypedArray()
        for (dayStr in days) {
            var day = Integer.parseInt(dayStr)
            day = if (day == 0) 7 else day
            dt = dt.with(ChronoField.DAY_OF_WEEK, day.toLong())
            var remMillis = dt.toInstant().toEpochMilli()
            while (System.currentTimeMillis() >= remMillis) {
                remMillis += 604800000
            }
            (habitReminders as MutableList<HabitReminder?>).add(HabitReminder(0, id, remMillis))
            dt = ZonedDateTime.now().withHour(hour)
                    .withMinute(minute).withSecond(0).withNano(0)
        }
    }

    private fun initHabitRemindersDayOfMonth() {
        val dateTimes = detail!!.split(" ".toRegex()).toTypedArray()
        val times = dateTimes[1].split(":".toRegex()).toTypedArray()
        val hour = Integer.parseInt(times[0])
        val minute = Integer.parseInt(times[1])
        var dt = ZonedDateTime.now().withHour(hour)
                .withMinute(minute).withSecond(0).withNano(0)
        val days = dateTimes[0].split(",".toRegex()).toTypedArray()
        for (dayStr in days) {
            val day = Integer.parseInt(dayStr)
            var remMillis: Long
            if (day == 27) {
                var year = dt.getYear()
                var month = dt.getMonthValue()
                dt = dt.withDayOfMonth(DateTimeUtil.getDaysOfMonth(year, month))
                remMillis = dt.toInstant().toEpochMilli()
                while (System.currentTimeMillis() >= remMillis) {
                    dt = dt.plusMonths(1)
                    year = dt.getYear()
                    month = dt.getMonthValue()
                    dt = dt.withDayOfMonth(DateTimeUtil.getDaysOfMonth(year, month))
                    remMillis = dt.toInstant().toEpochMilli()
                }
            } else {
                dt = dt.withDayOfMonth(day + 1)
                remMillis = dt.toInstant().toEpochMilli()
                while (System.currentTimeMillis() >= remMillis) {
                    dt = dt.plusMonths(1)
                    remMillis = dt.toInstant().toEpochMilli()
                }
            }
            (habitReminders as MutableList<HabitReminder?>).add(HabitReminder(0, id, remMillis))
            dt = ZonedDateTime.now().withHour(hour)
                    .withMinute(minute).withSecond(0).withNano(0)
        }
    }

    private fun initHabitRemindersMonthOfYear() {
        val dateTimes = detail!!.split(" ".toRegex()).toTypedArray()
        val day = Integer.parseInt(dateTimes[1])
        val times = dateTimes[2].split(":".toRegex()).toTypedArray()
        val hour = Integer.parseInt(times[0])
        val minute = Integer.parseInt(times[1])
        var dt = ZonedDateTime.now().withHour(hour)
                .withMinute(minute).withSecond(0).withNano(0)
        val months = dateTimes[0].split(",".toRegex()).toTypedArray()
        for (monthStr in months) {
            val month = Integer.parseInt(monthStr) + 1
            dt = dt.withMonth(month)
            var remMillis: Long
            if (day == 28) {
                val year = dt.getYear()
                dt = dt.withDayOfMonth(DateTimeUtil.getDaysOfMonth(year, month))
                remMillis = dt.toInstant().toEpochMilli()
                while (System.currentTimeMillis() >= remMillis) {
                    dt = dt.plusYears(1).withDayOfMonth(
                            DateTimeUtil.getDaysOfMonth(year + 1, month))
                    remMillis = dt.toInstant().toEpochMilli()
                }
            } else {
                dt = dt.withDayOfMonth(day)
                remMillis = dt.toInstant().toEpochMilli()
                while (System.currentTimeMillis() >= remMillis) {
                    remMillis = dt.plusYears(1).toInstant().toEpochMilli()
                }
            }
            (habitReminders as MutableList<HabitReminder?>).add(HabitReminder(0, id, remMillis))
            dt = ZonedDateTime.now().withHour(hour)
                    .withMinute(minute).withSecond(0).withNano(0)
        }
    }

    companion object {

        @JvmStatic
        fun noUpdate(habit: Habit?, type: Int, detail: String?): Boolean {
            return habit!!.type == type && habit.detail!!.equals(detail)
        }

        @JvmStatic
        fun noTypeUpdate(habit: Habit?, type: Int): Boolean {
            return habit!!.type == type
        }

        @JvmStatic
        fun generateDetailTimeOfDay(times: List<Int?>?): String? {
            val sb = StringBuilder()
            var i = 0
            while (i < times!!.size) {
                val minute = times[i + 1].toString()
                sb.append(times[i]).append(":")
                        .append(if (minute.length == 1) "0" + minute else minute).append(",")
                i += 2
            }
            return sb.substring(0, sb.length - 1)
        }

        @JvmStatic
        fun getDayTimeListFromDetail(detail: String?): List<Int?>? {
            val timeList: ArrayList<Int?> = ArrayList()
            val times = detail!!.split(",".toRegex()).toTypedArray()
            for (time in times) {
                val hm = time.split(":".toRegex()).toTypedArray()
                timeList.add(Integer.parseInt(hm[0]))
                timeList.add(Integer.parseInt(hm[1]))
            }
            return timeList
        }

        @JvmStatic
        fun generateDetailDayOf(days: List<Int?>?, hour: Int, minute: Int): String? {
            val sb = StringBuilder()
            for (day in days!!) {
                sb.append(day).append(",")
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(" ").append(hour).append(":").append(if (minute < 10) "0" + minute else minute)
            return sb.toString()
        }

        @JvmStatic
        fun getDayOrMonthListFromDetail(detail: String?): List<Int?>? {
            val domList: ArrayList<Int?> = ArrayList()
            val dayTimes = detail!!.split(" ".toRegex()).toTypedArray()
            val doms = dayTimes[0].split(",".toRegex()).toTypedArray()
            for (dom in doms) {
                domList.add(Integer.parseInt(dom))
            }
            return domList
        }

        @JvmStatic
        fun getTimeFromDetailWeekMonth(detail: String?): Array<String?>? {
            val dayTimes = detail!!.split(" ".toRegex()).toTypedArray()
            val times = dayTimes[1].split(":".toRegex()).toTypedArray()
            if (times[1].length == 1) {
                times[1] = "0" + times[1]
            }
            @Suppress("UNCHECKED_CAST")
            return times as Array<String?>
        }

        @JvmStatic
        fun generateDetailMonthOfYear(months: List<Int?>?, day: Int, hour: Int, minute: Int): String? {
            val sb = StringBuilder()
            for (month in months!!) {
                sb.append(month).append(",")
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(" ").append(day).append(" ").append(hour).append(":")
                    .append(if (minute < 10) "0" + minute else minute)
            return sb.toString()
        }

        @JvmStatic
        fun getTimeFromDetailYear(detail: String?): Array<String?>? {
            val dayTimes: Array<String?> = arrayOfNulls(3)
            val monthDayTimes = detail!!.split(" ".toRegex()).toTypedArray()
            dayTimes[0] = monthDayTimes[1]
            val times = monthDayTimes[2].split(":".toRegex()).toTypedArray()
            dayTimes[1] = times[0]
            dayTimes[2] = if (times[1].length == 1) "0" + times[1] else times[1]
            return dayTimes
        }
    }
}
