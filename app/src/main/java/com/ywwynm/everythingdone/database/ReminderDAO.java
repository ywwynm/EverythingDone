package com.ywwynm.everythingdone.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.model.Reminder;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2015/5/22.
 * Updated by ywwynm on 2015/9/6, from EverythingDoneDAO to {@link ReminderDAO}.
 * DAO layer between model {@link Reminder} and table "reminders".
 */
public class ReminderDAO {

    public static final String TAG = "ReminderDAO";

    private Context mContext;

    private SQLiteDatabase db;

    private static ReminderDAO sReminderDAO;

    private ReminderDAO(Context context) {
        mContext = context.getApplicationContext();
        DBHelper helper = new DBHelper(context);
        db = helper.getWritableDatabase();
    }

    public static ReminderDAO getInstance(Context context) {
        if (sReminderDAO == null) {
            synchronized (ReminderDAO.class) {
                if (sReminderDAO == null) {
                    sReminderDAO = new ReminderDAO(context.getApplicationContext());
                }
            }
        }
        return sReminderDAO;
    }

    public List<Reminder> getAllReminders() {
        List<Reminder> reminders = new ArrayList<>();
        Cursor cursor = db.query(Def.Database.TABLE_REMINDERS, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            reminders.add(new Reminder(cursor));
        }
        cursor.close();
        return reminders;
    }

    public Reminder getReminderById(long id) {
        Cursor cursor = db.query(Def.Database.TABLE_REMINDERS, null,
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
            values.put(Def.Database.COLUMN_ID_REMINDERS, id);
            values.put(Def.Database.COLUMN_NOTIFY_TIME_REMINDERS, notifyTime);
            values.put(Def.Database.COLUMN_STATE_REMINDERS, reminder.getState());
            values.put(Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS, reminder.getNotifyMillis());
            values.put(Def.Database.COLUMN_CREATE_TIME_REMINDERS, System.currentTimeMillis());
            values.put(Def.Database.COLUMN_UPDATE_TIME_REMINDERS, System.currentTimeMillis());
            db.insert(Def.Database.TABLE_REMINDERS, null, values);

            AlarmHelper.setReminderAlarm(mContext, id, notifyTime);
        }
    }

    public void update(Reminder updatedReminder) {
        if (updatedReminder != null) {
            long id = updatedReminder.getId();
            long notifyTime = updatedReminder.getNotifyTime();

            ContentValues values = new ContentValues();
            values.put(Def.Database.COLUMN_NOTIFY_TIME_REMINDERS, notifyTime);
            values.put(Def.Database.COLUMN_STATE_REMINDERS, updatedReminder.getState());
            values.put(Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS, updatedReminder.getNotifyMillis());
            values.put(Def.Database.COLUMN_UPDATE_TIME_REMINDERS, updatedReminder.getUpdateTime());
            db.update(Def.Database.TABLE_REMINDERS, values, "id=" + id, null);

            if (updatedReminder.getState() == Reminder.UNDERWAY) {
                AlarmHelper.setReminderAlarm(mContext, id, notifyTime);
            }
        }
    }

    public void resetGoal(Reminder goal) {
        if (goal == null) return;
        long millis = goal.getNotifyMillis();
        if (millis >= Reminder.GOAL_MILLIS) {
            long notifyTime = System.currentTimeMillis() + millis;
            goal.setNotifyTime(notifyTime);
            goal.setState(Reminder.UNDERWAY);
            goal.setUpdateTime(System.currentTimeMillis());
            update(goal);
        }
    }

    public void delete(long id) {
        db.delete(Def.Database.TABLE_REMINDERS, "id=" + id, null);
        AlarmHelper.deleteReminderAlarm(mContext, id);
    }
}
