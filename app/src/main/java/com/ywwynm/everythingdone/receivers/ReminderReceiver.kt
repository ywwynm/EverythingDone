package com.ywwynm.everythingdone.receivers

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
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

/**
 * Created by ywwynm on 2015/9/8.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [BroadcastReceiver] for [Reminder].
 */
open class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id: Long = intent.getLongExtra(Def.Communication.KEY_ID, 0)
        val reminderDAO: ReminderDAO = ReminderDAO.getInstance(context)!!
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, id, -1)
        val thing: Thing? = pair.first
        if (thing == null) {
            reminderDAO.delete(id)
            return
        }
        val position: Int = pair.second!!

        @Thing.Type val typeBefore: Int = thing.type

        val reminder: Reminder? = reminderDAO.getReminderById(id)
        if (reminder == null) {
            RemoteActionHelper.correctIfNoReminder(context, thing, position, typeBefore)
            return
        }

        if (reminder.state == Reminder.UNDERWAY) {

            val helper = ThingDoingHelper(context, thing)
            val shouldAutoStartDoing: Boolean = helper.shouldAutoStartDoing()
            if (thing.state == Thing.UNDERWAY
                    && App.getDoingThingId() == -1L && shouldAutoStartDoing) {
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, Reminder.EXPIRED)
                helper.startDoingAuto(-1, -1)
                return
            }

            val runningDetailActivities: List<Long?> = App.getRunningDetailActivities()
            for (thingId in runningDetailActivities) {
                if (thingId == id) {
                    val reminderStateAfter: Int = if (thing.state == Thing.UNDERWAY)
                            Reminder.REMINDED else Reminder.EXPIRED
                    updateReminderState(reminder, reminderDAO, context, thing, position,
                            typeBefore, reminderStateAfter)
                    Toast.makeText(context, R.string.notification_toast_checking_when_alarm_comes,
                            Toast.LENGTH_LONG).show()
                    return
                }
            }

            if (thing.state == Thing.UNDERWAY) {
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, if (App.getDoingThingId() == id) Reminder.EXPIRED else Reminder.REMINDED)
                if (App.getDoingThingId() != id) { // doing another thing
                    if (shouldAutoStartDoing) {
                        Toast.makeText(context, R.string.auto_start_doing_notification_toast_doing_another,
                                Toast.LENGTH_LONG).show()
                    }
                    notifyUser(context, id, position, thing)
                } else { // doing this thing
                    Toast.makeText(context, R.string.start_doing_notification_toast_doing_this,
                            Toast.LENGTH_LONG).show()
                }
            } else {
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, Reminder.EXPIRED)
            }
        }
    }

    private fun updateReminderState(
            reminder: Reminder, reminderDAO: ReminderDAO,
            context: Context, thing: Thing, position: Int,
            @Thing.Type typeBefore: Int, reminderStateAfter: Int) {
        reminder.state = reminderStateAfter
        reminderDAO.update(reminder)
        RemoteActionHelper.updateUiEverywhere(
                context, thing, position, typeBefore,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
    }

    private fun notifyUser(context: Context, id: Long, position: Int, thing: Thing) {
        val sp: SharedPreferences = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val moreNoticeable: Boolean = sp.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true)
        notifyUserBySystemNotification(context, id, position, thing, moreNoticeable)
    }

    private fun notifyUserBySystemNotification(
            context: Context, id: Long, position: Int, thing: Thing, moreNoticeable: Boolean) {
        val builder: NotificationCompat.Builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, id, position, thing, false)
        if (moreNoticeable) {
            // if we use a dialog to notify this alarm, we don't need to show heads-up notification
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        // Phase 8: pass full ThingBackground so the action's PendingIntent
        // carries the gradient across IPC to StartDoingActivity / DelayReminderActivity.
        SystemNotificationUtil.addActionsForReminderNotification(
                builder, context, id, position, thing.type, thing.isPrivate(),
                thing.getBackground())

        if (moreNoticeable) {
            val fullScreenIntent: Intent = NoticeableNotificationActivity.getOpenIntentForReminder(
                    context, id, position)
            fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            builder.setFullScreenIntent(PendingIntent.getActivity(
                    context, id.toInt(), fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
        }

        val deleteIntent = Intent(context, ReminderNotificationActionReceiver::class.java)
        deleteIntent.setAction(Def.Communication.NOTIFICATION_ACTION_CANCEL)
        deleteIntent.putExtra(Def.Communication.KEY_ID, id)
        builder.setDeleteIntent(PendingIntent.getBroadcast(
                context, id.toInt(), deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

        val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context)
        notificationManager.notify(id.toInt(), builder.build())
    }

    companion object {
        const val TAG: String = "ReminderReceiver"
    }
}
