package com.ywwynm.everythingdone.model;

import android.database.Cursor;
import android.support.annotation.IntDef;

import org.joda.time.DateTime;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by ywwynm on 2016/2/11.
 * model layer for table "habit_records"
 */
public class HabitRecord {

    public static final int TYPE_FINISHED = 0;
    public static final int TYPE_CANCEL_FINISHED = 1;
    public static final int TYPE_FAKE_FINISHED = 2;
    public static final int TYPE_FAKE_CANCEL_FINISHED = 3;

    @IntDef(value = {
            TYPE_FINISHED,
            TYPE_CANCEL_FINISHED,
            TYPE_FAKE_FINISHED,
            TYPE_FAKE_CANCEL_FINISHED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    private long id;
    private long habitId;
    private long habitReminderId;
    private long recordTime;
    private int recordYear;
    private int recordMonth;
    private int recordWeek;
    private int recordDay;
    private @Type int type;

    public HabitRecord(long habitId, long habitReminderId) {
        this.id = 0;
        this.habitId = habitId;
        this.habitReminderId = habitReminderId;
        this.recordTime = System.currentTimeMillis();
        DateTime dt = new DateTime(recordTime);
        this.recordYear = dt.getYear();
        this.recordMonth = dt.getMonthOfYear();
        this.recordWeek = dt.getWeekOfWeekyear();
        this.recordDay = dt.getDayOfMonth();
        this.type = TYPE_FINISHED;
    }

    public HabitRecord(long habitId, long habitReminderId, long recordTime) {
        this.id = 0;
        this.habitId = habitId;
        this.habitReminderId = habitReminderId;
        this.recordTime = recordTime;
        DateTime dt = new DateTime(recordTime);
        this.recordYear = dt.getYear();
        this.recordMonth = dt.getMonthOfYear();
        this.recordWeek = dt.getWeekOfWeekyear();
        this.recordDay = dt.getDayOfMonth();
        this.type = TYPE_FINISHED;
    }

    public HabitRecord(long id, long habitId, long habitReminderId, long recordTime,
                       int recordYear, int recordMonth, int recordWeek, int recordDay, @Type int type) {
        this.id = id;
        this.habitId = habitId;
        this.habitReminderId = habitReminderId;
        this.recordTime = recordTime;
        this.recordYear = recordYear;
        this.recordMonth = recordMonth;
        this.recordWeek = recordWeek;
        this.recordDay = recordDay;
        this.type = type;
    }

    public HabitRecord(Cursor c) {
        this(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
                c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8));
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

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }
}
