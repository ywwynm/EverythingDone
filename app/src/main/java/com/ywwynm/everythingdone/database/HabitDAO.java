package com.ywwynm.everythingdone.database;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitRecord;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.ThingsCounts;
import com.ywwynm.everythingdone.receivers.HabitReceiver;
import com.ywwynm.everythingdone.utils.DateTimeUtil;

import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Created by ywwynm on 2016/1/29.
 * dao layer between model {@link Habit} and table "habits".
 */
public class HabitDAO {

    public static final String TAG = "HabitDAO";

    private Context mContext;
    private long mHabitReminderId;
    private long mHabitRecordId;

    private SQLiteDatabase db;

    private AlarmManager mAlarmManager;

    private static HabitDAO sHabitDAO;

    private HabitDAO(Context context) {
        mContext = context;
        EverythingDoneSQLiteOpenHelper helper = new EverythingDoneSQLiteOpenHelper(context);
        db = helper.getWritableDatabase();
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        updateMaxHabitReminderRecordId();
    }

    private void updateMaxHabitReminderRecordId() {
        mHabitReminderId = -1;
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_REMINDERS,
                null, null, null, null, null, "id desc");
        if (c.moveToFirst()) {
            mHabitReminderId = c.getLong(0);
        }
        c.close();
        Cursor c2 = db.query(Definitions.Database.TABLE_HABIT_RECORDS,
                null, null, null, null, null, "id desc");
        if (c2.moveToFirst()) {
            mHabitRecordId = c2.getLong(0);
        }
        c2.close();
    }

    public static HabitDAO getInstance(Context context) {
        if (sHabitDAO == null) {
            synchronized (ReminderDAO.class) {
                if (sHabitDAO == null) {
                    sHabitDAO = new HabitDAO(context);
                }
            }
        }
        return sHabitDAO;
    }

    public Habit getHabitById(long id) {
        Cursor c = db.query(Definitions.Database.TABLE_HABITS, null,
                "id=" + id, null, null, null, null);
        Habit habit = null;
        if (c.moveToFirst()) {
            habit = new Habit(c);
            habit.setHabitReminders(getHabitRemindersByHabitId(id));
            habit.setHabitRecords(getHabitRecordsByHabitId(id));
        }
        c.close();
        return habit;
    }

    public HabitReminder getHabitReminderById(long id) {
        HabitReminder habitReminder = null;
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_REMINDERS, null,
                "id=" + id, null, null, null, null);
        if (c.moveToFirst()) {
            habitReminder = new HabitReminder(c);
        }
        c.close();
        return habitReminder;
    }

    public List<HabitReminder> getHabitRemindersByHabitId(long habitId) {
        List<HabitReminder> habitReminders = new ArrayList<>();
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_REMINDERS, null,
                "habit_id=" + habitId, null, null, null, null);
        while (c.moveToNext()) {
            habitReminders.add(new HabitReminder(c));
        }
        c.close();
        return habitReminders;
    }

    public List<HabitRecord> getHabitRecordsByHabitId(long habitId) {
        List<HabitRecord> habitRecords = new ArrayList<>();
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_RECORDS, null,
                "habit_id=" + habitId, null, null, null, null);
        while (c.moveToNext()) {
            habitRecords.add(new HabitRecord(c));
        }
        c.close();
        return habitRecords;
    }

    public void createHabit(Habit habit) {
        db.beginTransaction();
        try {
            long id = habit.getId();
            ContentValues values = new ContentValues();
            values.put(Definitions.Database.COLUMN_ID_HABITS, id);
            values.put(Definitions.Database.COLUMN_TYPE_HABITS, habit.getType());
            values.put(Definitions.Database.COLUMN_REMINDED_TIMES_HABITS, habit.getRemindedTimes());
            values.put(Definitions.Database.COLUMN_DETAIL_HABITS, habit.getDetail());
            values.put(Definitions.Database.COLUMN_RECORD_HABITS, habit.getRecord());
            values.put(Definitions.Database.COLUMN_INTERVAL_INFO_HABITS, habit.getIntervalInfo());
            values.put(Definitions.Database.COLUMN_CREATE_TIME_HABITS, habit.getCreateTime());
            values.put(Definitions.Database.COLUMN_FIRST_TIME_HABITS, habit.getFirstTime());
            db.insert(Definitions.Database.TABLE_HABITS, null, values);

            for (HabitReminder habitReminder : habit.getHabitReminders()) {
                createHabitReminder(habitReminder);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    public void createHabitReminder(HabitReminder habitReminder) {
        mHabitReminderId++;
        long notifyTime = habitReminder.getNotifyTime();
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_ID_HABIT_REMINDERS, mHabitReminderId);
        values.put(Definitions.Database.COLUMN_HABIT_ID_HABIT_REMINDERS, habitReminder.getHabitId());
        values.put(Definitions.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS, notifyTime);
        db.insert(Definitions.Database.TABLE_HABIT_REMINDERS, null, values);

        Intent intent = new Intent(mContext, HabitReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, mHabitReminderId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, (int) mHabitReminderId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
    }

    public HabitRecord createHabitRecord(HabitRecord habitRecord) {
        mHabitRecordId++;
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_ID_HABIT_RECORDS, mHabitRecordId);
        values.put(Definitions.Database.COLUMN_HABIT_ID_HABIT_RECORDS, habitRecord.getHabitId());
        values.put(Definitions.Database.COLUMN_HR_ID_HABIT_RECORDS, habitRecord.getHabitReminderId());
        values.put(Definitions.Database.COLUMN_RECORD_TIME_HABIT_RECORDS, habitRecord.getRecordTime());
        values.put(Definitions.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS, habitRecord.getRecordYear());
        values.put(Definitions.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS, habitRecord.getRecordMonth());
        values.put(Definitions.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS, habitRecord.getRecordWeek());
        values.put(Definitions.Database.COLUMN_RECORD_DAY_HABIT_RECORDS, habitRecord.getRecordDay());
        db.insert(Definitions.Database.TABLE_HABIT_RECORDS, null, values);
        habitRecord.setId(mHabitRecordId);
        return habitRecord;
    }

    public HabitRecord finishOneTime(Habit habit) {
        String record = habit.getRecord();
        int recordedTimes = record.length();
        int remindedTimes = habit.getRemindedTimes();
        long habitId = habit.getId(), habitReminderId;
        int newRecordCount = 1;

        if (recordedTimes >= remindedTimes) {
            // finish this habit once before notification
            HabitReminder closest = habit.getClosestHabitReminder();
            updateHabitReminderToNext(closest.getId());
            habitReminderId = closest.getId();
        } else {
            HabitReminder finalOne = habit.getFinalHabitReminder();
            long finalTime = finalOne.getNotifyTime();
            int type = habit.getType();
            long finalLastTime = DateTimeUtil.getHabitReminderTime(type, finalTime, -1);
            int gap = DateTimeUtil.calculateTimeGap(
                    finalLastTime, System.currentTimeMillis(), type);
            if (gap == 0) {
                // Reminded this T
                habitReminderId = finalOne.getId();
            } else {
                // Haven't reminded this T yet.
                // User want to finish this habit once before notification.
                HabitReminder closest = habit.getClosestHabitReminder();
                updateHabitReminderToNext(closest.getId());
                habitReminderId = closest.getId();

                // At the same time, this means that user didn't finish enough times last T.
                // More clearly, user didn't finish last time last T.
                record += "0";
                newRecordCount++;
            }
        }
        updateHabit(habitId, record + "1");
        ThingsCounts.getInstance(mContext).handleHabitRecorded(1, newRecordCount);
        return createHabitRecord(new HabitRecord(0, habitId, habitReminderId));
    }

    public void undoFinishOneTime(HabitRecord habitRecord) {
        long hrId = habitRecord.getId(), habitId = habitRecord.getHabitId();
        Habit habit = getHabitById(habitId);
        String record = habit.getRecord();
        final int len = record.length();
        if (len - 1 >= habit.getRemindedTimes()) {
            updateHabitReminderToLast(habit.getFinalHabitReminder());
        }

        updateHabit(habitId, record.substring(0, len - 1));
        deleteHabitRecord(hrId);
        ThingsCounts.getInstance(mContext).handleHabitRecorded(-1, -1);
    }

    public int getFinishedTimesThisT(Habit habit) {
        long habitId = habit.getId();
        int type = habit.getType();
        if (type == Calendar.DATE) {
            return getFinishedTimesToday(habitId);
        } else if (type == Calendar.WEEK_OF_YEAR) {
            return getFinishedTimesThisWeek(habitId);
        } else if (type == Calendar.MONTH) {
            return getFinishedTimesThisMonth(habitId);
        } else if (type == Calendar.YEAR) {
            return getFinishedTimesThisYear(habitId);
        }
        return 0;
    }

    private int getFinishedTimesToday(long habitId) {
        DateTime dt  = new DateTime();
        int curYear  = dt.getYear();
        int curMonth = dt.getMonthOfYear();
        int curDay   = dt.getDayOfMonth();
        int times    = 0;
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_RECORDS, null,
                "habit_id=" + habitId, null, null, null, "id desc");
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            int month = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS));
            int day = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_DAY_HABIT_RECORDS));
            if (year == curYear && month == curMonth && day == curDay) {
                times++;
            } else break;
        }
        c.close();
        return times;
    }

    private int getFinishedTimesThisWeek(long habitId) {
        DateTime dt = new DateTime();
        int curYear = dt.getYear();
        int curWeek = dt.getWeekOfWeekyear();
        int times   = 0;
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_RECORDS, null,
                "habit_id=" + habitId, null, null, null, "id desc");
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            int week = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS));
            if (year == curYear && week == curWeek) {
                times++;
            } else break;
        }
        c.close();
        return times;
    }

    private int getFinishedTimesThisMonth(long habitId) {
        DateTime dt = new DateTime();
        int curYear  = dt.getYear();
        int curMonth = dt.getMonthOfYear();
        int times    = 0;
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_RECORDS, null,
                "habit_id=" + habitId, null, null, null, "id desc");
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            int month = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS));
            if (year == curYear && month == curMonth) {
                times++;
            } else break;
        }
        c.close();
        return times;
    }

    private int getFinishedTimesThisYear(long habitId) {
        DateTime dt = new DateTime();
        int curYear = dt.getYear();
        int times   = 0;
        Cursor c = db.query(Definitions.Database.TABLE_HABIT_RECORDS, null,
                "habit_id=" + habitId, null, null, null, "id desc");
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Definitions.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            if (year == curYear) {
                times++;
            } else break;
        }
        c.close();
        return times;
    }

    public void updateHabitToLatest(long id) {
        Habit habit = getHabitById(id);
        int recordTimes = habit.getRecord().length();
        updateHabit(id, recordTimes);
        List<HabitReminder> habitReminders = habit.getHabitReminders();
        List<Long> hrIds = new ArrayList<>();
        for (HabitReminder habitReminder : habitReminders) {
            hrIds.add(habitReminder.getId());
        }
        habit.initHabitReminders(); // habitReminders have become latest.
        habitReminders = habit.getHabitReminders();
        for (int i = 0; i < hrIds.size(); i++) {
            long newTime = habitReminders.get(i).getNotifyTime();
            updateHabitReminder(hrIds.get(i), newTime);
        }

        // 将已经提前完成的habitReminder更新至新的周期里
        List<HabitRecord> habitRecordsThisT = habit.getHabitRecordsThisT();
        for (HabitRecord habitRecord : habitRecordsThisT) {
            HabitReminder hr = getHabitReminderById(habitRecord.getHabitReminderId());
            if (DateTimeUtil.calculateTimeGap(
                    System.currentTimeMillis(), hr.getNotifyTime(), habit.getType()) == 0) {
                updateHabitReminderToNext(hr.getId());
            }
        }
    }

    public void updateHabit(long id, String record) {
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_RECORD_HABITS, record);
        db.update(Definitions.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void updateHabit(long id, long remindedTimes) {
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_REMINDED_TIMES_HABITS, remindedTimes);
        db.update(Definitions.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void addHabitIntervalInfo(long id, String intervalInfoToAdd) {
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_INTERVAL_INFO_HABITS,
                getHabitById(id).getIntervalInfo() + intervalInfoToAdd);
        db.update(Definitions.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void removeLastHabitIntervalInfo(long id) {
        String interval = getHabitById(id).getIntervalInfo();
        interval = interval.substring(0,
                interval.lastIndexOf(interval.endsWith(";") ? "," : ";") + 1);
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_INTERVAL_INFO_HABITS, interval);
        db.update(Definitions.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void updateHabitReminder(long hrId, long notifyTime) {
        ContentValues values = new ContentValues();
        values.put(Definitions.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS, notifyTime);
        db.update(Definitions.Database.TABLE_HABIT_REMINDERS, values, "id=" + hrId, null);

        Intent intent = new Intent(mContext, HabitReceiver.class);
        intent.putExtra(Definitions.Communication.KEY_ID, hrId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, (int) hrId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, notifyTime, pendingIntent);
    }

    public void updateHabitReminderToNext(long hrId) {
        HabitReminder habitReminder = getHabitReminderById(hrId);
        Habit habit = getHabitById(habitReminder.getHabitId());
        int type = habit.getType();
        long time = habitReminder.getNotifyTime();
        updateHabitReminder(hrId, DateTimeUtil.getHabitReminderTime(type, time, 1));
    }

    public void updateHabitReminderToLast(HabitReminder habitReminder) {
        Habit habit = getHabitById(habitReminder.getHabitId());
        int type = habit.getType();
        long time = habitReminder.getNotifyTime();
        updateHabitReminder(habitReminder.getId(),
                DateTimeUtil.getHabitReminderTime(type, time, -1));
    }

    public void deleteHabit(long id) {
        db.beginTransaction();
        try {
            db.delete(Definitions.Database.TABLE_HABITS, "id=" + id, null);
            deleteHabitReminders(id);
            deleteHabitRecords(id);
            updateMaxHabitReminderRecordId();
            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteHabitReminders(long habitId) {
        List<HabitReminder> habitReminders = getHabitRemindersByHabitId(habitId);
        for (HabitReminder habitReminder : habitReminders) {
            long id = habitReminder.getId();
            Intent intent = new Intent(mContext, HabitReceiver.class);
            intent.putExtra(Definitions.Communication.KEY_ID, id);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, (int) id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            mAlarmManager.cancel(pendingIntent);
        }
        db.delete(Definitions.Database.TABLE_HABIT_REMINDERS, "habit_id=" + habitId, null);
    }

    public void deleteHabitRecords(long habitId) {
        db.delete(Definitions.Database.TABLE_HABIT_RECORDS, "habit_id=" + habitId, null);
    }

    public void deleteHabitRecord(long hrId) {
        db.delete(Definitions.Database.TABLE_HABIT_RECORDS, "id=" + hrId, null);
    }

}
