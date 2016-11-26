package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.StringRes;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DoingActivity;
import com.ywwynm.everythingdone.activities.StartDoingActivity;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DateTimeUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Created by ywwynm on 2016/11/22.
 * A helper class to restore and get thing's doing strategy
 */
public class ThingDoingHelper {

    public static final String TAG = "ThingDoingHelper";

    public static final long TIME_BEFORE_NEXT_HABIT_REMINDER
            = BuildConfig.DEBUG ? 0 : 5 * 60 * 1000L;

    public static int KEY_INDEX_AUTO_START_DOING               = 0;

    public static int AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL = 0;
    public static int AUTO_START_DOING_STRATEGY_ENABLED        = 1;
    public static int AUTO_START_DOING_STRATEGY_DISABLED       = 2;

    public static int SYS_AUTO_START_DOING_STRATEGY_DISABLED   = 0;
    public static int SYS_AUTO_START_DOING_STRATEGY_REMINDER   = 1;
    public static int SYS_AUTO_START_DOING_STRATEGY_HABIT      = 2;
    public static int SYS_AUTO_START_DOING_STRATEGY_ALL        = 3;


    public static int KEY_INDEX_AUTO_STRICT_MODE               = 1;

    public static int AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL = 0;
    public static int AUTO_STRICT_MODE_STRATEGY_ENABLED        = 1;
    public static int AUTO_STRICT_MODE_STRATEGY_DISABLED       = 2;

    public static int SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED   = 0;
    public static int SYS_AUTO_STRICT_MODE_STRATEGY_REMINDER   = 1;
    public static int SYS_AUTO_STRICT_MODE_STRATEGY_HABIT      = 2;
    public static int SYS_AUTO_STRICT_MODE_STRATEGY_ALL        = 3;


    public static int KEY_INDEX_AUTO_START_DOING_TIME          = 2;


    public static final String START_DOING_TIME_FOLLOW_GENERAL_PICKED = "-2,-2";
    public static final String START_DOING_TIME_NOT_SURE_PICKED       = "-1,-1";

    private Context mContext;
    private Thing mThing;

    private SharedPreferences mSpStartDoing;
    private SharedPreferences mSpSettings;

    public ThingDoingHelper(Context context, Thing thing) {
        mContext = context;
        mThing = thing;

        mSpStartDoing = context.getSharedPreferences(
                Def.Meta.DOING_STRATEGY_NAME, Context.MODE_PRIVATE);
        mSpSettings = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static Pair<List<Integer>, List<Integer>> getStartDoingTypeTimes(
            boolean addFollowGeneral) {
        List<Integer> types = new ArrayList<>();
        List<Integer> times = new ArrayList<>();

        if (addFollowGeneral) {
            types.add(-2);
            types.add(-2);
        }

        types.add(-1);
        times.add(-1);

        if (BuildConfig.DEBUG) {
            types.add(Calendar.SECOND);
            times.add(6);
        }

        types.add(Calendar.MINUTE);
        types.add(Calendar.MINUTE);
        types.add(Calendar.MINUTE);
        types.add(Calendar.HOUR_OF_DAY);
        types.add(Calendar.MINUTE);
        types.add(Calendar.HOUR_OF_DAY);
        types.add(Calendar.HOUR_OF_DAY);
        types.add(Calendar.HOUR_OF_DAY);

        times.add(15);
        times.add(30);
        times.add(45);
        times.add(1);
        times.add(90);
        times.add(2);
        times.add(3);
        times.add(4);

        return new Pair<>(types, times);
    }

    /**
     * Not sure
     * 6  seconds(DEBUG)
     * 15 minutes
     * 30 minutes
     * 45 minutes
     * 1  hour
     * 90 minutes
     * 2  hours
     * 3  hours
     * 4  hours
     */
    public static List<String> getStartDoingTimeItems(Context context) {
        Pair<List<Integer>, List<Integer>> typeTimes = getStartDoingTypeTimes(false);
        List<String> items = new ArrayList<>();
        items.add(context.getString(R.string.start_doing_time_not_sure));
        final int size = typeTimes.first.size();
        for (int i = 1; i < size; i++) {
            items.add(DateTimeUtil.getDateTimeStr(
                    typeTimes.first.get(i), typeTimes.second.get(i), context));
        }
        return items;
    }

    public static int getStartDoingTimeIndex(String picked, boolean hasFollowGeneral) {
        if (TextUtils.isEmpty(picked)) {
            return 0;
        }
        Pair<List<Integer>, List<Integer>> pair = getStartDoingTypeTimes(hasFollowGeneral);
        final int size = pair.first.size();
        for (int i = 0; i < size; i++) {
            String str = pair.first.get(i) + "," + pair.second.get(i);
            if (str.equals(picked)) {
                return i;
            }
        }
        return 0;
    }

    public static String getStartDoingTimePickedStr(
            int index, boolean hasFollowGeneral) {
        Pair<List<Integer>, List<Integer>> pair = getStartDoingTypeTimes(hasFollowGeneral);
        return pair.first.get(index) + "," + pair.second.get(index);
    }

    public void startDoing(long timeInMillis, @DoingService.StartType int startType, long hrTime) {
        if (mThing == null) {
            return;
        }
        App.setDoingThingId(mThing.getId());
        mContext.startService(DoingService.getOpenIntent(
                mContext, mThing, System.currentTimeMillis(), timeInMillis, startType, hrTime));
        mContext.startActivity(DoingActivity.getOpenIntent(mContext, false));
        RemoteActionHelper.doingOrCancel(mContext, mThing);
    }

    public void tryToOpenStartDoingActivityUser() {
        if (App.getDoingThingId() != -1) {
            @StringRes int res = R.string.start_doing_doing_another_thing;
            if (App.getDoingThingId() == mThing.getId()) {
                res = R.string.start_doing_doing_this_thing;
            }
            Toast.makeText(mContext, res, Toast.LENGTH_LONG).show();
            return;
        }

        long hrTime = -1;
        if (mThing.getType() == Thing.HABIT) {
            Habit habit = HabitDAO.getInstance(mContext).getHabitById(mThing.getId());
            if (habit != null) {
                int remindedTimes = habit.getRemindedTimes();
                int recordedTimes = habit.getRecord().length();
                if (remindedTimes == recordedTimes) {
                    // user want to do this thing for upcoming habit reminder.
                    hrTime = habit.getMinHabitReminderTime();
                } else if (remindedTimes > recordedTimes) {
                    // habit reminder is notified but user hasn't finished it yet.
                    long maxTime = habit.getFinalHabitReminder().getNotifyTime();
                    hrTime = DateTimeUtil.getHabitReminderTime(habit.getType(), maxTime, -1);
                } else { // remindedTimes < recordedTimes
                    // user finished habit some times in advance, now he decided to enlarge
                    // the advantage
                    hrTime = habit.getMinHabitReminderTime();
                }
            }
        }

        mContext.startActivity(StartDoingActivity.getOpenIntent(
                mContext, mThing.getId(), -1, mThing.getColor(),
                DoingService.START_TYPE_USER, hrTime));
    }

    public void startDoingAlarm(long timeInMillis, long hrTime) {
        startDoing(timeInMillis, DoingService.START_TYPE_ALARM, hrTime);
    }

    public void startDoingAuto(long shouldEndTime, long hrTime) {
        startDoing(getAutoDoingTime(shouldEndTime), DoingService.START_TYPE_AUTO, hrTime);
    }

    public void startDoingUser(long timeInMillis, long hrTime) {
        startDoing(timeInMillis, DoingService.START_TYPE_USER, hrTime);
    }

    /**
     * Get auto start doing strategy for a thing with given id
     * @param thingId thing's id
     * @return auto start doing strategy for this thing, should be one of:
     *      {@link #AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL},
     *      {@link #AUTO_START_DOING_STRATEGY_DISABLED},
     *      {@link #AUTO_START_DOING_STRATEGY_ENABLED}.
     */
    public int getAutoStartDoingStrategy() {
        String key = mThing.getId() + "_" + KEY_INDEX_AUTO_START_DOING;
        return mSpStartDoing.getInt(key, AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL);
    }

    /**
     * Judge if user should start doing the thing automatically when its alarm ring.
     * This method will consider both general settings and unique settings for that thing.
     * @param thingId thing's id
     * @return {@code true} if user should start doing the thing when its alarm ring.
     *         {@code false} otherwise.
     */
    public boolean shouldAutoStartDoing() {
        int strategy = getAutoStartDoingStrategy();
        if (strategy == AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL) {
            int sysStrategy = mSpSettings.getInt(Def.Meta.KEY_AUTO_START_DOING,
                    SYS_AUTO_START_DOING_STRATEGY_DISABLED);
            if (sysStrategy == SYS_AUTO_START_DOING_STRATEGY_DISABLED) {
                return false;
            } else if (sysStrategy == SYS_AUTO_START_DOING_STRATEGY_ALL) {
                // suppose that this method will only be called for Reminder or Habit
                return true;
            } else {
                if (mThing == null) {
                    return false;
                }
                @Thing.Type int thingType = mThing.getType();
                if (thingType == Thing.REMINDER && sysStrategy == SYS_AUTO_START_DOING_STRATEGY_REMINDER) {
                    return true;
                } else if (thingType == Thing.HABIT && sysStrategy == SYS_AUTO_START_DOING_STRATEGY_HABIT) {
                    return true;
                } else return false;
            }
        } else if (strategy == AUTO_START_DOING_STRATEGY_ENABLED) {
            return true;
        } else { // AUTO_START_DOING_STRATEGY_DISABLED
            return false;
        }
    }


    /**
     * Get auto strict mode strategy for the thing with given id
     * @param thingId thing's id
     * @return auto start doing strategy for this thing, should be one of:
     *      {@link #AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL},
     *      {@link #AUTO_STRICT_MODE_STRATEGY_DISABLED},
     *      {@link #AUTO_STRICT_MODE_STRATEGY_ENABLED}.
     */
    public int getAutoStrictModeStrategy() {
        String key = mThing.getId() + "_" + KEY_INDEX_AUTO_STRICT_MODE;
        return mSpStartDoing.getInt(key, AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL);
    }


    /**
     * Judge if strict mode should be turned on automatically when user starts doing the thing.
     * This method will consider both general settings and unique settings for that thing.
     * @param thingId thing's id
     * @return {@code true} if strict mode should be turned on automatically when user starts doing
     *         the thing. {@code false} otherwise.
     */
    public boolean shouldAutoStrictMode() {
        int strategy = getAutoStrictModeStrategy();
        if (strategy == AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL) {
            int sysStrategy = mSpSettings.getInt(Def.Meta.KEY_AUTO_STRICT_MODE,
                    SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED);
            if (sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED) {
                return false;
            } else if (sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_ALL) {
                // suppose that this method will only be called for Reminder or Habit
                return true;
            } else {
                if (mThing == null) {
                    return false;
                }
                @Thing.Type int thingType = mThing.getType();
                if (thingType == Thing.REMINDER && sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_REMINDER) {
                    return true;
                } else if (thingType == Thing.HABIT && sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_HABIT) {
                    return true;
                } else return false;
            }
        } else if (strategy == AUTO_STRICT_MODE_STRATEGY_ENABLED) {
            return true;
        } else { // AUTO_STRICT_MODE_STRATEGY_DISABLED
            return false;
        }
    }

    public long getAutoDoingTime(long shouldEndTime) {
        String key = mThing.getId() + "_" + KEY_INDEX_AUTO_START_DOING_TIME;
        String doingTimeStr = mSpStartDoing.getString(
                key, START_DOING_TIME_FOLLOW_GENERAL_PICKED);
        if (START_DOING_TIME_FOLLOW_GENERAL_PICKED.equals(doingTimeStr)) {
            if (mThing.getType() == Thing.REMINDER) {
                key = Def.Meta.KEY_ASD_TIME_REMINDER;
            } else {
                key = Def.Meta.KEY_ASD_TIME_HABIT;
            }
            doingTimeStr = mSpSettings.getString(key, START_DOING_TIME_NOT_SURE_PICKED);
        }

        if (START_DOING_TIME_NOT_SURE_PICKED.equals(doingTimeStr)) {
            return -1;
        }

        String[] arr = doingTimeStr.split(",");
        int type = Integer.parseInt(arr[0]);
        int time = Integer.parseInt(arr[1]);
        long doingTime = DateTimeUtil.getActualTimeAfterSomeTime(0, type, time);
        if (shouldEndTime != -1 && mThing.getType() == Thing.HABIT) {
            while (System.currentTimeMillis() + doingTime + TIME_BEFORE_NEXT_HABIT_REMINDER
                    > shouldEndTime) {
                doingTime -= 5 * 60 * 1000L;
            }
            if (doingTime < 5 * 60 * 1000L) {
                doingTime = -1;
            }
        }

        return doingTime;
    }

}
