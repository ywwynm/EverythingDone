package com.ywwynm.everythingdone.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.annotation.StringRes;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import static com.ywwynm.everythingdone.Def.Meta.KEY_1_0_3_TO_1_0_4;
import static com.ywwynm.everythingdone.Def.Meta.KEY_1_0_4_TO_1_0_5;
import static com.ywwynm.everythingdone.Def.Meta.KEY_1_1_4_TO_1_1_5;
import static com.ywwynm.everythingdone.Def.Meta.META_DATA_NAME;

/**
 * Created by ywwynm on 2016/4/19.
 * handle update from old version to a new version
 */
public class AppUpdateHelper {

    public static final String TAG = "AppUpdateHelper";

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

        updateFrom1_0_3To1_0_4(sp);
    }

    public void showInfo(Activity activity) {
        SharedPreferences sp = mContext.getSharedPreferences(
                META_DATA_NAME, Context.MODE_PRIVATE);

        showFrom1_0_4To1_0_5(sp, activity);
    }

    private void updateFrom1_0_3To1_0_4(SharedPreferences sp) {
        boolean updated = sp.getBoolean(KEY_1_0_3_TO_1_0_4, false);
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

        sp.edit().putBoolean(KEY_1_0_3_TO_1_0_4, true).apply();
    }

    private boolean showFrom1_0_4To1_0_5(SharedPreferences sp, Activity activity) {
        boolean updated = sp.getBoolean(KEY_1_0_4_TO_1_0_5, false);
        if (updated) {
            return false;
        }

        LongTextDialogFragment ltdf = createLongTextDialog(
                R.string.title_important_alert, R.string.content_important_reminder_permission);
        ltdf.showAllowingStateLoss(activity.getFragmentManager(), LongTextDialogFragment.TAG);

        sp.edit().putBoolean(KEY_1_0_4_TO_1_0_5, true).apply();
        return true;
    }

    public static boolean updateFrom1_1_4To1_1_5(Activity activity, int color) {
        SharedPreferences sp = activity.getSharedPreferences(
                META_DATA_NAME, Context.MODE_PRIVATE);
        boolean updated = sp.getBoolean(KEY_1_1_4_TO_1_1_5, false);
        if (updated) {
            return false;
        }

        AlertDialogFragment adf = new AlertDialogFragment();
        adf.setShowCancel(false);
        adf.setTitleColor(color);
        adf.setConfirmColor(color);
        adf.setTitle(activity.getString(R.string.from_1_1_4_to_1_1_5_title));
        adf.setContent(activity.getString(R.string.from_1_1_4_to_1_1_5_content));
        adf.showAllowingStateLoss(activity.getFragmentManager(), AlertDialogFragment.TAG);

        sp.edit().putBoolean(KEY_1_1_4_TO_1_1_5, true).apply();

        return true;
    }

    private LongTextDialogFragment createLongTextDialog(@StringRes int titleRes, @StringRes int contentRes) {
        LongTextDialogFragment ltdf = new LongTextDialogFragment();
        ltdf.setAccentColor(DisplayUtil.getRandomColor(mContext));
        ltdf.setTitle(mContext.getString(titleRes));
        ltdf.setContent(mContext.getString(contentRes));
        return ltdf;
    }

}
