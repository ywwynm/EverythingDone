package com.ywwynm.everythingdone.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
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

    public static final String KEY_START_TIME     = "start_time";
    public static final String KEY_TIME_IN_MILLIS = "time_in_millis";

    private static final long MINUTE_MILLIS = 60 * 1000L;
    private static final long HOUR_MILLIS   = 60 * MINUTE_MILLIS;

    public static Intent getOpenIntent(Context context, Thing thing, long startTime, long timeInMillis) {
        return new Intent(context, DoingService.class)
                .putExtra(Def.Communication.KEY_THING, thing)
                .putExtra(KEY_START_TIME, startTime)
                .putExtra(KEY_TIME_IN_MILLIS, timeInMillis);
    }

    public interface DoingListener {
        void onLeftTimeChanged(int[] numbersFrom, int[] numbersTo, long leftTimeBefore, long leftTimeAfter);
        void onAdd5Min(long leftTime);
        void onCountdownFailed();
        void onCountdownEnd();
    }
    private DoingListener mDoingListener;

    private DoingBinder mBinder;

    private Thing mThing;
    private Habit mHabit;

    private long mTimeInMillis;
    private long mStartTime;
    private long mLeftTime;

    private int[] mTimeNumbers = { -1, -1, -1, -1, -1, -1 };

    private int mAdd5MinTimes = 0;

    private boolean mInStrictMode = false;
    private int mPlayedTimes = 0;
    private long mStartPlayTime = -1L;
    private long mTotalPlayedTime = 0;
    private boolean mHasTurnedStrictModeOn = false;
    private boolean mHasTurnedStrictModeOff = false;

    private boolean mCarelessToasted = false;

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == 96) {
                Log.i(TAG, "User is doing something, counting down, "
                        + "mAdd5MinTimes[" + mAdd5MinTimes + "], "
                        + "mLeftTimeBefore[" + mLeftTime + "], "
                        + "mTimeInMillisBefore[" + mTimeInMillis + "]");

                long leftTimeBefore = mLeftTime;
                if (mAdd5MinTimes != 0 && mLeftTime == 0) {
                    // Countdown stopped but we want to add 5 more minutes. Current numbers are all 0
                    // and we want to start from 05:00, as a result, we should add another 1 second.
                    mLeftTime += 1000;
                }
                for (int i = 1; i <= mAdd5MinTimes; i++) {
                    mLeftTime += 5 * MINUTE_MILLIS;
                    mTimeInMillis += 5 * MINUTE_MILLIS;
                }
                if (mAdd5MinTimes != 0 && mDoingListener != null) {
                    mDoingListener.onAdd5Min(mLeftTime);
                }
                mAdd5MinTimes = 0;

                if (mLeftTime > 0) {
                    int[] from = new int[6];
                    System.arraycopy(mTimeNumbers, 0, from, 0, 6);
                    mLeftTime -= 1000;
                    calculateTimeNumbers(mLeftTime);
                    if (mDoingListener != null) {
                        mDoingListener.onLeftTimeChanged(from, mTimeNumbers, leftTimeBefore, mLeftTime);
                    }
                }

                if (mStartPlayTime != -1) {
                    mTotalPlayedTime += 1000;
                }

                boolean carelessCon1 = mPlayedTimes >= 3;
                boolean carelessCon2 = mTotalPlayedTime >= 5 * MINUTE_MILLIS;
                boolean careless = carelessCon1 || carelessCon2;
                @State int doingState = careless ? STATE_FAILED_CARELESS : STATE_DOING;
                startForeground((int) mThing.getId(),
                        SystemNotificationUtil.createDoingNotification(
                                DoingService.this, mThing, doingState, getLeftTimeStr()));

                Log.i(TAG, "mLeftTimeAfter[" + mLeftTime + "], "
                        + "mTimeInMillisAfter[" + mTimeInMillis + "], "
                        + "leftTimeStr[" + getLeftTimeStr() + "], "
                        + "doingState[" + doingState + "], "
                        + "mStartPlayTime[" + mStartPlayTime + "], "
                        + "mPlayedTimes[" + mPlayedTimes + "], "
                        + "mTotalPlayedTime[" + mTotalPlayedTime + "]");

                if (careless) {
                    if (!mCarelessToasted) {
                        Toast.makeText(DoingService.this, R.string.doing_failed_careless,
                                Toast.LENGTH_LONG).show();
                        mCarelessToasted = true;
                    }
                    if (mDoingListener != null) {
                        mDoingListener.onCountdownFailed();
                    }
                }

                if (mLeftTime <= 0 && mDoingListener != null) {
                    mDoingListener.onCountdownEnd();
                }
                mHandler.sendEmptyMessageDelayed(96, 1000);
                return true;
            }
            return false;
        }
    });

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) {
            mBinder = new DoingBinder();
        }
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate()");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand()");
        mThing = intent.getParcelableExtra(Def.Communication.KEY_THING);
        if (mThing.getType() == Thing.HABIT) {
            mHabit = HabitDAO.getInstance(getApplicationContext()).getHabitById(mThing.getId());
        }

        mTimeInMillis = intent.getLongExtra(KEY_TIME_IN_MILLIS, -1L);
        mStartTime    = intent.getLongExtra(KEY_START_TIME, -1L);

        if (mTimeInMillis == -1) {
            mLeftTime = -1;
        } else {
            if (System.currentTimeMillis() - mStartTime < 6 * 1000L) {
                mLeftTime = mTimeInMillis / 1000L * 1000L;
            } else {
                mLeftTime = (mStartTime + mTimeInMillis - System.currentTimeMillis()) / 1000L * 1000L;
            }
        }

        mAdd5MinTimes = 0;

        mInStrictMode = false; // TODO: 2016/11/3 read from settings
        mPlayedTimes = 0;
        mStartPlayTime = -1L;

        mCarelessToasted = false;

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");

        App.setDoingThingId(-1L);
        mHandler.removeMessages(96);
        stopForeground(true);

        mThing = null;
        mHandler = null;
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
                long nextTime = mHabit.getMinHabitReminderTime();
                if (etc >= nextTime - 6 * MINUTE_MILLIS) {
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
