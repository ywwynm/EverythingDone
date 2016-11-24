package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.activities.DoingActivity;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;

/**
 * Created by ywwynm on 2016/11/22.
 * A helper class to restore and get thing's doing strategy
 */
public class ThingDoingHelper {

    public static final String TAG = "ThingDoingHelper";

    public static int KEY_INDEX_AUTO_START_DOING                = 0;

    public static int AUTO_START_DOING_STRATEGY_FOLLOW_SETTINGS = 0;
    public static int AUTO_START_DOING_STRATEGY_ENABLED         = 1;
    public static int AUTO_START_DOING_STRATEGY_DISABLED        = 2;

    public static int SYS_AUTO_START_DOING_STRATEGY_DISABLED    = 0;
    public static int SYS_AUTO_START_DOING_STRATEGY_REMINDER    = 1;
    public static int SYS_AUTO_START_DOING_STRATEGY_HABIT       = 2;
    public static int SYS_AUTO_START_DOING_STRATEGY_ALL         = 3;


    public static int KEY_INDEX_AUTO_STRICT_MODE                = 1;

    public static int AUTO_STRICT_MODE_STRATEGY_FOLLOW_SETTINGS = 0;
    public static int AUTO_STRICT_MODE_STRATEGY_ENABLED         = 1;
    public static int AUTO_STRICT_MODE_STRATEGY_DISABLED        = 2;

    public static int SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED    = 0;
    public static int SYS_AUTO_STRICT_MODE_STRATEGY_REMINDER    = 1;
    public static int SYS_AUTO_STRICT_MODE_STRATEGY_HABIT       = 2;
    public static int SYS_AUTO_STRICT_MODE_STRATEGY_ALL         = 3;

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

    public void startDoing(long timeInMillis, @DoingService.StartType int startType) {
        if (mThing == null) {
            return;
        }
        App.setDoingThingId(mThing.getId());
        mContext.startService(DoingService.getOpenIntent(
                mContext, mThing, System.currentTimeMillis(), timeInMillis, startType));
        mContext.startActivity(DoingActivity.getOpenIntent(mContext, false));
        RemoteActionHelper.doingOrCancel(mContext, mThing);
    }

    public void startDoingAlarm(long timeInMillis) {
        startDoing(timeInMillis, DoingService.START_TYPE_ALARM);
    }

    public void startDoingAuto() {
        long timeInMillis = 0;
        startDoing(timeInMillis, DoingService.START_TYPE_AUTO);
    }

    public void startDoingUser(long timeInMillis) {
        startDoing(timeInMillis, DoingService.START_TYPE_USER);
    }

    /**
     * Get auto start doing strategy for a thing with given id
     * @param thingId thing's id
     * @return auto start doing strategy for this thing, should be one of:
     *      {@link #AUTO_START_DOING_STRATEGY_FOLLOW_SETTINGS},
     *      {@link #AUTO_START_DOING_STRATEGY_DISABLED},
     *      {@link #AUTO_START_DOING_STRATEGY_ENABLED}.
     */
    public int getAutoStartDoingStrategy() {
        String key = mThing.getId() + "_" + KEY_INDEX_AUTO_START_DOING;
        return mSpStartDoing.getInt(key, AUTO_START_DOING_STRATEGY_FOLLOW_SETTINGS);
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
        if (strategy == AUTO_START_DOING_STRATEGY_FOLLOW_SETTINGS) {
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
     *      {@link #AUTO_STRICT_MODE_STRATEGY_FOLLOW_SETTINGS},
     *      {@link #AUTO_STRICT_MODE_STRATEGY_DISABLED},
     *      {@link #AUTO_STRICT_MODE_STRATEGY_ENABLED}.
     */
    public int getAutoStrictModeStrategy() {
        String key = mThing.getId() + "_" + KEY_INDEX_AUTO_STRICT_MODE;
        return mSpStartDoing.getInt(key, AUTO_STRICT_MODE_STRATEGY_FOLLOW_SETTINGS);
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
        if (strategy == AUTO_STRICT_MODE_STRATEGY_FOLLOW_SETTINGS) {
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

    public long getAutoDoingTime() {
        throw new UnsupportedOperationException();
    }

}
