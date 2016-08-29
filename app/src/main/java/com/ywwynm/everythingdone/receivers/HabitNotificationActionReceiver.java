package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.AuthenticationActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.model.HabitReminder;

public class HabitNotificationActionReceiver extends BroadcastReceiver {

    public static final String TAG = "HabitNotificationActionReceiver";

    public HabitNotificationActionReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long hrId = intent.getLongExtra(Def.Communication.KEY_ID, 0);

        if (action.equals(Def.Communication.NOTIFICATION_ACTION_FINISH)) {
            int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
            long time = intent.getLongExtra(Def.Communication.KEY_TIME, 0);
            HabitDAO habitDAO = HabitDAO.getInstance(context);
            HabitReminder habitReminder = habitDAO.getHabitReminderById(hrId);
            long id = habitReminder.getHabitId();
            for (Long dId : App.getRunningDetailActivities()) if (dId == id) {
                return;
            }
            Intent actionIntent = AuthenticationActivity.getOpenIntent(
                    context, TAG, id, position,
                    Def.Communication.AUTHENTICATE_ACTION_FINISH,
                    context.getString(R.string.act_finish));
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            actionIntent.putExtra(Def.Communication.KEY_TIME, time);
            context.startActivity(actionIntent);
        }
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        nmc.cancel((int) hrId);
    }
}
