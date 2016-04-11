package com.ywwynm.everythingdone.model;

import android.database.Cursor;

/**
 * Created by ywwynm on 2016/1/29.
 * model layer. related to table "habit_reminders".
 */
public class HabitReminder {

    private long id;
    private long habitId;
    private long notifyTime;

    public HabitReminder(long id, long habitId, long notifyTime) {
        this.id = id;
        this.habitId = habitId;
        this.notifyTime = notifyTime;
    }

    public HabitReminder(Cursor c) {
        this(c.getLong(0), c.getLong(1), c.getLong(2));
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

    public long getNotifyTime() {
        return notifyTime;
    }

    public void setNotifyTime(long notifyTime) {
        this.notifyTime = notifyTime;
    }
}
