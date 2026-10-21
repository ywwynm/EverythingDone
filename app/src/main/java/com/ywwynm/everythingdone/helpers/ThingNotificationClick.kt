package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AuthenticationActivity
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity

/** 仅通知正文采用用户的打开方式；操作按钮、小组件和快捷方式保留原行为。 */
object ThingNotificationClick {
    private const val ACTION_OPEN = "com.ywwynm.everythingdone.action.OPEN_THING_NOTIFICATION"
    private const val KEY_HABIT_REMINDER_ID = "notification_habit_reminder_id"
    const val KEY_CANCEL_ON_DISMISS = "notification_cancel_on_dismiss"

    fun createIntent(
        context: Context, senderName: String?, thingId: Long, position: Int,
        habitReminderId: Long = -1L, habitReminderTime: Long = -1L, ongoing: Boolean = false
    ): Intent = AuthenticationActivity.getOpenIntent(
        context, senderName, thingId, position, ACTION_OPEN, context.getString(R.string.check_private_thing)
    ).apply {
        // extras 不参与 PendingIntent 身份比较；区分来源及习惯提醒，避免相互覆盖。
        action = ACTION_OPEN
        data = Uri.Builder().scheme("everythingdone").authority("notification")
            .appendPath(senderName.orEmpty())
            .appendPath((if (habitReminderId >= 0) habitReminderId else thingId).toString()).build()
        putExtra(KEY_HABIT_REMINDER_ID, habitReminderId)
        putExtra(Def.Communication.KEY_TIME, habitReminderTime)
        putExtra(KEY_CANCEL_ON_DISMISS, !ongoing)
    }

    fun noticeableIntent(context: Context, source: Intent): Intent? {
        if (source.action != ACTION_OPEN || !context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE
            ).getBoolean(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE, false)) return null

        val position = source.getIntExtra(Def.Communication.KEY_POSITION, -1)
        val habitReminderId = source.getLongExtra(KEY_HABIT_REMINDER_ID, -1L)
        return (if (habitReminderId >= 0) {
            NoticeableNotificationActivity.getOpenIntentForHabit(
                context, habitReminderId, position, source.getLongExtra(Def.Communication.KEY_TIME, -1L)
            )
        } else {
            NoticeableNotificationActivity.getOpenIntentForReminder(
                context, source.getLongExtra(Def.Communication.KEY_ID, -1L), position
            )
        }).putExtra(KEY_CANCEL_ON_DISMISS, source.getBooleanExtra(KEY_CANCEL_ON_DISMISS, true))
    }
}
