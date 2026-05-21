package com.ywwynm.everythingdone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.util.Pair

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService

/**
 * Created by qiizhang on 2016/11/3.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * receiver for doing notification actions
 */
open class DoingNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action: String = intent.action!!
        if (ACTION_FINISH != action
            && ACTION_USER_CANCEL != action
            && ACTION_STOP_SERVICE != action
        ) {
            return
        }

        if (ACTION_FINISH == action) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
            val thingId: Long = intent.getLongExtra(Def.Communication.KEY_ID, -1L)
            val pair: Pair<Thing, Int> = App.getThingAndPosition(context, thingId, -1)!!
            val thing: Thing? = pair.first
            if (thing != null) {
                @Thing.Type val thingType: Int = thing.type
                if (thingType == Thing.HABIT) {
                    val hrTime: Long = intent.getLongExtra(Def.Communication.KEY_TIME, -1)
                    if (!RemoteActionHelper.finishHabitOnce(context, thing, pair.second!!, hrTime)) {
                        DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER
                    }
                } else {
                    RemoteActionHelper.finishReminder(context, thing, pair.second!!)
                }
            }
        } else if (ACTION_USER_CANCEL == action) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER
        }

        context.sendBroadcast(Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH))
        context.stopService(Intent(context, DoingService::class.java))
    }

    companion object {
        const val TAG: String = "DoingNotificationActionReceiver"

        const val ACTION_FINISH: String       = "DoingNotificationActionReceiver.finish"
        const val ACTION_USER_CANCEL: String  = "DoingNotificationActionReceiver.user_cancel"
        const val ACTION_STOP_SERVICE: String = "DoingNotificationActionReceiver.stop_service"
    }
}
