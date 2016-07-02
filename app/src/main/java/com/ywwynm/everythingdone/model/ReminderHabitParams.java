package com.ywwynm.everythingdone.model;

import android.content.Context;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.utils.DateTimeUtil;

/**
 * Created by ywwynm on 2016/7/1.
 * params of reminder and habit
 */
public class ReminderHabitParams {

    private long   mReminderInMillis;
    private int[]  mReminderAfterTime;
    private int    mHabitType;
    private String mHabitDetail;

    public ReminderHabitParams() {
        mReminderInMillis  = -1;
        mReminderAfterTime = null;
        mHabitType         = -1;
        mHabitDetail       = null;
    }

    public ReminderHabitParams(ReminderHabitParams anotherParams) {
        mReminderInMillis = anotherParams.mReminderInMillis;
        if (anotherParams.mReminderAfterTime != null) {
            mReminderAfterTime = new int[2];
            System.arraycopy(anotherParams.mReminderAfterTime, 0,
                    mReminderAfterTime, 0, mReminderAfterTime.length);
        } else {
            mReminderAfterTime = null;
        }
        mHabitType   = anotherParams.mHabitType;
        mHabitDetail = anotherParams.mHabitDetail;
    }

    public long getReminderTime() {
        if (mReminderInMillis != -1) {
            return mReminderInMillis;
        }
        if (mReminderAfterTime != null) {
            return DateTimeUtil.getActualTimeAfterSomeTime(mReminderAfterTime);
        }
        return -1;
    }

    public void reset() {
        mReminderInMillis = -1;
        mReminderAfterTime = null;
        mHabitType = -1;
        mHabitDetail = null;
    }

    public String getDateTimeStr() {
        Context context = App.getApp();
        if (mReminderInMillis != -1) {
            return DateTimeUtil.getDateTimeStrAt(mReminderInMillis, context, false);
        } else if (mReminderAfterTime != null) {
            return DateTimeUtil.getDateTimeStrAfter(
                    mReminderAfterTime[0], mReminderAfterTime[1], context);
        } else {
            return DateTimeUtil.getDateTimeStrRec(context, mHabitType, mHabitDetail);
        }
    }

    public long getReminderInMillis() {
        return mReminderInMillis;
    }

    public void setReminderInMillis(long reminderInMillis) {
        this.mReminderInMillis = reminderInMillis;
    }

    public int[] getReminderAfterTime() {
        return mReminderAfterTime;
    }

    public void setReminderAfterTime(int[] reminderAfterTime) {
        this.mReminderAfterTime = reminderAfterTime;
    }

    public int getHabitType() {
        return mHabitType;
    }

    public void setHabitType(int habitType) {
        this.mHabitType = habitType;
    }

    public String getHabitDetail() {
        return mHabitDetail;
    }

    public void setHabitDetail(String habitDetail) {
        this.mHabitDetail = habitDetail;
    }


}
