package com.ywwynm.everythingdone.views;

import android.widget.TextView;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.bean.ThingsCounts;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

/**
 * Created by ywwynm on 2015/8/1.
 * A class to provide operations for DrawerHeader
 */
public class DrawerHeader {

    public static final String TAG = "DrawerHeader";

    private EverythingDoneApplication mApplication;

    private TextView mTvLocation;
    private TextView mTvCompletionRate;

    public DrawerHeader(EverythingDoneApplication application, TextView tvLocation,
                        TextView tvCompletionRate) {
        mApplication = application;
        mTvLocation = tvLocation;
        mTvCompletionRate = tvCompletionRate;

        if (LocaleUtil.isChinese(mApplication)) {
            mTvLocation.setTextSize(16);
            mTvCompletionRate.setTextSize(28);
        } else if (LocaleUtil.isEnglish(mApplication)) {
            int width = DisplayUtil.getScreenSize(mApplication).x;
            if (width <= 720) {
                mTvLocation.setTextSize(12);
            } else if (width <= 1080) {
                mTvLocation.setTextSize(13);
            } else {
                mTvLocation.setTextSize(14);
            }
            mTvCompletionRate.setTextSize(24);
        }
    }

    public void updateAll() {
        switch (mApplication.getLimit()) {
            case Definitions.LimitForGettingThings.ALL_UNDERWAY:
            case Definitions.LimitForGettingThings.ALL_FINISHED:
            case Definitions.LimitForGettingThings.ALL_DELETED:
                mTvLocation.setText(R.string.completion_rate_all);
                break;
            case Definitions.LimitForGettingThings.NOTE_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_note);
                break;
            case Definitions.LimitForGettingThings.REMINDER_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_reminder);
                break;
            case Definitions.LimitForGettingThings.HABIT_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_habit);
                break;
            case Definitions.LimitForGettingThings.GOAL_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_goal);
                break;
        }

        updateCompletionRate();
    }

    public void updateCompletionRate() {
        mTvCompletionRate.setText(
                ThingsCounts.getInstance(mApplication).getCompletionRate(mApplication.getLimit()));
    }

}
