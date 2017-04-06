package com.ywwynm.everythingdone.receivers;

import android.app.Notification;
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
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
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
        Habit habit = habitDAO.getHabitById(habitId);
        if (habit == null || habit.isPaused()) {
            // Don't keep alarms(updateHabitReminderToNext). Resume alarms when resume the Habit.
            return;
        }

        // remove existed notification for same Habit
        SystemNotificationUtil.cancelNotification(habitId, Thing.HABIT, context);
        context.sendBroadcast(
                new Intent(NoticeableNotificationActivity.BROADCAST_ACTION_JUST_FINISH)
                        .putExtra(Def.Communication.KEY_ID, habitId));

        long hrTime = habitReminder.getNotifyTime();
        long curDoingId = App.getDoingThingId();
        if (curDoingId == habitId && hrTime > DoingService.sHrTime) {
            // if user is doing this Habit for the last time, now he/she cannot do it any longer
            // since this time is coming
            ThingDoingHelper.stopDoing(context, DoingRecord.STOP_REASON_CANCEL_NEXT_ALARM);
        }

        Pair<Thing, Integer> pair = App.getThingAndPosition(context, habitId, -1);
        final Thing thing = pair.first;
        if (thing == null) {
            habitDAO.deleteHabit(habitId);
            return;
        }

        if (curDoingId == habitId && hrTime > DoingService.sHrTime) {
            Toast.makeText(context, R.string.doing_failed_next_alarm,
                    Toast.LENGTH_LONG).show();
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
                            App.getApp(), thing, DoingService.STATE_FAILED_NEXT_ALARM, null, -1, 2);
                    NotificationManagerCompat.from(App.getApp()).notify((int) habitId, notification);
                }
            }).start();
        }

        int position = pair.second;

        if (curDoingId == habitId && hrTime <= DoingService.sHrTime) {
            Toast.makeText(context, R.string.start_doing_notification_toast_doing_this_habit,
                    Toast.LENGTH_LONG).show();
            updateHabitRecordTimes(context, hrId);
            RemoteActionHelper.updateUiEverywhere(
                    context, thing, position, thing.getType(),
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
            return;
        }

        if (thing.getState() == Thing.UNDERWAY) {

            ThingDoingHelper helper = new ThingDoingHelper(context, thing);
            boolean shouldAutoStartDoing = helper.shouldAutoStartDoing();
            if (curDoingId == -1 && shouldAutoStartDoing) {
                updateHabitRecordTimesAndUi(context, hrId, thing, position);
                habit = habitDAO.getHabitById(habitId);
                if (habit == null) {
                    helper.startDoingAuto(-1, hrTime);
                } else {
                    helper.startDoingAuto(habit.getMinHabitReminderTime(), hrTime);
                }
                return;
            }

            List<Long> runningDetailActivities = App.getRunningDetailActivities();
            for (Long rThingId : runningDetailActivities) {
                if (rThingId == habitId) {
                    updateHabitRecordTimesAndUi(context, hrId, thing, position);
                    Toast.makeText(context, R.string.notification_toast_checking_when_alarm_comes,
                            Toast.LENGTH_LONG).show();
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

            updateHabitRecordTimesAndUi(context, hrId, thing, position);

            // possible conditions when logic goes here(or):
            // curDoingId == habitId && hrTime > DoingService.sHrTime
            // curDoingId == -1 && !shouldAutoStartDoing
            // curDoingId == another thing's id

            if (shouldAutoStartDoing) {
                if (curDoingId == habitId) {
                    Toast.makeText(context, R.string.auto_start_doing_notification_toast_doing_habit_this,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, R.string.auto_start_doing_notification_toast_doing_another,
                            Toast.LENGTH_LONG).show();
                }
            }

            if (hrTime > System.currentTimeMillis()) {
                // this should be impossible
                hrTime = DateTimeUtil.getHabitReminderTime(habit.getType(), hrTime, -1);
            }
            notifyUser(context, habitId, hrId, hrTime, position, thing);
        }
    }

    private void updateHabitRecordTimesAndUi(Context context, long hrId, Thing thing, int position) {
        updateHabitRecordTimes(context, hrId);
        RemoteActionHelper.updateUiEverywhere(
                context, thing, position, thing.getType(),
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
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
            Context context, long habitId, long hrId, long hrTime, int position, Thing thing) {
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        boolean moreNoticeable = sp.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true);
        notifyUserBySystemNotification(context, habitId, hrId, hrTime, position,
                thing, moreNoticeable);
        if (moreNoticeable) {
            Intent intent = NoticeableNotificationActivity.getOpenIntentForHabit(
                    context, hrId, position, hrTime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            context.startActivity(intent);
        }
    }

    private void notifyUserBySystemNotification(
            Context context, long habitId, long hrId, long hrTime, int position,
            Thing thing, boolean moreNoticeable) {
        NotificationCompat.Builder builder = SystemNotificationUtil
                .newGeneralNotificationBuilder(context, TAG, habitId, position, thing, false);
        if (moreNoticeable && DeviceUtil.hasLollipopApi()) {
            // if we use a dialog to notify this alarm, we don't need to show heads-up notification
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        }

        SystemNotificationUtil.addActionsForHabitNotification(
                context, builder, hrId, position, hrTime);

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.notify((int) hrId, builder.build());
    }
}
