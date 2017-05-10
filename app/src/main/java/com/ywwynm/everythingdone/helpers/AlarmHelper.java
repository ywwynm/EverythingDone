package com.ywwynm.everythingdone.helpers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.v4.util.Pair;
import android.util.Log;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver;
import com.ywwynm.everythingdone.receivers.DailyCreateTodoReceiver;
import com.ywwynm.everythingdone.receivers.DailyUpdateHabitReceiver;
import com.ywwynm.everythingdone.receivers.HabitReceiver;
import com.ywwynm.everythingdone.receivers.ReminderReceiver;
import com.ywwynm.everythingdone.utils.DeviceUtil;

import org.joda.time.DateTime;

import java.util.List;

/**
 * Created by ywwynm on 2016/3/21.
 * utils for creating/canceling alarms.
 */
public class AlarmHelper {

    public static final String TAG = "AlarmHelper";

    private AlarmHelper() {}

    public static void setReminderAlarm(Context context, long id, long notifyTime) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(Def.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, (int) id, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        if (DeviceUtil.hasMarshmallowApi()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        } else if (DeviceUtil.hasKitKatApi()) {
            am.setExact(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        }
    }

    public static void deleteReminderAlarm(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(Def.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pendingIntent);
    }

    public static void setHabitReminderAlarm(Context context, long id, long notifyTime) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, HabitReceiver.class);
        intent.putExtra(Def.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        if (DeviceUtil.hasMarshmallowApi()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        } else if (DeviceUtil.hasKitKatApi()) {
            am.setExact(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        }
    }

    public static void deleteHabitReminderAlarm(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, HabitReceiver.class);
        intent.putExtra(Def.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pendingIntent);
    }

    public static void cancelAlarms(Context context, List<Long> thingIds, List<Long> reminderIds,
                              List<Long> habitReminderIds) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (long thingId : thingIds) {
            Intent intent = new Intent(context, AutoNotifyReceiver.class);
            intent.putExtra(Def.Communication.KEY_ID, thingId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) thingId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pendingIntent);
        }
        for (long reminderId : reminderIds) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            intent.putExtra(Def.Communication.KEY_ID, reminderId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) reminderId,
                    intent, PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pendingIntent);
        }
        for (long habitReminderId : habitReminderIds) {
            Intent intent = new Intent(context, HabitReceiver.class);
            intent.putExtra(Def.Communication.KEY_ID, habitReminderId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) habitReminderId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pendingIntent);
        }
    }

    /**
     * Set all alarms again to ensure that they can still ring.
     * At first we only do this job after device reboot, app update and restore data, which are all
     * normal behaviors. However, we need to do this under more situations now, like after screen on,
     * for some third-party roms those who changed behaviors of system components like
     * {@link android.app.AlarmManager} and those who are willing to kill background apps in a
     * force-stop-like way to "improve Android user experience". Yes, I'm talking about Huawei EMUI
     * devices(maybe Xiaomi MIUI devices and Samsung devices are also interesting subjects, but
     * Huawei is most stupid).
     * @param context
     * @param updateHabitRemindedTimes
     */
    public static void createAllAlarms(
            Context context, boolean updateHabitRemindedTimes) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        ReminderDAO reminderDAO = ReminderDAO.getInstance(context);
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        Cursor cursor = thingDAO.getThingsCursor("state=" + Thing.UNDERWAY);
        while (cursor.moveToNext()) {
            long id = cursor.getLong(
                    cursor.getColumnIndex(Def.Database.COLUMN_ID_THINGS));
            @Thing.Type int type = cursor.getInt(
                    cursor.getColumnIndex(Def.Database.COLUMN_TYPE_THINGS));
            int state = cursor.getInt(
                    cursor.getColumnIndex(Def.Database.COLUMN_STATE_THINGS));
            if (state != Thing.UNDERWAY) continue;
            if (Thing.isReminderType(type)) {
                Reminder reminder = reminderDAO.getReminderById(id);
                if (reminder == null || reminder.getState() != Reminder.UNDERWAY) {
                    continue;
                }
                long notifyTime = reminder.getNotifyTime();
                if (notifyTime < System.currentTimeMillis()) {
                    reminder.setState(Reminder.EXPIRED);
                    reminderDAO.update(reminder);
                } else {
                    Intent intent = new Intent(context, ReminderReceiver.class);
                    intent.putExtra(Def.Communication.KEY_ID, id);
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            context, (int) id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                    if (DeviceUtil.hasMarshmallowApi()) {
                        am.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
                    } else if (DeviceUtil.hasKitKatApi()) {
                        am.setExact(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
                    }
                }
            } else if (type == Thing.HABIT) {
                // 直接将习惯的提醒时间更新到最新时刻
                // 当用户收到提醒但未完成一次，备份应用，并在下一个周期恢复时，该习惯将不会为这一次添加记录"0"
                habitDAO.updateHabitToLatest(
                        id, updateHabitRemindedTimes, false);
            }
        }
        cursor.close();

        createDailyUpdateHabitAlarm(context);
        tryToCreateDailyTodoAlarm(context);
    }

    public static void createDailyUpdateHabitAlarm(Context context) {
        Intent intent = new Intent(context, DailyUpdateHabitReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        DateTime dt = new DateTime().plusDays(1).withTime(0, 0, 0, 0);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, dt.getMillis(), 86400000, pendingIntent);
    }

    public static void tryToCreateDailyTodoAlarm(Context context) {
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        int index = sp.getInt(Def.Meta.KEY_DAILY_TODO, 0);
        if (index == 0) {
            return;
        }

        List<Pair<Integer, Integer>> dailyTodoPairs = DailyTodoHelper.getDailyTodoTimePairs();
        Pair<Integer, Integer> pair = dailyTodoPairs.get(index);

        Intent intent = new Intent(context, DailyCreateTodoReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        DateTime dt = new DateTime().withTime(pair.first, pair.second, 0, 0);
        if (dt.getMillis() < System.currentTimeMillis()) {
            dt = dt.plusDays(1);
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, dt.getMillis(), 86400000, pendingIntent);
        Log.d(TAG, "daily todo alarm is created");
    }

    public static void cancelDailyTodoAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, DailyCreateTodoReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pendingIntent);
    }

}
