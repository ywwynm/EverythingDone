package com.ywwynm.everythingdone.receivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;

/**
 * Created by ywwynm on 2016/8/25.
 * habit widget action BroadcastReceiver
 */
@SuppressLint("LongLogTag")
public class HabitWidgetActionReceiver extends BroadcastReceiver {

    public static final String TAG = "HabitWidgetActionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Def.Communication.WIDGET_ACTION_FINISH.equals(intent.getAction())) {
            long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
            int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);

            for (Long dId : App.getRunningDetailActivities()) if (dId == id) {
                return;
            }

            Pair<Thing, Integer> pair = App.getThingAndPosition(context, id, position);
            Thing thing = pair.first;
            if (thing == null) {
                return;
            }
            position = pair.second;

            Habit habit = HabitDAO.getInstance(context).getHabitById(id);
            if (habit == null) {
                RemoteActionHelper.correctIfNoHabit(context, thing, position, thing.getType());
                return;
            }

            NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
            for (HabitReminder habitReminder : habit.getHabitReminders()) {
                nmc.cancel((int) habitReminder.getId());
            }

            RemoteActionHelper.finishHabitOnce(context, thing, position, -1L);
        }
    }
}
