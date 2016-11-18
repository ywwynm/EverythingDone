package com.ywwynm.everythingdone.receivers;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.Pair;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DoingActivity;
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DeviceUtil;
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

        final long habitId = habitReminder.getHabitId();
        SystemNotificationUtil.cancelNotification(habitId, Thing.HABIT, context);
        // remove existed notification for same Habit
        context.sendBroadcast(
                new Intent(NoticeableNotificationActivity.BROADCAST_ACTION_JUST_FINISH)
                        .putExtra(Def.Communication.KEY_ID, habitId));

        final boolean isDoingLastTime = App.getDoingThingId() == habitId;
        if (isDoingLastTime) {
            // if user is doing this Habit for the last time, now he/she cannot do it any longer
            // since this time is coming
            Toast.makeText(context, R.string.doing_failed_next_alarm,
                    Toast.LENGTH_LONG).show();
            context.sendBroadcast(new Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH));
            DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_NEXT_ALARM;
            context.stopService(new Intent(context, DoingService.class));
        }

        Pair<Thing, Integer> pair = App.getThingAndPosition(context, habitId, -1);
        final Thing thing = pair.first;
        if (thing == null) {
            habitDAO.deleteHabit(habitId);
            return;
        }

        if (isDoingLastTime) {
            // create this notification after 1600ms, otherwise it will be cancelled because of
            // stopping a service bound a foreground notification with same id
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1600);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    Notification notification = SystemNotificationUtil.createDoingNotification(
                            App.getApp(), thing, DoingService.STATE_FAILED_NEXT_ALARM, null, true);
                    NotificationManagerCompat.from(App.getApp()).notify((int) habitId, notification);
                }
            }).start();
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

            notifyUser(context, habitId, hrId, position, thing, habitReminder);
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

    private void notifyUser(
            Context context, long habitId, long hrId, int position,
            Thing thing, HabitReminder habitReminder) {
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        boolean moreNoticeable = sp.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true);
        notifyUserBySystemNotification(context, habitId, hrId, position,
                thing, habitReminder, moreNoticeable);
        if (moreNoticeable) {
            Intent intent = NoticeableNotificationActivity.getOpenIntentForHabit(
                    context, hrId, position, habitReminder.getNotifyTime());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            context.startActivity(intent);
        }
    }

    private void notifyUserBySystemNotification(
            Context context, long habitId, long hrId, int position,
            Thing thing, HabitReminder habitReminder, boolean moreNoticeable) {
        NotificationCompat.Builder builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, habitId, position, thing, false);
        if (moreNoticeable && DeviceUtil.hasLollipopApi()) {
            // if we use a dialog to notify this alarm, we don't need to show heads-up notification
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        }

        Intent finishIntent = new Intent(context, HabitNotificationActionReceiver.class);
        finishIntent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
        finishIntent.putExtra(Def.Communication.KEY_ID, hrId);
        finishIntent.putExtra(Def.Communication.KEY_POSITION, position);
        finishIntent.putExtra(Def.Communication.KEY_TIME, habitReminder.getNotifyTime());
        builder.addAction(R.drawable.act_finish, context.getString(R.string.act_finish_this_time_habit),
                PendingIntent.getBroadcast(context,
                        (int) hrId, finishIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        Intent startIntent = new Intent(context, HabitNotificationActionReceiver.class);
        startIntent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
        startIntent.putExtra(Def.Communication.KEY_ID, hrId);
        startIntent.putExtra(Def.Communication.KEY_POSITION, position);
        builder.addAction(R.drawable.act_start_doing,
                context.getString(R.string.act_start_doing),
                PendingIntent.getBroadcast(context,
                        (int) hrId, startIntent, PendingIntent.FLAG_UPDATE_CURRENT));

//            Intent getItIntent = new Intent(context, HabitNotificationActionReceiver.class);
//            getItIntent.setAction(Def.Communication.NOTIFICATION_ACTION_GET_IT);
//            getItIntent.putExtra(Def.Communication.KEY_ID, hrId);
//            getItIntent.putExtra(Def.Communication.KEY_POSITION, position);
//            builder.addAction(R.drawable.act_get_it,
//                    context.getString(R.string.act_get_it),
//                    PendingIntent.getBroadcast(context,
//                            (int) hrId, getItIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.notify((int) hrId, builder.build());
    }
}
