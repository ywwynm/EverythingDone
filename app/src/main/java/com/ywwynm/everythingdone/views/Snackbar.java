package com.ywwynm.everythingdone.views;

import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;

/**
 * Created by ywwynm on 2015/7/4.
 * A simple Snackbar inspired by Material Design based on PopupWindow.
 *
 * updated on 2016/8/30
 * Change the implementation to {@link ViewGroup#addView(View, ViewGroup.LayoutParams)} instead
 * of PopupWindow, fixed problems when there is a NavigationBar and window has translucent flags.
 * Besides, this implementation will also be compatible with multi-window announced in Android Nougat.
 * Now, the animation and behavior is like official {@link com.google.android.material.snackbar.Snackbar},
 * but this one suits Material Design better than that.
 */
public class Snackbar {

    public static final String TAG = "Snackbar";

    public static final int NORMAL = 0;
    public static final int UNDO  = 1;

    private App mApp;
    private int mType;
    private float mHeight;
    /** True between {@link #show()} and {@link #dismiss()}. Replaces the
     *  old translation-based check after the hidden-rest position became
     *  dynamic (depends on the current bottom system-bar inset). */
    private boolean mIsShowing;

    private Thread mHideThread;

    private View mContentView;
    private TextView mTvMessage;
    private Button mBtUndo;
    private ViewGroup mTargetParent;

    private FloatingActionButton mBindingFab;

    public Snackbar(App app, int type, ViewGroup targetParent,
                    FloatingActionButton bindingFab) {
        mApp = app;
        mType = type;
        if (mType == NORMAL) {
            mHideThread = new Thread() {
                @Override
                public void run() {
                    if (isShowing()) {
                        dismiss();
                    }
                }
            };
        }

        mTargetParent = targetParent;

        mContentView = LayoutInflater.from(targetParent.getContext())
                .inflate(R.layout.snackbar_undo, null);
        mTvMessage = (TextView) mContentView.findViewById(R.id.tv_message);
        if (mType == UNDO) {
            mBtUndo = (Button) mContentView.findViewById(R.id.bt_undo);
            mBtUndo.setVisibility(View.VISIBLE);
        }

        mBindingFab = bindingFab;
        mHeight = mApp.getResources().getDimension(R.dimen.sb_height);
    }

    public void show() {
        if (isShowing()) {
            return;
        }

        if (mBindingFab != null &&
                mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            mBindingFab.showFromBottom();
            mBindingFab.raise(mHeight);
        }

        // Edge-to-edge: read the current gesture / 3-button nav-bar inset and
        // both push the Snackbar up by that amount (so it sits above the nav
        // bar) and extend the hidden-state offset by it (so dismiss really
        // slides off-screen, not just down to the nav bar where a strip would
        // peek through).
        int insetBottom = readBottomSystemInset();
        if (mContentView.getParent() == null) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) mHeight);
            flp.gravity = Gravity.BOTTOM;
            flp.bottomMargin = insetBottom;
            mTargetParent.addView(mContentView, flp);
        } else {
            // Re-attached / re-shown — re-sync the bottom margin in case the
            // inset changed (rotation, multi-window, gesture-bar toggle).
            ViewGroup.LayoutParams lp = mContentView.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).bottomMargin = insetBottom;
                mContentView.setLayoutParams(lp);
            }
        }

        mContentView.setTranslationY(mHeight + insetBottom);
        mContentView.animate().translationY(0).setDuration(200).start();
        mIsShowing = true;

        if (mType == NORMAL) {
            mTargetParent.postDelayed(mHideThread, 1200 + 160);
        }
    }

    public void dismiss() {
        try {
            mIsShowing = false;
            int insetBottom = readBottomSystemInset();
            mContentView.animate().translationY(mHeight + insetBottom)
                    .setDuration(200).start();
            //mPopupWindow.dismiss();
            if (mType == NORMAL && mHideThread != null) {
                mHideThread.interrupt();
            }
            if (mBindingFab != null &&
                    mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                mBindingFab.fall();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isShowing() {
        // Use the explicit flag rather than translationY: the hidden rest
        // position is now dynamic (mHeight + bottom inset), so a plain
        // numeric comparison would mis-fire on inset changes.
        return mIsShowing && mContentView.getParent() != null;
    }

    /** Read the current bottom system-bar inset from the target parent's
     *  attached root window. Returns 0 before the view is attached or when
     *  the platform reports no insets (rare, mostly older OEMs). */
    private int readBottomSystemInset() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mTargetParent);
        if (insets == null) return 0;
        return insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout()).bottom;
    }

    public void setUndoListener(View.OnClickListener onClickListener) {
        if (mType == NORMAL) {
            throw new IllegalStateException("Type must be Snackbar.UNDO");
        }
        mBtUndo.setOnClickListener(onClickListener);
    }

    public void setMessage(@StringRes int stringRes) {
        mTvMessage.setText(mApp.getString(stringRes));
    }

    public void setMessage(String msg) {
        mTvMessage.setText(msg);
    }

    public void setUndoText(@StringRes int stringRes) {
        setUndoText(mApp.getString(stringRes));
    }

    public void setUndoText(String text) {
        if (mType == NORMAL) {
            throw new IllegalStateException("Type must be Snackbar.UNDO");
        }
        mBtUndo.setText(text);
    }

    public float getHeight() {
        return mHeight;
    }
}