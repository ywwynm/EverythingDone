package com.ywwynm.everythingdone.receivers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DeviceUtil;
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
        Pair<Thing, Integer> pair = App.getThingAndPosition(context, id, -1);
        Thing thing = pair.first;
        if (thing == null) {
            reminderDAO.delete(id);
            return;
        }
        int position = pair.second;

        @Thing.Type int typeBefore = thing.getType();

        Reminder reminder = reminderDAO.getReminderById(id);
        if (reminder == null) {
            RemoteActionHelper.correctIfNoReminder(context, thing, position, typeBefore);
            return;
        }

        if (reminder.getState() == Reminder.UNDERWAY) {

            ThingDoingHelper helper = new ThingDoingHelper(context, thing);
            if (thing.getState() == Thing.UNDERWAY
                    && App.getDoingThingId() != id
                    && helper.shouldAutoStartDoing()) {
                if (App.getDoingThingId() != -1) {

                }
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, Reminder.EXPIRED);
                helper.startDoingAuto(-1, -1);
                return;
            }

            List<Long> runningDetailActivities = App.getRunningDetailActivities();
            for (Long thingId : runningDetailActivities) {
                if (thingId == id) {
                    int reminderStateAfter = thing.getState() == Thing.UNDERWAY ?
                            Reminder.REMINDED : Reminder.EXPIRED;
                    updateReminderState(reminder, reminderDAO, context, thing, position,
                            typeBefore, reminderStateAfter);
                    return;
                }
            }

            if (App.getDoingThingId() == id) {
                // user start doing this thing
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, Reminder.EXPIRED);
                return;
            }

            if (thing.getState() == Thing.UNDERWAY) {
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, Reminder.REMINDED);
                notifyUser(context, id, position, thing);
            } else {
                updateReminderState(reminder, reminderDAO, context, thing, position,
                        typeBefore, Reminder.EXPIRED);
            }
        }
    }

    private void updateReminderState(
            Reminder reminder, ReminderDAO reminderDAO,
            Context context, Thing thing, int position,
            @Thing.Type int typeBefore, int reminderStateAfter) {
        reminder.setState(reminderStateAfter);
        reminderDAO.update(reminder);
        RemoteActionHelper.updateUiEverywhere(
                context, thing, position, typeBefore,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
    }

    private void notifyUser(Context context, long id, int position, Thing thing) {
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        boolean moreNoticeable = sp.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true);
        notifyUserBySystemNotification(context, id, position, thing, moreNoticeable);
        if (moreNoticeable) {
            Intent intent = NoticeableNotificationActivity.getOpenIntentForReminder(context, id, position);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            context.startActivity(intent);
        }
    }

    private void notifyUserBySystemNotification(
            Context context, long id, int position, Thing thing, boolean moreNoticeable) {
        NotificationCompat.Builder builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, id, position, thing, false);
        if (moreNoticeable && DeviceUtil.hasLollipopApi()) {
            // if we use a dialog to notify this alarm, we don't need to show heads-up notification
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        }

        Intent finishIntent = new Intent(context, ReminderNotificationActionReceiver.class);
        finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
        finishIntent.putExtra(Def.Communication.KEY_ID, id);
        finishIntent.putExtra(Def.Communication.KEY_POSITION, position);
        builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish),
                PendingIntent.getBroadcast(context,
                        (int) id, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        if (thing.getType() == Thing.REMINDER) {
            Intent startIntent = new Intent(context, ReminderNotificationActionReceiver.class);
            startIntent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
            startIntent.putExtra(Def.Communication.KEY_ID, id);
            startIntent.putExtra(Def.Communication.KEY_POSITION, position);
            builder.addAction(R.drawable.act_start_doing,
                    context.getString(R.string.act_start_doing),
                    PendingIntent.getBroadcast(context,
                            (int) id, startIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            Intent delayIntent = new Intent(context, ReminderNotificationActionReceiver.class);
            delayIntent.setAction(Def.Communication.NOTIFICATION_ACTION_DELAY);
            delayIntent.putExtra(Def.Communication.KEY_ID, id);
            delayIntent.putExtra(Def.Communication.KEY_POSITION, position);
            builder.addAction(R.drawable.act_delay,
                    context.getString(R.string.act_delay),
                    PendingIntent.getBroadcast(context,
                            (int) id, delayIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        }

        Intent deleteIntent = new Intent(context, ReminderNotificationActionReceiver.class);
        deleteIntent.setAction(Def.Communication.NOTIFICATION_ACTION_CANCEL);
        deleteIntent.putExtra(Def.Communication.KEY_ID, id);
        builder.setDeleteIntent(PendingIntent.getBroadcast(
                context, (int) id, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify((int) id, builder.build());
    }
}
