package com.ywwynm.everythingdone.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitRecord;
import com.ywwynm.everythingdone.model.HabitReminder;
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

    private static HabitDAO sHabitDAO;

    private HabitDAO(Context context) {
        mContext = context.getApplicationContext();
        DBHelper helper = new DBHelper(context);
        db = helper.getWritableDatabase();
        updateMaxHabitReminderRecordId();
    }

    private void updateMaxHabitReminderRecordId() {
        mHabitReminderId = -1;
        Cursor c = db.query(Def.Database.TABLE_HABIT_REMINDERS,
                null, null, null, null, null, "id desc");
        if (c.moveToFirst()) {
            mHabitReminderId = c.getLong(0);
        }
        c.close();
        Cursor c2 = db.query(Def.Database.TABLE_HABIT_RECORDS,
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
                    sHabitDAO = new HabitDAO(context.getApplicationContext());
                }
            }
        }
        return sHabitDAO;
    }

    public List<Habit> getAllHabits() {
        List<Habit> habits = new ArrayList<>();
        Cursor cursor = db.query(Def.Database.TABLE_HABITS, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            habits.add(new Habit(cursor));
        }
        cursor.close();
        return habits;
    }

    public List<HabitReminder> getAllHabitReminders() {
        List<HabitReminder> hrs = new ArrayList<>();
        Cursor cursor = db.query(Def.Database.TABLE_HABIT_REMINDERS, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            hrs.add(new HabitReminder(cursor));
        }
        cursor.close();
        return hrs;
    }

    public List<HabitRecord> getAllHabitRecords() {
        List<HabitRecord> hrs = new ArrayList<>();
        Cursor cursor = db.query(Def.Database.TABLE_HABIT_RECORDS, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            hrs.add(new HabitRecord(cursor));
        }
        cursor.close();
        return hrs;
    }

    public Habit getHabitById(long id) {
        Cursor c = db.query(Def.Database.TABLE_HABITS, null,
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
        Cursor c = db.query(Def.Database.TABLE_HABIT_REMINDERS, null,
                "id=" + id, null, null, null, null);
        if (c.moveToFirst()) {
            habitReminder = new HabitReminder(c);
        }
        c.close();
        return habitReminder;
    }

    public List<HabitReminder> getHabitRemindersByHabitId(long habitId) {
        List<HabitReminder> habitReminders = new ArrayList<>();
        Cursor c = db.query(Def.Database.TABLE_HABIT_REMINDERS, null,
                "habit_id=" + habitId, null, null, null, null);
        while (c.moveToNext()) {
            habitReminders.add(new HabitReminder(c));
        }
        c.close();
        return habitReminders;
    }

    public List<HabitRecord> getHabitRecordsByHabitId(long habitId) {
        List<HabitRecord> habitRecords = new ArrayList<>();
        String condition = Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS + "=" + habitId
                + " and (" +
                    Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FINISHED
                        + " or " +
                    Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FAKE_FINISHED
                + ")";
        Cursor c = db.query(Def.Database.TABLE_HABIT_RECORDS, null,
                condition, null, null, null,
                Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS + " asc");
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
            values.put(Def.Database.COLUMN_ID_HABITS, id);
            values.put(Def.Database.COLUMN_TYPE_HABITS, habit.getType());
            values.put(Def.Database.COLUMN_REMINDED_TIMES_HABITS, habit.getRemindedTimes());
            values.put(Def.Database.COLUMN_DETAIL_HABITS, habit.getDetail());
            values.put(Def.Database.COLUMN_RECORD_HABITS, habit.getRecord());
            values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS, habit.getIntervalInfo());
            values.put(Def.Database.COLUMN_CREATE_TIME_HABITS, habit.getCreateTime());
            values.put(Def.Database.COLUMN_FIRST_TIME_HABITS, habit.getFirstTime());
            db.insert(Def.Database.TABLE_HABITS, null, values);

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
        values.put(Def.Database.COLUMN_ID_HABIT_REMINDERS, mHabitReminderId);
        values.put(Def.Database.COLUMN_HABIT_ID_HABIT_REMINDERS, habitReminder.getHabitId());
        values.put(Def.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS, notifyTime);
        db.insert(Def.Database.TABLE_HABIT_REMINDERS, null, values);
        AlarmHelper.setHabitReminderAlarm(mContext, mHabitReminderId, notifyTime);
    }

    public HabitRecord createHabitRecord(HabitRecord habitRecord) {
        mHabitRecordId++;
        ContentValues values = getContentValuesFromHabitRecord(habitRecord, true);
        db.insert(Def.Database.TABLE_HABIT_RECORDS, null, values);
        habitRecord.setId(mHabitRecordId);
        return habitRecord;
    }

    private ContentValues getContentValuesFromHabitRecord(HabitRecord habitRecord, boolean putId) {
        ContentValues values = new ContentValues();
        if (putId) {
            values.put(Def.Database.COLUMN_ID_HABIT_RECORDS, mHabitRecordId);
        }
        values.put(Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS, habitRecord.getHabitId());
        values.put(Def.Database.COLUMN_HR_ID_HABIT_RECORDS, habitRecord.getHabitReminderId());
        values.put(Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS, habitRecord.getRecordTime());
        values.put(Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS, habitRecord.getRecordYear());
        values.put(Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS, habitRecord.getRecordMonth());
        values.put(Def.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS, habitRecord.getRecordWeek());
        values.put(Def.Database.COLUMN_RECORD_DAY_HABIT_RECORDS, habitRecord.getRecordDay());
        values.put(Def.Database.COLUMN_TYPE_HABIT_RECORDS, habitRecord.getType());
        return values;
    }

    // added on 2017/3/2, version should be 1.3.6(38)
    public void pause(long habitId) {
        Habit habit = getHabitById(habitId);
        if (habit == null || habit.isPaused()) return;
        addHabitIntervalInfo(habitId, System.currentTimeMillis() + ",");
    }

    public void resume(long habitId) {
        Habit habit = getHabitById(habitId);
        if (habit == null || !habit.isPaused()) return;
        addHabitIntervalInfo(habitId, System.currentTimeMillis() + ";");
    }
    // added end

    public boolean isPaused(long habitId) {
        Cursor cursor = db.query(
                Def.Database.TABLE_HABITS,
                new String[] { Def.Database.COLUMN_INTERVAL_INFO_HABITS },
                Def.Database.COLUMN_ID_HABITS + "=" + habitId,
                null, null, null, null
        );
        boolean paused = false;
        if (cursor.moveToFirst()) {
            String intervalInfo = cursor.getString(0);
            paused = intervalInfo.endsWith(",");
        }
        cursor.close();
        return paused;
    }

    public void dailyUpdate(long habitId) {
        Habit habit = getHabitById(habitId);
        String record = habit.getRecord();
        int recordedTimes = record.length();
        int remindedTimes = habit.getRemindedTimes();
        if (recordedTimes < remindedTimes) {
            int type = habit.getType();
            long start = System.currentTimeMillis() - 86400000;
            int gap = DateTimeUtil.calculateTimeGap(start, System.currentTimeMillis(), type);
            if (gap != 0) {
                StringBuilder sb = new StringBuilder(record);
                for (int i = recordedTimes; i < remindedTimes; i++) {
                    sb.append("0");
                }
                updateRecordOfHabit(habitId, sb.toString());
            }
        }
    }

    public HabitRecord finishOneTime(Habit habit) {
        String record = habit.getRecord();
        int recordedTimes = record.length();
        int remindedTimes = habit.getRemindedTimes();
        long habitId = habit.getId(), habitReminderId;

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
            }
        }
        updateRecordOfHabit(habitId, record + "1");
        return createHabitRecord(new HabitRecord(habitId, habitReminderId));
    }

    public void undoFinishOneTime(HabitRecord habitRecord) {
        long hrId = habitRecord.getId(), habitId = habitRecord.getHabitId();
        Habit habit = getHabitById(habitId);
        String record = habit.getRecord();
        final int len = record.length();
        if (len - 1 >= habit.getRemindedTimes()) {
            updateHabitReminderToLast(habit.getFinalHabitReminder());
        }

        updateRecordOfHabit(habitId, record.substring(0, len - 1));
        deleteHabitRecord(hrId);
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

    private Cursor getFinishedHabitRecordCursor(long habitId, int limitCount) {
        String condition = Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS + "=" + habitId
                + " and (" +
                Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FINISHED
                + " or " +
                Def.Database.COLUMN_TYPE_HABIT_RECORDS + "=" + HabitRecord.TYPE_FAKE_FINISHED
                + ")";
        String orderBy = Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS + " desc";
        return db.query(Def.Database.TABLE_HABIT_RECORDS, null,
                condition, null, null, null, orderBy, String.valueOf(limitCount));
    }

    private int getFinishedTimesToday(long habitId) {
        DateTime dt  = new DateTime();
        int curYear  = dt.getYear();
        int curMonth = dt.getMonthOfYear();
        int curDay   = dt.getDayOfMonth();
        int times    = 0;
        Cursor c = getFinishedHabitRecordCursor(habitId, 4);
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            int month = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS));
            int day = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_DAY_HABIT_RECORDS));
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
        Cursor c = getFinishedHabitRecordCursor(habitId, 7);
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            int week = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS));
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
        Cursor c = getFinishedHabitRecordCursor(habitId, 31);
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            int month = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS));
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
        Cursor c = getFinishedHabitRecordCursor(habitId, 12);
        while (c.moveToNext()) {
            int year = c.getInt(c.getColumnIndex(
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS));
            if (year == curYear) {
                times++;
            } else break;
        }
        c.close();
        return times;
    }

    /**
     * Update all data of a habit(habitReminders, habitRecords and alarms) to latest and
     * correct state.
     * This method will be often called for these reasons:
     * 1. User finished a habit once  thus we should update the habit to next alarm;
     * 2. Habit Notification appeared thus we should update the habit to next alarm;
     * 3. We just want to set every alarms(of course including alarms for Habit) again to ensure
     *    that they can still ring. see {@link AlarmHelper#createAllAlarms(Context, boolean)}
     *    for more details.
     *
     * @param id id of the habit
     * @param updateRemindedTimes
     * @param forceToUpdateRemindedTimes
     */
    public void updateHabitToLatest(
            long id, boolean updateRemindedTimes, boolean forceToUpdateRemindedTimes) {
        Habit habit = getHabitById(id);
        if (habit == null) {
            // This may happen if the universe boom, so we should consider it strictly.
            return;
        }

        int recordTimes = habit.getRecord().length();
        if (updateRemindedTimes && forceToUpdateRemindedTimes) {
            // This will prevent this habit from finishing in this T if it was notified but
            // user didn't finish it at once.
            updateHabitRemindedTimes(id, recordTimes);
        }

        List<HabitReminder> habitReminders = habit.getHabitReminders();
        List<Long> hrIds = new ArrayList<>();
        for (HabitReminder habitReminder : habitReminders) {
            hrIds.add(habitReminder.getId());
        }

        habit.initHabitReminders(); // habitReminders have become latest.
        // You may see that Habit#initHabitReminders() will also set member firstTime again,
        // but this makes no change here because we don't update habit to database.

        habitReminders = habit.getHabitReminders();
        final int hrIdsSize = hrIds.size();
        if (hrIdsSize != habitReminders.size()) {
            /**
             * it seems that this cannot happen but it did happen according to a user log.
             * I've tried to solve this problem by ensuring old habit is deleted successfully
             * when updating a habit in DetailActivity.
             * See {@link com.ywwynm.everythingdone.activities.DetailActivity#setOrUpdateHabit(boolean, boolean, boolean)}
             * and {@link #deleteHabit(long)} for more details.
             * Maybe I didn't actually solve it. Let's see if there are more logs about that.
             */
            return;
        }
        for (int i = 0; i < hrIdsSize; i++) {
            long newTime = habitReminders.get(i).getNotifyTime();
            updateHabitReminder(hrIds.get(i), newTime);
        }

        // 将已经提前完成的habitReminder更新至新的周期里
        int habitType = habit.getType();
        List<HabitRecord> habitRecordsThisT = habit.getHabitRecordsThisT();
        for (HabitRecord habitRecord : habitRecordsThisT) {
            HabitReminder hr = getHabitReminderById(habitRecord.getHabitReminderId());
            if (hr != null && DateTimeUtil.calculateTimeGap(
                    System.currentTimeMillis(), hr.getNotifyTime(), habitType) == 0) {
                updateHabitReminderToNext(hr.getId());
            }
        }

        if (updateRemindedTimes && !forceToUpdateRemindedTimes) {
            int remindedTimes = habit.getRemindedTimes();
            if (recordTimes < remindedTimes) {
                long minTime = habit.getMinHabitReminderTime();
                long maxTime = habit.getFinalHabitReminder().getNotifyTime();
                long maxLastTime = DateTimeUtil.getHabitReminderTime(habitType, maxTime, -1);
                long curTime = System.currentTimeMillis();
                if (maxLastTime < curTime && curTime < minTime) {
                    if (DateTimeUtil.calculateTimeGap(maxLastTime, curTime, habitType) != 0) {
                        updateHabitRemindedTimes(id, recordTimes);
                    }
                    // else 用户还能“补”掉这一次未完成的情况，因此不更新remindedTimes
                } else {
                    updateHabitRemindedTimes(id, recordTimes);
                }
            } else if (recordTimes > remindedTimes) {
                updateHabitRemindedTimes(id, recordTimes);
            }
        }
    }

    public void changeHabitRecordsByUser(Habit habit, String recordBefore, String recordAfter) throws Exception {
        char[] arrBefore = recordBefore.toCharArray();
        char[] arrAfter  = recordAfter.toCharArray();
        long habitId = habit.getId();
        habit = getHabitById(habitId);
        final int len = arrBefore.length;
        if (len == 0) return;
        boolean recordEndWith0 = arrBefore[len - 1] == '0';
        List<HabitRecord> habitRecords = getHabitRecordsByHabitId(habitId);
        for (int i = len - 1; i >= 0; i--) {
            if (arrBefore[i] != arrAfter[i]) {
                if (arrBefore[i] == '0') {
                    createFakeFinishedHabitRecord(
                            habit, len, len - i, recordEndWith0);
                } else {
                    int indexFromLast = indexFromLast(recordBefore, arrBefore[i], i);
                    cancelFinishHabitRecord(habitRecords, indexFromLast);
                }
            }
        }
    }

    private void createFakeFinishedHabitRecord(Habit habit, int recordTimes, int indexFromLast, boolean recordEndWith0) throws Exception {
        long habitId = habit.getId();
        List<HabitReminder> habitReminders = getHabitRemindersByHabitId(habitId);
        final int timesEachT = habitReminders.size();

        long curTime = System.currentTimeMillis();
        int nextRemindIndex = timesEachT; // 找出下一个提醒时刻所对应的下标，如果为timesEachT，则说明下一个提醒时刻在下个周期
        for (int i = 0; i < timesEachT; i++) if (habitReminders.get(i).getNotifyTime() < curTime) {
            nextRemindIndex = i;
            break;
        }
        int backFrom; // 从哪一个HabitReminder开始回溯，找到我们需要伪造HabitRecord的对应的HabitReminder
        int preVary = 0;
        if (nextRemindIndex == 0) {
            backFrom = timesEachT - 1;
        } else if (!recordEndWith0 && recordTimes == habit.getRemindedTimes()) {
            backFrom = nextRemindIndex - 1;
        } else {
            backFrom = nextRemindIndex - 2;
            if (backFrom < 0) {
                backFrom += timesEachT;
            }
            preVary--;
        }

        int indexToPreVary = backFrom + 1;
        if (indexToPreVary >= timesEachT) {
            indexToPreVary -= timesEachT;
        }
        HabitReminder hr = habitReminders.get(indexToPreVary);
        int habitType = habit.getType();
        hr.setNotifyTime(DateTimeUtil.getHabitReminderTime(habitType, hr.getNotifyTime(), preVary));

        int i, j;
        for (i = backFrom, j = 1; j <= indexFromLast; j++) {
            hr = habitReminders.get(i);
            hr.setNotifyTime(DateTimeUtil.getHabitReminderTime(habitType, hr.getNotifyTime(), -1));
            i--;
            if (i < 0) {
                i += timesEachT;
            }
        }
        i++;
        if (i >= timesEachT) {
            i -= timesEachT;
        }
        hr = habitReminders.get(i);
        HabitRecord fakeFinishedHabitRecord = new HabitRecord(
                habitId, hr.getId(), hr.getNotifyTime() + 6000);
        fakeFinishedHabitRecord.setType(HabitRecord.TYPE_FAKE_FINISHED);
        createHabitRecord(fakeFinishedHabitRecord);
    }

    /**
     * 给定一个位置的某一字符，得到它是字符串从后往前数的第几个该字符
     * 比如：字符串是011011010，c为1，index为2，那么c就是从后往前数的第4个1
     */
    private int indexFromLast(String src, char c, int index) {
        int lastN = 0;
        char[] arr = src.toCharArray();
        for (int i = arr.length - 1; i >= index; i--) if (arr[i] == c) lastN++;
        return lastN;
    }

    private void cancelFinishHabitRecord(List<HabitRecord> habitRecords, int indexFromLast) {
        final int size = habitRecords.size();
        for (int j = size - 1; j >= 0; j--) {
            if (size - j == indexFromLast) {
                HabitRecord hr = habitRecords.get(j);
                @HabitRecord.Type int type = hr.getType();
                if (type == HabitRecord.TYPE_FINISHED) {
                    hr.setType(HabitRecord.TYPE_CANCEL_FINISHED);
                } else if (type == HabitRecord.TYPE_FAKE_FINISHED) {
                    hr.setType(HabitRecord.TYPE_FAKE_CANCEL_FINISHED);
                }
                updateHabitRecord(hr);
            }
        }
    }

    public void updateHabitRecord(HabitRecord updatedHabitRecord) {
        ContentValues values = getContentValuesFromHabitRecord(updatedHabitRecord, false);
        db.update(Def.Database.TABLE_HABIT_RECORDS, values,
                Def.Database.COLUMN_ID_HABIT_RECORDS + "=" + updatedHabitRecord.getId(), null);
    }

    public void updateRecordOfHabit(long id, String record) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_RECORD_HABITS, record);
        db.update(Def.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void updateHabitRemindedTimes(long id, long remindedTimes) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_REMINDED_TIMES_HABITS, remindedTimes);
        db.update(Def.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void addHabitIntervalInfo(long id, String intervalInfoToAdd) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS,
                getHabitById(id).getIntervalInfo() + intervalInfoToAdd);
        db.update(Def.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void removeLastHabitIntervalInfo(long id) {
        String interval = getHabitById(id).getIntervalInfo();
        interval = interval.substring(0,
                interval.lastIndexOf(interval.endsWith(";") ? "," : ";") + 1);
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS, interval);
        db.update(Def.Database.TABLE_HABITS, values, "id=" + id, null);
    }

    public void updateHabitReminder(long hrId, long notifyTime) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS, notifyTime);
        db.update(Def.Database.TABLE_HABIT_REMINDERS, values, "id=" + hrId, null);
        AlarmHelper.setHabitReminderAlarm(mContext, hrId, notifyTime);
    }

    public void updateHabitReminderToNext(long hrId) {
        HabitReminder habitReminder = getHabitReminderById(hrId);
        Habit habit = getHabitById(habitReminder.getHabitId());
        int type = habit.getType();
        long time = habitReminder.getNotifyTime();

        // do one time before loop if user finish a habit for 1 time in advance
        time = DateTimeUtil.getHabitReminderTime(type, time, 1);
        while (time < System.currentTimeMillis()) {
            time = DateTimeUtil.getHabitReminderTime(type, time, 1);
        }
        updateHabitReminder(hrId, time);
    }

    public void updateHabitReminderToLast(HabitReminder habitReminder) {
        Habit habit = getHabitById(habitReminder.getHabitId());
        int type = habit.getType();
        long time = habitReminder.getNotifyTime();
        updateHabitReminder(habitReminder.getId(),
                DateTimeUtil.getHabitReminderTime(type, time, -1));
    }

    public boolean updateHabit(Habit updatedHabit) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_TYPE_HABITS, updatedHabit.getType());
        values.put(Def.Database.COLUMN_REMINDED_TIMES_HABITS, updatedHabit.getRemindedTimes());
        values.put(Def.Database.COLUMN_DETAIL_HABITS, updatedHabit.getDetail());
        values.put(Def.Database.COLUMN_RECORD_HABITS, updatedHabit.getRecord());
        values.put(Def.Database.COLUMN_INTERVAL_INFO_HABITS, updatedHabit.getIntervalInfo());
        return db.update(Def.Database.TABLE_HABITS, values,
                Def.Database.COLUMN_ID_HABITS + "=" + updatedHabit.getId(), null) == 1;
    }

    public boolean deleteHabit(long id) {
        db.beginTransaction();
        try {
            db.delete(Def.Database.TABLE_HABITS, "id=" + id, null);
            deleteHabitReminders(id);
            deleteHabitRecords(id);
            updateMaxHabitReminderRecordId();
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public void deleteHabitReminders(long habitId) {
        List<HabitReminder> habitReminders = getHabitRemindersByHabitId(habitId);
        for (HabitReminder habitReminder : habitReminders) {
            AlarmHelper.deleteHabitReminderAlarm(mContext, habitReminder.getId());
        }
        db.delete(Def.Database.TABLE_HABIT_REMINDERS, "habit_id=" + habitId, null);
    }

    public void deleteHabitRecords(long habitId) {
        db.delete(Def.Database.TABLE_HABIT_RECORDS, "habit_id=" + habitId, null);
    }

    public void deleteHabitRecord(long hrId) {
        db.delete(Def.Database.TABLE_HABIT_RECORDS, "id=" + hrId, null);
    }

}
