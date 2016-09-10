package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.ywwynm.everythingdone.utils.DeviceUtil;

/**
 * Created by ywwynm on 2015/8/16.
 * A custom FloatingActionButton with more animation for appearing/disappearing.
 * It can also attach to a RecyclerView and appear/disappear according to scrolling.
 * Based on FloatingActionButton in Support Design Library.
 */
public class FloatingActionButton extends android.support.design.widget.FloatingActionButton {

    public static final String TAG = "FloatingActionButton";

    private AccelerateDecelerateInterpolator mAccelerateDecelerateInterpolator;
    private AccelerateInterpolator mAccelerateInterpolator;
    private OvershootInterpolator mOvershootInterpolator;

    private boolean mOnScreen = true;
    private boolean mShrunk = false;

    private Snackbar[] mSnackbars;

    public FloatingActionButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public FloatingActionButton(Context context) {
        super(context);
        init();
    }

    public FloatingActionButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mAccelerateDecelerateInterpolator = new AccelerateDecelerateInterpolator();
        mAccelerateInterpolator = new AccelerateInterpolator();
        mOvershootInterpolator = new OvershootInterpolator();
    }

    private int getMarginBottom() {
        if (!DeviceUtil.hasLollipopApi()) {
            return 0;
        } else return (int) (16 * getResources().getDisplayMetrics().density);
    }

    public void bindSnackbars(Snackbar... snackbars) {
        mSnackbars = snackbars;
    }

    public void attachToRecyclerView(RecyclerView recyclerView) {
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    hideToBottom();
                } else {
                    showFromBottom();
                }
            }
        });
    }

    public void raise(float y) {
        animate().translationY(-y).setInterpolator(null).setDuration(200);
    }

    public void fall() {
        animate().translationY(0).setInterpolator(null).setDuration(200);
    }

    public void spread() {
        showFromBottom();
        if (mShrunk) {
            animate().scaleX(1.0f).setInterpolator(mOvershootInterpolator).setDuration(200);
            animate().scaleY(1.0f).setInterpolator(mOvershootInterpolator).setDuration(200);
            setClickable(true);
            mShrunk = false;
        }
    }

    public void shrink() {
        if (!mShrunk) {
            setClickable(false);
            animate().scaleX(0).setInterpolator(mAccelerateInterpolator).setDuration(160);
            animate().scaleY(0).setInterpolator(mAccelerateInterpolator).setDuration(160);
            mShrunk = true;
        }
    }

    public void shrinkWithoutAnim() {
        if (!mShrunk) {
            setClickable(false);
            setScaleX(0);
            setScaleY(0);
            mShrunk = true;
        }
    }

    public void showFromBottom() {
        if (!mOnScreen) {
            animate().translationY(isSnackbarShowing() ? -mSnackbars[0].getHeight() : 0)
                    .setInterpolator(mAccelerateDecelerateInterpolator).setDuration(200);
            setClickable(true);
            mOnScreen = true;
        }
    }

    public void hideToBottom() {
        if (mOnScreen) {
            setClickable(false);
            animate().translationY(getHeight() + getMarginBottom())
                    .setInterpolator(mAccelerateDecelerateInterpolator).setDuration(200);
            mOnScreen = false;
        }
    }

    private boolean isSnackbarShowing() {
        if (mSnackbars == null) {
            return false;
        }
        for (Snackbar snackbar : mSnackbars) {
            if (snackbar != null && snackbar.isShowing()) {
                return true;
            }
        }
        return false;
    }
}
