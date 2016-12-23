package com.ywwynm.everythingdone.services;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.DoingRecordDAO;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.GregorianCalendar;

/**
 * Created by qiizhang on 2016/11/2.
 * A Service to control countdown of a thing that user is currently doing
 */
public class DoingService extends Service {

    public static final String TAG = "DoingService";

    public static final int STATE_DOING             = 0;
    public static final int STATE_FAILED_CARELESS   = 1;
    public static final int STATE_FAILED_NEXT_ALARM = 2;

    @IntDef({STATE_DOING, STATE_FAILED_CARELESS, STATE_FAILED_NEXT_ALARM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    public static @DoingRecord.StopReason int sStopReason = DoingRecord.STOP_REASON_CANCEL_USER;

    public static boolean sSendBroadcastToUpdateMainUi = true;

    public static boolean sResetDoingIdInOnDestroy = true;

    public static final String KEY_START_TIME     = "start_time";
    public static final String KEY_TIME_IN_MILLIS = "time_in_millis";
    public static final String KEY_START_TYPE     = "start_type";

    public static final int START_TYPE_ALARM = 0;
    public static final int START_TYPE_AUTO  = 1;
    public static final int START_TYPE_USER  = 2;

    @IntDef({START_TYPE_ALARM, START_TYPE_AUTO, START_TYPE_USER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartType {}

    private static final long MINUTE_MILLIS = 60 * 1000L;
    private static final long HOUR_MILLIS   = 60 * MINUTE_MILLIS;

    public static Intent getOpenIntent(
            Context context, Thing thing, long startTime, long timeInMillis,
            @StartType int startType, long hrTime) {
        return new Intent(context, DoingService.class)
                .putExtra(Def.Communication.KEY_THING, thing)
                .putExtra(KEY_START_TIME, startTime)
                .putExtra(KEY_TIME_IN_MILLIS, timeInMillis)
                .putExtra(KEY_START_TYPE, startType)
                .putExtra(Def.Communication.KEY_TIME, hrTime);
    }

    public interface DoingListener {
        void onLeftTimeChanged(int[] numbersFrom, int[] numbersTo, long leftTimeBefore, long leftTimeAfter);
        void onAdd5Min(long leftTime);
        void onCountdownFailed();
        void onCountdownEnd();
    }
    private DoingListener mDoingListener;

    private DoingBinder mBinder;

    private @StartType int mStartType;
    private boolean mShouldAutoStrictMode;

    private Thing mThing;
    private Habit mHabit;

    private long mTimeInMillis;
    private long mPredictDoingTime;
    private long mStartTime;
    private long mLeftTime;
    private long mEndTime;

    public static long sHrTime = -1;

    private int[] mTimeNumbers = { -1, -1, -1, -1, -1, -1 };

    private int mAdd5MinTimes = 0;
    private int mTotalAdd5MinTimes = 0;

    private boolean mInStrictMode = false;
    private int mPlayedTimes = 0;
    private long mStartPlayTime = -1L;
    private long mTotalPlayedTime = 0;
    private boolean mHasTurnedStrictModeOn = false;
    private boolean mHasTurnedStrictModeOff = false;

    private boolean mCarelessWarned = false;

    private boolean mStartHighlighted = false;
    private boolean mEndHighlighted = false;

    private PowerManager.WakeLock mWakeLock;

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == 96) {
                Log.i(TAG, "User is doing something, counting down, "
                        + "mAdd5MinTimes[" + mAdd5MinTimes + "], "
                        + "mLeftTimeBefore[" + mLeftTime + "], "
                        + "mTimeInMillisBefore[" + mTimeInMillis + "]");

                long leftTimeBefore = mLeftTime;
                handleAdd5Min();

                if (mLeftTime > 0) {
                    handleLeftTimeChange(leftTimeBefore);
                }

                if (mStartPlayTime != -1) {
                    mTotalPlayedTime += 1000;
                }

                boolean carelessCon1 = mPlayedTimes >= 3;
                boolean carelessCon2 = mTotalPlayedTime >= 5 * MINUTE_MILLIS;
                boolean careless = carelessCon1 || carelessCon2;
                @State int doingState = careless ? STATE_FAILED_CARELESS : STATE_DOING;

                Notification notification = SystemNotificationUtil.createDoingNotification(
                        DoingService.this, mThing, doingState, getLeftTimeStr(), sHrTime,
                        getHighlightStrategy(careless));
                mStartHighlighted = true;
                startForeground((int) mThing.getId(), notification);

                Log.i(TAG, "mLeftTimeAfter[" + mLeftTime + "], "
                        + "mTimeInMillisAfter[" + mTimeInMillis + "], "
                        + "leftTimeStr[" + getLeftTimeStr() + "], "
                        + "doingState[" + doingState + "], "
                        + "mStartPlayTime[" + DateTimeUtil.getGeneralDateTimeStr(DoingService.this, mStartPlayTime) + "], "
                        + "mPlayedTimes[" + mPlayedTimes + "], "
                        + "mTotalPlayedTime[" + mTotalPlayedTime + "]");

                if (careless) {
                    handleCareless();
                }

                if (mLeftTime == 0) {
                    handleCountdownEnd();
                }

                if (doingState == STATE_DOING) {
                    mHandler.sendEmptyMessageDelayed(96, 1000);
                    if (mWakeLock != null && !mWakeLock.isHeld()) {
                        mWakeLock.acquire();
                    }
                } else if (mWakeLock != null && mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                return true;
            }
            return false;
        }
    });

    private void handleAdd5Min() {
        if (mAdd5MinTimes != 0 && mLeftTime == 0) {
            // Countdown stopped but we want to add 5 more minutes. Current numbers are all 0
            // and we want to start from 05:00, as a result, we should add another 1 second.
            mLeftTime += 1000;

            // also reset mEndHighlighted
            mEndHighlighted = false;
        }
        for (int i = 1; i <= mAdd5MinTimes; i++) {
            mLeftTime += 5 * MINUTE_MILLIS;
            mTimeInMillis += 5 * MINUTE_MILLIS;
        }
        if (mAdd5MinTimes != 0 && mDoingListener != null) {
            mDoingListener.onAdd5Min(mLeftTime);
        }
        mAdd5MinTimes = 0;
    }

    private void handleLeftTimeChange(long leftTimeBefore) {
        int[] from = new int[6];
        System.arraycopy(mTimeNumbers, 0, from, 0, 6);
        mLeftTime -= 1000;
        calculateTimeNumbers(mLeftTime);
        if (mDoingListener != null) {
            mDoingListener.onLeftTimeChanged(from, mTimeNumbers, leftTimeBefore, mLeftTime);
        }
    }

    private void handleCareless() {
        App.setDoingThingId(-1L);
        RemoteActionHelper.doingOrCancel(DoingService.this, mThing);
        mEndTime = System.currentTimeMillis();
        sStopReason = DoingRecord.STOP_REASON_CANCEL_CARELESS;

        if (!mCarelessWarned) {
            Toast.makeText(DoingService.this, R.string.doing_failed_careless,
                    Toast.LENGTH_LONG).show();
            mCarelessWarned = true;
        }
        if (mDoingListener != null) {
            mDoingListener.onCountdownFailed();
        }
    }

    private void handleCountdownEnd() {
        mEndHighlighted = true;
        if (mDoingListener != null) {
            mDoingListener.onCountdownEnd();
        }
    }

    private int getHighlightStrategy(boolean careless) {
        if (mStartType == START_TYPE_AUTO && !mStartHighlighted) {
            return 1;
        }
        if (careless && !mCarelessWarned) {
            return 2;
        }
        if (mLeftTime == 0 && !mEndHighlighted) {
            return 2;
        }
        return 0;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) {
            mBinder = new DoingBinder();
        }
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mDoingListener = null;
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand() start");
        if (intent == null) {
            stopSelf(startId);
            return super.onStartCommand(null, flags, startId);
        }

        Thing thing = intent.getParcelableExtra(Def.Communication.KEY_THING);
        if (thing == null) {
            stopSelf(startId);
            return super.onStartCommand(null, flags, startId);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mThing = new Thing(thing);

        if (mThing.getType() == Thing.HABIT) {
            mHabit = HabitDAO.getInstance(getApplicationContext()).getHabitById(mThing.getId());
        }

        mTimeInMillis = intent.getLongExtra(KEY_TIME_IN_MILLIS, -1L);
        mPredictDoingTime = mTimeInMillis;
        mStartTime = intent.getLongExtra(KEY_START_TIME, -1L);
        mEndTime = -1;

        if (mTimeInMillis == -1) {
            mLeftTime = -1;
        } else {
            if (System.currentTimeMillis() - mStartTime < 6 * 1000L) {
                mLeftTime = mTimeInMillis / 1000L * 1000L;
            } else {
                mLeftTime = (mStartTime + mTimeInMillis - System.currentTimeMillis()) / 1000L * 1000L;
            }
        }

        sHrTime = intent.getLongExtra(Def.Communication.KEY_TIME, -1L);

        mAdd5MinTimes = 0;
        mTotalAdd5MinTimes = 0;

        mStartType = intent.getIntExtra(KEY_START_TYPE, START_TYPE_ALARM);
        ThingDoingHelper helper = new ThingDoingHelper(this, mThing);
        mShouldAutoStrictMode = helper.shouldAutoStrictMode();

        Log.i(TAG, "start counting down, mPredictDoingTime[" + mPredictDoingTime + "], "
                + "mStartTime[" + DateTimeUtil.getGeneralDateTimeStr(this, mStartTime) + "], "
                + "mThing.type[" + mThing.getType() + "], "
                + "sHrTime[" + DateTimeUtil.getGeneralDateTimeStr(this, sHrTime) + "], "
                + "mStartType[" + mStartType + "], "
                + "mShouldAutoStrictMode[" + mShouldAutoStrictMode + "]");

        mInStrictMode = mShouldAutoStrictMode;
        mPlayedTimes = 0;
        mStartPlayTime = -1L;

        mCarelessWarned = false;

        sStopReason = DoingRecord.STOP_REASON_CANCEL_USER;
        sSendBroadcastToUpdateMainUi = true;
        sResetDoingIdInOnDestroy = true;

        Log.i(TAG, "onStartCommand() end");

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy() start");

        if (sResetDoingIdInOnDestroy) {
            App.setDoingThingId(-1L);
        }
        mHandler.removeMessages(96);
        stopForeground(true);

        if (mEndTime == -1L) {
            mEndTime = System.currentTimeMillis();
        }

        if (mThing != null) {
            DoingRecord doingRecord = new DoingRecord(-1, mThing.getId(), mThing.getType(),
                    mTotalAdd5MinTimes, mPlayedTimes, mTotalPlayedTime,
                    mPredictDoingTime, mStartTime, mEndTime, sStopReason,
                    mStartType, mShouldAutoStrictMode);
            DoingRecordDAO.getInstance(this).insert(doingRecord);

            if (sSendBroadcastToUpdateMainUi) {
                RemoteActionHelper.doingOrCancel(this, mThing);
            }
        }

        sHrTime = -1;

        mThing = null;
        mHandler = null;
        mDoingListener = null;

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mWakeLock = null;

        Log.i(TAG, "onDestroy() end");
    }

    private void setDoingListener(DoingListener listener) {
        mDoingListener = listener;
    }

    private void startCountDown(boolean resume) {
        if (resume) {
            mHandler.removeMessages(96);
            for (int i = 0; i < mTimeNumbers.length; i++) {
                mTimeNumbers[i] = -1;
            }
            if (mLeftTime == 0) {
                // countdown stopped but we resumed DoingActivity, so at least play animation to
                // show timely views
                mLeftTime = 1000;
            }
        } else if (mTimeInMillis != -1) {
            mLeftTime += 1000;
        }
        mAdd5MinTimes = 0;
        mHandler.sendEmptyMessageDelayed(96, 1000);
    }

    private Thing getThing() {
        return mThing;
    }

    private void setThing(Thing thing) {
        mThing = thing;
    }

    private long getLeftTime() {
        return mLeftTime;
    }

    private long getTimeInMillis() {
        return mTimeInMillis;
    }

    private void calculateTimeNumbers(long leftTime) {
        long hours = leftTime / HOUR_MILLIS;
        if (hours > 99) hours = 99;
        mTimeNumbers[0] = (int) (hours / 10);
        mTimeNumbers[1] = (int) (hours % 10);

        leftTime %= HOUR_MILLIS;
        long minutes = leftTime / MINUTE_MILLIS;
        mTimeNumbers[2] = (int) (minutes / 10);
        mTimeNumbers[3] = (int) (minutes % 10);

        leftTime %= MINUTE_MILLIS;
        long seconds = leftTime / 1000;
        mTimeNumbers[4] = (int) (seconds / 10);
        mTimeNumbers[5] = (int) (seconds % 10);
    }

    private void add5Min() {
        mAdd5MinTimes++;
        mTotalAdd5MinTimes++;
    }

    private boolean canAdd5Min() {
        if (mTimeInMillis == -1) {
            return false; // Your time is already infinite, why would you like 5 more minutes?
        }

        long leftTime = mLeftTime + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1);
        if (leftTime / HOUR_MILLIS > 99) {
            Toast.makeText(this, R.string.doing_toast_add5_above99, Toast.LENGTH_LONG).show();
            return false;
        }

        if (mHabit != null) {
            long etc;
            if (mLeftTime == 0) { // countdown is over
                etc = System.currentTimeMillis() + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1);
            } else {
                etc = mStartTime + mTimeInMillis + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1);
            }
            int habitType = mHabit.getType();
            GregorianCalendar calendar = new GregorianCalendar();
            int ct = calendar.get(habitType); // current t
            calendar.setTimeInMillis(etc);
            if (calendar.get(habitType) != ct) {
                Toast.makeText(this, R.string.doing_toast_add5_time_long_t,
                        Toast.LENGTH_LONG).show();
                return false;
            } else {
                long nextTime = mHabit.getDoingEndLimitTime();
                if (etc >= nextTime - ThingDoingHelper.TIME_BEFORE_NEXT_HABIT_REMINDER) {
                    Toast.makeText(this, R.string.doing_toast_add5_time_long_alarm,
                            Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isInStrictMode() {
        return mInStrictMode;
    }

    private void setInStrictMode(boolean inStrictMode) {
        mInStrictMode = inStrictMode;
    }

    private int getPlayedTimes() {
        return mPlayedTimes;
    }

    private void setPlayedTimes(int playedTimes) {
        mPlayedTimes = playedTimes;
    }

    private long getStartPlayTime() {
        return mStartPlayTime;
    }

    private void setStartPlayTime(long startPlayTime) {
        mStartPlayTime = startPlayTime;
    }

    private void setTotalPlayedTime(long totalPlayedTime) {
        mTotalPlayedTime = totalPlayedTime;
    }

    private boolean hasTurnedStrictModeOn() {
        return mHasTurnedStrictModeOn;
    }

    private boolean hasTurnedStrictModeOff() {
        return mHasTurnedStrictModeOff;
    }

    private String getLeftTimeStr() {
        if (mTimeInMillis == -1) {
            return getString(R.string.infinity);
        } else {
            if (mTimeNumbers[0] == -1) {
                return "00:00:00";
            } else {
                return mTimeNumbers[0] + "" + mTimeNumbers[1] + ":"
                        + mTimeNumbers[2] + "" + mTimeNumbers[3] + ":"
                        + mTimeNumbers[4] + "" + mTimeNumbers[5];
            }
        }
    }

    public class DoingBinder extends Binder {

        public void setCountdownListener(DoingListener listener) {
            DoingService.this.setDoingListener(listener);
        }

        public void startCountdown(boolean resume) {
            DoingService.this.startCountDown(resume);
        }

        public Thing getThing() {
            return DoingService.this.getThing();
        }

        public void setThing(Thing thing) {
            DoingService.this.setThing(thing);
        }

        public long getLeftTime() {
            return DoingService.this.getLeftTime();
        }

        public long getTimeInMillis() {
            return DoingService.this.getTimeInMillis();
        }

        public boolean canAdd5Min() {
            return DoingService.this.canAdd5Min();
        }

        public void add5Min() {
            DoingService.this.add5Min();
        }

        public boolean isInStrictMode() {
            return DoingService.this.isInStrictMode();
        }

        public void setInStrictMode(boolean inStrictMode) {
            if (!inStrictMode) {
                mHasTurnedStrictModeOff = true;
            } else {
                mHasTurnedStrictModeOn = true;
            }
            DoingService.this.setInStrictMode(inStrictMode);
        }

        public int getPlayedTimes() {
            return DoingService.this.getPlayedTimes();
        }

        public void setPlayedTimes(int playedTimes) {
            DoingService.this.setPlayedTimes(playedTimes);
        }

        public long getStartPlayTime() {
            return DoingService.this.getStartPlayTime();
        }

        public void setStartPlayTime(long startPlayTime) {
            DoingService.this.setStartPlayTime(startPlayTime);
        }

        public void setTotalPlayedTime(long totalPlayedTime) {
            DoingService.this.setTotalPlayedTime(totalPlayedTime);
        }

        public boolean hasTurnedStrictModeOn() {
            return DoingService.this.hasTurnedStrictModeOn();
        }

        public boolean hasTurnedStrictModeOff() {
            return DoingService.this.hasTurnedStrictModeOff();
        }
    }
}
