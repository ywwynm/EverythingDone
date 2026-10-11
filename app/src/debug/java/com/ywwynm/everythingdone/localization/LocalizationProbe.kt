@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.localization

import android.app.Instrumentation
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.RecurrencePickerAdapter
import com.ywwynm.everythingdone.adapters.StatisticAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.LocalizedText
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.ChineseText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅 debug：测试真实格式化入口及日期弹窗；临时切换应用语言，结束后恢复设置。 */
class LocalizationProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private val failures = JSONArray()
    private val samples = JSONArray()
    private var checks = 0
    private var host: DetailActivity? = null
    private var dialog: DateTimeDialogFragment? = null
    private var controls: Dialog? = null
    private val languages = listOf("en", "zh-CN", "zh-TW", "zh-HK", "de", "es", "fr", "hi", "it", "ja", "ko", "pt", "ru")

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        // start() 可先于 Application.onCreate 执行；等主线程完成应用初始化再读取 App。
        waitForIdleSync()
        val run = args.getString("run_id", "localization")!!
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "localization/$run").apply { mkdirs() }
        val originalConfig = Configuration(App.getApp()!!.resources.configuration)
        val originalDefault = Locale.getDefault()
        val originalLanguage = languagePreference()
        val before = databaseDigest()
        val result = JSONObject()
        try {
            // 故意让进程默认语言与测试语言不同，验证代码使用传入的应用资源语言。
            Locale.setDefault(Locale.US)
            for (tag in languages) functional(tag)
            for (tag in args.getString("ui_languages", "zh-CN,ja,ru")!!.split(',')) ui(tag)
        } catch (error: Throwable) {
            failures.put(error.stackTraceToString())
        } finally {
            runOnMainSync {
                controls?.dismiss()
                dialog?.dismissAllowingStateLoss()
                host?.finish()
            }
            waitForIdleSync()
            setLanguagePreference(originalLanguage)
            runOnMainSync {
                LocaleUtil.applyStoredLanguageToAppCompat(targetContext)
                App.getApp()!!.resources.updateConfiguration(originalConfig, App.getApp()!!.resources.displayMetrics)
            }
            Locale.setDefault(originalDefault)
            val after = databaseDigest()
            verify("existing_database_unchanged", before == after,
                (before.keys + after.keys).filter { before[it] != after[it] }.toString())
            verify("language_preference_unchanged", originalLanguage == languagePreference())
            result.put("checks", checks).put("failures", failures).put("samples", samples)
                .put("languages", JSONArray(languages)).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("model", android.os.Build.MODEL)
                .put("database_before", JSONObject(before)).put("database_after", JSONObject(after))
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures.length() == 0) 0 else 1, Bundle().apply {
                putString("result", "checks=$checks failures=${failures.length()} path=$output/result.json")
            })
        }
    }

    private fun functional(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        val ctx = targetContext.createConfigurationContext(Configuration(targetContext.resources.configuration).apply { setLocale(locale) })
        val sample = JSONObject().put("locale", tag)
        val quantities = JSONArray()
        val file = File(output, "attachment-fixture").apply { writeText("fixture") }
        for (count in listOf(0, 1, 2, 5, 11, 21)) {
            val images = AttachmentHelper.toAttachmentStr(List(count) { "${AttachmentHelper.IMAGE}${file.path}" }, null)
            val audios = AttachmentHelper.toAttachmentStr(null, List(count) { "${AttachmentHelper.AUDIO}${file.path}" })
            val imageText = AttachmentHelper.getImageAttachmentCountStr(images, ctx)
            val audioText = AttachmentHelper.getAudioAttachmentCountStr(audios, ctx)
            verify("$tag image count $count", if (count == 0) imageText == null else !imageText.isNullOrBlank())
            verify("$tag audio count $count", if (count == 0) audioText == null else !audioText.isNullOrBlank())
            val expectedImage = when (tag) {
                "en" -> "$count image" + if (count == 1) "" else "s"
                "ja" -> "画像${count}枚"
                "ko" -> "이미지 ${count}개"
                "zh-CN" -> "$count 张图片"
                "zh-TW", "zh-HK" -> "$count 張圖片"
                "ru" -> "$count " + when (count) { 1, 21 -> "изображение"; 2 -> "изображения"; else -> "изображений" }
                else -> null
            }
            if (expectedImage != null && count > 0) verify("$tag image grammar $count", imageText == expectedImage, imageText)
            if (tag.startsWith("zh") && count > 0) {
                val expectedAudio = if (tag == "zh-CN") "$count 段音频" else "$count 段音訊"
                verify("$tag original audio count $count", audioText == expectedAudio, audioText)
            }
            val period = LocalizedText.frequency(ctx, Calendar.DATE, count)
            val checklist = CheckListAdapter.finishedItemsCountStr(ctx, count)
            val folders = LocalizedText.folderCount(ctx, count, count)
            verify("$tag quantities $count", listOf(period, checklist, folders).none { it.contains('%') || it.isBlank() })
            quantities.put(JSONObject().put("count", count).put("images", imageText).put("audios", audioText)
                .put("frequency", period).put("checklist", checklist).put("folders", folders))
        }
        val mixed = AttachmentHelper.toAttachmentStr(listOf("0${file.path}", "1${file.path}", "0${file.path}", "0${file.path}-missing"), null)
        sample.put("mixed_attachments", AttachmentHelper.getImageAttachmentCountStr(mixed, ctx))
        val imageOnly = AttachmentHelper.toAttachmentStr(listOf("0${file.path}", "0${file.path}"), null)
        val videoOnly = AttachmentHelper.toAttachmentStr(listOf("1${file.path}"), null)
        verify("$tag attachment punctuation", AttachmentHelper.getImageAttachmentCountStr(mixed, ctx) ==
            AttachmentHelper.getImageAttachmentCountStr(imageOnly, ctx) + ", " + AttachmentHelper.getImageAttachmentCountStr(videoOnly, ctx))
        val separator = when {
            tag.startsWith("zh") -> "，"
            tag == "ja" -> "、"
            tag in listOf("de", "es", "fr", "hi", "it", "pt", "ru") -> " · "
            else -> ", "
        }
        for (count in listOf(1, 2, 5, 11, 21)) {
            verify("$tag folder punctuation $count", LocalizedText.folderCount(ctx, count, 5) ==
                LocalizedText.folderCount(ctx, count, 0) + separator + LocalizedText.folderCount(ctx, 0, 5))
            val impact = LocalizedText.folderImpact(ctx, count, 5)
            verify("$tag folder operation punctuation $count", impact ==
                LocalizedText.folderImpact(ctx, count, 0) + ctx.getString(R.string.folder_impact_separator) + LocalizedText.folderImpact(ctx, 0, 5), impact)
        }
        sample.put("folder_summary", LocalizedText.folderCount(ctx, 2, 5))
            .put("folder_operation_counts", LocalizedText.folderImpact(ctx, 2, 5))
        if (tag.startsWith("zh")) {
            val simplified = tag == "zh-CN"
            val originalMixed = if (simplified) "2 张图片, 1 个视频" else "2 張圖片, 1 個影片"
            val mixedText = AttachmentHelper.getImageAttachmentCountStr(mixed, ctx)
            verify("$tag original mixed attachments", mixedText == originalMixed, mixedText)
            val videoOnly = AttachmentHelper.toAttachmentStr(listOf("1${file.path}"), null)
            val videoText = AttachmentHelper.getImageAttachmentCountStr(videoOnly, ctx)
            verify("$tag original video count", videoText == if (simplified) "1 个视频" else "1 個影片", videoText)
            val originalCounts = if (simplified) listOf("0件记事", "1个文件夹", "1件记事", "2个文件夹，5件记事")
                else listOf("0件記事", "1個資料夾", "1件記事", "2個資料夾，5件記事")
            val actualCounts = listOf(0 to 0, 1 to 0, 0 to 1, 2 to 5).map { (folders, things) ->
                LocalizedText.folderCount(ctx, folders, things)
            }
            verify("$tag original folder summaries", actualCounts == originalCounts, actualCounts.toString())
            sample.put("original_folder_summaries", JSONArray(actualCounts))
        }
        verify("$tag missing attachment ignored", AttachmentHelper.getImageAttachmentCountStr("${AttachmentHelper.SIGNAL}0${file.path}-missing", ctx) == null)
        file.delete()
        sample.put("quantities", quantities)
        val fixed = ZonedDateTime.now().withYear(2026).withMonth(4).withDayOfMonth(23).withHour(17).withMinute(8).withSecond(6)
        val millis = fixed.toInstant().toEpochMilli()
        val dateTime = DateTimeUtil.getGeneralDateTimeStr(ctx, millis)!!
        sample.put("date", DateTimeUtil.getGeneralDateStr(ctx, millis)).put("datetime", dateTime)
        val pattern = LocalizedText.datePattern(ctx, "yMMMEdHms").replace(Regex("'[^']*'"), "")
        verify("$tag 24 hour", 'H' in pattern && 'h' !in pattern && dateTime.contains("17"), dateTime)
        val order = LocalizedText.dateFieldOrder(ctx).toList()
        val expectedOrder = when {
            tag in listOf("zh-CN", "zh-TW", "ja", "ko") -> listOf(0, 1, 2)
            tag == "en" -> listOf(1, 2, 0)
            else -> listOf(2, 1, 0)
        }
        verify("$tag date field order", order == expectedOrder, order.toString())
        val schedule = JSONArray()
        for ((type, detail) in listOf(Calendar.DATE to "6:30,17:08", Calendar.WEEK_OF_YEAR to "0,6 17:08",
            Calendar.MONTH to "0,10,20,26,27 17:08", Calendar.YEAR to "0,11 1 17:08", Calendar.YEAR to "0,11 28 17:08")) {
            val text = DateTimeUtil.getDateTimeStrRec(ctx, type, detail)!!
            schedule.put(text)
            verify("$tag recurrence $detail", text.contains("17:08") && !text.contains('%'), text)
            if (type == Calendar.YEAR && detail == "0,11 1 17:08") {
                verify("$tag yearly day one", text.contains(ctx.getString(R.string.l10n_month_day, 1)), text)
            }
            if ((type == Calendar.MONTH && detail.startsWith("0,10")) || detail.contains(" 28 ")) {
                verify("$tag last day", text.contains(ctx.getString(R.string.l10n_month_last_day)), text)
            }
        }
        sample.put("recurrence", schedule)
        grammar(ctx, tag, sample, millis)
        if (tag.startsWith("zh")) chineseOriginal(ctx, tag, sample, fixed)
        val after = LocalizedText.after(ctx, Calendar.MINUTE, 21)
        sample.put("after_21_minutes", after)
        if (tag == "ru") verify("ru relative case", after.replace('\u00a0', ' ') == "через 21 минуту", after)
        verify("$tag hour alias", LocalizedText.after(ctx, Calendar.HOUR, 1) == LocalizedText.after(ctx, Calendar.HOUR_OF_DAY, 1))
        verify("$tag sharing 21 is plural", LocalizedText.selectionShareTitle(ctx, true, 21) == ctx.getString(R.string.l10n_share_folders))
        val thing = Thing(-1L, Thing.NOTE, 0, -1L)
        verify("$tag complete share title", SendInfoHelper.getShareThingTitle(ctx, thing) == ctx.getString(R.string.l10n_share_note))
        for (type in listOf(Def.PickerType.DAY_OF_WEEK, Def.PickerType.DAY_OF_MONTH, Def.PickerType.MONTH_OF_YEAR)) {
            runOnMainSync { verify("$tag recurrence picker $type", RecurrencePickerAdapter(ctx, type, 0xff336699.toInt()).itemCount > 0) }
        }
        val lessThanDay = if (tag.startsWith("zh")) "< 1 " + ctx.getString(R.string.days) else LocalizedText.elapsed(ctx, Calendar.DATE, 0)
        verify("$tag rounded less than day", DateTimeUtil.getTimeLengthStrOnlyDay(12 * 3600000L, ctx) == lessThanDay)
        verify("$tag rounded day", DateTimeUtil.getTimeLengthStrOnlyDay(13 * 3600000L, ctx) == LocalizedText.elapsed(ctx, Calendar.DATE, 1))
        verify("$tag media hour duration", DateTimeUtil.getDurationBriefStr(3600000L) == "01:00:00")
        verify("$tag media duration beyond one day", DateTimeUtil.getDurationBriefStr(90061000L) == "25:01:01")
        val shortDuration = DateTimeUtil.getTimeLengthStr(3661000L, ctx)!!
        sample.put("duration", shortDuration)
        if (tag == "ja") verify("ja duration units", shortDuration.contains("時間") && shortDuration.contains("分"), shortDuration)
        if (tag == "ko") verify("ko duration units", shortDuration.contains("시간") && shortDuration.contains("분"), shortDuration)
        val resources = ctx.resources
        for (field in R.string::class.java.fields) {
            if (field.type != Int::class.javaPrimitiveType) continue
            val value = resources.getString(field.getInt(null))
            formatCheck("$tag/${field.name}", locale, value)
        }
        for (field in R.plurals::class.java.fields) {
            for (count in listOf(0, 1, 2, 5, 11, 21)) formatCheck("$tag/${field.name}/$count", locale,
                resources.getQuantityString(field.getInt(null), count), count)
        }
        samples.put(sample)
    }

    /** 检查真实格式化入口生成的整句与边界词形，避免只验证“能格式化”。 */
    private fun grammar(ctx: Context, tag: String, sample: JSONObject, millis: Long) {
        val sentences = JSONArray()
        val dailyExpected = mapOf(
            "es" to "Cada día a la 1:00 y a las 17:08",
            "it" to "Ogni giorno all’1:00 e alle 17:08",
            "pt" to "Todos os dias à 1:00 e às 17:08",
            "en" to "Every day at 1:00, at 17:08"
        )
        val daily = DateTimeUtil.getDateTimeStrRec(ctx, Calendar.DATE, "1:00,17:08")!!
        dailyExpected[tag]?.let { verify("$tag mixed hours sentence", daily == it, daily) }
        sentences.put(daily)
        for (time in listOf("1:00", "01:00", "1:30", "0:00", "13:00", "17:08")) {
            val singular = time in listOf("1:00", "01:00", "1:30")
            val ending = when (tag) {
                "es" -> if (singular) "a la $time" else "a las $time"
                "it" -> if (singular) "all’$time" else "alle $time"
                "pt" -> if (singular) "à $time" else "às $time"
                else -> null
            }
            for ((type, detail) in listOf(Calendar.DATE to time, Calendar.WEEK_OF_YEAR to "0,6 $time",
                Calendar.MONTH to "0,27 $time", Calendar.YEAR to "0,11 28 $time")) {
                val actual = DateTimeUtil.getDateTimeStrRec(ctx, type, detail)!!
                verify("$tag recurrence 24h $type/$time", actual.contains(time) && !actual.contains('%'), actual)
                if (ending != null) verify("$tag time agreement $type/$time", actual.endsWith(ending), actual)
                sentences.put(actual)
            }
        }
        val now = ZonedDateTime.now()
        for (days in listOf(0, 1, 2, 5)) {
            val start = now.minusDays(days.toLong()).toInstant().toEpochMilli()
            val goal = Reminder(-1L, start, Reminder.EXPIRED, 0L, start, start)
            val celebration = goal.getCelebrationText(ctx)!!
            // 当天逾期使用已经过去的本地午夜，避免样例时间与被测时钟处于同一毫秒。
            val deadline = if (days == 0) now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli() else start
            val overdue = DateTimeUtil.getDateTimeStrGoal(ctx, deadline, start, 0L, Thing.UNDERWAY, Reminder.EXPIRED)
            if (tag == "de") {
                val duration = if (days == 0) "weniger als 1 Tag" else "$days " + if (days == 1) "Tag" else "Tage"
                val expected = "Sie haben $duration gebraucht, um dieses Ziel zu erreichen. Jetzt ist es Zeit zu feiern!"
                verify("de goal sentence $days", celebration.startsWith(expected + "\n"), celebration)
                val expectedOverdue = if (days == 0) "Überfällig" else "Frist um $duration überschritten"
                verify("de overdue sentence $days", overdue == expectedOverdue, overdue)
            }
            verify("$tag goal sentence formatted $days", !celebration.contains('%') && !overdue.contains('%'))
            sentences.put(celebration).put(overdue)
        }
        for (weeks in listOf(0, 1, 2, 5, 11, 21)) {
            val habit = object : Habit(-1L, Calendar.WEEK_OF_YEAR, 2, "0 1:00", "11", "", millis, millis) {
                override fun getPersistInT() = weeks
            }
            val actual = habit.getCelebrationText(ctx)!!
            if (tag == "ru") {
                val duration = when (weeks) {
                    0 -> "меньше чем 1 неделю"
                    1, 21 -> "$weeks неделю"
                    2 -> "2 недели"
                    else -> "$weeks недель"
                }
                val expected = "Вы упорно занимались этим $duration и выполнили нужное действие 2 раза."
                verify("ru habit accusative $weeks", actual.startsWith(expected), actual)
                if (weeks == 1) verify("ru independent week stays nominative",
                    LocalizedText.elapsed(ctx, Calendar.WEEK_OF_YEAR, weeks).replace('\u00a0', ' ') == "1 неделя")
            }
            verify("$tag habit sentence formatted $weeks", !actual.contains('%'))
            sentences.put(actual)
        }
        if (tag == "ru") {
            val month = DateTimeUtil.getDateTimeStrRec(ctx, Calendar.MONTH, "0,27 1:00")!!
            verify("ru monthly sentence", month == "Каждый месяц 1-го и в последний день в 1:00", month)
            verify("ru completion rate is a percentage", ctx.getString(R.string.habit_detail_completion_rate) == "Процент выполнения" &&
                ctx.getString(R.string.statistic_habit_completion_rate) == "Процент выполнения")
        }
        val todoTitle = LocalizedText.dailyTodoTitle(ctx, millis)
        val todoHelp = ctx.getString(R.string.create_daily_todo_help_info, todoTitle)
        if (tag.startsWith("zh")) verify("$tag original daily todo example",
            todoHelp.contains("yyyy年M月d日要做的事情") && (todoHelp.contains("下午两点") || todoHelp.contains("下午兩點")), todoHelp)
        else verify("$tag daily todo example", todoHelp.contains(todoTitle) && !todoHelp.contains('%') && todoHelp.contains("14:00"), todoHelp)
        sample.put("grammar_sentences", sentences).put("daily_todo_help", todoHelp)
    }

    /** 使用改造前代码及原句作为预期，检查原标点、空格、量词和句式。 */
    private fun chineseOriginal(ctx: Context, tag: String, sample: JSONObject, fixed: ZonedDateTime) {
        val original = JSONObject()
        val now = ZonedDateTime.now()
        for (offset in listOf(-400, -10, -2, -1, 0, 1, 2, 5, 400)) {
            for (hour in listOf(0, 6, 8, 12, 15, 18, 21, 23)) {
                val date = now.plusDays(offset.toLong()).withHour(hour).withMinute(8)
                for (period in listOf(false, true)) {
                    val actual = DateTimeUtil.getDateTimeStrAt(date, ctx, period)
                    verify("$tag original date $offset/$hour/$period", actual == ChineseDateTimeBaseline.getDateTimeStrAt(date, ctx, period), actual)
                }
            }
        }
        for (time in listOf("0:00", "6:30", "12:00", "17:08", "23:59")) {
            for ((type, detail) in listOf(Calendar.DATE to "1:00,$time", Calendar.WEEK_OF_YEAR to "0,1,3,6 $time",
                Calendar.MONTH to "0,10,20,26,27 $time", Calendar.YEAR to "0,5,11 1 $time", Calendar.YEAR to "0,11 28 $time")) {
                val actual = DateTimeUtil.getDateTimeStrRec(ctx, type, detail)
                verify("$tag original recurrence $type/$detail", actual == ChineseDateTimeBaseline.getDateTimeStrRec(ctx, type, detail), actual)
            }
        }
        val start = fixed.toInstant().toEpochMilli()
        val deadline = fixed.plusDays(2).toInstant().toEpochMilli()
        for (finishDays in listOf(0, 1, 2, 5)) {
            val end = fixed.plusDays(finishDays.toLong()).toInstant().toEpochMilli()
            val actual = DateTimeUtil.getDateTimeStrGoal(ctx, deadline, start, end, Thing.FINISHED, Reminder.EXPIRED)
            verify("$tag original finished goal $finishDays", actual == ChineseDateTimeBaseline.getDateTimeStrGoal(ctx, deadline, start, end, Thing.FINISHED, Reminder.EXPIRED), actual)
            original.put("finished_goal_$finishDays", actual)
        }
        for (offset in listOf(-5, -1, 0, 1, 5)) {
            val end = now.plusDays(offset.toLong()).withHour(0).withMinute(0).toInstant().toEpochMilli()
            val actual = DateTimeUtil.getDateTimeStrGoal(ctx, end, start, 0, Thing.UNDERWAY, Reminder.EXPIRED)
            verify("$tag original ongoing goal $offset", actual == ChineseDateTimeBaseline.getDateTimeStrGoal(ctx, end, start, 0, Thing.UNDERWAY, Reminder.EXPIRED), actual)
        }
        for (duration in listOf(0L, 500L, 1000L, 59000L, 60000L, 61000L, 3600000L, 3661000L, 86400000L, 90061000L, 31536000000L)) {
            val actual = DateTimeUtil.getTimeLengthStr(duration, ctx)
            verify("$tag original duration $duration", actual == ChineseDateTimeBaseline.getTimeLengthStr(duration, ctx), actual)
        }
        for (type in listOf(Calendar.DATE, Calendar.WEEK_OF_YEAR, Calendar.MONTH, Calendar.YEAR)) {
            val expected = ctx.getString(R.string.every) + DateTimeUtil.getTimeTypeStr(type, ctx) + " 2 " + ctx.getString(R.string.times)
            verify("$tag original frequency $type", LocalizedText.frequency(ctx, type, 2) == expected)
        }
        for (type in listOf(Thing.NOTE, Thing.REMINDER, Thing.HABIT, Thing.GOAL)) {
            val expected = ctx.getString(R.string.act_share) + ctx.getString(R.string.this_gai) + Thing.getTypeStr(type, ctx)
            verify("$tag original share title $type", LocalizedText.typeTitle(ctx, type, true) == expected)
        }
        val simplified = tag == "zh-CN"
        val progress = ChineseText.habitProgress(ctx, Calendar.WEEK_OF_YEAR, 0, 2)
        verify("$tag original habit progress", progress == if (simplified) "我已经坚持了<1个星期, 完成了2次" else "我已經堅持了<1個星期, 完成了2次", progress)
        val goal = ChineseText.goalShare(ctx, 0)
        verify("$tag original goal share", goal == if (simplified) "我努力了<1天, 最终达成了这个目标" else "我努力了<1天, 最終達成了這個目標", goal)
        val statistics = ChineseText.statisticSince(ctx, start, 2)
        val expectedStatistics = when (tag) {
            "zh-CN" -> "我从 2026 年 4 月 23 日开始使用完事儿。在这 2 天里，我完成、创建了："
            "zh-TW" -> "我從 2026 年 4 月 23 日開始使用完事兒。在這 2 天裡，我完成、建立了："
            else -> "我從 2026 年 4 月 23 日開始使用完事兒，在這 2 天裏，我建立、完成了："
        }
        verify("$tag original statistic sentence", statistics == expectedStatistics, statistics)
        original.put("habit_progress", progress).put("goal_share", goal).put("statistics", statistics)
        for ((key, prefix) in listOf(R.string.l10n_created_at to R.string.create_at,
            R.string.l10n_updated_at to R.string.update_at, R.string.l10n_habit_developed_at to R.string.finish_at_habit)) {
            val actual = ctx.getString(key, "2026年4月23日")
            verify("$tag original date prefix $key", actual == ctx.getString(prefix) + "2026年4月23日", actual)
            original.put(ctx.resources.getResourceEntryName(key), actual)
        }
        sample.put("chinese_original", original)
    }

    private fun formatCheck(name: String, locale: Locale, text: String, count: Int = 21) {
        val tokens = Regex("%(\\d+)\\$([sdf])").findAll(text).toList()
        if (tokens.isEmpty()) return
        val arguments = Array<Any>(tokens.maxOf { it.groupValues[1].toInt() }) { "示例" }
        for (token in tokens) arguments[token.groupValues[1].toInt() - 1] = when (token.groupValues[2]) {
            "d" -> count; "f" -> count.toDouble(); else -> "示例"
        }
        try { String.format(locale, text, *arguments); checks++ }
        catch (error: Exception) { verify(name, false, error.toString()) }
    }

    private fun ui(tag: String) {
        setLanguagePreference(tag.replace('-', '_').let { if ('_' in it) it else "${it}_" })
        runOnMainSync { LocaleUtil.applyStoredLanguageToAppCompat(targetContext) }
        val intent = DetailActivity.getOpenIntentForCreate(targetContext, "localization-probe", null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host = startActivitySync(intent) as DetailActivity
        waitForIdleSync()
        val activity = host!!
        runOnMainSync {
            verify("$tag UI resource locale", activity.resources.configuration.locales[0].language == Locale.forLanguageTag(tag).language)
            verify("$tag UI independent reminder label", activity.findViewById<TextView>(R.id.tv_remind_me).text == activity.getString(R.string.l10n_reminder_label))
            if (tag.startsWith("zh")) {
                val percentages = listOf(LocaleUtil.getPercentStr(1, 4), LocaleUtil.getPercentStr(0, 0), LocaleUtil.getPercentStr(1, 3))
                verify("$tag original percent spacing", percentages == listOf("25 %", "0 %", "33.33 %"), percentages.toString())
                verify("$tag original short media duration", DateTimeUtil.getDurationBriefStr(500) == "< 1s")
            }
            dialog = DateTimeDialogFragment.newInstance(Thing(-1L, Thing.NOTE, 0xff336699.toInt(), -1L))
            dialog!!.show(activity.supportFragmentManager, "localization-probe")
            activity.supportFragmentManager.executePendingTransactions()
        }
        waitForIdleSync()
        val root = dialog!!.requireView()
        val pager = root.findViewById<ViewPager>(R.id.vp_date_time)
        val appearance = com.ywwynm.everythingdone.fragments.BaseDialogFragment::class.java
            .getDeclaredField("appearanceSettled").apply { isAccessible = true }
        var appeared = false
        val appearanceDeadline = SystemClock.uptimeMillis() + 5000
        while (!appeared && SystemClock.uptimeMillis() < appearanceDeadline) {
            runOnMainSync { appeared = appearance.getBoolean(dialog) }
            if (!appeared) SystemClock.sleep(32)
        }
        verify("$tag appearance finished", appeared)
        runOnMainSync {
            val tabs = root.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tab_layout)
            val tabsWidth = (0 until tabs.tabCount).sumOf { tabs.getTabAt(it)!!.view.width }
            verify("$tag reminder tabs fit", tabsWidth <= tabs.width - tabs.paddingLeft - tabs.paddingRight,
                "$tabsWidth/${tabs.width}")
        }
        for (page in 0..2) {
            runOnMainSync { pager.setCurrentItem(page, false) }
            waitForIdleSync()
            // 等实际布局稳定，不靠坐标或固定长延迟操作界面。
            val deadline = SystemClock.uptimeMillis() + 3000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync { ready = !root.isLayoutRequested && root.width > 0 && pager.currentItem == page && root.isShown && root.alpha == 1f && root.hasWindowFocus() }
                if (!ready) SystemClock.sleep(32)
            }
            verify("$tag UI page $page laid out", ready)
            val frame = CountDownLatch(1)
            runOnMainSync { root.postOnAnimation { root.postOnAnimation { frame.countDown() } } }
            verify("$tag UI frame $page", frame.await(3, TimeUnit.SECONDS))
            runOnMainSync {
                if (page == 0) {
                    val ids = listOf(R.id.et_year_at, R.id.et_month_at, R.id.et_day_at)
                    val actual = ids.indices.sortedBy { index ->
                        IntArray(2).also { root.findViewById<EditText>(ids[index]).getLocationOnScreen(it) }[0]
                    }
                    verify("$tag UI date order", actual == LocalizedText.dateFieldOrder(activity).toList(), actual.toString())
                } else {
                    if (page == 1) root.findViewById<EditText>(R.id.et_time_after).setText("21")
                    val chinese = tag.startsWith("zh")
                    val label = root.findViewById<TextView>(if (page == 1) {
                        if (chinese) R.id.tv_time_as_bt_after else R.id.tv_summary_after
                    } else R.id.tv_time_as_bt_recurrence)
                    verify("$tag UI full sentence $page", !label.text.isNullOrBlank(), label.text.toString())
                    if (page == 1 && chinese) {
                        verify("$tag UI original delay unit", label.text.toString() == activity.getString(R.string.minute) + activity.getString(R.string.after), label.text.toString())
                        verify("$tag UI original delay labels", listOf(R.id.tv_amount_label_after, R.id.tv_unit_label_after, R.id.tv_summary_after).all {
                            root.findViewById<View>(it).visibility == View.GONE
                        })
                    } else if (page == 1) verify("$tag UI delay preview updates", label.text.toString() == LocalizedText.after(activity, Calendar.MINUTE, 21), label.text.toString())
                    verify("$tag UI no ellipsis $page", label.layout != null && (0 until label.layout.lineCount).all { label.layout.getEllipsisCount(it) == 0 })
                }
            }
            val captureFrame = CountDownLatch(1)
            runOnMainSync { root.postOnAnimation { root.postOnAnimation { captureFrame.countDown() } } }
            verify("$tag capture frame $page", captureFrame.await(3, TimeUnit.SECONDS))
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "$tag-page$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val closing = dialog!!.requireDialog()
        val animationFinished = closing.javaClass.getDeclaredField("animationFinished").apply { isAccessible = true }
        runOnMainSync { dialog!!.dismiss(); activity.supportFragmentManager.executePendingTransactions() }
        var dismissed = false
        val dismissDeadline = SystemClock.uptimeMillis() + 10000
        while (!dismissed && SystemClock.uptimeMillis() < dismissDeadline) {
            runOnMainSync { dismissed = !closing.isShowing && animationFinished.getBoolean(closing) }
            if (!dismissed) SystemClock.sleep(32)
        }
        verify("$tag date dialog dismissal finished", dismissed)
        controlUi(activity, tag)
        runOnMainSync { activity.finish() }
        waitForIdleSync()
        val finishDeadline = SystemClock.uptimeMillis() + 5000
        while (!activity.isDestroyed && SystemClock.uptimeMillis() < finishDeadline) SystemClock.sleep(32)
        verify("$tag UI activity finished", activity.isDestroyed)
        dialog = null
        host = null
    }

    /** 使用生产清单适配器和统计行，在独立临时弹窗绑定数据，不写入用户记事。 */
    private fun controlUi(activity: DetailActivity, tag: String) {
        lateinit var checklist: RecyclerView
        lateinit var statistics: RecyclerView
        var actionAfter: String? = null
        val rows = intArrayOf(R.string.statistic_habit_developed_rate, R.string.statistic_habit_finished_all,
            R.string.statistic_habit_completion_rate, R.string.statistic_habit_longest_finish_times,
            R.string.statistic_habit_longest_pit)
        runOnMainSync {
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            checklist = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                itemAnimator = null
                adapter = CheckListAdapter(activity, CheckListAdapter.EDITTEXT_EDITABLE,
                    mutableListOf("11" + activity.getString(R.string.thing), "2")).apply {
                    setThingColor(android.graphics.Color.WHITE)
                    setActionCallback(object : CheckListAdapter.ActionCallback {
                        override fun onAction(before: String?, after: String?) { actionAfter = after }
                    })
                }
            }
            statistics = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                itemAnimator = null
                adapter = StatisticAdapter(activity,
                    intArrayOf(R.drawable.drawer_finished, R.drawable.ic_habit_finish_and_all,
                        R.drawable.ic_habit_finish_rate, R.drawable.ic_longest_finish_times, R.drawable.ic_longest_pit),
                    rows, floatArrayOf(12f, 12f, 14f, 12f, 12f), arrayOf("100 %", "21 / 25", "84 %", "21", "21"))
            }
            content.addView(checklist, LinearLayout.LayoutParams(-1, -2))
            content.addView(statistics, LinearLayout.LayoutParams(-1, -2))
            controls = Dialog(activity).apply {
                setContentView(ScrollView(activity).apply { addView(content) })
                show()
                val density = activity.resources.displayMetrics.density
                window!!.setLayout(minOf(activity.resources.displayMetrics.widthPixels - (32 * density).toInt(),
                    (360 * density).toInt()), -2)
            }
        }
        waitForIdleSync()
        fun awaitRows() {
            var ready = false
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync { ready = !checklist.isLayoutRequested && !statistics.isLayoutRequested &&
                    checklist.findViewHolderForAdapterPosition(0) != null && statistics.childCount == rows.size &&
                    controls!!.window!!.decorView.hasWindowFocus() }
                if (!ready) SystemClock.sleep(32)
            }
            verify("$tag production rows laid out", ready)
        }
        awaitRows()
        runOnMainSync {
            val icon = checklist.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.iv_check_list_state)
            verify("$tag finished accessibility action", icon.contentDescription == activity.getString(R.string.cd_checklist_finished_item_clickable), icon.contentDescription.toString())
            verify("$tag unfinish click delivered", icon.performClick())
        }
        waitForIdleSync()
        awaitRows()
        runOnMainSync {
            verify("$tag unfinish changes item state", actionAfter?.contains("01" + activity.getString(R.string.thing)) == true, actionAfter)
            val icon = checklist.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.iv_check_list_state)
            verify("$tag unfinished accessibility action", icon.contentDescription == activity.getString(R.string.cd_checklist_unfinished_item_clickable), icon.contentDescription.toString())
            for (index in rows.indices) {
                val holder = statistics.findViewHolderForAdapterPosition(index) as StatisticAdapter.StatisticHolder
                val label = holder.tvFirst!!
                val value = holder.tvSecond!!
                verify("$tag statistic label $index", label.text == activity.getString(rows[index]), label.text.toString())
                verify("$tag statistic columns $index", label.right <= value.left, "${label.right}/${value.left}")
                verify("$tag statistic no vertical clipping $index", label.layout != null &&
                    label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom,
                    "${label.layout?.height}/${label.height}")
            }
        }
        val frame = CountDownLatch(1)
        runOnMainSync { statistics.postOnAnimation { statistics.postOnAnimation { frame.countDown() } } }
        verify("$tag controls capture frame", frame.await(3, TimeUnit.SECONDS))
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$tag-controls.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        runOnMainSync { controls!!.dismiss(); controls = null }
        waitForIdleSync()
    }

    private fun verify(name: String, success: Boolean, detail: String? = null) {
        checks++
        if (!success) failures.put(JSONObject().put("check", name).put("detail", detail))
    }

    private fun languagePreference() = targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(Def.Meta.KEY_LANGUAGE_CODE, null)

    private fun setLanguagePreference(value: String?) {
        val editor = targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
        if (value == null) editor.remove(Def.Meta.KEY_LANGUAGE_CODE) else editor.putString(Def.Meta.KEY_LANGUAGE_CODE, value)
        check(editor.commit())
    }

    private fun databaseDigest(): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        val db = DBHelper(targetContext).readableDatabase
        val tables = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use {
            while (it.moveToNext()) tables += it.getString(0)
        }
        for (table in tables) {
            val digest = MessageDigest.getInstance("SHA-256")
            db.rawQuery("SELECT * FROM \"${table.replace("\"", "\"\"")}\" ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) for (i in 0 until cursor.columnCount) {
                    if (cursor.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) digest.update(cursor.getBlob(i))
                    else digest.update((cursor.getString(i) ?: "<null>").toByteArray())
                    digest.update(0.toByte())
                }
            }
            hashes[table] = digest.digest().joinToString("") { "%02x".format(it) }
        }
        return hashes
    }
}
