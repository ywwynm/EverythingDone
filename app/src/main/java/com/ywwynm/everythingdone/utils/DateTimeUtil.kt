package com.ywwynm.everythingdone.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import androidx.core.util.Pair
import android.widget.EditText

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.R.string.days
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * Created by ywwynm on 2015/8/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Helper for formatting datetime and getting information about that.
 */
object DateTimeUtil {

    const val TAG: String = "EverythingDone\$DateTimeUtil"

    @JvmStatic
    fun getGeneralDateStr(context: Context?, time: Long): String? {
        return formatMillis(time, getGeneralDateFormatPattern(context))
    }

    @JvmStatic
    fun getGeneralDateTimeStr(context: Context?, time: Long): String? {
        return formatMillis(time, getGeneralDateTimeFormatPattern(context))
    }

    @JvmStatic
    fun getGeneralDateFormatPattern(context: Context?): String? {
        if (LocaleUtil.isChinese(context)) {
            val year: String  = context!!.getString(R.string.year)
            val month: String = context.getString(R.string.month)
            val day: String   = context.getString(R.string.day)
            return "yyyy" + year + "M" + month + "d" + day
        } else {
            return "MMM d, yyyy"
        }
    }

    @JvmStatic
    fun getGeneralDateTimeFormatPattern(context: Context?): String? {
        if (LocaleUtil.isChinese(context)) {
            val year: String  = context!!.getString(R.string.year)
            val month: String = context.getString(R.string.month)
            val day: String   = context.getString(R.string.day)
            return "yyyy" + year + "M" + month + "d" + day + "EEEE H:mm:ss"
        } else {
            return "H:mm:ss, MMM d, yyyy, EEEE"
        }
    }

    @JvmStatic
    fun getDateTimeStr(type: Int, time: Int, context: Context?): String? {
        var typeStr: String = getTimeTypeStr(type, context)!!
        if (LocaleUtil.isChinese(context)) {
            return time.toString() + typeStr
        } else {
            if (time > 1) {
                typeStr += "s"
            }
            return time.toString() + " " + typeStr.lowercase(java.util.Locale.getDefault())
        }
    }

    @JvmStatic
    fun getTemporalFieldFor(type: Int): java.time.temporal.TemporalField? {
        when (type) {
            Calendar.MINUTE ->
                return java.time.temporal.ChronoField.MINUTE_OF_HOUR
            Calendar.HOUR_OF_DAY ->
                return java.time.temporal.ChronoField.HOUR_OF_DAY
            Calendar.DATE ->
                return java.time.temporal.ChronoField.DAY_OF_MONTH
            Calendar.WEEK_OF_YEAR ->
                return java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()
            Calendar.MONTH ->
                return java.time.temporal.ChronoField.MONTH_OF_YEAR
            Calendar.YEAR ->
                return java.time.temporal.ChronoField.YEAR
            else ->
                return java.time.temporal.ChronoField.ERA
        }
    }

    @JvmStatic
    fun getTimeTypeStr(type: Int, context: Context?): String? {
        when (type) {
            Calendar.SECOND ->
                return context!!.getString(R.string.second)
            Calendar.MINUTE ->
                return context!!.getString(R.string.minute)
            Calendar.HOUR_OF_DAY ->
                return context!!.getString(R.string.hours)
            Calendar.DATE ->
                return context!!.getString(days)
            Calendar.WEEK_OF_YEAR ->
                return context!!.getString(R.string.weeks)
            Calendar.MONTH ->
                return context!!.getString(R.string.months)
            Calendar.YEAR ->
                return context!!.getString(R.string.year)
            else ->
                return ""
        }
    }

    @JvmStatic
    fun getActualTimeAfterSomeTime(reminderAfterTime: IntArray?): Long {
        if (reminderAfterTime!!.size != 2) return 0
        return getActualTimeAfterSomeTime(reminderAfterTime[0], reminderAfterTime[1])
    }

    @JvmStatic
    fun getActualTimeAfterSomeTime(timeType: Int, afterTime: Int): Long {
        return getActualTimeAfterSomeTime(System.currentTimeMillis(), timeType, afterTime)
    }

    @JvmStatic
    fun getActualTimeAfterSomeTime(startTime: Long, timeType: Int, afterTime: Int): Long {
        val calendar: GregorianCalendar = GregorianCalendar()
        calendar.setTimeInMillis(startTime)
        calendar.add(timeType, afterTime)
        return calendar.getTimeInMillis()
    }

    /**
     * see [getDateTimeStrReminder]
     */
    @JvmStatic
    fun getDateTimeStrReminder(context: Context?, thingId: Long, timePeriod: Boolean): String? {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, thingId, -1)!!
        val thing: Thing? = pair.first
        if (thing == null) {
            return ""
        }
        val reminder: Reminder? = ReminderDAO.getInstance(context)!!.getReminderById(thingId)
        if (reminder == null) {
            return ""
        }
        return getDateTimeStrReminder(context, thing, reminder, timePeriod)
    }

    /**
     * see [getDateTimeStrReminder]
     */
    @JvmStatic
    fun getDateTimeStrReminder(context: Context?, thing: Thing?, reminder: Reminder?): String? {
        return getDateTimeStrReminder(context, thing, reminder, false)
    }

    /**
     * see [getDateTimeStrReminder]
     */
    @JvmStatic
    fun getDateTimeStrReminder(
            context: Context?, thing: Thing?, reminder: Reminder?, timePeriod: Boolean): String? {
        return getDateTimeStrReminder(
                context, reminder!!.notifyTime, thing!!.state, reminder.state, timePeriod)
    }

    /**
     * For the first part of returned string, see [getDateTimeStrAt].
     *
     * if both the Thing and the Reminder are not underway, there will be second part.
     * For the second part, see [Reminder.getStateDescription].
     */
    @JvmStatic
    fun getDateTimeStrReminder(
            context: Context?, notifyTime: Long, @Thing.State thingState: Int, reminderState: Int,
            timePeriod: Boolean): String? {
        var timeStr: String = getDateTimeStrAt(notifyTime, context, timePeriod)!!
        if (timeStr.startsWith("on ")) {
            timeStr = timeStr.substring(3, timeStr.length)
        }
        var ret: String = timeStr
        if (thingState != Thing.UNDERWAY || reminderState != Reminder.UNDERWAY) {
            ret += ", " + Reminder.getStateDescription(thingState, reminderState, context)
        }
        return ret
    }

    /**
     * see [getDateTimeStrGoal]
     */
    @JvmStatic
    fun getDateTimeStrGoal(context: Context?, thingId: Long): String? {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, thingId, -1)!!
        val thing: Thing? = pair.first
        if (thing == null) {
            return ""
        }
        val goal: Reminder? = ReminderDAO.getInstance(context)!!.getReminderById(thingId)
        if (goal == null) {
            return ""
        }
        return getDateTimeStrGoal(context, thing, goal)
    }

    /**
     * see [getDateTimeStrGoal]
     */
    @JvmStatic
    fun getDateTimeStrGoal(context: Context?, thing: Thing?, goal: Reminder?): String? {
        return getDateTimeStrGoal(
                context, goal!!.notifyTime, goal.updateTime, thing!!.finishTime,
                thing.state, goal.state)
    }

    /**
     * Returned strings are as followings:
     *
     * 1. if the Thing is UNDERWAY:
     * 应于19:30前完成 should be finished before 19:30
     * 应于270天内完成 should be finished in 270 days
     * 已逾期         overdue
     * 已逾期2天      2 days overdue
     *
     * 2. if the Thing is FINISHED:
     * 已用60天完成      finished in 60 days
     * 已用160天完成,逾期 finished in 160 days, overdue
     *
     * 3. if the Thing is DELETED:
     * see [Reminder.getStateDescription]
     */
    @JvmStatic
    fun getDateTimeStrGoal(
            context: Context?, notifyTime: Long, goalCreateTime: Long, thingFinishTime: Long,
            @Thing.State thingState: Int, goalState: Int): String? {
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (thingState == Thing.UNDERWAY) {
            val curTime: Long = System.currentTimeMillis()
            val days: Int = Math.abs(calculateTimeGap(curTime, notifyTime, Calendar.DATE))
            if (days == 0) { // the alarm will ring today
                if (curTime <= notifyTime) {
                    val shouldBefore: String = context!!.getString(R.string.goal_should_finish_before)
                    return String.format(shouldBefore, formatMillis(notifyTime, "H:mm"))
                } else {
                    if (isChinese) {
                        val overdue: String = context!!.getString(R.string.goal_overdue)
                        return overdue.substring(0, 3) // "已逾期"
                    } else {
                        return "overdue"
                    }
                }
            } else if (curTime < notifyTime) { // days >= 1
                var shouldFinishIn: String = context!!.getString(R.string.goal_should_finish_in)
                shouldFinishIn = String.format(shouldFinishIn, days)
                if (days == 1 && !isChinese) {
                    shouldFinishIn = shouldFinishIn.replace("days", "day")
                }
                return shouldFinishIn
            } else { // later than deadline while the thing isn't finished
                var overdue: String = context!!.getString(R.string.goal_overdue)
                overdue = String.format(overdue, days)
                if (days == 1 && !isChinese) {
                    overdue = overdue.replace("days", "day")
                }
                return overdue
            }
        } else if (thingState == Thing.FINISHED) {
            val finishDays: Int = calculateTimeGap(goalCreateTime, thingFinishTime, Calendar.DATE)
            val goalDays: Int = calculateTimeGap(goalCreateTime, notifyTime, Calendar.DATE)
            var finishedIn: String = context!!.getString(R.string.goal_finished_normal)
            if (finishDays < goalDays) {
                finishedIn = context.getString(R.string.goal_finished_in_advance)
            } else if (finishDays > goalDays) {
                finishedIn = context.getString(R.string.goal_finished_overdue)
            }
            val daysStr: String
            if (finishDays == 0) {
                daysStr = "<1"
            } else {
                daysStr = finishDays.toString()
            }
            finishedIn = String.format(finishedIn, daysStr)
            if (finishDays <= 1 && !isChinese) {
                finishedIn = finishedIn.replace("days", "day")
            }
            return finishedIn
        } else {
            return Reminder.getStateDescription(thingState, goalState, context)
        }
    }

    /**
     * Returned strings are as followings:
     *
     * should be achieved before yesterday, 19:30 at night;
     * should be achieved before today, 7:40 in the morning;
     * should be achieved before tomorrow, 16:00 in the afternoon;
     * should be achieved before Monday, 23:00 deep at night;
     * should be achieved before Jun 6, 18:00 at dusk;
     * should be achieved before Oct 31, 2016, 6:15 early in the morning.
     *
     * As you can see, this method is made for a Goal. You should not call this for other
     * types of things.
     */
    @JvmStatic
    fun getShouldBeAchievedBeforeStr(
            context: Context?, notifyTime: Long, timePeriod: Boolean): String? {
        val shouldBefore: String = context!!.getString(R.string.goal_should_finish_before)
        var dateTimeAtStr: String = getDateTimeStrAt(notifyTime, context, timePeriod)!!
        if (dateTimeAtStr.startsWith("on ")) {
            dateTimeAtStr = dateTimeAtStr.substring(3, dateTimeAtStr.length)
        }
        return String.format(shouldBefore, dateTimeAtStr)
    }

    /**
     * Used to display [com.ywwynm.everythingdone.model.Reminder.notifyTime]
     * of a Reminder which belongs to a [com.ywwynm.everythingdone.model.Thing]
     * object with type [com.ywwynm.everythingdone.model.Thing.REMINDER].
     *
     * @return A string with type of "after some time" according to `time`.
     *         For example, "after 15 minutes" or "after 1 day".
     */
    @JvmStatic
    fun getDateTimeStrAfter(type: Int, time: Int, context: Context?): String? {
        if (time == 0) {
            return getThisTStr(type, context)
        }
        val str: String = getDateTimeStr(type, time, context)!!
        val after: String = context!!.getString(R.string.after)
        if (LocaleUtil.isChinese(context)) {
            return str + after
        } else {
            return after + " " + str
        }
    }

    @JvmStatic
    fun getDateTimeStrAt(time: Long, context: Context?, timePeriod: Boolean): String? {
        return getDateTimeStrAt(toZoned(time), context, timePeriod)
    }

    /**
     * Used to display [com.ywwynm.everythingdone.model.Reminder.notifyTime]
     * of a Reminder which belongs to a [com.ywwynm.everythingdone.model.Thing]
     * object with type [com.ywwynm.everythingdone.model.Thing.REMINDER] in detailed way.
     *
     * @param zdt A [ZonedDateTime] object representing
     *                 [com.ywwynm.everythingdone.model.Reminder.notifyTime].
     *
     * @param timePeriod Whether the returned string should contain time period
     *                   information such as "in the morning", "at night" and so on.
     *                   Used in non-Chinese contexts.
     *
     * @return A string describing time in a detailed way. For example, will be
     *         "on Jan 29, 1995, 16:40", "yesterday, 2:33", "星期六清晨5:55" and so on.
     */
    @JvmStatic
    fun getDateTimeStrAt(zdt: ZonedDateTime?, context: Context?, timePeriod: Boolean): String? {
        val cur: ZonedDateTime = ZonedDateTime.now()
        val year: Int = zdt!!.getYear()
        val curYear: Int = cur.getYear()
        val month: Int = zdt.getMonthValue()
        val day: Int = zdt.getDayOfMonth()
        var dayOfWeek: Int = zdt.getDayOfWeek().getValue()
        dayOfWeek = if (dayOfWeek == 7) 1 else dayOfWeek + 1
        var curDayOfWeek: Int = cur.getDayOfWeek().getValue()
        curDayOfWeek = if (curDayOfWeek == 7) 1 else curDayOfWeek + 1
        val hour: Int = zdt.getHour()
        val minute: Int = zdt.getMinute()

        val res: Resources = context!!.getResources()
        val date: Date = Date.from(zdt.toInstant())
        val sb: StringBuilder = StringBuilder()
        val isChinese: Boolean = LocaleUtil.isChinese(context)

        val days: Int = calculateTimeGap(cur.toInstant().toEpochMilli(),
                zdt.toInstant().toEpochMilli(), Calendar.DATE)
        if (days < 0) {
            if (days == -1) {
                sb.append(res.getString(R.string.yesterday))
            } else if (days >= -getEarlyWeekLimitDays(curDayOfWeek)) {
                if (!isChinese) {
                    sb.append("on ")
                }
                sb.append(res.getStringArray(R.array.day_of_week)[dayOfWeek - 1])
            } else {
                appendYearMonthDayStr(year, month, day, curYear, date, context, sb, isChinese)
            }
        } else if (days == 0) {
            sb.append(res.getString(R.string.today))
        } else {
            if (days == 1) {
                sb.append(res.getString(R.string.tomorrow))
            } else if (days <= getLateWeekLimitDays(curDayOfWeek)) {
                if (!isChinese) {
                    sb.append("on ")
                }
                sb.append(res.getStringArray(R.array.day_of_week)[dayOfWeek - 1])
            } else {
                appendYearMonthDayStr(year, month, day, curYear, date, context, sb, isChinese)
            }
        }
        if (isChinese) {
            sb.append(getTimePeriodStr(hour, res))
            appendHourMinute(hour, minute, sb)
        } else {
            sb.append(", ")
            appendHourMinute(hour, minute, sb)
            if (timePeriod) {
                sb.append(" ").append(getTimePeriodStr(hour, res))
            }
        }
        return sb.toString()
    }

    /**
     * Help `et` to ensure that it displays hour text correctly. When the field is above
     * 23, we should make it be 23 since there are only 24 hours in a day and 24:00 means 0:00
     * in EverythingDone.
     * @param et the [EditText] to improve text format.
     */
    @SuppressLint("SetTextI18n")
    @JvmStatic
    fun limitHourForEditText(et: EditText?) {
        val hourStr: String = et!!.getText().toString()
        if (!hourStr.isEmpty()) {
            val hour: Int = hourStr.toInt()
            if (hour >= 24) {
                et.setText("23")
            }
        }
    }

    /**
     * Help `et` to ensure that it displays minute text correctly. When the field is below
     * 10, we should add a zero before the original text to make it display in form of pattern "mm".
     * Besides, when the minute is above 59, since a hour contains only 60 minutes, we should let it
     * be 59, too.
     * @param et the [EditText] to improve text format.
     */
    @SuppressLint("SetTextI18n")
    @JvmStatic
    fun formatLimitMinuteForEditText(et: EditText?) {
        val minuteStr: String = et!!.getText().toString()
        if (minuteStr.length == 1) {
            et.setText("0" + minuteStr)
        } else if (!minuteStr.isEmpty()) {
            val minute: Int = minuteStr.toInt()
            if (minute > 59) {
                et.setText("59")
            }
        }
    }

    /**
     * Get datetime string for [com.ywwynm.everythingdone.model.Habit]. For the time being,
     * this method will be called only in [com.ywwynm.everythingdone.activities.DetailActivity]
     * and [com.ywwynm.everythingdone.fragments.DateTimeDialogFragment].
     *
     * This method will return a string in form of:
     * 1. For a daily habit: at 6:30, 12:00 every day; 每天6:30, 12:00
     * 2. For a weekly habit: at 19:00 every Monday, Wednesday; 每周一,三晚上19:00
     * 3. For a monthly habit: at 6:30 on the 1st, 6th, 16th, last day of every month;
     *                         每个月1号,6号,16号,月末早晨6:30
     * 4. For a yearly habit: at 18:00 on the last day of June, December in every year;
     *                        每年六月,十二月月末傍晚18:00
     *
     * @param context context used to get string resources
     * @param type habit's type
     * @param detail habit's detail information
     * @return appropriate string to describe the habit
     */
    @JvmStatic
    fun getDateTimeStrRec(context: Context?, type: Int, detail: String?): String? {
        if (type == Calendar.DATE) {
            return getDateTimeStrRecTimeOfDay(context, detail)
        } else if (type == Calendar.WEEK_OF_YEAR) {
            return getDateTimeStrRecDayOfWeek(context, detail)
        } else if (type == Calendar.MONTH) {
            return getDateTimeStrRecDayOfMonth(context, detail)
        } else if (type == Calendar.YEAR) {
            return getDateTimeStrRecMonthOfYear(context, detail)
        }
        return null
    }

    private fun getDateTimeStrRecTimeOfDay(context: Context?, detail: String?): String? {
        val sb: StringBuilder = StringBuilder()
        val every: String = context!!.getString(R.string.every)
        val day: String = context.getString(days) // 天
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (isChinese) {
            sb.append(every).append(day)
        } else {
            sb.append("at ")
        }
        val times: Array<String> = detail!!.split(",".toRegex()).toTypedArray()
        for (time in times) {
            sb.append(time).append(", ")
        }
        sb.deleteCharAt(sb.length - 1)
        sb.deleteCharAt(sb.length - 1)
        if (!isChinese) {
            sb.append(every).append(" ").append(day)
        }
        return sb.toString()
    }

    private fun getDateTimeStrRecDayOfWeek(context: Context?, detail: String?): String? {
        val sb: StringBuilder = StringBuilder()
        val dateTimes: Array<String> = detail!!.split(" ".toRegex()).toTypedArray()
        val days: Array<String> = dateTimes[0].split(",".toRegex()).toTypedArray()
        val times: Array<String> = dateTimes[1].split(":".toRegex()).toTypedArray()
        val every: String = context!!.getString(R.string.every)
        val dayOfWeek: Array<String?> = context.getResources().getStringArray(R.array.day_of_week)
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (isChinese) {
            sb.append(every)
            sb.append(dayOfWeek[0]!!.substring(0, 1))
            for (day in days) {
                sb.append(dayOfWeek[day.toInt()]!!.substring(1, 2)).append(",")
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(getTimePeriodStr(times[0].toInt(), context.getResources()))
                    .append(dateTimes[1])
        } else {
            sb.append("at ")
            sb.append(dateTimes[1]).append(every).append(" ")
            for (day in days) {
                sb.append(dayOfWeek[day.toInt()]).append(", ")
            }
            sb.deleteCharAt(sb.length - 1)
            sb.deleteCharAt(sb.length - 1)
        }
        return sb.toString()
    }

    private fun getDateTimeStrRecDayOfMonth(context: Context?, detail: String?): String? {
        val sb: StringBuilder = StringBuilder()
        val dateTimes: Array<String> = detail!!.split(" ".toRegex()).toTypedArray()
        val days: Array<String> = dateTimes[0].split(",".toRegex()).toTypedArray()
        val times: Array<String> = dateTimes[1].split(":".toRegex()).toTypedArray()
        val every: String = context!!.getString(R.string.every)
        val monthStr: String = context.getString(R.string.months)
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (isChinese) { // 每个月1号,6号,16号,26号早晨6:30
            sb.append(every).append(monthStr)
            val monthDay: String = context.getString(R.string.month_day)
            for (day in days) {
                if ("27".equals(day)) { // different from 28 in method below, but is correct.
                    sb.append(context.getString(R.string.end_of_month)).append(",")
                } else {
                    sb.append(day.toInt() + 1).append(monthDay).append(",")
                }
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(getTimePeriodStr(times[0].toInt(), context.getResources()))
                    .append(dateTimes[1])
        } else { // at 6:30 on the 1st, 6th, 16th, 26th day of every month
            sb.append("at ")
            sb.append(dateTimes[1]).append(" on the ")
            for (day in days) {
                if ("27".equals(day)) {
                    sb.append("last, ")
                } else {
                    sb.append(getDayOfMonthStrInEnglish(day)).append(", ")
                }
            }
            sb.deleteCharAt(sb.length - 2)
            sb.append("day of").append(every).append(" ").append(monthStr)
        }
        return sb.toString()
    }

    private fun getDateTimeStrRecMonthOfYear(context: Context?, detail: String?): String? {
        val sb: StringBuilder = StringBuilder()
        val dateTimes: Array<String> = detail!!.split(" ".toRegex()).toTypedArray()
        val months: Array<String> = dateTimes[0].split(",".toRegex()).toTypedArray()
        val day: String = dateTimes[1]
        val times: Array<String> = dateTimes[2].split(":".toRegex()).toTypedArray()
        val every: String = context!!.getString(R.string.every)
        val yearStr: String = context.getString(R.string.year).lowercase(java.util.Locale.getDefault())
        val monthOfYear: Array<String?> = context.getResources().getStringArray(R.array.month_of_year)
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (isChinese) { // 每年六月,十二月月末傍晚18:00
            sb.append(every).append(yearStr)
            for (month in months) {
                sb.append(monthOfYear[month.toInt()]).append(",")
            }
            sb.deleteCharAt(sb.length - 1)
            if ("28".equals(day)) {
                sb.append(context.getString(R.string.end_of_month))
            } else {
                sb.append(day.toInt()).append(context.getString(R.string.month_day))
            }
            sb.append(getTimePeriodStr(times[0].toInt(), context.getResources()))
                    .append(dateTimes[2])
        } else { // at 18:00 on the last day of June, December in every year
            sb.append("at ")
            sb.append(dateTimes[2]).append(" on the ")
            if ("28".equals(day)) {
                sb.append("last")
            } else {
                sb.append(getDayOfMonthStrInEnglish(day))
            }
            sb.append(" day of ")
            for (month in months) {
                sb.append(monthOfYear[month.toInt()]).append(", ")
            }
            sb.deleteCharAt(sb.length - 1)
            sb.deleteCharAt(sb.length - 1)
            sb.append(" in").append(every).append(" ").append(yearStr)
        }
        return sb.toString()
    }

    private fun getDayOfMonthStrInEnglish(dayStr: String?): String? {
        val day: Int = dayStr!!.toInt()
        val postfix: String
        if (day % 10 == 0) {
            postfix = "st"
        } else if (day % 10 == 1) {
            postfix = "nd"
        } else if (day % 11 == 2) {
            postfix = "rd"
        } else {
            postfix = "th"
        }
        return (day + 1).toString() + postfix
    }

    private fun appendYearMonthDayStr(year: Int, month: Int, day: Int, curYear: Int,
                                      date: Date, context: Context?, sb: StringBuilder, isChinese: Boolean) {
        val res: Resources = context!!.getResources()
        if (isChinese) {
            if (year != curYear) {
                sb.append(year)
                        .append(res.getString(R.string.year))
            }
            sb.append(month)
                    .append(res.getString(R.string.month))
                    .append(day)
                    .append(res.getString(R.string.day))
        } else {
            sb.append("on ")
            val sdf: java.text.SimpleDateFormat
            if (year != curYear) {
                sdf = java.text.SimpleDateFormat("MMM d, yyyy")
            } else {
                sdf = java.text.SimpleDateFormat("MMM d")
            }
            sb.append(sdf.format(date))
        }
    }

    private fun appendHourMinute(hour: Int, minute: Int, sb: StringBuilder) {
        sb.append(hour).append(":")
        if (minute < 10) {
            sb.append(0)
        }
        sb.append(minute)
    }

    private fun getEarlyWeekLimitDays(dayOfWeek: Int): Int {
        return if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
    }

    private fun getLateWeekLimitDays(dayOfWeek: Int): Int {
        return if (dayOfWeek == Calendar.SUNDAY) 0 else 8 - dayOfWeek
    }

    /**
     * Get a string describes time period. Often used in Chinese environment.
     * They are: 凌晨, 早晨, 上午, 中午, 下午, 傍晚, 晚上, 深夜
     */
    @JvmStatic
    fun getTimePeriodStr(hour: Int, res: Resources?): String? {
        val periods: Array<String?> = res!!.getStringArray(R.array.time_period)
        val limits: IntArray = intArrayOf(6, 8, 12, 13, 17, 19, 22)
        for (i in 0 until limits.size) {
            if (hour < limits[i]) {
                return periods[i]
            }
        }
        return periods[7]
    }

    // todo: add annotations for methods below.
    @JvmStatic
    fun getTimeTypeLimit(y: Int, m: Int, index: Int): Int {
        if (index == 1) {
            return 12
        } else if (index == 3) {
            return 23
        } else if (index == 4) {
            return 59
        } else if (index == 2) {
            return getDaysOfMonth(y, m)
        } else return Int.MAX_VALUE
    }

    @JvmStatic
    fun getDurationBriefStr(time: Long): String? {
        val second: Float = time / 1000f
        if (second < 1) {
            return "< 1s"
        } else if (second < 3600) {
            return java.text.SimpleDateFormat("mm:ss").format(Date(time))
        } else return java.text.SimpleDateFormat("HH:mm:ss").format(Date(time))
    }

    @JvmStatic
    fun getTimeLengthStr(time: Long, context: Context?): String? {
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        val BLK: String = if (isChinese) " " else ""

        val second: Long = time / 1000
        val secStr: String = context!!.getString(R.string.statistic_second)
        var minStr: String = context.getString(R.string.statistic_minute)
        val hourStr: String = context.getString(R.string.statistic_hour)
        val dayStr: String = context.getString(R.string.statistic_day)
        val yearStr: String = context.getString(R.string.statistic_year)
        if (second < 1) {
            return "< 1" + BLK + secStr
        } else if (second < 60) {
            return second.toString() + BLK + secStr
        } else if (second < 3600) {
            val min: Long = second / 60
            val sec: Long = second % 60
            if (sec == 0L) {
                return min.toString() + BLK + minStr
            } else {
                if (isChinese) {
                    minStr = minStr.substring(0, minStr.length - 1)
                }
                return min.toString() + BLK + minStr + " " + sec + BLK + secStr
            }
        } else if (second < 86400) {
            val hour: Long = second / 3600
            val min: Long = (second % 3600) / 60
            if (min == 0L) {
                return hour.toString() + BLK + hourStr
            } else {
                return hour.toString() + BLK + hourStr + " " + min + BLK + minStr
            }
        } else if (second < 86400 * 365) {
            val day: Long  =   second / 86400
            val hour: Long =  (second % 86400) / 3600
            val min: Long  = ((second % 86400) % 3600) / 60
            if (hour == 0L) {
                return day.toString() + BLK + dayStr
            } else if (min == 0L) {
                return day.toString() + BLK + dayStr + " " + hour + BLK + hourStr
            } else {
                return day.toString() + BLK + dayStr + " " +
                        hour + BLK + hourStr + " " +
                        min + BLK + minStr
            }
        } else {
            val year: Long =   second / 31536000
            var day: Long  =  (second % 31536000) / 86400
            val hour: Long = ((second % 31536000) % 86400) / 3600
            if (hour > 12) day++
            if (day == 0L) {
                return year.toString() + BLK + yearStr
            } else {
                return year.toString() + BLK + yearStr + " " + day + BLK + dayStr
            }
        }
    }

    @JvmStatic
    fun getTimeLengthStrOnlyDay(time: Long, context: Context?): String? {
        var day: Long = time / 86400000L
        val hour: Long = (time % 86400000) / 3600
        if (hour > 12) day++
        val dayStr: String = context!!.getString(days)
        if (day == 0L) {
            return "< 1 " + dayStr
        } else if (day == 1L) {
            return day.toString() + " " + dayStr
        } else {
            return day.toString() + " " + dayStr + (if (LocaleUtil.isChinese(context)) "" else "s")
        }
    }

    @JvmStatic
    fun getHabitReminderTime(type: Int, curHrTime: Long, vary: Int): Long {
        if (type == Calendar.DATE) {
            return curHrTime + vary * 86400000L
        } else if (type == Calendar.WEEK_OF_YEAR) {
            return curHrTime + vary * 604800000L
        }
        var zdt: ZonedDateTime = toZoned(curHrTime)
        var year: Int = zdt.getYear()
        var month: Int = zdt.getMonthValue()
        val day: Int = zdt.getDayOfMonth()
        if (type == Calendar.MONTH) {
            val days: Int = getDaysOfMonth(year, month)
            zdt = zdt.plusMonths(vary.toLong())
            if (day == days) {
                year = zdt.getYear()
                month = zdt.getMonthValue()
                zdt = zdt.withDayOfMonth(getDaysOfMonth(year, month))
            }
            return zdt.toInstant().toEpochMilli()
        } else if (type == Calendar.YEAR) {
            val days: Int = getDaysOfMonth(year, month)
            zdt = zdt.plusYears(vary.toLong())
            if (day == days) {
                zdt = zdt.withDayOfMonth(getDaysOfMonth(year + vary, month))
            }
            return zdt.toInstant().toEpochMilli()
        }
        return 0
    }

    @JvmStatic
    fun getDaysOfMonth(y: Int, m: Int): Int {
        val days: IntArray = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) {
            days[1] = 29
        }
        return days[m - 1]
    }

    @JvmStatic
    fun getThisTStr(type: Int, context: Context?): String? {
        if (type == Calendar.DATE) {
            return context!!.getString(R.string.today)
        } else if (type == Calendar.WEEK_OF_YEAR) {
            return context!!.getString(R.string.this_week)
        } else if (type == Calendar.MONTH) {
            return context!!.getString(R.string.this_month)
        } else if (type == Calendar.YEAR) {
            return context!!.getString(R.string.this_year)
        }
        return ""
    }

    @JvmStatic
    fun calculateTimeGap(start: Long, end: Long, type: Int): Int {
        var sZdt: ZonedDateTime = toZoned(start).truncatedTo(ChronoUnit.DAYS)
        var eZdt: ZonedDateTime = toZoned(end).truncatedTo(ChronoUnit.DAYS)

        if (type == Calendar.DATE) {
            return ChronoUnit.DAYS.between(sZdt, eZdt).toInt()
        } else if (type == Calendar.WEEK_OF_YEAR) {
            sZdt = sZdt.with(TemporalAdjusters.previousOrSame(
                    WeekFields.ISO.getFirstDayOfWeek()))
            eZdt = eZdt.with(TemporalAdjusters.previousOrSame(
                    WeekFields.ISO.getFirstDayOfWeek()))
            return ChronoUnit.WEEKS.between(sZdt, eZdt).toInt()
        } else if (type == Calendar.MONTH) {
            sZdt = sZdt.withDayOfMonth(1)
            eZdt = eZdt.withDayOfMonth(1)
            return ChronoUnit.MONTHS.between(sZdt, eZdt).toInt()
        } else if (type == Calendar.YEAR) {
            return eZdt.getYear() - sZdt.getYear()
        }
        return 0
    }

    private fun formatMillis(millis: Long, pattern: String?): String? {
        return toZoned(millis).format(DateTimeFormatter.ofPattern(pattern))
    }

    private fun toZoned(millis: Long): ZonedDateTime {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    }

}
