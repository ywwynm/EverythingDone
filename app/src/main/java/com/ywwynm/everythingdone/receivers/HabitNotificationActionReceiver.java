package com.ywwynm.everythingdone.receivers;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.bean.Habit;
import com.ywwynm.everythingdone.bean.HabitReminder;
import com.ywwynm.everythingdone.bean.Thing;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;

import java.util.List;

public class HabitNotificationActionReceiver extends BroadcastReceiver {

    public HabitNotificationActionReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long hrId = intent.getLongExtra(Definitions.Communication.KEY_ID, 0);

        if (action.equals(Definitions.Communication.NOTIFICATION_ACTION_FINISH)) {
            int position = intent.getIntExtra(Definitions.Communication.KEY_POSITION, -1);
            long time = intent.getLongExtra(Definitions.Communication.KEY_TIME, 0);
            HabitDAO habitDAO = HabitDAO.getInstance(context);
            HabitReminder habitReminder = habitDAO.getHabitReminderById(hrId);
            long id = habitReminder.getHabitId();
            Habit habit = habitDAO.getHabitById(id);
            if (habit.allowFinish(time)) {
                habitDAO.finishOneTime(habit);
                sendBroadCastToUpdateMainUI(context, id, position);
            }
        }
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int) hrId);
    }

    private void sendBroadCastToUpdateMainUI(Context context, long id, int position) {
        Thing thing;
        if (position == -1) {
            thing = ThingDAO.getInstance(context).getThingById(id);
        } else {
            ThingManager thingManager = ThingManager.getInstance(context);
            List<Thing> things = thingManager.getThings();
            final int size = things.size();
            thing = null;
            if (position > size - 1 || things.get(position).getId() != id) {
                for (int i = 0; i < size; i++) {
                    thing = things.get(i);
                    if (thing.getId() == id) {
                        position = i;
                        break;
                    }
                }
            } else {
                thing = things.get(position);
            }
        }

        EverythingDoneApplication.setSomethingUpdatedSpecially(true);
        Intent broadcastIntent = new Intent(
                Definitions.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Definitions.Communication.KEY_RESULT_CODE,
                Definitions.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
        broadcastIntent.putExtra(Definitions.Communication.KEY_THING, thing);
        broadcastIntent.putExtra(Definitions.Communication.KEY_POSITION, position);
        broadcastIntent.putExtra(Definitions.Communication.KEY_TYPE_BEFORE, thing.getType());
        context.sendBroadcast(broadcastIntent);
    }
}
