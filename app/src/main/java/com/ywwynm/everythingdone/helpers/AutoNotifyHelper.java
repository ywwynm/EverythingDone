package com.ywwynm.everythingdone.helpers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver;
import com.ywwynm.everythingdone.utils.DateTimeUtil;

import java.util.Calendar;

/**
 * Created by ywwynm on 2016/3/13.
 * utils for auto notify function
 */
public class AutoNotifyHelper {

    public static final String TAG = "AutoNotifyHelper";

    private AutoNotifyHelper() {}

    public static int[] AUTO_NOTIFY_TIMES = {
            15, 30, 1, 2, 6, 1, 3, 1
    };
    public static int[] AUTO_NOTIFY_TYPES = {
            Calendar.MINUTE, Calendar.MINUTE, Calendar.HOUR_OF_DAY,
            Calendar.HOUR_OF_DAY, Calendar.HOUR_OF_DAY, Calendar.DATE,
            Calendar.DATE, Calendar.WEEK_OF_YEAR
    };

    static {
        if (BuildConfig.DEBUG) {
            AUTO_NOTIFY_TIMES = new int[] {
                    10, 15, 30, 1, 2, 6, 1, 3, 1
            };
            AUTO_NOTIFY_TYPES = new int[] {
                    Calendar.SECOND, Calendar.MINUTE, Calendar.MINUTE, Calendar.HOUR_OF_DAY,
                    Calendar.HOUR_OF_DAY, Calendar.HOUR_OF_DAY, Calendar.DATE,
                    Calendar.DATE, Calendar.WEEK_OF_YEAR
            };
        }
    }

    public static void createAutoNotify(Thing thing, Context context) {
        if (!shouldCreateAutoNotify(thing, context)) {
            return;
        }
        long id = thing.getId();
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AutoNotifyReceiver.class);
        intent.putExtra(Def.Communication.KEY_ID, id);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, (int) id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        long time = DateTimeUtil.getActualTimeAfterSomeTime(getAutoNotifyPreferences(context));
        alarmManager.set(AlarmManager.RTC_WAKEUP, time, pendingIntent);
    }

    private static boolean shouldCreateAutoNotify(Thing thing, Context context) {
        int[] typeTime = getAutoNotifyPreferences(context);
        if (typeTime[1] == 0) {
            return false;
        }
        int thingType = thing.getType();
        if (thingType == Thing.GOAL) {
            return false;
        }

        long id = thing.getId();
        int twoTimesTime = typeTime[1] * 2;
        long limitTime = DateTimeUtil.getActualTimeAfterSomeTime(typeTime[0], twoTimesTime);
        if (thingType == Thing.REMINDER) {
            Reminder reminder = ReminderDAO.getInstance(context).getReminderById(id);
            long time = reminder.getNotifyTime();
            return time >= limitTime;
        } else if (thingType == Thing.HABIT) {
            Habit habit = HabitDAO.getInstance(context).getHabitById(id);
            long time = habit.getFirstTime();
            return time >= limitTime;
        }
        return true;
    }

    private static int[] getAutoNotifyPreferences(Context context) {
        int index = getAutoNotifyPreferencesIndex(context);
        int[] ret = new int[2];
        if (index == 0) {
            ret[1] = 0;
        } else {
            ret[0] = AUTO_NOTIFY_TYPES[index - 1];
            ret[1] = AUTO_NOTIFY_TIMES[index - 1];
        }
        return ret;
    }

    private static int getAutoNotifyPreferencesIndex(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        return preferences.getInt(Def.Meta.KEY_AUTO_NOTIFY, 0);
    }

}
