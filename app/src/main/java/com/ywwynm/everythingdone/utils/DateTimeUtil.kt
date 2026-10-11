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

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.TemporalField
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.abs

/**
 * Created by ywwynm on 2015/8/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Helper for formatting datetime and getting information about that.
 */
object DateTimeUtil {

    const val TAG: String = $$"EverythingDone$DateTimeUtil"

    @JvmStatic
    fun getGeneralDateStr(context: Context?, time: Long): String? {
        return LocalizedText.date(context!!, time)
    }

    @JvmStatic
    fun getGeneralDateTimeStr(context: Context?, time: Long): String? {
        return LocalizedText.date(context!!, time, "yMMMEdHms")
    }

    @JvmStatic
    fun getGeneralDateFormatPattern(context: Context?): String {
        return LocalizedText.datePattern(context!!, "yMMMd")
    }

    @JvmStatic
    fun getGeneralDateTimeFormatPattern(context: Context?): String {
        return LocalizedText.datePattern(context!!, "yMMMEdHms")
    }

    @JvmStatic
    fun getDateTimeStr(type: Int, time: Int, context: Context?): String {
        return LocalizedText.duration(context!!, type, time)
    }

    @JvmStatic
    fun getTemporalFieldFor(type: Int): TemporalField? {
        when (type) {
            Calendar.MINUTE ->
                return ChronoField.MINUTE_OF_HOUR
            Calendar.HOUR_OF_DAY ->
                return ChronoField.HOUR_OF_DAY
            Calendar.DATE ->
                return ChronoField.DAY_OF_MONTH
            Calendar.WEEK_OF_YEAR ->
                return WeekFields.ISO.weekOfWeekBasedYear()
            Calendar.MONTH ->
                return ChronoField.MONTH_OF_YEAR
            Calendar.YEAR ->
                return ChronoField.YEAR
            else ->
                return ChronoField.ERA
        }
    }

    @JvmStatic
    fun getTimeTypeStr(type: Int, context: Context?): String {
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
        val calendar = GregorianCalendar()
        calendar.setTimeInMillis(startTime)
        calendar.add(timeType, afterTime)
        return calendar.getTimeInMillis()
    }

    /**
     * see [getDateTimeStrReminder]
     */
    @JvmStatic
    fun getDateTimeStrReminder(context: Context?, thingId: Long, timePeriod: Boolean): String {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, thingId, -1)
        val thing: Thing = pair.first ?: return ""
        val reminder: Reminder =
            ReminderDAO.getInstance(context)!!.getReminderById(thingId) ?: return ""
        return getDateTimeStrReminder(context, thing, reminder, timePeriod)
    }

    /**
     * see [getDateTimeStrReminder]
     */
    @JvmStatic
    fun getDateTimeStrReminder(context: Context?, thing: Thing?, reminder: Reminder?): String {
        return getDateTimeStrReminder(context, thing, reminder, false)
    }

    /**
     * see [getDateTimeStrReminder]
     */
    @JvmStatic
    fun getDateTimeStrReminder(
            context: Context?, thing: Thing?, reminder: Reminder?, timePeriod: Boolean): String {
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
            timePeriod: Boolean): String {
        var timeStr: String = getDateTimeStrAt(notifyTime, context, timePeriod)
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
    fun getDateTimeStrGoal(context: Context?, thingId: Long): String {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, thingId, -1)
        val thing: Thing = pair.first ?: return ""
        val goal: Reminder =
            ReminderDAO.getInstance(context)!!.getReminderById(thingId) ?: return ""
        return getDateTimeStrGoal(context, thing, goal)
    }

    /**
     * see [getDateTimeStrGoal]
     */
    @JvmStatic
    fun getDateTimeStrGoal(context: Context?, thing: Thing?, goal: Reminder?): String {
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
            @Thing.State thingState: Int, goalState: Int): String {
        val ctx = context!!
        if (LocaleUtil.isChinese(ctx)) return ChineseText.goalStatus(ctx, notifyTime, goalCreateTime, thingFinishTime, thingState, goalState)
        if (thingState == Thing.UNDERWAY) {
            val now = System.currentTimeMillis()
            val days = abs(calculateTimeGap(now, notifyTime, Calendar.DATE))
            return when {
                days == 0 && now <= notifyTime -> ctx.getString(R.string.goal_should_finish_before,
                    LocalizedText.time(ctx, notifyTime))
                days == 0 -> ctx.getString(R.string.l10n_goal_overdue)
                now < notifyTime -> ctx.getString(R.string.l10n_goal_remaining,
                    LocalizedText.duration(ctx, Calendar.DATE, days))
                else -> ctx.getString(R.string.l10n_goal_overdue_duration,
                    LocalizedText.duration(ctx, Calendar.DATE, days))
            }
        }
        if (thingState == Thing.FINISHED) {
            val finishDays = calculateTimeGap(goalCreateTime, thingFinishTime, Calendar.DATE)
            val goalDays = calculateTimeGap(goalCreateTime, notifyTime, Calendar.DATE)
            val key = when {
                finishDays < goalDays -> R.string.l10n_goal_finished_early
                finishDays > goalDays -> R.string.l10n_goal_finished_late
                else -> R.string.l10n_goal_finished
            }
            return ctx.getString(key, LocalizedText.elapsed(ctx, Calendar.DATE, finishDays))
        }
        return Reminder.getStateDescription(thingState, goalState, ctx)
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
            context: Context?, notifyTime: Long, timePeriod: Boolean): String {
        val shouldBefore: String = context!!.getString(R.string.goal_should_finish_before)
        var dateTimeAtStr: String = getDateTimeStrAt(notifyTime, context, timePeriod)
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
        return if (time == 0) getThisTStr(type, context)
        else LocalizedText.after(context!!, type, time)
    }

    @JvmStatic
    fun getDateTimeStrAt(time: Long, context: Context?, timePeriod: Boolean): String {
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
    fun getDateTimeStrAt(zdt: ZonedDateTime?, context: Context?, timePeriod: Boolean): String {
        val ctx = context!!
        val value = zdt!!
        if (LocaleUtil.isChinese(ctx)) return ChineseText.dateAt(ctx, value)
        val now = ZonedDateTime.now()
        val millis = value.toInstant().toEpochMilli()
        val days = calculateTimeGap(now.toInstant().toEpochMilli(), millis, Calendar.DATE)
        val weekday = if (now.dayOfWeek.value == 7) 1 else now.dayOfWeek.value + 1
        val label = when {
            days == -1 -> ctx.getString(R.string.yesterday)
            days == 0 -> ctx.getString(R.string.today)
            days == 1 -> ctx.getString(R.string.tomorrow)
            days in -getEarlyWeekLimitDays(weekday)..getLateWeekLimitDays(weekday) ->
                LocalizedText.date(ctx, millis, "EEEE")
            else -> LocalizedText.date(ctx, millis, if (value.year == now.year) "MMMd" else "yMMMd")
        }
        return if (timePeriod || LocaleUtil.isChinese(ctx)) {
            ctx.getString(R.string.l10n_date_time_period, label, LocalizedText.time(ctx, millis),
                getTimePeriodStr(value.hour, ctx.resources))
        } else ctx.getString(R.string.l10n_date_time, label, LocalizedText.time(ctx, millis))
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
            et.setText("0$minuteStr")
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
        if (type !in listOf(Calendar.DATE, Calendar.WEEK_OF_YEAR, Calendar.MONTH, Calendar.YEAR)) return null
        return LocalizedText.recurrence(context!!, type, detail!!)
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
        return when (index) {
            1 -> 12
            3 -> 23
            4 -> 59
            2 -> getDaysOfMonth(y, m)
            else -> Int.MAX_VALUE
        }
    }

    @JvmStatic
    fun getDurationBriefStr(time: Long): String? {
        val context = App.getApp()!!
        val seconds = time / 1000
        return if (seconds < 1) {
            if (LocaleUtil.isChinese(context)) "< 1s"
            else "< " + LocalizedText.duration(context, Calendar.SECOND, 1, android.icu.text.MeasureFormat.FormatWidth.NARROW)
        } else if (seconds < 3600) {
            String.format(LocalizedText.locale(context), "%02d:%02d", seconds / 60, seconds % 60)
        } else {
            String.format(LocalizedText.locale(context), "%02d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60)
        }
    }

    @JvmStatic
    fun getTimeLengthStr(time: Long, context: Context?): String? {
        val ctx = context!!
        val second = time / 1000
        val parts = mutableListOf<kotlin.Pair<Int, Number>>()
        if (second < 1) {
            return "< " + LocalizedText.duration(ctx, Calendar.SECOND, 1,
                android.icu.text.MeasureFormat.FormatWidth.SHORT)
        } else if (second < 60) {
            parts += Calendar.SECOND to second
        } else if (second < 3600) {
            parts += Calendar.MINUTE to second / 60
            if (second % 60 != 0L) parts += Calendar.SECOND to second % 60
        } else if (second < 86400) {
            parts += Calendar.HOUR_OF_DAY to second / 3600
            if (second % 3600 / 60 != 0L) parts += Calendar.MINUTE to second % 3600 / 60
        } else if (second < 31536000) {
            parts += Calendar.DATE to second / 86400
            val hour = second % 86400 / 3600
            val minute = second % 3600 / 60
            if (hour != 0L) parts += Calendar.HOUR_OF_DAY to hour
            if (minute != 0L) parts += Calendar.MINUTE to minute
        } else {
            parts += Calendar.YEAR to second / 31536000
            var day = second % 31536000 / 86400
            if (second % 86400 / 3600 > 12) day++
            if (day != 0L) parts += Calendar.DATE to day
        }
        return LocalizedText.durationParts(ctx, *parts.toTypedArray())
    }

    @JvmStatic
    fun getTimeLengthStrOnlyDay(time: Long, context: Context?): String? {
        var day = time / 86400000L
        val hour = (time % 86400000L) / 3600000L
        if (hour > 12) day++
        if (LocaleUtil.isChinese(context)) return (if (day == 0L) "< 1" else day.toString()) + " " + context!!.getString(days)
        return LocalizedText.elapsed(context!!, Calendar.DATE, day.toInt())
    }

    @JvmStatic
    fun getHabitReminderTime(type: Int, curHrTime: Long, vary: Int): Long {
        if (type == Calendar.DATE) {
            return curHrTime + vary * 86400000L
        } else if (type == Calendar.WEEK_OF_YEAR) {
            return curHrTime + vary * 604800000L
        }
        var zdt: ZonedDateTime = toZoned(curHrTime)
        var year: Int = zdt.year
        var month: Int = zdt.monthValue
        val day: Int = zdt.dayOfMonth
        if (type == Calendar.MONTH) {
            val days: Int = getDaysOfMonth(year, month)
            zdt = zdt.plusMonths(vary.toLong())
            if (day == days) {
                year = zdt.year
                month = zdt.monthValue
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
    fun getThisTStr(type: Int, context: Context?): String {
        return when (type) {
            Calendar.DATE -> context!!.getString(R.string.today)
            Calendar.WEEK_OF_YEAR -> context!!.getString(R.string.this_week)
            Calendar.MONTH -> context!!.getString(R.string.this_month)
            Calendar.YEAR -> context!!.getString(R.string.this_year)
            else -> ""
        }
    }

    @JvmStatic
    fun calculateTimeGap(start: Long, end: Long, type: Int): Int {
        var sZdt: ZonedDateTime = toZoned(start).truncatedTo(ChronoUnit.DAYS)
        var eZdt: ZonedDateTime = toZoned(end).truncatedTo(ChronoUnit.DAYS)

        return when (type) {
            Calendar.DATE -> ChronoUnit.DAYS.between(sZdt, eZdt).toInt()
            Calendar.WEEK_OF_YEAR -> {
                sZdt = sZdt.with(TemporalAdjusters.previousOrSame(
                        WeekFields.ISO.firstDayOfWeek
                ))
                eZdt = eZdt.with(TemporalAdjusters.previousOrSame(
                        WeekFields.ISO.firstDayOfWeek
                ))
                ChronoUnit.WEEKS.between(sZdt, eZdt).toInt()
            }
            Calendar.MONTH -> {
                sZdt = sZdt.withDayOfMonth(1)
                eZdt = eZdt.withDayOfMonth(1)
                ChronoUnit.MONTHS.between(sZdt, eZdt).toInt()
            }
            Calendar.YEAR -> eZdt.year - sZdt.year
            else -> 0
        }
    }

    /**
     * 当前时间在本地时区下的"日历日"序号（epoch day）。与 [calculateTimeGap] 的
     * `truncatedTo(DAYS)` 同口径（均按 [ZoneId.systemDefault] 的午夜边界切分），供大文件夹缩略图
     * 签名判定 reminder / goal 倒计时文本是否跨日需刷新使用。
     */
    @JvmStatic
    fun getLocalEpochDay(millis: Long): Long {
        return toZoned(millis).toLocalDate().toEpochDay()
    }

    private fun formatMillis(millis: Long, pattern: String?): String? {
        return toZoned(millis).format(DateTimeFormatter.ofPattern(pattern))
    }

    private fun toZoned(millis: Long): ZonedDateTime {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    }

}
