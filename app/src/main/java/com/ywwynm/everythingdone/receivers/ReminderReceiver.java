package com.ywwynm.everythingdone.receivers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.AppWidgetHelper;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.util.List;

/**
 * Created by ywwynm on 2015/9/8.
 * A subclass of {@link BroadcastReceiver} for {@link Reminder}.
 */

public class ReminderReceiver extends BroadcastReceiver {

    public static final String TAG = "ReminderReceiver";

    public ReminderReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(Def.Communication.KEY_ID, 0);
        ReminderDAO reminderDAO = ReminderDAO.getInstance(context);
        Reminder reminder = reminderDAO.getReminderById(id);

        if (reminder.getState() == Reminder.UNDERWAY) {
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

            if (thing == null) {
                reminderDAO.delete(id);
                return;
            }

            List<Long> runningDetailActivities = App.getRunningDetailActivities();
            for (Long thingId : runningDetailActivities) {
                if (thingId == id) {
                    reminder.setState(thing.getState() == Thing.UNDERWAY ?
                            Reminder.REMINDED : Reminder.EXPIRED);
                    reminderDAO.update(reminder);
                    sendBroadCastToUpdateMainUI(context, thing, position);
                    AppWidgetHelper.updateAppWidget(context, id);
                    return;
                }
            }

            if (thing.getState() == Thing.UNDERWAY) {
                reminder.setState(Reminder.REMINDED);
                reminderDAO.update(reminder);
                sendBroadCastToUpdateMainUI(context, thing, position);
                AppWidgetHelper.updateAppWidget(context, id);

                NotificationCompat.Builder builder = SystemNotificationUtil
                        .newGeneralNotificationBuilder(context, TAG, id, position, thing, false);

                Intent finishIntent = new Intent(context, ReminderNotificationActionReceiver.class);
                finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
                finishIntent.putExtra(Def.Communication.KEY_ID, id);
                finishIntent.putExtra(Def.Communication.KEY_POSITION, position);
                builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                        PendingIntent.getBroadcast(context,
                                (int) id, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                if (thing.getType() == Thing.REMINDER) {
                    Intent delayIntent = new Intent(context, ReminderNotificationActionReceiver.class);
                    delayIntent.setAction(Def.Communication.NOTIFICATION_ACTION_DELAY);
                    delayIntent.putExtra(Def.Communication.KEY_ID, id);
                    delayIntent.putExtra(Def.Communication.KEY_POSITION, position);
                    builder.addAction(R.drawable.act_delay_10_minutes,
                            context.getString(R.string.act_delay_10_minutes),
                            PendingIntent.getBroadcast(context,
                                    (int) id, delayIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                }

                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                notificationManager.notify((int) id, builder.build());
            } else {
                reminder.setState(Reminder.EXPIRED);
                reminderDAO.update(reminder);
            }
        }
    }

    private void sendBroadCastToUpdateMainUI(Context context, Thing thing, int position) {
        App.setSomethingUpdatedSpecially(true);
        Intent broadcastIntent = new Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
        broadcastIntent.putExtra(Def.Communication.KEY_THING, thing);
        broadcastIntent.putExtra(Def.Communication.KEY_POSITION, position);
        broadcastIntent.putExtra(Def.Communication.KEY_TYPE_BEFORE, thing.getType());
        context.sendBroadcast(broadcastIntent);
    }
}
