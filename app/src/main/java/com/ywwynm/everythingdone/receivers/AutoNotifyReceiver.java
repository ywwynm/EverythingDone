package com.ywwynm.everythingdone.receivers;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

public class AutoNotifyReceiver extends BroadcastReceiver {

    public static final String TAG = "AutoNotifyReceiver";

    public AutoNotifyReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(Def.Communication.KEY_ID, 0);

        Pair<Thing, Integer> pair = App.getThingAndPosition(context, id, -1);
        Thing thing = pair.first;
        if (thing == null || thing.getState() != Thing.UNDERWAY) {
            return;
        }
        for (Long dId : App.getRunningDetailActivities()) if (dId == id) {
            return;
        }

        NotificationCompat.Builder builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, id, pair.second, thing, true);
        builder.setContentTitle(context.getString(R.string.auto_notify) + "-" + builder.mContentTitle);
        builder.setPriority(Notification.PRIORITY_DEFAULT);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify((int) id, builder.build());
    }
}
