package com.ywwynm.everythingdone.model;

import android.database.Cursor;

import org.joda.time.DateTime;

/**
 * Created by ywwynm on 2016/2/11.
 * model layer for table "habit_records"
 */
public class HabitRecord {

    private long id;
    private long habitId;
    private long habitReminderId;
    private long recordTime;
    private int recordYear;
    private int recordMonth;
    private int recordWeek;
    private int recordDay;

    public HabitRecord(long id, long habitId, long habitReminderId) {
        this.id = id;
        this.habitId = habitId;
        this.habitReminderId = habitReminderId;
        this.recordTime = System.currentTimeMillis();
        DateTime dt = new DateTime(recordTime);
        this.recordYear = dt.getYear();
        this.recordMonth = dt.getMonthOfYear();
        this.recordWeek = dt.getWeekOfWeekyear();
        this.recordDay = dt.getDayOfMonth();
    }

    public HabitRecord(long id, long habitId, long habitReminderId, long recordTime,
                       int recordYear, int recordMonth, int recordWeek, int recordDay) {
        this.id = id;
        this.habitId = habitId;
        this.habitReminderId = habitReminderId;
        this.recordTime = recordTime;
        this.recordYear = recordYear;
        this.recordMonth = recordMonth;
        this.recordWeek = recordWeek;
        this.recordDay = recordDay;
    }

    public HabitRecord(Cursor c) {
        this(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
                c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6));
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getHabitId() {
        return habitId;
    }

    public void setHabitId(long habitId) {
        this.habitId = habitId;
    }

    public long getHabitReminderId() {
        return habitReminderId;
    }

    public void setHabitReminderId(long habitReminderId) {
        this.habitReminderId = habitReminderId;
    }

    public long getRecordTime() {
        return recordTime;
    }

    public void setRecordTime(long recordTime) {
        this.recordTime = recordTime;
    }

    public int getRecordYear() {
        return recordYear;
    }

    public void setRecordYear(int recordYear) {
        this.recordYear = recordYear;
    }

    public int getRecordMonth() {
        return recordMonth;
    }

    public void setRecordMonth(int recordMonth) {
        this.recordMonth = recordMonth;
    }

    public int getRecordWeek() {
        return recordWeek;
    }

    public void setRecordWeek(int recordWeek) {
        this.recordWeek = recordWeek;
    }

    public int getRecordDay() {
        return recordDay;
    }

    public void setRecordDay(int recordDay) {
        this.recordDay = recordDay;
    }
}
