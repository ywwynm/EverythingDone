package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Pair
import android.widget.Toast

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AuthenticationActivity
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity
import com.ywwynm.everythingdone.activities.StartDoingActivity
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.model.HabitReminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class HabitNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.action
        // 来自全屏通知、其内部已对私密记事鉴权过的动作：跳过本 receiver 的有效私密二次鉴权（问题5）。
        val alreadyAuthenticated = intent.getBooleanExtra(
            Def.Communication.KEY_ALREADY_AUTHENTICATED, false)
        val hrId: Long = intent.getLongExtra(Def.Communication.KEY_ID, -1)
        val thingId: Long
        if (hrId == -1L) { // ongoing Habit
            thingId = context.getSharedPreferences(
                    Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE).getLong(
                    Def.Meta.KEY_ONGOING_THING_ID, -1L)
        } else {
            val habitDAO: HabitDAO = HabitDAO.getInstance(context)!!
            val hr: HabitReminder = habitDAO.getHabitReminderById(hrId) ?: return
            thingId = hr.habitId

            val nmc: NotificationManagerCompat = NotificationManagerCompat.from(context)
            nmc.cancel(hrId.toInt())
        }

        // NOTIFICATION_ACTION_CANCEL is used as the delete intent; just cancel notification
        if (Def.Communication.NOTIFICATION_ACTION_CANCEL == action) {
            return
        }

        // Only NOTIFICATION_ACTION_FINISH and NOTIFICATION_ACTION_START_DOING are handled here
        if (Def.Communication.NOTIFICATION_ACTION_FINISH != action
            && Def.Communication.NOTIFICATION_ACTION_START_DOING != action
        ) {
            return
        }

        for (dId in App.getRunningDetailActivities()) if (dId == thingId) {
            Toast.makeText(context, R.string.notification_toast_checking_action,
                    Toast.LENGTH_LONG).show()
            return
        }

        var position: Int = intent.getIntExtra(Def.Communication.KEY_POSITION, -1)
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, thingId, position)
        val thing: Thing = pair.first ?: return
        position = pair.second!!

        context.sendBroadcast(
                Intent(NoticeableNotificationActivity.BROADCAST_ACTION_JUST_FINISH)
                        .putExtra(Def.Communication.KEY_ID, thing.id))

        val hrTime: Long = intent.getLongExtra(Def.Communication.KEY_TIME, -1)
        if (Def.Communication.NOTIFICATION_ACTION_FINISH == action) {
            if (!alreadyAuthenticated && ThingPrivacyResolver.isEffectivelyPrivate(context, thing)) {
                val actionIntent: Intent = AuthenticationActivity.getOpenIntent(
                        context, TAG, thingId, position,
                        Def.Communication.AUTHENTICATE_ACTION_FINISH,
                        context.getString(R.string.act_finish))!!
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                actionIntent.putExtra(Def.Communication.KEY_TIME, hrTime)
                context.startActivity(actionIntent)
            } else {
                RemoteActionHelper.finishHabitOnce(context, thing, position, hrTime)
            }
        } else if (Def.Communication.NOTIFICATION_ACTION_START_DOING == action) {
            if (thingId == App.getDoingThingId()) {
                Toast.makeText(context, R.string.start_doing_doing_this_thing,
                        Toast.LENGTH_LONG).show()
                return
            }

            val actionIntent: Intent
            if (!alreadyAuthenticated && ThingPrivacyResolver.isEffectivelyPrivate(context, thing)) {
                actionIntent = AuthenticationActivity.getOpenIntent(
                        context, TAG, thingId, position,
                        Def.Communication.AUTHENTICATE_ACTION_START_DOING,
                        context.getString(R.string.start_doing_full_title))
                actionIntent.putExtra(Def.Communication.KEY_TIME, hrTime)
            } else {
                // Phase 8: pass the full ThingBackground so a GRADIENT habit's
                // start-doing chooser renders gradient.
                actionIntent = StartDoingActivity.getOpenIntent(
                        context, thing.id, position, thing.getBackground(),
                        DoingService.START_TYPE_ALARM, hrTime)
            }
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(actionIntent)
        }
    }

    companion object {
        const val TAG: String = "HabitNotificationActionReceiver"
    }
}
