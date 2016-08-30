package com.ywwynm.everythingdone.views;

import android.graphics.Rect;
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
 */
public class Snackbar {

    public static final String TAG = "Snackbar";

    public static final int NORMAL = 0;
    public static final int UNDO  = 1;

    private App mApp;
    private int mType;
    private float mHeight;

    private Rect mWindowRect;
    private Thread mHideThread;

    private View mContentView;
    private TextView mTvMessage;
    private Button mBtUndo;
    private ViewGroup mTargetParent;

    //private PopupWindow mPopupWindow;

    private FloatingActionButton mBindingFab;

    public interface DismissCallback {
        void onDismiss();
    }
    private DismissCallback mDismissCallback;

    public void setDismissCallback(DismissCallback dismissCallback) {
        mDismissCallback = dismissCallback;
    }

    public Snackbar(App app, int type, View targetParent,
                    FloatingActionButton bindingFab) {
        mApp = app;
        mType = type;
        mWindowRect = new Rect();
        int layoutId = 0;
        if (mType == NORMAL) {
            layoutId = R.layout.snackbar_blank;
            mHideThread = new Thread() {
                @Override
                public void run() {
                    if (isShowing()) {
                        dismiss();
                    }
                }
            };
        } else if (mType == UNDO) {
            layoutId = R.layout.snackbar_undo;
        }

        mTargetParent = (ViewGroup) targetParent;

        mContentView = LayoutInflater.from(targetParent.getContext()).inflate(layoutId, null);
        mTvMessage = (TextView) mContentView.findViewById(R.id.tv_message);
        if (mType == UNDO) {
            mBtUndo = (Button) mContentView.findViewById(R.id.bt_undo);
        }
        // TODO: 2016/8/30 don't cast directly

//        mPopupWindow = new PopupWindow(inflater,
//                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
//        if (!DeviceUtil.hasLollipopApi()) {
//            mPopupWindow.setAnimationStyle(R.style.SnackbarAnimation);
//        } else {
//            mContentView = (RelativeLayout) inflater.findViewById(R.id.rl_snackbar);
//            mPopupWindow.setAnimationStyle(R.style.SnackbarAnimationOnlyExit);
//        }
        mBindingFab = bindingFab;
        mHeight = mApp.getResources().getDimension(R.dimen.sb_height);
    }

    public void show() {
        if (isShowing()) {
            return;
        }

//        Point popupDisplay = DisplayUtil.getDisplaySize(mApp);
//        mTargetParent.getWindowVisibleDisplayFrame(mWindowRect);
//
//        mPopupWindow.setWidth(popupDisplay.x == mWindowRect.right ? popupDisplay.x : mWindowRect.right);
//
//        int offsetY = 0;
//        if (popupDisplay.y != mWindowRect.bottom) {
//            // if there is a Navigation Bar, Snackbar should show above it.
//            offsetY = popupDisplay.y - mWindowRect.bottom;
//        }

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

//        try {
//            mPopupWindow.showAtLocation(mTargetParent, Gravity.START | Gravity.BOTTOM, 0, offsetY);
//        } catch (WindowManager.BadTokenException e) {
//            e.printStackTrace();
//            return;
//        }

//        if (DeviceUtil.hasLollipopApi()) {
//            mContentView.setTranslationY(mHeight);
//            mContentView.animate().translationY(0).setDuration(200);
//        }

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
            if (mDismissCallback != null) {
                mDismissCallback.onDismiss();
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