package com.ywwynm.everythingdone.views;

import android.content.res.Configuration;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

/**
 * Created by ywwynm on 2015/7/5.
 * A simple class combines actionbarShadow, activity title and completion rate together
 * and provides methods to update them.
 */
public class ActivityHeader {

    public static final String TAG = "ActivityHeader";

    private App mApp;
    private float mScreenDensity;

    private boolean shouldListenToScroll = true;

    private float headerTranslationYFactor;
    private float titleShrinkFactor;

    private float actionbarShadowAlpha;

    private View mActionbarShadow;
    private RelativeLayout mRelativeLayout;
    private TextView mTitle;
    private TextView mSubtitle;

    private RecyclerView mBindingRecyclerView;

    private ModeManager mModeManager;

    public ActivityHeader(App app, RecyclerView recyclerView,
                          View actionbarShadow, RelativeLayout relativeLayout, TextView title,
                          TextView subtitle) {
        mApp = app;
        mScreenDensity = DisplayUtil.getScreenDensity(mApp);

        computeFactors(null);

        mActionbarShadow = actionbarShadow;
        mRelativeLayout = relativeLayout;
        mTitle = title;
        mSubtitle = subtitle;

        mBindingRecyclerView = recyclerView;
        updateSubtitle();
    }

    public void setModeManager(ModeManager modeManager) {
        mModeManager = modeManager;
    }

    public void computeFactors(Toolbar actionbar) {
        headerTranslationYFactor = 65f / 90;
        titleShrinkFactor = -1.0f / 540 / mScreenDensity;
        boolean isTablet    = DisplayUtil.isTablet(mApp);
        boolean isLandscape = mApp.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (isTablet) {
            headerTranslationYFactor = 62f / 90;
            titleShrinkFactor = -1.0f / 540 / mScreenDensity;
        } else if (isLandscape) {
            headerTranslationYFactor = 68f / 90;
            titleShrinkFactor = -1.0f / 360 / mScreenDensity;
        }

        if (actionbar != null) {
            int actionbarHeight = actionbar.getHeight();
            if (near(actionbarHeight, (int) (mScreenDensity * 48))) {
                headerTranslationYFactor = 68f / 90;
                titleShrinkFactor = -1.0f / 360 / mScreenDensity;
            } else if (near(actionbarHeight, (int) (mScreenDensity * 56))) {
                headerTranslationYFactor = 65f / 90;
                titleShrinkFactor = -1.0f / 540 / mScreenDensity;
            } else if (near(actionbarHeight, (int) (mScreenDensity * 64))) {
                headerTranslationYFactor = 62f / 90;
                titleShrinkFactor = -1.0f / 540 / mScreenDensity;
            }
        }
    }

    private boolean near(int h1, int h2) {
        return Math.abs(h1 - h2) < 8;
    }

    public void updateAll(int firstVisibleItemPosition, boolean anim) {
        if (mBindingRecyclerView == null) {
            throw new NullPointerException(
                    "There is no binding RecyclerView or binding RecyclerView is null.");
        }

        if (!shouldListenToScroll) {
            return;
        }

        float actionbarShadowAlphaAfter = 0;
        int scrollY = -mBindingRecyclerView.getChildAt(0).getTop();
        final int titleAndShadowScrollY = (int) (mScreenDensity * 90);
        final int shadowAppearCompletelyScrollY = (int) (mScreenDensity * 102);

        /**
         * Sometimes, especially when an item is removed or moved,
         * RecyclerView will give a wrong scroll distance much lower than 0,
         * use this if block to correct it.
         * Otherwise, since header's height is 102dp, all actions of this class
         * should only happen between 0 and this distance.
         */
        if (scrollY >= mScreenDensity * 102 || scrollY < 0) {
            scrollY = 0;
        }

        if (firstVisibleItemPosition == 0) {
            if (scrollY <= titleAndShadowScrollY) {
                updateHeader(scrollY, anim);
            } else if (scrollY <= shadowAppearCompletelyScrollY) {
                actionbarShadowAlphaAfter = 1f / 12 / mScreenDensity * scrollY
                        - 90f / 12;
                updateHeader((int) (90 * mScreenDensity), anim);
            } else {
                updateHeader((int) (90 * mScreenDensity), anim);
                actionbarShadowAlphaAfter = 1.0f;
            }
        } else {
            updateHeader((int) (90 * mScreenDensity), anim);
            actionbarShadowAlphaAfter = 1.0f;
        }
        if (mModeManager.getCurrentMode() != ModeManager.SELECTING) {
            if (anim) {
                mActionbarShadow.animate().alpha(actionbarShadowAlphaAfter).withLayer().setDuration(160);
            } else {
                mActionbarShadow.setAlpha(actionbarShadowAlphaAfter);
            }
        } else {
            actionbarShadowAlpha = actionbarShadowAlphaAfter;
        }
    }

    public void updateText() {
        switch (mApp.getLimit()) {
            case Def.LimitForGettingThings.NOTE_UNDERWAY:
                mTitle.setText(R.string.note);
                break;
            case Def.LimitForGettingThings.REMINDER_UNDERWAY:
                mTitle.setText(R.string.reminder);
                break;
            case Def.LimitForGettingThings.HABIT_UNDERWAY:
                mTitle.setText(R.string.habit);
                break;
            case Def.LimitForGettingThings.GOAL_UNDERWAY:
                mTitle.setText(R.string.goal);
                break;
            case Def.LimitForGettingThings.ALL_FINISHED:
                mTitle.setText(R.string.finished);
                break;
            case Def.LimitForGettingThings.ALL_DELETED:
                mTitle.setText(R.string.deleted);
                break;
            case Def.LimitForGettingThings.ALL_UNDERWAY:
            default:
                mTitle.setText(R.string.underway);
                break;
        }
        updateSubtitle();
    }

    private void updateSubtitle() {
        int thingsCount = ThingManager.getInstance(mApp).getThingsCounts()
                .getThingsCountForActivityHeader(mApp.getLimit());
        String subtitle = thingsCount == 0 ? mApp.getString(R.string.empty) :
                "" + thingsCount + " " + mApp.getString(R.string.a_thing);
        if (thingsCount > 1 && !LocaleUtil.isChinese(mApp)) {
            subtitle += "s";
        }
        mSubtitle.setText(subtitle);
    }

    public void hideActionbarShadow() {
        actionbarShadowAlpha = mActionbarShadow.getAlpha();
        mActionbarShadow.animate().alpha(0).withLayer().setDuration(160);
    }

    public void showActionbarShadow() {
        showActionbarShadow(actionbarShadowAlpha);
    }

    public void showActionbarShadow(float alpha) {
        mActionbarShadow.animate().alpha(alpha).withLayer();
    }

    public void hideTitles() {
        mRelativeLayout.setVisibility(View.INVISIBLE);
    }

    public void reset(boolean anim) {
        mRelativeLayout.setVisibility(View.VISIBLE);
        if (anim) {
            mRelativeLayout.animate().translationY(0);
            mTitle.animate().scaleX(1.0f);
            mTitle.animate().scaleY(1.0f);
            mSubtitle.animate().alpha(1.0f);
            mActionbarShadow.animate().alpha(0);
        } else {
            mRelativeLayout.setTranslationY(0);
            mTitle.setScaleX(1.0f);
            mTitle.setScaleY(1.0f);
            mSubtitle.setAlpha(1.0f);
            mActionbarShadow.setAlpha(0);
        }
    }

    public void setShouldListenToScroll(boolean shouldListenToScroll) {
        this.shouldListenToScroll = shouldListenToScroll;
    }

    private void updateHeader(int scrollY, boolean anim) {
        float scale = titleShrinkFactor * scrollY + 1;
        mTitle.setPivotX(1);
        mTitle.setPivotY(1);

        if (anim) {
            mRelativeLayout.animate().translationY(-headerTranslationYFactor * scrollY);

            /**
             * Changing scaleX and scaleY of title is better than changing its textSize.
             * pivotX and pivotY should be remained as 1 so that title's location won't
             * be changed incorrectly.
             */

            mTitle.animate().scaleX(scale).setDuration(160);
            mTitle.animate().scaleY(scale).setDuration(160);
            mSubtitle.animate().alpha(-1.0f / mScreenDensity / 90 * scrollY + 1).withLayer().setDuration(160);
        } else {
            mRelativeLayout.setTranslationY((int) (-headerTranslationYFactor * scrollY));
            mTitle.setScaleX(scale);
            mTitle.setScaleY(scale);
            mSubtitle.setAlpha(-1.0f / mScreenDensity / 90 * scrollY + 1);
        }
    }
}
