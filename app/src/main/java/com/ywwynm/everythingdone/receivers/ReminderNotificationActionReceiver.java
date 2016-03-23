package com.ywwynm.everythingdone.receivers;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.bean.Reminder;
import com.ywwynm.everythingdone.bean.Thing;
import com.ywwynm.everythingdone.bean.ThingsCounts;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;

import java.util.List;

public class ReminderNotificationActionReceiver extends BroadcastReceiver {

    public ReminderNotificationActionReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long id = intent.getLongExtra(Definitions.Communication.KEY_ID, 0);
        int position = intent.getIntExtra(Definitions.Communication.KEY_POSITION, -1);

        Thing thing;
        if (action.equals(Definitions.Communication.NOTIFICATION_ACTION_FINISH)) {
            if (position == -1) {
                ThingDAO thingDAO = ThingDAO.getInstance(context);
                thing = thingDAO.getThingById(id);
                thing = Thing.getSameCheckStateThing(thing, Thing.UNDERWAY, Thing.FINISHED);
                thingDAO.updateState(thing, thing.getLocation(), Thing.UNDERWAY, Thing.FINISHED,
                        true, true, false, thingDAO.getHeaderId(), true);
                ThingsCounts.getInstance(context).writeToFile();
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
            sendBroadCastToUpdateMainUI(context, thing, position,
                    Definitions.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT);
        } else if (action.equals(Definitions.Communication.NOTIFICATION_ACTION_DELAY)) {
            if (position == -1) {
                ThingDAO thingDAO = ThingDAO.getInstance(context);
                thing = thingDAO.getThingById(id);
                thing.setUpdateTime(System.currentTimeMillis());
                thingDAO.update(thing.getType(), thing, false, false);
                ThingsCounts.getInstance(context).writeToFile();
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
                thing.setUpdateTime(System.currentTimeMillis());
                thingManager.update(thing.getType(), thing, position, false);
            }
            ReminderDAO dao = ReminderDAO.getInstance(context);
            Reminder reminder = dao.getReminderById(id);
            reminder.setNotifyTime(System.currentTimeMillis() + 10 * 60 * 1000);
            reminder.setState(Reminder.UNDERWAY);
            dao.update(reminder);

            EverythingDoneApplication.setSomethingUpdatedSpecially(true);
            sendBroadCastToUpdateMainUI(context, thing, position,
                    Definitions.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
        }

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int) id);
    }

    private void sendBroadCastToUpdateMainUI(Context context, Thing thing, int position, int resultCode) {
        Intent broadcastIntent = new Intent(
                Definitions.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Definitions.Communication.KEY_RESULT_CODE, resultCode);
        broadcastIntent.putExtra(Definitions.Communication.KEY_THING, thing);
        broadcastIntent.putExtra(Definitions.Communication.KEY_POSITION, position);
        if (resultCode == Definitions.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT) {
            broadcastIntent.putExtra(Definitions.Communication.KEY_STATE_AFTER, Thing.FINISHED);
            if (position != -1) {
                ThingManager thingManager = ThingManager.getInstance(context);
                broadcastIntent.putExtra(Definitions.Communication.KEY_CALL_CHANGE,
                        thingManager.updateState(thing, position, thing.getLocation(), Thing.UNDERWAY,
                                Thing.FINISHED, false, true));
            }
        } else {
            broadcastIntent.putExtra(Definitions.Communication.KEY_TYPE_BEFORE, thing.getType());
        }
        context.sendBroadcast(broadcastIntent);
    }
}
