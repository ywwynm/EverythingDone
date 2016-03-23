package com.ywwynm.everythingdone.database;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.bean.Reminder;
import com.ywwynm.everythingdone.receivers.ReminderReceiver;

/**
 * Created by ywwynm on 2015/5/22.
 * Updated by ywwynm on 2015/9/6, from EverythingDoneDAO to {@link ReminderDAO}.
 * DAO layer between model {@link Reminder} and table "reminders".
 */
public class ReminderDAO {

    public static final String TAG = "ReminderDAO";

    private Context mContext;

    private SQLiteDatabase db;

    private AlarmManager mAlarmManager;

    private static ReminderDAO sReminderDAO;

    private ReminderDAO(Context context) {
        mContext = context;
        EverythingDoneSQLiteOpenHelper helper = new EverythingDoneSQLiteOpenHelper(context);
        db = helper.getWritableDatabase();
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    public static ReminderDAO getInstance(Context context) {
        if (sReminderDAO == null) {
            synchronized (ReminderDAO.class) {
                if (sReminderDAO == null) {
                    sReminderDAO = new ReminderDAO(context);
                }
            }
        }
        return sReminderDAO;
    }

    public Reminder getReminderById(long id) {
        Cursor cursor = db.query(Definitions.Database.TABLE_REMINDERS, null,
                "id=" + id, null, null, null, null);
        Reminder reminder = null;
        if (cursor.moveToFirst()) {
            reminder = new Reminder(cursor);
        }
        cursor.close();
        return reminder;
    }

    public void create(Reminder reminder) {
        if (reminder != null) {
            long id = reminder.getId();
            long notifyTime = reminder.getNotifyTime();

            ContentValues values = new ContentValues();
            values.put(Definitions.Database.COLUMN_ID_REMINDERS, id);
            values.put(Definitions.Database.COLUMN_NOTIFY_TIME_REMINDERS, notifyTime);
            values.put(Definitions.Database.COLUMN_STATE_REMINDERS, reminder.getState());
            values.put(Definitions.Database.COLUMN_GOAL_DAYS_REMINDERS, reminder.getGoalDays());
            values.put(Definitions.Database.COLUMN_CREATE_TIME_REMINDERS, System.currentTimeMillis());
            values.put(Definitions.Database.COLUMN_UPDATE_TIME_REMINDERS, System.currentTimeMillis());
            db.insert(Definitions.Database.TABLE_REMINDERS, null, values);

            Intent intent = new Intent(mContext, ReminderReceiver.class);
            intent.putExtra(Definitions.Communication.KEY_ID, id);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    mContext, (int) id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
        }
    }

    public void update(Reminder updatedReminder) {
        if (updatedReminder != null) {
            long id = updatedReminder.getId();
            long notifyTime = updatedReminder.getNotifyTime();

            ContentValues values = new ContentValues();
            values.put(Definitions.Database.COLUMN_NOTIFY_TIME_REMINDERS, notifyTime);
            values.put(Definitions.Database.COLUMN_STATE_REMINDERS, updatedReminder.getState());
            values.put(Definitions.Database.COLUMN_GOAL_DAYS_REMINDERS, updatedReminder.getGoalDays());
            values.put(Definitions.Database.COLUMN_UPDATE_TIME_REMINDERS, updatedReminder.getUpdateTime());
            db.update(Definitions.Database.TABLE_REMINDERS, values, "id=" + id, null);

            if (updatedReminder.getState() == Reminder.UNDERWAY) {
                Intent intent = new Intent(mContext, ReminderReceiver.class);
                intent.putExtra(Definitions.Communication.KEY_ID, id);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, (int) id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                mAlarmManager.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
            }
        }
    }

    public void resetGoal(Reminder goal) {
        long goalDays = goal.getGoalDays();
        if (goal != null && goalDays >= 4 * 30) {
            long notifyTime = System.currentTimeMillis() + goalDays * 24 * 60 * 60 * 1000;
            goal.setNotifyTime(notifyTime);
            goal.setState(Reminder.UNDERWAY);
            goal.setUpdateTime(System.currentTimeMillis());
            update(goal);
        }
    }

    public void delete(long id) {
        db.delete(Definitions.Database.TABLE_REMINDERS, "id=" + id, null);
        Intent intent = new Intent(mContext, ReminderReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.cancel(pendingIntent);
    }
}
