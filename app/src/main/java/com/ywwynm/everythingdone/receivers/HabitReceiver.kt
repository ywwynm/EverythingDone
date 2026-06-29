package com.ywwynm.everythingdone.receivers

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Pair
import android.widget.Toast

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.HabitReminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

/**
 * Created by ywwynm on 2015/9/8.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [BroadcastReceiver] for [Habit].
 */
open class HabitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hrId: Long = intent.getLongExtra(Def.Communication.KEY_ID, 0)
        val habitDAO: HabitDAO = HabitDAO.getInstance(context)!!
        val habitReminder: HabitReminder = habitDAO.getHabitReminderById(hrId) ?: return

        val habitId: Long = habitReminder.habitId
        var habit: Habit? = habitDAO.getHabitById(habitId)
        if (habit == null || habit.isPaused()) {
            // Don't keep alarms(updateHabitReminderToNext). Resume alarms when resume the Habit.
            return
        }

        // remove existed notification for same Habit
        SystemNotificationUtil.cancelNotification(habitId, Thing.HABIT, context)
        context.sendBroadcast(
                Intent(NoticeableNotificationActivity.BROADCAST_ACTION_JUST_FINISH)
                        .putExtra(Def.Communication.KEY_ID, habitId))

        var hrTime: Long = habitReminder.notifyTime
        val curDoingId: Long = App.getDoingThingId()
        if (curDoingId == habitId && hrTime > DoingService.sHrTime) {
            // if user is doing this Habit for the last time, now he/she cannot do it any longer
            // since this time is coming
            ThingDoingHelper.stopDoing(context, DoingRecord.STOP_REASON_CANCEL_NEXT_ALARM)
        }

        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, habitId, -1)
        val thing: Thing? = pair.first
        if (thing == null) {
            habitDAO.deleteHabit(habitId)
            return
        }

        if (curDoingId == habitId && hrTime > DoingService.sHrTime) {
            Toast.makeText(context, R.string.doing_failed_next_alarm,
                    Toast.LENGTH_LONG).show()
            // create this notification after 1600ms, otherwise it will be cancelled because of
            // stopping a service bound a foreground notification with same id
            Thread {
                try {
                    Thread.sleep(1600)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                val notification: Notification = SystemNotificationUtil.createDoingNotification(
                        App.getApp(), thing, DoingService.STATE_FAILED_NEXT_ALARM, null, -1, 2)
                NotificationManagerCompat.from(App.getApp()!!).notify(habitId.toInt(), notification)
            }.start()
        }

        val position: Int = pair.second!!

        if (curDoingId == habitId && hrTime <= DoingService.sHrTime) {
            Toast.makeText(context, R.string.start_doing_notification_toast_doing_this_habit,
                    Toast.LENGTH_LONG).show()
            updateHabitRecordTimes(context, hrId)
            RemoteActionHelper.updateUiEverywhere(
                    context, thing, position, thing.type,
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
            return
        }

        if (thing.state == Thing.UNDERWAY) {

            val helper = ThingDoingHelper(context, thing)
            val shouldAutoStartDoing: Boolean = helper.shouldAutoStartDoing()
            if (curDoingId == -1L && shouldAutoStartDoing) {
                updateHabitRecordTimesAndUi(context, hrId, thing, position)
                habit = habitDAO.getHabitById(habitId)
                if (habit == null) {
                    helper.startDoingAuto(-1, hrTime)
                } else {
                    helper.startDoingAuto(habit.getMinHabitReminderTime(), hrTime)
                }
                return
            }

            val runningDetailActivities: List<Long?> = App.getRunningDetailActivities()
            for (rThingId in runningDetailActivities) {
                if (rThingId == habitId) {
                    updateHabitRecordTimesAndUi(context, hrId, thing, position)
                    Toast.makeText(context, R.string.notification_toast_checking_when_alarm_comes,
                            Toast.LENGTH_LONG).show()
                    return
                }
            }

            val content: String = thing.content!!
            if (CheckListHelper.isCheckListStr(content)) {
                val sameCheckContent: String = content.replace(
                        (CheckListHelper.SIGNAL + "1").toRegex(), CheckListHelper.SIGNAL + "0")
                thing.content = sameCheckContent
                if (position != -1) {
                    ThingManager.getInstance(context)!!.update(Thing.HABIT, thing, position, false)
                } else {
                    ThingDAO.getInstance(context)!!.update(Thing.HABIT, thing, false, false)
                }
            }

            updateHabitRecordTimesAndUi(context, hrId, thing, position)

            // possible conditions when logic goes here(or):
            // curDoingId == habitId && hrTime > DoingService.sHrTime
            // curDoingId == -1 && !shouldAutoStartDoing
            // curDoingId == another thing's id

            if (shouldAutoStartDoing) {
                if (curDoingId == habitId) {
                    Toast.makeText(context, R.string.auto_start_doing_notification_toast_doing_habit_this,
                            Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.auto_start_doing_notification_toast_doing_another,
                            Toast.LENGTH_LONG).show()
                }
            }

            if (hrTime > System.currentTimeMillis()) {
                // this should be impossible
                hrTime = DateTimeUtil.getHabitReminderTime(habit.type, hrTime, -1)
            }
            notifyUser(context, habitId, hrId, hrTime, position, thing)
        }
    }

    private fun updateHabitRecordTimesAndUi(context: Context, hrId: Long, thing: Thing, position: Int) {
        updateHabitRecordTimes(context, hrId)
        RemoteActionHelper.updateUiEverywhere(
                context, thing, position, thing.type,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
    }

    private fun updateHabitRecordTimes(context: Context, hrId: Long) {
        val habitDAO: HabitDAO = HabitDAO.getInstance(context)!!
        val habitReminder: HabitReminder = habitDAO.getHabitReminderById(hrId) ?: return
        val habitId: Long = habitReminder.habitId
        val habit: Habit = habitDAO.getHabitById(habitId) ?: return
        habitDAO.updateHabitReminderToNext(hrId)
        var recordTimes: Int = habit.record!!.length
        val remindedTimes: Int = habit.remindedTimes
        if (recordTimes <= remindedTimes) {
            // user doesn't finish this time in advance.
            if (recordTimes < remindedTimes) {
                val sb: StringBuilder = StringBuilder(habit.record!!)
                while (recordTimes < remindedTimes) {
                    sb.append("0")
                    recordTimes++
                }
                habitDAO.updateRecordOfHabit(habitId, sb.toString())
            }
            habitDAO.updateHabitRemindedTimes(habitId, (remindedTimes + 1).toLong())
        } else {
            // recordTimes > remindedTimes means that user finish a habit in advance of notification.
            // Add 1: it is real a notification this time, user doesn't finish this time in advance.
            // At the same time, we can see previous finishes as finishes after notifications.
            habitDAO.updateHabitRemindedTimes(habitId, (recordTimes + 1).toLong())
        }
    }

    private fun notifyUser(
            context: Context, habitId: Long, hrId: Long, hrTime: Long, position: Int, thing: Thing) {
        val sp: SharedPreferences = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val moreNoticeable: Boolean = sp.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true)
        notifyUserBySystemNotification(context, habitId, hrId, hrTime, position,
                thing, moreNoticeable)
    }

    private fun notifyUserBySystemNotification(
            context: Context, habitId: Long, hrId: Long, hrTime: Long, position: Int,
            thing: Thing, moreNoticeable: Boolean) {
        val builder: NotificationCompat.Builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, habitId, position, thing, false)
        if (moreNoticeable) {
            // if we use a dialog to notify this alarm, we don't need to show heads-up notification
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        // Phase 8: full ThingBackground for gradient on the action dialog.
        SystemNotificationUtil.addActionsForHabitNotification(
                context, builder, hrId, position, hrTime,
                ThingPrivacyResolver.isEffectivelyPrivate(context, thing), habitId, thing.getBackground())

        if (moreNoticeable) {
            val fullScreenIntent: Intent = NoticeableNotificationActivity.getOpenIntentForHabit(
                    context, hrId, position, hrTime)
            fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            builder.setFullScreenIntent(PendingIntent.getActivity(
                    context, hrId.toInt(), fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
        }

        val nm: NotificationManagerCompat = NotificationManagerCompat.from(context)
        nm.notify(hrId.toInt(), builder.build())
    }

    companion object {
        const val TAG: String = "HabitReceiver"
    }
}
