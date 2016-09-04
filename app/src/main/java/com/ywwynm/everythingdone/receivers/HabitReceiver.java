package com.ywwynm.everythingdone.receivers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.util.List;

/**
 * Created by ywwynm on 2015/9/8.
 * A subclass of {@link BroadcastReceiver} for {@link Habit}.
 */
public class HabitReceiver extends BroadcastReceiver {

    public static final String TAG = "HabitReceiver";

    public HabitReceiver() {}

    @Override
    public void onReceive(Context context, Intent intent) {
        long hrId = intent.getLongExtra(Def.Communication.KEY_ID, 0);
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        HabitReminder habitReminder = habitDAO.getHabitReminderById(hrId);
        if (habitReminder == null) {
            return;
        }

        long habitId = habitReminder.getHabitId();
        SystemNotificationUtil.cancelNotification(habitId, Thing.HABIT, context);

        Pair<Thing, Integer> pair = App.getThingAndPosition(context, habitId, -1);
        Thing thing = pair.first;
        if (thing == null) {
            habitDAO.deleteHabit(habitId);
            return;
        }
        int position = pair.second;

        if (thing.getState() == Thing.UNDERWAY) {
            List<Long> runningDetailActivities = App.getRunningDetailActivities();
            for (Long rThingId : runningDetailActivities) {
                if (rThingId == habitId) {
                    updateHabitRecordTimes(context, hrId);
                    RemoteActionHelper.updateUiEverywhere(
                            context, thing, position, thing.getType(),
                            Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
                    return;
                }
            }

            String content = thing.getContent();
            if (CheckListHelper.isCheckListStr(content)) {
                String sameCheckContent = content.replaceAll(
                        CheckListHelper.SIGNAL + "1", CheckListHelper.SIGNAL + "0");
                thing.setContent(sameCheckContent);
                if (position != -1) {
                    ThingManager.getInstance(context).update(Thing.HABIT, thing, position, false);
                } else {
                    ThingDAO.getInstance(context).update(Thing.HABIT, thing, false, false);
                }
            }

            updateHabitRecordTimes(context, hrId);
            RemoteActionHelper.updateUiEverywhere(
                    context, thing, position, thing.getType(),
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);

            NotificationCompat.Builder builder = SystemNotificationUtil
                    .newGeneralNotificationBuilder(context, TAG, habitId, position, thing, false);

            Intent finishIntent = new Intent(context, HabitNotificationActionReceiver.class);
            finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
            finishIntent.putExtra(Def.Communication.KEY_ID, hrId);
            finishIntent.putExtra(Def.Communication.KEY_POSITION, position);
            finishIntent.putExtra(Def.Communication.KEY_TIME, habitReminder.getNotifyTime());

            Intent getItIntent = new Intent(context, HabitNotificationActionReceiver.class);
            getItIntent.putExtra(Def.Communication.KEY_ID, hrId);
            getItIntent.setAction(Def.Communication.NOTIFICATION_ACTION_GET_IT);

            builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish_this_time_habit),
                            PendingIntent.getBroadcast(context,
                                    (int) hrId, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                   .addAction(R.drawable.act_get_it, context.getString(R.string.act_get_it),
                            PendingIntent.getBroadcast(context,
                                    (int) hrId, getItIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            notificationManager.notify((int) hrId, builder.build());
        }
    }

    private void updateHabitRecordTimes(Context context, long hrId) {
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        HabitReminder habitReminder = habitDAO.getHabitReminderById(hrId);
        if (habitReminder == null) return;
        long habitId = habitReminder.getHabitId();
        Habit habit = habitDAO.getHabitById(habitId);
        if (habit == null) return;
        habitDAO.updateHabitReminderToNext(hrId);
        int recordTimes = habit.getRecord().length();
        int remindedTimes = habit.getRemindedTimes();
        if (recordTimes <= remindedTimes) {
            // user doesn't finish this time in advance.
            if (recordTimes < remindedTimes) {
                StringBuilder sb = new StringBuilder(habit.getRecord());
                while (recordTimes < remindedTimes) {
                    sb.append("0");
                    recordTimes++;
                }
                habitDAO.updateRecordOfHabit(habitId, sb.toString());
            }
            habitDAO.updateHabitRemindedTimes(habitId, remindedTimes + 1);
        } else {
            // recordTimes > remindedTimes means that user finish a habit in advance of notification.
            // Add 1: it is real a notification this time, user doesn't finish this time in advance.
            // At the same time, we can see previous finishes as finishes after notifications.
            habitDAO.updateHabitRemindedTimes(habitId, recordTimes + 1);
        }
    }
}
