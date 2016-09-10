package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.model.ThingsCounts;
import com.ywwynm.everythingdone.permission.PermissionUtil;
import com.ywwynm.everythingdone.utils.BitmapUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import java.io.File;

/**
 * Created by ywwynm on 2015/8/1.
 * A class to provide operations for DrawerHeader
 */
public class DrawerHeader {

    public static final String TAG = "DrawerHeader";

    private App mApp;

    private ImageView mIvHeader;
    private TextView mTvLocation;
    private TextView mTvCompletionRate;

    public DrawerHeader(App app, ImageView ivHeader,
                        TextView tvLocation, TextView tvCompletionRate) {
        mApp = app;

        mIvHeader = ivHeader;
        mTvLocation = tvLocation;
        mTvCompletionRate = tvCompletionRate;

        updateDrawerHeader();

        if (LocaleUtil.isChinese(mApp)) {
            mTvLocation.setTextSize(16);
            mTvCompletionRate.setTextSize(28);
        } else {
            int width = DisplayUtil.getScreenSize(mApp).x;
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

    public void updateDrawerHeader() {
        final String D = SettingsActivity.DEFAULT_DRAWER_HEADER;

        SharedPreferences sp = mApp.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        String header = sp.getString(Def.Meta.KEY_DRAWER_HEADER, D);
        if (D.equals(header)) {
            mIvHeader.setImageResource(R.drawable.drawer_header);
        } else {
            if (!new File(header).exists()) {
                mIvHeader.setImageResource(R.drawable.drawer_header);
                sp.edit().putString(Def.Meta.KEY_DRAWER_HEADER, D).apply();
                return;
            }

            if (!PermissionUtil.hasStoragePermission(mApp)) {
                // sometimes after re-installing the app by Android Studio, old data remains.
                mIvHeader.setImageResource(R.drawable.drawer_header);
                return;
            }

            int width = (int) (320 * DisplayUtil.getScreenDensity(mApp));
            Bitmap bm = BitmapUtil.decodeFileWithRequiredSize(
                    header, width, width * 9 / 16);
            mIvHeader.setImageBitmap(bm);
        }
    }

    public void updateTexts() {
        switch (mApp.getLimit()) {
            case Def.LimitForGettingThings.ALL_UNDERWAY:
            case Def.LimitForGettingThings.ALL_FINISHED:
            case Def.LimitForGettingThings.ALL_DELETED:
                mTvLocation.setText(R.string.completion_rate_all);
                break;
            case Def.LimitForGettingThings.NOTE_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_note);
                break;
            case Def.LimitForGettingThings.REMINDER_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_reminder);
                break;
            case Def.LimitForGettingThings.HABIT_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_habit);
                break;
            case Def.LimitForGettingThings.GOAL_UNDERWAY:
                mTvLocation.setText(R.string.completion_rate_goal);
                break;
            default:break;
        }

        updateCompletionRate();
    }

    public void updateCompletionRate() {
        mTvCompletionRate.setText(
                ThingsCounts.getInstance(mApp).getCompletionRate(mApp.getLimit()));
    }

}
