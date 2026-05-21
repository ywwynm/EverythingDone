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

    @JvmStatic
    fun getShareThingTitle(context: Context?, thing: Thing?): String? {
        if (thing == null) return null

        val isChinese: Boolean = LocaleUtil.isChinese(context)
        var title: String = context!!.getString(R.string.act_share)
        val thisStr: String = context.getString(R.string.this_gai)
        if (!isChinese) {
            title = "$title $thisStr "
        } else {
            title += thisStr
        }
        return title + Thing.getTypeStr(thing.type, context)
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
                sb.append(CheckListHelper.toContentStr(content, "X  ", "√  "))
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
                val reminderMe: String = context!!.getString(R.string.remind_me)
                val reminderInfoStr: String = DateTimeUtil.getDateTimeStrReminder(
                        context, thing, reminder, true)
                sb.append(StringUtil.upperFirst(reminderMe)).append(reminderInfoStr).append("\n\n")
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
        val habit: Habit = HabitDAO.getInstance(context)!!.getHabitById(id) ?: return ""

        val type: Int = habit.type
        val piT: Int = habit.getPersistInT()
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        val sb: StringBuilder = StringBuilder()

        val remindMe: String = context!!.getString(R.string.remind_me)
        var habitStr: String? = DateTimeUtil.getDateTimeStrRec(context, habit.type, habit.detail)
        if (habitStr == null) {
            habitStr = habit.getSummary(context)
        } else if (habitStr.startsWith("at ")) {
            habitStr = habitStr.substring(3, habitStr.length)
        }
        if (habit.isPaused()) {
            habitStr += ", " + habit.getStateDescription(context)
        }
        val GAP = if (isChinese) {
            ""
        } else " "
        sb.append(remindMe).append(habitStr).append("\n")
                .append(context.getString(R.string.share_i_persist_in_for)).append(GAP)
                .append(if (piT < 1) "<1" else piT.toString()).append(GAP)
                .append(DateTimeUtil.getTimeTypeStr(type, context))
        if (!isChinese && piT > 1) {
            sb.append("s")
        }
        sb.append(", ")
                .append(context.getString(R.string.act_finish))
        if (isChinese) {
            sb.append("了")
        }
        val finishedTimes: Int = habit.getFinishedTimes()
        sb.append(GAP).append(LocaleUtil.getTimesStr(context, finishedTimes, false))

        if (thingState == Thing.FINISHED) {
            sb.append(", ").append(context.getString(R.string.share_habit_developed))
        }

        return sb.toString()
    }

    private fun getFinishedThingInfo(context: Context?, thing: Thing): String {
        val isChinese: Boolean = LocaleUtil.isChinese(context)
        val sb: StringBuilder = StringBuilder()
        @Thing.Type val type: Int = thing.type

        if (type == Thing.GOAL) {
            val goal: Reminder = ReminderDAO.getInstance(context)!!.getReminderById(thing.id)!!
            val gap: Int = DateTimeUtil.calculateTimeGap(
                    goal.updateTime, thing.finishTime, Calendar.DATE)
            val gapStr = if (gap == 0) {
                "<1"
            } else {
                gap.toString()
            }

            val STRGAP = if (isChinese) {
                ""
            } else " "
            sb.append(context!!.getString(R.string.share_i_work_hard_for)).append(STRGAP)
                    .append(gapStr).append(STRGAP).append(context.getString(days))
            if (!isChinese && gap > 1) {
                sb.append("s")
            }

            val achieve: String = context.getString(R.string.share_goal_finished)
            if (isChinese) {
                sb.append(", ").append(achieve)
            } else {
                sb.append(" ").append(achieve)
            }
            return sb.toString()
        }

        // else : type isn't GOAL
        sb.append(String.format(context!!.getString(R.string.finish_at_normal),
                DateTimeUtil.getDateTimeStrAt(thing.finishTime, context, true)))

        return sb.toString()
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
