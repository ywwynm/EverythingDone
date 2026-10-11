package com.ywwynm.everythingdone.utils

import android.content.Context
import android.icu.text.ListFormatter
import android.icu.text.MeasureFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Thing
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 展示始终使用当前资源语言；不依赖系统语言，也不改变业务时间或周期编码。 */
object LocalizedText {
    fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    fun number(context: Context, value: Number): String =
        NumberFormat.getIntegerInstance(locale(context)).format(value)

    fun quantity(context: Context, resource: Int, count: Int): String =
        context.resources.getQuantityString(resource, count, count)

    fun list(context: Context, values: List<String>): String =
        if (locale(context).language == "en") values.joinToString(", ")
        else ListFormatter.getInstance(locale(context)).format(values)

    fun datePattern(context: Context, skeleton: String): String {
        if (LocaleUtil.isChinese(context)) {
            if (skeleton == "yMMMd") return ChineseText.datePattern(context)
            if (skeleton == "yMMMEdHms") return ChineseText.datePattern(context) + "EEEE H:mm:ss"
        }
        return DateFormat.getBestDateTimePattern(locale(context), skeleton)
    }

    /** 索引始终对应原来的年、月、日字段，只改变展示和焦点顺序。 */
    fun dateFieldOrder(context: Context): IntArray = datePattern(context, "yMd")
        .replace(Regex("'[^']*'"), "")
        .filter { it in "yMLd" }
        .map { if (it == 'y') 0 else if (it == 'd') 2 else 1 }
        .distinct().toIntArray()

    fun date(context: Context, millis: Long, skeleton: String = "yMMMd"): String =
        SimpleDateFormat(datePattern(context, skeleton), locale(context)).format(Date(millis))

    fun time(context: Context, millis: Long): String =
        SimpleDateFormat("H:mm", locale(context)).format(Date(millis))

    /** 创建页与设置帮助共用标题示例，日期顺序始终跟随应用语言。 */
    fun dailyTodoTitle(context: Context, millis: Long = System.currentTimeMillis()): String =
        context.getString(R.string.l10n_daily_todo_title, date(context, millis))

    private fun unit(type: Int): MeasureUnit = when (type) {
        Calendar.SECOND -> MeasureUnit.SECOND
        Calendar.MINUTE -> MeasureUnit.MINUTE
        Calendar.HOUR, Calendar.HOUR_OF_DAY -> MeasureUnit.HOUR
        Calendar.DATE -> MeasureUnit.DAY
        Calendar.WEEK_OF_YEAR -> MeasureUnit.WEEK
        Calendar.MONTH -> MeasureUnit.MONTH
        Calendar.YEAR -> MeasureUnit.YEAR
        else -> error("Unsupported calendar unit: $type")
    }

    fun duration(context: Context, type: Int, count: Number,
                 width: MeasureFormat.FormatWidth = MeasureFormat.FormatWidth.WIDE): String {
        if (LocaleUtil.isChinese(context) && width == MeasureFormat.FormatWidth.WIDE) {
            val normalizedType = if (type == Calendar.HOUR) Calendar.HOUR_OF_DAY else type
            return count.toString() + DateTimeUtil.getTimeTypeStr(normalizedType, context)
        }
        if (LocaleUtil.isChinese(context) && width == MeasureFormat.FormatWidth.SHORT && type == Calendar.SECOND) {
            return "$count " + context.getString(R.string.statistic_second)
        }
        return MeasureFormat.getInstance(locale(context), width).formatMeasures(Measure(count, unit(type)))
    }

    fun durationParts(context: Context, vararg parts: Pair<Int, Number>): String {
        if (LocaleUtil.isChinese(context)) {
            return parts.joinToString(" ") { (type, count) ->
                var unit = context.getString(when (type) {
                    Calendar.SECOND -> R.string.statistic_second
                    Calendar.MINUTE -> R.string.statistic_minute
                    Calendar.HOUR, Calendar.HOUR_OF_DAY -> R.string.statistic_hour
                    Calendar.DATE -> R.string.statistic_day
                    else -> R.string.statistic_year
                })
                if (type == Calendar.MINUTE && parts.any { it.first == Calendar.SECOND }) unit = unit.dropLast(1)
                "$count $unit"
            }
        }
        return MeasureFormat.getInstance(locale(context), MeasureFormat.FormatWidth.SHORT)
            .formatMeasures(*parts.map { (type, count) -> Measure(count, unit(type)) }.toTypedArray())
    }

    fun elapsed(context: Context, type: Int, count: Int): String =
        if (LocaleUtil.isChinese(context)) ChineseText.elapsed(context, type, count)
        else if (count < 1) context.getString(R.string.l10n_less_than, duration(context, type, 1))
        else duration(context, type, count)

    /** 坚持或努力的整句使用时长宾语；俄语“1 周”在此需要宾格，统计标签仍使用 elapsed。 */
    fun effortElapsed(context: Context, type: Int, count: Int): String {
        val amount = count.coerceAtLeast(1)
        val text = if (type == Calendar.WEEK_OF_YEAR) {
            quantity(context, R.plurals.l10n_effort_weeks, amount)
        } else duration(context, type, amount)
        return if (count < 1) context.getString(R.string.l10n_less_than, text) else text
    }

    /** 为每个 24 小时时刻选择完整时间短语，再由本地化列表连接；01:00 与 1:00 等价。 */
    fun recurrenceTime(context: Context, time: String): String {
        val hour = time.substringBefore(':').toIntOrNull()
        val resource = if (hour == 1) R.string.l10n_recurrence_at_one
                       else R.string.l10n_recurrence_at_time
        return context.getString(resource, time)
    }

    fun after(context: Context, type: Int, count: Int): String {
        if (LocaleUtil.isChinese(context)) return duration(context, type, count) + context.getString(R.string.after)
        val relativeUnit = when (type) {
            Calendar.SECOND -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
            Calendar.MINUTE -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
            Calendar.HOUR, Calendar.HOUR_OF_DAY -> RelativeDateTimeFormatter.RelativeUnit.HOURS
            Calendar.DATE -> RelativeDateTimeFormatter.RelativeUnit.DAYS
            Calendar.WEEK_OF_YEAR -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
            Calendar.MONTH -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
            Calendar.YEAR -> RelativeDateTimeFormatter.RelativeUnit.YEARS
            else -> error("Unsupported calendar unit: $type")
        }
        return RelativeDateTimeFormatter.getInstance(locale(context))
            .format(count.toDouble(), RelativeDateTimeFormatter.Direction.NEXT, relativeUnit)
    }

    fun folderCount(context: Context, folders: Int, things: Int): String {
        // 中文数量摘要保留既有标点与量词，不使用 ICU 的“和／及”连接。
        if (LocaleUtil.isChinese(context)) {
            return when {
                folders > 0 && things > 0 -> context.getString(R.string.thing_folder_count_folders_things, folders, things)
                folders > 0 -> context.getString(R.string.thing_folder_count_folders, folders)
                else -> context.getString(R.string.thing_folder_count, things)
            }
        }
        val parts = mutableListOf<String>()
        if (folders > 0) parts += quantity(context, R.plurals.l10n_folders, folders)
        if (things > 0 || folders == 0) parts += quantity(context, R.plurals.l10n_things, things)
        val separator = when (locale(context).language) {
            "ja" -> "、"
            "de", "es", "fr", "hi", "it", "pt", "ru" -> " · "
            else -> ", "
        }
        return parts.joinToString(separator)
    }

    fun typeTitle(context: Context, type: Int, share: Boolean): String {
        val id = if (share) when (type) {
            Thing.NOTE -> R.string.l10n_share_note
            Thing.REMINDER -> R.string.l10n_share_reminder_title
            Thing.HABIT -> R.string.l10n_share_habit
            else -> R.string.l10n_share_goal
        } else when (type) {
            Thing.NOTE -> R.string.l10n_edit_note
            Thing.REMINDER -> R.string.l10n_edit_reminder
            Thing.HABIT -> R.string.l10n_edit_habit
            else -> R.string.l10n_edit_goal
        }
        return context.getString(id)
    }

    fun folderImpact(context: Context, folders: Int, things: Int): String {
        val parts = mutableListOf<String>()
        if (folders > 0) parts += quantity(context, R.plurals.l10n_subfolders, folders)
        if (things > 0) parts += quantity(context, R.plurals.l10n_things, things)
        return if (parts.isEmpty()) context.getString(R.string.folder_impact_empty)
        else parts.joinToString(context.getString(R.string.folder_impact_separator))
    }

    fun selectionShareTitle(context: Context, foldersOnly: Boolean, count: Int): String =
        context.getString(if (foldersOnly) {
            if (count == 1) R.string.l10n_share_folder else R.string.l10n_share_folders
        } else {
            if (count == 1) R.string.l10n_share_thing else R.string.l10n_share_things
        })

    fun frequency(context: Context, type: Int, count: Int): String {
        if (LocaleUtil.isChinese(context)) return context.getString(R.string.every) +
            DateTimeUtil.getTimeTypeStr(type, context) + " " + LocaleUtil.getTimesStr(context, count)
        val id = when (type) {
            Calendar.DATE -> R.string.l10n_frequency_day
            Calendar.WEEK_OF_YEAR -> R.string.l10n_frequency_week
            Calendar.MONTH -> R.string.l10n_frequency_month
            else -> R.string.l10n_frequency_year
        }
        return context.getString(id, quantity(context, R.plurals.l10n_times, count))
    }

    fun recurrence(context: Context, type: Int, detail: String): String {
        if (LocaleUtil.isChinese(context)) return ChineseText.recurrence(context, type, detail)
        val fields = detail.split(' ')
        val selected = fields[0].split(',')
        return when (type) {
            Calendar.DATE -> context.getString(R.string.l10n_recurrence_daily,
                list(context, selected.map { recurrenceTime(context, it) }))
            Calendar.WEEK_OF_YEAR -> {
                val days = context.resources.getStringArray(R.array.day_of_week)
                context.getString(R.string.l10n_recurrence_weekly,
                    list(context, selected.map { days[it.toInt()] }), recurrenceTime(context, fields[1]))
            }
            Calendar.MONTH -> context.getString(R.string.l10n_recurrence_monthly,
                list(context, selected.map {
                    // 月周期按零起点保存，27 专门代表月末，不能作为 28 日显示。
                    if (it.toInt() == 27) context.getString(R.string.l10n_month_last_day)
                    else context.getString(R.string.l10n_month_day, it.toInt() + 1)
                }), recurrenceTime(context, fields[1]))
            Calendar.YEAR -> {
                val months = context.resources.getStringArray(R.array.month_of_year)
                val day = fields[1].toInt()
                // 年周期的日从 1 开始，28 代表月末，与月周期编码不同。
                context.getString(R.string.l10n_recurrence_yearly,
                    list(context, selected.map { months[it.toInt()] }),
                    if (day == 28) context.getString(R.string.l10n_month_last_day)
                    else context.getString(R.string.l10n_month_day, day), recurrenceTime(context, fields[2]))
            }
            else -> error("Unsupported recurrence: $type")
        }
    }
}
