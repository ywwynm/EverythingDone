package com.ywwynm.everythingdone.helpers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver;
import com.ywwynm.everythingdone.receivers.DailyUpdateHabitReceiver;
import com.ywwynm.everythingdone.receivers.HabitReceiver;
import com.ywwynm.everythingdone.receivers.ReminderReceiver;

import org.joda.time.DateTime;

import java.util.List;

/**
 * Created by ywwynm on 2016/3/21.
 * utils for creating/canceling alarms.
 */
public class AlarmHelper {

    public static void setReminderAlarm(Context context, long id, long notifyTime) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, (int) id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
    }

    public static void deleteReminderAlarm(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pendingIntent);
    }

    public static void setHabitReminderAlarm(Context context, long id, long notifyTime) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, HabitReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
    }

    public static void deleteHabitReminderAlarm(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, HabitReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pendingIntent);
    }

    public static void cancelAlarms(Context context, List<Long> thingIds, List<Long> reminderIds,
                              List<Long> habitReminderIds) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (long thingId : thingIds) {
            Intent intent = new Intent(context, AutoNotifyReceiver.class);
            intent.putExtra(Definitions.Communication.KEY_ID, thingId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) thingId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pendingIntent);
        }
        for (long reminderId : reminderIds) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            intent.putExtra(Definitions.Communication.KEY_ID, reminderId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) reminderId,
                    intent, PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pendingIntent);
        }
        for (long habitReminderId : habitReminderIds) {
            Intent intent = new Intent(context, HabitReceiver.class);
            intent.putExtra(Definitions.Communication.KEY_ID, habitReminderId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) habitReminderId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pendingIntent);
        }
    }

    public static void createAllAlarms(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        ReminderDAO reminderDAO = ReminderDAO.getInstance(context);
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        Cursor cursor = thingDAO.getAllThingsCursor();
        while (cursor.moveToNext()) {
            long id = cursor.getLong(
                    cursor.getColumnIndex(Definitions.Database.COLUMN_ID_THINGS));
            int type = cursor.getInt(
                    cursor.getColumnIndex(Definitions.Database.COLUMN_TYPE_THINGS));
            int state = cursor.getInt(
                    cursor.getColumnIndex(Definitions.Database.COLUMN_STATE_THINGS));
            if (state != Thing.UNDERWAY) continue;
            if (Thing.isReminderType(type)) {
                Reminder reminder = reminderDAO.getReminderById(id);
                if (reminder.getState() == Reminder.UNDERWAY) {
                    long notifyTime = reminder.getNotifyTime();
                    if (notifyTime < System.currentTimeMillis()) {
                        reminder.setState(Reminder.EXPIRED);
                        reminderDAO.update(reminder);
                    } else {
                        Intent intent = new Intent(context, ReminderReceiver.class);
                        intent.putExtra(Definitions.Communication.KEY_ID, id);
                        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                                context, (int) id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                        alarmManager.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
                    }
                }
            } else if (type == Thing.HABIT) {
                // 直接将习惯的提醒时间更新到最新时刻
                // 当用户收到提醒但未完成一次，备份应用，并在下一个周期恢复时，该习惯将不会为这一次添加记录"0"
                habitDAO.updateHabitToLatest(id);
            }
        }
        cursor.close();
    }

    public static void createDailyUpdateHabitAlarm(Context context) {
        Intent intent = new Intent(context, DailyUpdateHabitReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        DateTime dt = new DateTime().plusDays(1).withTime(0, 0, 0, 0);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, dt.getMillis(), 86400000, pendingIntent);
    }

}
