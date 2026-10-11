package com.ywwynm.everythingdone.utils

import android.content.Context
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import java.time.ZonedDateTime
import java.util.Calendar
import kotlin.math.abs

/** 中文沿用本地化改造前的资源与排版；外语语法调整不能改写中文基准。 */
internal object ChineseText {
    fun elapsed(context: Context, type: Int, count: Int, spaced: Boolean = true): String =
        (if (count < 1) "<1" else count.toString()) + (if (spaced) " " else "") +
            DateTimeUtil.getTimeTypeStr(type, context)

    fun datePattern(context: Context): String =
        "yyyy${context.getString(R.string.year)}M${context.getString(R.string.month)}d${context.getString(R.string.day)}"

    fun dateAt(context: Context, value: ZonedDateTime): String {
        val now = ZonedDateTime.now()
        val days = DateTimeUtil.calculateTimeGap(now.toInstant().toEpochMilli(), value.toInstant().toEpochMilli(), Calendar.DATE)
        val weekday = if (now.dayOfWeek.value == 7) 1 else now.dayOfWeek.value + 1
        val first = if (weekday == 1) 6 else weekday - 2
        val last = if (weekday == 1) 0 else 8 - weekday
        val label = when {
            days == -1 -> context.getString(R.string.yesterday)
            days == 0 -> context.getString(R.string.today)
            days == 1 -> context.getString(R.string.tomorrow)
            days in -first..last -> context.resources.getStringArray(R.array.day_of_week)[value.dayOfWeek.value % 7]
            else -> (if (value.year != now.year) "${value.year}${context.getString(R.string.year)}" else "") +
                "${value.monthValue}${context.getString(R.string.month)}${value.dayOfMonth}${context.getString(R.string.day)}"
        }
        return label + DateTimeUtil.getTimePeriodStr(value.hour, context.resources) +
            "${value.hour}:${value.minute.toString().padStart(2, '0')}"
    }

    fun recurrence(context: Context, type: Int, detail: String): String {
        val fields = detail.split(' ')
        val selected = fields[0].split(',')
        val every = context.getString(R.string.every)
        if (type == Calendar.DATE) return every + context.getString(R.string.days) + selected.joinToString(", ")
        val time = fields.last()
        val suffix = DateTimeUtil.getTimePeriodStr(time.substringBefore(':').toInt(), context.resources) + time
        return when (type) {
            Calendar.WEEK_OF_YEAR -> {
                val days = context.resources.getStringArray(R.array.day_of_week)
                every + days[0].substring(0, 1) + selected.joinToString(",") { days[it.toInt()].substring(1, 2) } + suffix
            }
            Calendar.MONTH -> every + context.getString(R.string.months) + selected.joinToString(",") {
                if (it == "27") context.getString(R.string.end_of_month)
                else "${it.toInt() + 1}${context.getString(R.string.month_day)}"
            } + suffix
            Calendar.YEAR -> {
                val months = context.resources.getStringArray(R.array.month_of_year)
                every + context.getString(R.string.year) + selected.joinToString(",") { months[it.toInt()] } +
                    (if (fields[1] == "28") context.getString(R.string.end_of_month)
                     else "${fields[1].toInt()}${context.getString(R.string.month_day)}") + suffix
            }
            else -> error("Unsupported recurrence: $type")
        }
    }

    fun goalStatus(context: Context, notifyTime: Long, createTime: Long, finishTime: Long, thingState: Int, goalState: Int): String {
        if (thingState == Thing.UNDERWAY) {
            val now = System.currentTimeMillis()
            val days = abs(DateTimeUtil.calculateTimeGap(now, notifyTime, Calendar.DATE))
            return when {
                days == 0 && now <= notifyTime -> context.getString(R.string.goal_should_finish_before, LocalizedText.time(context, notifyTime))
                days == 0 -> context.getString(R.string.goal_overdue).substring(0, 3)
                now < notifyTime -> context.getString(R.string.goal_should_finish_in, days)
                else -> context.getString(R.string.goal_overdue, days)
            }
        }
        if (thingState == Thing.FINISHED) {
            val finishDays = DateTimeUtil.calculateTimeGap(createTime, finishTime, Calendar.DATE)
            val goalDays = DateTimeUtil.calculateTimeGap(createTime, notifyTime, Calendar.DATE)
            val key = when {
                finishDays < goalDays -> R.string.goal_finished_in_advance
                finishDays > goalDays -> R.string.goal_finished_overdue
                else -> R.string.goal_finished_normal
            }
            return context.getString(key, if (finishDays == 0) "<1" else finishDays.toString())
        }
        return Reminder.getStateDescription(thingState, goalState, context)
    }

    fun habitCelebration(context: Context, type: Int, periods: Int, times: Int): String =
        context.getString(R.string.celebration_habit_part_1) + " " + elapsed(context, type, periods) +
            context.getString(R.string.celebration_habit_part_2) + " " + LocaleUtil.getTimesStr(context, times) +
            context.getString(R.string.celebration_habit_part_3) + "\n" + context.getString(R.string.celebration_habit_part_4)

    fun goalCelebration(context: Context, days: Int): String =
        context.getString(R.string.celebration_goal_part_1) + " " + elapsed(context, Calendar.DATE, days) +
            context.getString(R.string.celebration_goal_part_2) + "\n" + context.getString(R.string.celebration_goal_part_3)

    fun habitProgress(context: Context, type: Int, periods: Int, times: Int): String =
        context.getString(R.string.share_i_persist_in_for) + elapsed(context, type, periods, spaced = false) +
            ", " + context.getString(R.string.act_finish) + "了" + LocaleUtil.getTimesStr(context, times, false)

    fun goalShare(context: Context, days: Int): String =
        context.getString(R.string.share_i_work_hard_for) + elapsed(context, Calendar.DATE, days, spaced = false) +
            ", " + context.getString(R.string.share_goal_finished)

    fun statisticSince(context: Context, millis: Long, days: Int): String {
        val pattern = " yyyy ${context.getString(R.string.year)} M ${context.getString(R.string.month)} d ${context.getString(R.string.day)}"
        val date = java.text.SimpleDateFormat(pattern, LocalizedText.locale(context)).format(java.util.Date(millis))
        return context.getString(R.string.statistic_start_from_part_1) + date +
            context.getString(R.string.statistic_start_from_part_2) + " $days " + context.getString(R.string.statistic_start_from_part_3)
    }
}
