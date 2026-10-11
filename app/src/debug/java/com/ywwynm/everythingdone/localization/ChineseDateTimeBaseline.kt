package com.ywwynm.everythingdone.localization

import android.content.Context
import android.content.res.Resources
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.R.string.days
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil.calculateTimeGap
import com.ywwynm.everythingdone.utils.DateTimeUtil.getTimePeriodStr
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 仅 debug：从 51b71df3c6898fcddb7160ccc3b0171b5b03fc26 的 DateTimeUtil 原样提取。
 * 只用于中文原文对照；不要随新的格式化实现一起修改预期。
 * 原有毫秒换算及媒体时长问题另行验证，不要求恢复数值错误。
 */
internal object ChineseDateTimeBaseline {
    private fun formatMillis(millis: Long, pattern: String): String = SimpleDateFormat(pattern).format(Date(millis))
    fun getDateTimeStrGoal(
            context: Context?, notifyTime: Long, goalCreateTime: Long, thingFinishTime: Long,
            @Thing.State thingState: Int, goalState: Int): String {
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (thingState == Thing.UNDERWAY) {
            val curTime: Long = System.currentTimeMillis()
            val days: Int = abs(calculateTimeGap(curTime, notifyTime, Calendar.DATE))
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
            val daysStr: String = if (finishDays == 0) {
                "<1"
            } else {
                finishDays.toString()
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

    fun getDateTimeStrAt(time: Long, context: Context?, timePeriod: Boolean): String {
        return getDateTimeStrAt(toZoned(time), context, timePeriod)
    }

    fun getDateTimeStrAt(zdt: ZonedDateTime?, context: Context?, timePeriod: Boolean): String {
        val cur: ZonedDateTime = ZonedDateTime.now()
        val year: Int = zdt!!.year
        val curYear: Int = cur.year
        val month: Int = zdt.monthValue
        val day: Int = zdt.dayOfMonth
        var dayOfWeek: Int = zdt.dayOfWeek.value
        dayOfWeek = if (dayOfWeek == 7) 1 else dayOfWeek + 1
        var curDayOfWeek: Int = cur.dayOfWeek.value
        curDayOfWeek = if (curDayOfWeek == 7) 1 else curDayOfWeek + 1
        val hour: Int = zdt.hour
        val minute: Int = zdt.minute

        val res: Resources = context!!.resources
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

    fun getDateTimeStrRec(context: Context?, type: Int, detail: String?): String? {
        return when (type) {
            Calendar.DATE -> getDateTimeStrRecTimeOfDay(context, detail)
            Calendar.WEEK_OF_YEAR -> getDateTimeStrRecDayOfWeek(context, detail)
            Calendar.MONTH -> getDateTimeStrRecDayOfMonth(context, detail)
            Calendar.YEAR -> getDateTimeStrRecMonthOfYear(context, detail)
            else -> null
        }
    }

    private fun getDateTimeStrRecTimeOfDay(context: Context?, detail: String?): String {
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

    private fun getDateTimeStrRecDayOfWeek(context: Context?, detail: String?): String {
        val sb: StringBuilder = StringBuilder()
        val dateTimes: Array<String> = detail!!.split(" ".toRegex()).toTypedArray()
        val days: Array<String> = dateTimes[0].split(",".toRegex()).toTypedArray()
        val times: Array<String> = dateTimes[1].split(":".toRegex()).toTypedArray()
        val every: String = context!!.getString(R.string.every)
        val dayOfWeek: Array<String?> = context.resources.getStringArray(R.array.day_of_week)
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (isChinese) {
            sb.append(every)
            sb.append(dayOfWeek[0]!!.substring(0, 1))
            for (day in days) {
                sb.append(dayOfWeek[day.toInt()]!!.substring(1, 2)).append(",")
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(getTimePeriodStr(times[0].toInt(), context.resources))
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

    private fun getDateTimeStrRecDayOfMonth(context: Context?, detail: String?): String {
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
                if ("27" == day) { // different from 28 in method below, but is correct.
                    sb.append(context.getString(R.string.end_of_month)).append(",")
                } else {
                    sb.append(day.toInt() + 1).append(monthDay).append(",")
                }
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(getTimePeriodStr(times[0].toInt(), context.resources))
                    .append(dateTimes[1])
        } else { // at 6:30 on the 1st, 6th, 16th, 26th day of every month
            sb.append("at ")
            sb.append(dateTimes[1]).append(" on the ")
            for (day in days) {
                if ("27" == day) {
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

    private fun getDateTimeStrRecMonthOfYear(context: Context?, detail: String?): String {
        val sb: StringBuilder = StringBuilder()
        val dateTimes: Array<String> = detail!!.split(" ".toRegex()).toTypedArray()
        val months: Array<String> = dateTimes[0].split(",".toRegex()).toTypedArray()
        val day: String = dateTimes[1]
        val times: Array<String> = dateTimes[2].split(":".toRegex()).toTypedArray()
        val every: String = context!!.getString(R.string.every)
        val yearStr: String = context.getString(R.string.year).lowercase(Locale.getDefault())
        val monthOfYear: Array<String?> = context.resources.getStringArray(R.array.month_of_year)
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        if (isChinese) { // 每年六月,十二月月末傍晚18:00
            sb.append(every).append(yearStr)
            for (month in months) {
                sb.append(monthOfYear[month.toInt()]).append(",")
            }
            sb.deleteCharAt(sb.length - 1)
            if ("28" == day) {
                sb.append(context.getString(R.string.end_of_month))
            } else {
                sb.append(day.toInt()).append(context.getString(R.string.month_day))
            }
            sb.append(getTimePeriodStr(times[0].toInt(), context.resources))
                    .append(dateTimes[2])
        } else { // at 18:00 on the last day of June, December in every year
            sb.append("at ")
            sb.append(dateTimes[2]).append(" on the ")
            if ("28" == day) {
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

    private fun getDayOfMonthStrInEnglish(dayStr: String?): String {
        val day: Int = dayStr!!.toInt()
        val postfix: String = if (day % 10 == 0) {
            "st"
        } else if (day % 10 == 1) {
            "nd"
        } else if (day % 11 == 2) {
            "rd"
        } else {
            "th"
        }
        return (day + 1).toString() + postfix
    }

    private fun appendYearMonthDayStr(year: Int, month: Int, day: Int, curYear: Int,
                                      date: Date, context: Context?, sb: StringBuilder, isChinese: Boolean) {
        val res: Resources = context!!.resources
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
            val sdf: SimpleDateFormat = if (year != curYear) {
                SimpleDateFormat("MMM d, yyyy")
            } else {
                SimpleDateFormat("MMM d")
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
            return "< 1$BLK$secStr"
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
                return "$min$BLK$minStr $sec$BLK$secStr"
            }
        } else if (second < 86400) {
            val hour: Long = second / 3600
            val min: Long = (second % 3600) / 60
            return if (min == 0L) {
                "$hour$BLK$hourStr"
            } else {
                "$hour$BLK$hourStr $min$BLK$minStr"
            }
        } else if (second < 86400 * 365) {
            val day: Long  =   second / 86400
            val hour: Long =  (second % 86400) / 3600
            val min: Long  = ((second % 86400) % 3600) / 60
            return if (hour == 0L) {
                "$day$BLK$dayStr"
            } else if (min == 0L) {
                "$day$BLK$dayStr $hour$BLK$hourStr"
            } else {
                "$day$BLK$dayStr $hour$BLK$hourStr $min$BLK$minStr"
            }
        } else {
            val year: Long =   second / 31536000
            var day: Long  =  (second % 31536000) / 86400
            val hour: Long = ((second % 31536000) % 86400) / 3600
            if (hour > 12) day++
            return if (day == 0L) {
                "$year$BLK$yearStr"
            } else {
                "$year$BLK$yearStr $day$BLK$dayStr"
            }
        }
    }

    private fun toZoned(millis: Long): ZonedDateTime {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    }
}
