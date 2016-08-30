package com.ywwynm.everythingdone.receivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.AuthenticationActivity;

public class ReminderNotificationActionReceiver extends BroadcastReceiver {

    public static final String TAG = "ReminderNotificationActionReceiver";

    public ReminderNotificationActionReceiver() { }

    @SuppressLint("LongLogTag")
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long id = intent.getLongExtra(Def.Communication.KEY_ID, 0);
        int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);

        if (action.equals(Def.Communication.NOTIFICATION_ACTION_FINISH)
                || action.equals(Def.Communication.WIDGET_ACTION_FINISH)) {
            for (Long dId : App.getRunningDetailActivities()) if (dId == id) {
                return;
            }
            Intent actionIntent = AuthenticationActivity.getOpenIntent(
                    context, TAG, id, position,
                    Def.Communication.AUTHENTICATE_ACTION_FINISH,
                    context.getString(R.string.act_finish));
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(actionIntent);
        } else if (action.equals(Def.Communication.NOTIFICATION_ACTION_DELAY)) {
            for (Long dId : App.getRunningDetailActivities()) if (dId == id) {
                return;
            }
            Intent actionIntent = AuthenticationActivity.getOpenIntent(
                    context, TAG, id, position,
                    Def.Communication.AUTHENTICATE_ACTION_DELAY,
                    context.getString(R.string.act_delay_10_minutes));
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(actionIntent);
        }

        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        nmc.cancel((int) id);
    }
}
