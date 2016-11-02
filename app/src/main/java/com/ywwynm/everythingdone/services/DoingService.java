package com.ywwynm.everythingdone.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DoingActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.util.GregorianCalendar;

/**
 * Created by qiizhang on 2016/11/2.
 * A Service to control countdown of a thing that user is currently doing
 */
public class DoingService extends Service {

    public static final String TAG = "DoingService";

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

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == 96) {
                long leftTimeBefore = mLeftTime;
                for (int i = 1; i <= mAdd5MinTimes; i++) {
                    mLeftTime += 5 * MINUTE_MILLIS;
                    mTimeInMillis += 5 * MINUTE_MILLIS;
                }
                if (mAdd5MinTimes != 0 && mDoingListener != null) {
                    mDoingListener.onAdd5Min(mLeftTime);
                }
                mAdd5MinTimes = 0;

                int[] from = new int[6];
                System.arraycopy(mTimeNumbers, 0, from, 0, 6);
                mLeftTime -= 1000;
                calculateTimeNumbers(mLeftTime);
                if (mDoingListener != null) {
                    mDoingListener.onLeftTimeChanged(from, mTimeNumbers, leftTimeBefore, mLeftTime);
                }

                Log.i(TAG, "User is doing something, counting down, left time is "
                        + numbersToString());

                startForeground((int) mThing.getId(), createNotification());

                if (mLeftTime > 0) {
                    mHandler.sendEmptyMessageDelayed(96, 1000);
                } else {
                    if (mDoingListener != null) {
                        mDoingListener.onCountdownEnd();
                    }
                }
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

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
        mHandler.removeMessages(96);
        stopSelf();
    }

    private Notification createNotification() {
        @Thing.Type int thingType = mThing.getType();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setColor(mThing.getColor())
                .setSmallIcon(Thing.getTypeIconWhiteLarge(thingType))
                .setContentTitle(getNotificationTitle())
                .setContentText(getString(R.string.doing_left_time) + " " + numbersToString());

        Intent contentIntent = DoingActivity.getOpenIntent(this, true);
        builder.setContentIntent(PendingIntent.getActivity(
                this, (int) mThing.getId(), contentIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        return builder.build();
    }

    private String getNotificationTitle() {
        StringBuilder nTitle = new StringBuilder(getString(R.string.doing_currently_doing));
        nTitle.append(" ");
        String thingTitle = mThing.getTitleToDisplay();
        if (!thingTitle.isEmpty()) {
            nTitle.append(thingTitle);
        } else {
            if (mThing.isPrivate()) {
                nTitle.append(getString(R.string.private_thing));
                return nTitle.toString();
            }
            String thingContent = mThing.getContent();
            if (!thingContent.isEmpty()) {
                if (CheckListHelper.isCheckListStr(thingContent)) {
                    thingContent = CheckListHelper.toContentStr(thingContent, "X ", "√ ");
                    thingContent = thingContent.replaceAll("\n", "\n  ");
                }
                nTitle.append(thingContent);
            } else {
                // there should be attachment
                String attachment = mThing.getAttachment();
                if (!attachment.isEmpty() && !"to QQ".equals(attachment)) {
                    String imgStr = AttachmentHelper.getImageAttachmentCountStr(attachment, this);
                    if (imgStr != null) {
                        nTitle.append(imgStr).append(", ");
                    }
                    String audStr = AttachmentHelper.getAudioAttachmentCountStr(attachment, this);
                    if (audStr != null) {
                        nTitle.append(audStr);
                    }
                }
            }
        }
        return nTitle.toString();
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
        } else {
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

    private void addLeftTime(long add) {
        mLeftTime += add;
    }

    public long getTimeInMillis() {
        return mTimeInMillis;
    }

    private void addTimeInMillis(long add) {
        mTimeInMillis += add;
    }

    private void calculateTimeNumbers(long leftTime) {
        long hours = leftTime / HOUR_MILLIS;
        if (hours > 100) hours = 99;
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

    private int[] getTimeNumbers() {
        return mTimeNumbers;
    }

    private void add5Min() {
        mAdd5MinTimes++;
    }

    private boolean canAdd5Min() {
        if (mLeftTime <= 0) {
            return false;
        }
        if (mHabit != null) {
            long etc = mStartTime + mTimeInMillis + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1);
            int habitType = mHabit.getType();
            GregorianCalendar calendar = new GregorianCalendar();
            int ct = calendar.get(habitType); // current t
            calendar.setTimeInMillis(etc);
            if (calendar.get(habitType) != ct) {
                Toast.makeText(getApplicationContext(),
                        R.string.start_doing_time_long_t, Toast.LENGTH_LONG).show();
                return false;
            } else {
                long nextTime = mHabit.getMinHabitReminderTime();
                if (etc >= nextTime - 6 * MINUTE_MILLIS) {
                    Toast.makeText(getApplicationContext(),
                            R.string.start_doing_time_long_alarm, Toast.LENGTH_LONG).show();
                }
                return false;
            }
        }
        return true;
    }

    private String numbersToString() {
        if (mTimeNumbers[0] == -1) {
            return "-1";
        } else {
            return mTimeNumbers[0] + "" + mTimeNumbers[1] + ":"
                 + mTimeNumbers[2] + "" + mTimeNumbers[3] + ":"
                 + mTimeNumbers[4] + "" + mTimeNumbers[5];
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
    }
}
