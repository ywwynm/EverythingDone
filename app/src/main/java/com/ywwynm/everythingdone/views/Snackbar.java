package com.ywwynm.everythingdone.views;

import android.graphics.Point;
import android.graphics.Rect;
import android.support.annotation.StringRes;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.VersionUtil;

/**
 * Created by ywwynm on 2015/7/4.
 * A simple Snackbar inspired by Material Design based on PopupWindow.
 */
public class Snackbar {

    public static final String TAG = "Snackbar";

    public static final int NORMAL = 0;
    public static final int UNDO  = 1;

    private EverythingDoneApplication mApplication;
    private int mType;
    private float mHeight;

    private Rect mWindowRect;
    private Thread mHideThread;

    private RelativeLayout mContentLayout;
    private TextView mTvMessage;
    private Button mBtUndo;
    private View mDecorView;
    private PopupWindow mPopupWindow;

    private FloatingActionButton mBindingFab;

    public interface DismissCallback {
        void onDismiss();
    }
    private DismissCallback mDismissCallback;

    public void setDismissCallback(DismissCallback dismissCallback) {
        mDismissCallback = dismissCallback;
    }

    public Snackbar(EverythingDoneApplication application, int type, View decorView,
                    FloatingActionButton bindingFab) {
        mApplication = application;
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
        View inflater = LayoutInflater.from(decorView.getContext()).inflate(layoutId, null);
        mTvMessage = (TextView) inflater.findViewById(R.id.tv_message);
        if (mType == UNDO) {
            mBtUndo = (Button) inflater.findViewById(R.id.bt_undo);
        }
        mDecorView = decorView;
        mPopupWindow = new PopupWindow(inflater,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!VersionUtil.hasLollipopApi()) {
            mPopupWindow.setAnimationStyle(R.style.SnackbarAnimation);
        } else {
            mContentLayout = (RelativeLayout) inflater.findViewById(R.id.rl_snackbar);
            mPopupWindow.setAnimationStyle(R.style.SnackbarAnimationOnlyExit);
        }
        mBindingFab = bindingFab;
        mHeight = mApplication.getResources().getDimension(R.dimen.sb_height);
    }

    public void show() {
        if (mPopupWindow.isShowing()) {
            return;
        }

        Point popupDisplay = DisplayUtil.getDisplaySize(mApplication);
        mDecorView.getWindowVisibleDisplayFrame(mWindowRect);

        mPopupWindow.setWidth(popupDisplay.x == mWindowRect.right ? popupDisplay.x : mWindowRect.right);

        int offsetY = 0;
        if (popupDisplay.y != mWindowRect.bottom) {
            // if there is a Navigation Bar, Snackbar should show above it.
            offsetY = popupDisplay.y - mWindowRect.bottom;
        }

        if (mBindingFab != null &&
                mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
            mBindingFab.showFromBottom();
            mBindingFab.raise(mHeight);
        }

        try {
            mPopupWindow.showAtLocation(mDecorView, Gravity.START | Gravity.BOTTOM, 0, offsetY);
        } catch (WindowManager.BadTokenException e) {
            e.printStackTrace();
            return;
        }

        if (VersionUtil.hasLollipopApi()) {
            mContentLayout.setTranslationY(mHeight);
            mContentLayout.animate().translationY(0).setDuration(200);
        }

        if (mType == NORMAL) {
            mDecorView.postDelayed(mHideThread, 1200 + 160);
        }
    }

    public void dismiss() {
        mPopupWindow.dismiss();
        if (mType == NORMAL && mHideThread != null) {
            mHideThread.interrupt();
        }
        if (mBindingFab != null &&
                mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
            mBindingFab.fall();
        }
        if (mDismissCallback != null) {
            mDismissCallback.onDismiss();
        }
    }

    public boolean isShowing() {
        return mPopupWindow.isShowing();
    }

    public void setUndoListener(View.OnClickListener onClickListener) {
        if (mType == NORMAL) {
            throw new IllegalStateException("Type must be Snackbar.UNDO");
        }
        mBtUndo.setOnClickListener(onClickListener);
    }

    public void setMessage(@StringRes int stringRes) {
        mTvMessage.setText(mApplication.getString(stringRes));
    }

    public void setMessage(String msg) {
        mTvMessage.setText(msg);
    }

    public void setUndoText(@StringRes int stringRes) {
        setUndoText(mApplication.getString(stringRes));
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