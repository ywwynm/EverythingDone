package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

public class ReminderNotificationActionReceiver extends BroadcastReceiver {

    public ReminderNotificationActionReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long id = intent.getLongExtra(Def.Communication.KEY_ID, 0);
        int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);

        Thing thing;
        if (action.equals(Def.Communication.NOTIFICATION_ACTION_FINISH)) {
            if (position == -1) {
                ThingDAO thingDAO = ThingDAO.getInstance(context);
                thing = thingDAO.getThingById(id);
                thing = Thing.getSameCheckStateThing(thing, Thing.UNDERWAY, Thing.FINISHED);
                boolean handleNotifyEmpty = true;
                boolean handleCurrentLimit = true;
                boolean toUndo = false;
                boolean shouldUpdateHeader = true;
                thingDAO.updateState(thing, thing.getLocation(), Thing.UNDERWAY, Thing.FINISHED,
                        handleNotifyEmpty, handleCurrentLimit, toUndo, thingDAO.getHeaderId(),
                        shouldUpdateHeader);
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

            App.setSomethingUpdatedSpecially(true);
            sendBroadCastToUpdateMainUI(context, thing, position,
                    Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT);
        } else if (action.equals(Def.Communication.NOTIFICATION_ACTION_DELAY)) {
            if (position == -1) {
                ThingDAO thingDAO = ThingDAO.getInstance(context);
                thing = thingDAO.getThingById(id);
                thing.setUpdateTime(System.currentTimeMillis());
                thingDAO.update(thing.getType(), thing, false, false);
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
            reminder.setNotifyMillis(System.currentTimeMillis() - reminder.getNotifyTime()
                    + reminder.getNotifyMillis() + 10 * 60 * 1000);
            reminder.setState(Reminder.UNDERWAY);
            reminder.setUpdateTime(System.currentTimeMillis());
            dao.update(reminder);

            App.setSomethingUpdatedSpecially(true);
            sendBroadCastToUpdateMainUI(context, thing, position,
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
        }

        AppWidgetHelper.updateAppWidget(context, id);

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
