package com.ywwynm.everythingdone.receivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.AuthenticationActivity;
import com.ywwynm.everythingdone.activities.DelayReminderActivity;
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity;
import com.ywwynm.everythingdone.activities.StartDoingActivity;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;

public class ReminderNotificationActionReceiver extends BroadcastReceiver {

    public static final String TAG = "ReminderNotificationActionReceiver";

    private static final String[] LEGAL_ACTIONS = {
            Def.Communication.NOTIFICATION_ACTION_FINISH,
            Def.Communication.WIDGET_ACTION_FINISH,
            Def.Communication.NOTIFICATION_ACTION_DELAY,
            Def.Communication.NOTIFICATION_ACTION_START_DOING,
            Def.Communication.NOTIFICATION_ACTION_CANCEL
    };

    public ReminderNotificationActionReceiver() { }

    @SuppressLint("LongLogTag")
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long thingId = intent.getLongExtra(Def.Communication.KEY_ID, 0);
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        nmc.cancel((int) thingId);
        context.sendBroadcast(
                new Intent(NoticeableNotificationActivity.BROADCAST_ACTION_JUST_FINISH)
                        .putExtra(Def.Communication.KEY_ID, thingId));

        boolean matched = false;
        for (String legalAction : LEGAL_ACTIONS) {
            if (legalAction.equals(action)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return;
        }

        for (Long dId : App.getRunningDetailActivities()) if (dId == thingId) {
            Toast.makeText(context, R.string.notification_toast_checking_action,
                    Toast.LENGTH_LONG).show();
            return;
        }

        int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
        Pair<Thing, Integer> pair = App.getThingAndPosition(context, thingId, position);
        Thing thing = pair.first;
        if (thing == null) {
            ReminderDAO.getInstance(context).delete(thingId);
            return;
        }
        position = pair.second;

        switch (action) {
            case Def.Communication.NOTIFICATION_ACTION_FINISH:
            case Def.Communication.WIDGET_ACTION_FINISH:
                if (thing.isPrivate()) {
                    Intent actionIntent = AuthenticationActivity.getOpenIntent(
                            context, TAG, thingId, position,
                            Def.Communication.AUTHENTICATE_ACTION_FINISH,
                            context.getString(R.string.act_finish));
                    actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    context.startActivity(actionIntent);
                } else {
                    RemoteActionHelper.finishReminder(context, thing, position);
                }
                break;
            case Def.Communication.NOTIFICATION_ACTION_DELAY: {
                Intent actionIntent;
                if (thing.isPrivate()) {
                    actionIntent = AuthenticationActivity.getOpenIntent(
                            context, TAG, thingId, position,
                            Def.Communication.AUTHENTICATE_ACTION_DELAY,
                            context.getString(R.string.act_delay));
                } else {
                    actionIntent = DelayReminderActivity.getOpenIntent(
                            context, thing.getId(), position, thing.getColor());
                }
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                context.startActivity(actionIntent);
                break;
            }
            case Def.Communication.NOTIFICATION_ACTION_START_DOING: {
                if (thingId == App.getDoingThingId()) {
                    Toast.makeText(context, R.string.start_doing_doing_this_thing,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                Intent actionIntent;
                if (thing.isPrivate()) {
                    actionIntent = AuthenticationActivity.getOpenIntent(
                            context, TAG, thingId, position,
                            Def.Communication.AUTHENTICATE_ACTION_START_DOING,
                            context.getString(R.string.start_doing_full_title));
                } else {
                    actionIntent = StartDoingActivity.getOpenIntent(
                            context, thing.getId(), position, thing.getColor(),
                            DoingService.START_TYPE_ALARM, -1);
                }
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                context.startActivity(actionIntent);
                break;
            }
        }
    }
}
