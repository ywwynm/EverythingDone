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
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;

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

    private void sendBroadCastToUpdateMainUI(Context context, Thing thing, int position, int resultCode) {
        Intent broadcastIntent = new Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode);
        broadcastIntent.putExtra(Def.Communication.KEY_THING, thing);
        broadcastIntent.putExtra(Def.Communication.KEY_POSITION, position);
        if (resultCode == Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT) {
            broadcastIntent.putExtra(Def.Communication.KEY_STATE_AFTER, Thing.FINISHED);
            if (position != -1) {
                ThingManager thingManager = ThingManager.getInstance(context);
                broadcastIntent.putExtra(Def.Communication.KEY_CALL_CHANGE,
                        thingManager.updateState(thing, position, thing.getLocation(), Thing.UNDERWAY,
                                Thing.FINISHED, false, true));
            }
        } else {
            broadcastIntent.putExtra(Def.Communication.KEY_TYPE_BEFORE, thing.getType());
        }
        context.sendBroadcast(broadcastIntent);
    }
}
