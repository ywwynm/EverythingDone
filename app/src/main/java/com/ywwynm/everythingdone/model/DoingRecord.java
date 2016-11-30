package com.ywwynm.everythingdone.model;

import android.database.Cursor;
import android.support.annotation.IntDef;

import com.ywwynm.everythingdone.services.DoingService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by qiizhang on 2016/11/9.
 * model layer for table "doing_records"
 */
public class DoingRecord {

    public static final int STOP_REASON_CANCEL_CARELESS   = 0;
    public static final int STOP_REASON_CANCEL_NEXT_ALARM = 1;
    public static final int STOP_REASON_CANCEL_USER       = 2;
    public static final int STOP_REASON_CANCEL_OTHER      = 3;
    public static final int STOP_REASON_FINISH            = 4;
    public static final int STOP_REASON_INIT_FAILED       = 5;

    @IntDef({STOP_REASON_CANCEL_CARELESS,
            STOP_REASON_CANCEL_NEXT_ALARM,
            STOP_REASON_CANCEL_USER,
            STOP_REASON_CANCEL_OTHER,
            STOP_REASON_FINISH,
            STOP_REASON_INIT_FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface StopReason {}

    private long id;
    private long thingId;
    private @Thing.Type int thingType;
    private int add5Times;
    private int playedTimes;
    private long totalPlayTime;
    private long predictDoingTime;
    private long startTime;
    private long endTime;
    private @StopReason int stopReason;

    private @DoingService.StartType int startType;
    private boolean shouldAutoStrictMode;

    public DoingRecord(long id,
                       long thingId, int thingType,
                       int add5Times,
                       int playedTimes, long totalPlayTime,
                       long predictDoingTime, long startTime, long endTime,
                       @StopReason int stopReason,
                       @DoingService.StartType int startType,
                       boolean shouldAutoStrictMode) {
        this.id = id;
        this.thingId = thingId;
        this.thingType = thingType;
        this.add5Times = add5Times;
        this.playedTimes = playedTimes;
        this.totalPlayTime = totalPlayTime;
        this.predictDoingTime = predictDoingTime;
        this.startTime = startTime;
        this.endTime = endTime;
        this.stopReason = stopReason;
        this.startType = startType;
        this.shouldAutoStrictMode = shouldAutoStrictMode;
    }

    public DoingRecord(Cursor cursor) {
        this(cursor.getLong(0),
                cursor.getLong(1),
                cursor.getInt(2),
                cursor.getInt(3),
                cursor.getInt(4),
                cursor.getLong(5),
                cursor.getLong(6),
                cursor.getLong(7),
                cursor.getLong(8),
                cursor.getInt(9),
                cursor.getInt(10),
                cursor.getInt(11) != 0);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getThingId() {
        return thingId;
    }

    public void setThingId(long thingId) {
        this.thingId = thingId;
    }

    public int getThingType() {
        return thingType;
    }

    public void setThingType(int thingType) {
        this.thingType = thingType;
    }

    public int getAdd5Times() {
        return add5Times;
    }

    public void setAdd5Times(int add5Times) {
        this.add5Times = add5Times;
    }

    public int getPlayedTimes() {
        return playedTimes;
    }

    public void setPlayedTimes(int playedTimes) {
        this.playedTimes = playedTimes;
    }

    public long getTotalPlayTime() {
        return totalPlayTime;
    }

    public void setTotalPlayTime(long totalPlayTime) {
        this.totalPlayTime = totalPlayTime;
    }

    public long getPredictDoingTime() {
        return predictDoingTime;
    }

    public void setPredictDoingTime(long predictDoingTime) {
        this.predictDoingTime = predictDoingTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public @StopReason int getStopReason() {
        return stopReason;
    }

    public void setStopReason(@StopReason int stopReason) {
        this.stopReason = stopReason;
    }

    public @DoingService.StartType int getStartType() {
        return startType;
    }

    public void setStartType(@DoingService.StartType int startType) {
        this.startType = startType;
    }

    public boolean shouldAutoStrictMode() {
        return shouldAutoStrictMode;
    }

    public void setShouldAutoStrictMode(boolean shouldAutoStrictMode) {
        this.shouldAutoStrictMode = shouldAutoStrictMode;
    }
}
