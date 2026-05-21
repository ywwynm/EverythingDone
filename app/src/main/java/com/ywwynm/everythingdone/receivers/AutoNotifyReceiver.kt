@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.receivers

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Pair

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class AutoNotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id: Long = intent.getLongExtra(Def.Communication.KEY_ID, 0)

        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, id, -1)
        val thing: Thing? = pair.first
        if (thing == null || thing.state != Thing.UNDERWAY) {
            return
        }
        for (dId in App.getRunningDetailActivities()) if (dId == id) {
            return
        }

        val builder: NotificationCompat.Builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, id, pair.second!!, thing, true)
        var title: String = thing.getTitleToDisplay()!!
        if (title.isEmpty()) {
            title = Thing.getTypeStr(thing.type, context)!!
        }
        builder.setContentTitle(context.getString(R.string.auto_notify) + "-" + title)
        builder.setPriority(Notification.PRIORITY_DEFAULT)
        val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context)
        notificationManager.notify(id.toInt(), builder.build())
    }

    companion object {
        const val TAG: String = "AutoNotifyReceiver"
    }
}
