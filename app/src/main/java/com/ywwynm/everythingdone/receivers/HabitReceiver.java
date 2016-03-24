package com.ywwynm.everythingdone.receivers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingsCounts;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
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
        long hrId = intent.getLongExtra(Definitions.Communication.KEY_ID, 0);
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        HabitReminder habitReminder = habitDAO.getHabitReminderById(hrId);
        long habitId = habitReminder.getHabitId();
        SystemNotificationUtil.cancelNotification(habitId, Thing.HABIT, context);

        ThingManager thingManager = ThingManager.getInstance(context);
        List<Thing> things = thingManager.getThings();
        Thing thing = null;
        int size = things.size();
        int position = -1;
        for (int i = 0; i < size; i++) {
            thing = things.get(i);
            if (thing.getId() == habitId) {
                position = i;
                break;
            }
        }

        if (position == -1) { // not same type or limit.
            thing = ThingDAO.getInstance(context).getThingById(habitId);
        }

        if (thing == null) {
            habitDAO.deleteHabit(habitId);
            return;
        }

        if (thing.getState() == Thing.UNDERWAY) {
            List<Long> runningDetailActivities = EverythingDoneApplication.getRunningDetailActivities();
            for (Long rThingId : runningDetailActivities) {
                if (rThingId == habitId) {
                    updateHabitRecordTimes(context, hrId);
                    sendBroadCastToUpdateMainUI(context, thing, position);
                    return;
                }
            }

            String content = thing.getContent();
            if (CheckListHelper.isCheckListStr(content)) {
                String sameCheckContent = content.replaceAll(
                        CheckListHelper.SIGNAL + "1", CheckListHelper.SIGNAL + "0");
                thing.setContent(sameCheckContent);
                if (position != -1) {
                    thingManager.update(Thing.HABIT, thing, position, false);
                } else {
                    ThingDAO.getInstance(context).update(Thing.HABIT, thing, false, false);
                }
            }

            updateHabitRecordTimes(context, hrId);
            sendBroadCastToUpdateMainUI(context, thing, position);

            NotificationCompat.Builder builder = SystemNotificationUtil
                    .newGeneralNotificationBuilder(context, TAG, habitId, position, thing, false);

            Intent finishIntent = new Intent(context, HabitNotificationActionReceiver.class);
            finishIntent.setAction(Definitions.Communication.NOTIFICATION_ACTION_FINISH);
            finishIntent.putExtra(Definitions.Communication.KEY_ID, hrId);
            finishIntent.putExtra(Definitions.Communication.KEY_POSITION, position);
            finishIntent.putExtra(Definitions.Communication.KEY_TIME, habitReminder.getNotifyTime());

            Intent getItIntent = new Intent(context, HabitNotificationActionReceiver.class);
            getItIntent.putExtra(Definitions.Communication.KEY_ID, hrId);
            getItIntent.setAction(Definitions.Communication.NOTIFICATION_ACTION_GET_IT);

            builder.addAction(R.mipmap.act_finish, context.getString(R.string.act_finish_this_time_habit),
                            PendingIntent.getBroadcast(context,
                                    (int) hrId, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                   .addAction(R.mipmap.act_get_it, context.getString(R.string.act_get_it),
                            PendingIntent.getBroadcast(context,
                                    (int) hrId, getItIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            notificationManager.notify((int) hrId, builder.build());
        }
    }

    // TODO: 2016/3/23 finish habit at any time should alive this
    private void updateHabitRecordTimes(Context context, long hrId) {
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        HabitReminder habitReminder = habitDAO.getHabitReminderById(hrId);
        long habitId = habitReminder.getHabitId();
        Habit habit = habitDAO.getHabitById(habitId);
        habitDAO.updateHabitReminderToNext(hrId);
        int recordTimes = habit.getRecord().length();
        int remindedTimes = habit.getRemindedTimes();
        if (recordTimes <= remindedTimes) {
            // user doesn't finish this time in advance.
            if (recordTimes < remindedTimes) {
                String s = "";
                while (recordTimes < remindedTimes) {
                    s += "0";
                    recordTimes++;
                }
                habitDAO.updateHabit(habitId, habit.getRecord() + s);
                ThingsCounts.getInstance(context).handleHabitRecorded(0, s.length());
            }
            habitDAO.updateHabit(habitId, remindedTimes + 1);
        } else {
            // recordTimes > remindedTimes means that user finish a habit in advance of notification.
            // Add 1: it is real a notification this time, user doesn't finish this time in advance.
            // At the same time, we can see previous finishes as finishes after notifications.
            habitDAO.updateHabit(habitId, recordTimes + 1);
        }
    }

    private void sendBroadCastToUpdateMainUI(Context context, Thing thing, int position) {
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
