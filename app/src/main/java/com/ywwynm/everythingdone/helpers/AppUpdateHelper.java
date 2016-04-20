package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;

import static com.ywwynm.everythingdone.Definitions.MetaData.*;

/**
 * Created by ywwynm on 2016/4/19.
 * handle update from old version to a new version
 */
public class AppUpdateHelper {

    private static AppUpdateHelper sInstance;

    public static AppUpdateHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AppUpdateHelper.class) {
                if (sInstance == null) {
                    sInstance = new AppUpdateHelper(context);
                }
            }
        }
        return sInstance;
    }

    private Context mContext;

    private AppUpdateHelper(Context context) {
        mContext = context;
    }

    public void handleAppUpdate() {
        SharedPreferences sp = mContext.getSharedPreferences(
                META_DATA_NAME, Context.MODE_PRIVATE);

        from1_0_3To1_0_4(sp);
    }

    private void from1_0_3To1_0_4(SharedPreferences sp) {
        boolean updated = sp.getBoolean(kEY_1_0_3_TO_1_0_4, false);
        if (updated) {
            return;
        }

        // transfer some reminders to goals
        ThingDAO thingDAO = ThingDAO.getInstance(mContext);
        ReminderDAO reminderDAO = ReminderDAO.getInstance(mContext);
        Cursor cursor = thingDAO.getThingsCursor("type=" + Thing.REMINDER);
        while (cursor.moveToNext()) {
            Thing thing = new Thing(cursor);
            long id = thing.getId();
            Reminder reminder = reminderDAO.getReminderById(id);
            long millis = reminder.getNotifyMillis();
            if (millis < Reminder.GOAL_MILLIS) continue;

            thing.setType(Thing.GOAL);
            thingDAO.update(Thing.REMINDER, thing, true, true);
        }

        sp.edit().putBoolean(kEY_1_0_3_TO_1_0_4, true).apply();
    }

}
