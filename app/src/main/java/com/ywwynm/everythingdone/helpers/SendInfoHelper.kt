package com.ywwynm.everythingdone.helpers

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.widget.Toast

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.LocalizedText
import com.ywwynm.everythingdone.utils.ChineseText
import com.ywwynm.everythingdone.R.string.days
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.BitmapUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.StringUtil

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date

/**
 * Created by ywwynm on 2016/2/13.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * send information to other apps, like sharing or sending feedback.
 */
object SendInfoHelper {

    const val TAG: String = "SendInfoHelper"

    private const val EXTRA_WX_SHARE_EXPLORE_CONTENT: String = "Kdescription"

    /** 多条记事拼成一段文字时，条与条之间的分隔。 */
    private const val THINGS_SEPARATOR: String = "———\n\n"

    @JvmStatic
    fun shareApp(context: Context?) {
        val title: String = context!!.getString(R.string.act_share_everythingdone)
        val content: String = context.getString(R.string.app_share_info)

        val bm: Bitmap = (ContextCompat.getDrawable(
                context, R.drawable.ic_launcher_ori) as BitmapDrawable).bitmap
        val file: File = BitmapUtil.saveBitmapToStorage(FileUtil.getTempPath(context), "app.jpeg", bm)!!
        val uri: Uri = FileProvider.getUriForFile(context,
                "com.ywwynm.everythingdone", file)
        val list: ArrayList<Uri?> = ArrayList()
        list.add(uri)

        startShare(context, title, content, list, true)
    }

    @JvmStatic
    fun shareThing(context: Context?, thing: Thing?) {
        if (thing == null) return

        val title: String?      = getShareThingTitle(context, thing)
        val content: String?    = getThingShareInfo(context, thing)
        val attachment: String? = thing.attachment

        startShare(context, title, content,
                AttachmentHelper.toUriList(attachment), AttachmentHelper.isAllImage(attachment))
    }

    /**
     * 选择模式里选了多条时的「文本与图片」：几条的文字按列表顺序拼成一段，附件全部带上
     * （与单条一样，视频与录音也带）。选一条时与 [shareThing] 相同。
     */
    @JvmStatic
    fun shareThings(context: Context, things: List<Thing>) {
        if (things.isEmpty()) return
        if (things.size == 1) {
            shareThing(context, things[0])
            return
        }
        val sb = StringBuilder()
        val attachments = ArrayList<Uri?>()
        var allImage = true
        for (thing in things) {
            getThingShareBody(context, thing)?.let {
                if (sb.isNotEmpty()) sb.append(THINGS_SEPARATOR)
                sb.append(it)
            }
            val attachment = thing.attachment
            val uris = AttachmentHelper.toUriList(attachment) ?: continue
            attachments.addAll(uris)
            if (!AttachmentHelper.isAllImage(attachment)) allImage = false
        }
        sb.append(context.getString(R.string.from_everything_done))
        startShare(context, context.getString(R.string.act_share), sb.toString(),
                if (attachments.isEmpty()) null else attachments, allImage && attachments.isNotEmpty())
    }

    @JvmStatic
    fun getShareThingTitle(context: Context?, thing: Thing?): String? {
        return thing?.let { LocalizedText.typeTitle(context!!, it.type, share = true) }
    }

    @SuppressLint("SimpleDateFormat")
    @JvmStatic
    fun sendFeedback(context: Context?, attachLogFile: Boolean) {
        val intent = Intent()
        intent.putExtra(Intent.EXTRA_SUBJECT, context!!.getString(R.string.act_feedback) + "-" +
                SimpleDateFormat("yyyyMMddHHmmss").format(Date()) +
                "-" + BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE)
        intent.putExtra(Intent.EXTRA_TEXT, DeviceUtil.getDeviceInfo() + "\n")

        val email: String = Def.Meta.FEEDBACK_EMAIL
        if (!attachLogFile) {
            intent.setAction(Intent.ACTION_SENDTO)
            intent.setData(Uri.parse("mailto:$email"))
        } else {
            intent.setAction(Intent.ACTION_SEND)
            intent.setType("message/rfc822")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.putExtra(Intent.EXTRA_EMAIL, arrayOf<String?>(email))
            intent.putExtra(Intent.EXTRA_STREAM, getLatestLogUri(context))
        }

        try {
            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.send_feedback_to_developer)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.error_activity_not_found),
                    Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLatestLogUri(context: Context?): Uri? {
        val dirPath: String = Def.getAppFileDir(context) + "/log"
        val dir = File(dirPath)
        if (dir.exists()) {
            val files: Array<out File?> = dir.listFiles() ?: return null
            var max: File? = null
            var maxName = ""
            for (file in files) {
                val name: String = file!!.getName()
                if (name.endsWith(".log") && name > maxName) {
                    maxName = name
                    max = file
                }
            }
            return FileProvider.getUriForFile(context!!,
                    "com.ywwynm.everythingdone", max!!)
        }
        return null
    }

    @JvmStatic
    fun rateApp(context: Context?) {
        val uri: Uri = Uri.parse("market://details?id=" + context!!.packageName)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            context.startActivity(Intent.createChooser(
                    intent, context.getString(R.string.support_select_market)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.error_activity_not_found),
                    Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun getThingShareInfo(context: Context?, thing: Thing?): String? {
        val body: String = getThingShareBody(context, thing) ?: return null
        return body + context!!.getString(R.string.from_everything_done)
    }

    /** 一条记事的分享文字，不含末尾的「来自完事儿」：多条拼接时只在最后加一次。 */
    private fun getThingShareBody(context: Context?, thing: Thing?): String? {
        val title: String = thing!!.getTitleToDisplay()!!
        val content: String = thing.content!!
        if (title.isEmpty() && content.isEmpty()) {
            return null
        }
        val sb: StringBuilder = StringBuilder()
        if (!title.isEmpty()) {
            sb.append(title).append("\n\n")
        }
        if (!content.isEmpty()) {
            if (CheckListHelper.isCheckListStr(content)) {
                sb.append(CheckListHelper.toContentStr(content, "- [ ] ", "- [x] "))
            } else {
                sb.append(content)
            }
            sb.append("\n\n")
        }

        val id: Long = thing.id
        @Thing.Type  val type: Int  = thing.type
        @Thing.State val state: Int = thing.state
        if (type == Thing.REMINDER) {
            val reminder: Reminder? = ReminderDAO.getInstance(context)!!.getReminderById(id)
            if (reminder != null) {
                val ctx = context!!
                val reminderInfoStr: String = DateTimeUtil.getDateTimeStrReminder(
                        context, thing, reminder, true)
                sb.append(ctx.getString(R.string.l10n_share_reminder, reminderInfoStr)).append("\n\n")
            }
        } else if (type == Thing.HABIT) {
            val habit: Habit? = HabitDAO.getInstance(context)!!.getHabitById(id)
            if (habit != null) {
                sb.append(StringUtil.upperFirst(getHabitShareInfo(context, id, state))).append("\n\n")
            }
        } else if (type == Thing.GOAL) {
            if (state != Thing.FINISHED) {
                val goal: Reminder? = ReminderDAO.getInstance(context)!!.getReminderById(id)
                if (goal != null) {
                    val goalInfoStr: String = DateTimeUtil.getDateTimeStrGoal(context, thing, goal)
                    sb.append(StringUtil.upperFirst(goalInfoStr)).append("\n\n")
                }
            }
        }

        if (state == Thing.FINISHED && type != Thing.HABIT) {
            sb.append(StringUtil.upperFirst(getFinishedThingInfo(context, thing))).append("\n\n")
        }

        sb.append(context!!.getString(R.string.from_everything_done))
        return sb.toString()
    }

    @JvmStatic
    fun getHabitShareInfo(context: Context?, id: Long, thingState: Int): String {
        val habit = HabitDAO.getInstance(context)!!.getHabitById(id) ?: return ""
        val ctx = context!!
        var schedule = DateTimeUtil.getDateTimeStrRec(ctx, habit.type, habit.detail) ?: habit.getSummary(ctx)
        if (habit.isPaused()) schedule += ", " + habit.getStateDescription(ctx)
        if (LocaleUtil.isChinese(ctx)) {
            val text = ctx.getString(R.string.remind_me) + schedule + "\n" +
                ChineseText.habitProgress(ctx, habit.type, habit.getPersistInT(), habit.getFinishedTimes())
            return if (thingState == Thing.FINISHED) text + ", " + ctx.getString(R.string.share_habit_developed) else text
        }
        val text = ctx.getString(R.string.l10n_share_reminder, schedule) + "\n" +
            ctx.getString(R.string.l10n_habit_progress,
                LocalizedText.effortElapsed(ctx, habit.type, habit.getPersistInT()),
                LocaleUtil.getTimesStr(ctx, habit.getFinishedTimes(), false))
        return if (thingState == Thing.FINISHED) text + "\n" + ctx.getString(R.string.share_habit_developed)
        else text
    }

    private fun getFinishedThingInfo(context: Context?, thing: Thing): String {
        val ctx = context!!
        if (thing.type == Thing.GOAL) {
            val goal = ReminderDAO.getInstance(ctx)!!.getReminderById(thing.id)!!
            val gap = DateTimeUtil.calculateTimeGap(goal.updateTime, thing.finishTime, Calendar.DATE)
            if (LocaleUtil.isChinese(ctx)) return ChineseText.goalShare(ctx, gap)
            return ctx.getString(R.string.l10n_share_goal_finished,
                LocalizedText.effortElapsed(ctx, Calendar.DATE, gap))
        }
        return ctx.getString(R.string.finish_at_normal,
            DateTimeUtil.getDateTimeStrAt(thing.finishTime, ctx, true))
    }

    private fun startShare(
            context: Context?, title: String?, content: String?, attachments: ArrayList<Uri?>?, allImage: Boolean) {
        val intent = Intent()
        if (attachments == null) {
            intent.setAction(Intent.ACTION_SEND)
            intent.setType("text/plain")
            intent.putExtra(Intent.EXTRA_TEXT, content)
        } else {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (allImage) {
                intent.setType("image/*")
                intent.putExtra(EXTRA_WX_SHARE_EXPLORE_CONTENT, content)
            } else {
                intent.setType("*/*")
            }
            if (content != null) {
                intent.putExtra(Intent.EXTRA_TEXT, content)
            }
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments)
        }
        context!!.startActivity(Intent.createChooser(intent, title))
    }

}
