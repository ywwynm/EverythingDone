package com.ywwynm.everythingdone.receivers;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.util.List;

public class AutoNotifyReceiver extends BroadcastReceiver {

    public static final String TAG = "AutoNotifyReceiver";

    public AutoNotifyReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(Def.Communication.KEY_ID, 0);

        ThingManager thingManager = ThingManager.getInstance(context);
        List<Thing> things = thingManager.getThings();
        Thing thing = null;
        int size = things.size();
        int position = -1;
        for (int i = 0; i < size; i++) {
            thing = things.get(i);
            if (thing.getId() == id) {
                position = i;
                break;
            }
        }

        if (position == -1) { // not same type or limit.
            thing = ThingDAO.getInstance(context).getThingById(id);
        }

        if (thing == null || thing.getState() != Thing.UNDERWAY) {
            return;
        }

        for (Long dId : App.getRunningDetailActivities()) {
            if (dId == id) return;
        }

        NotificationCompat.Builder builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, id, position, thing, true);
        builder.setContentTitle(context.getString(R.string.auto_notify) + "-" + builder.mContentTitle);
        builder.setPriority(Notification.PRIORITY_DEFAULT);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify((int) id, builder.build());
    }
}
