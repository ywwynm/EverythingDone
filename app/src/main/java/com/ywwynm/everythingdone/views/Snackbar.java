package com.ywwynm.everythingdone.views;

import android.support.annotation.StringRes;
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
 * Now, the animation and behavior is like official {@link android.support.design.widget.Snackbar},
 * but this one suits Material Design better than that.
 */
public class Snackbar {

    public static final String TAG = "Snackbar";

    public static final int NORMAL = 0;
    public static final int UNDO  = 1;

    private App mApp;
    private int mType;
    private float mHeight;

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

        if (mContentView.getParent() == null) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) mHeight);
            flp.gravity = Gravity.BOTTOM;
            mTargetParent.addView(mContentView, flp);
        }

        mContentView.setTranslationY(mHeight);
        mContentView.animate().translationY(0).setDuration(200).start();

        if (mType == NORMAL) {
            mTargetParent.postDelayed(mHideThread, 1200 + 160);
        }
    }

    public void dismiss() {
        try {
            mContentView.animate().translationY(mHeight).setDuration(200).start();
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
        if (mContentView.getParent() == null) {
            return false;
        }
        return mContentView.getTranslationY() != mHeight;
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