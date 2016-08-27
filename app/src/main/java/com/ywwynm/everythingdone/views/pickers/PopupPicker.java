package com.ywwynm.everythingdone.views.pickers;

import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2015/8/18.
 * Simple Picker for EverythingDone using a PopupWindow to show contents.
 */
public abstract class PopupPicker {

    public static String TAG = "PopupPicker";

    protected Context mContext;
    protected float mScreenDensity;

    protected PopupWindow mPopupWindow;
    protected View mParent;
    protected Object mAnchor;
    protected View mContentView;
    protected RecyclerView mRecyclerView;

    public PopupPicker(Context context, View parent, int popupAnimStyle) {
        mContext = context;
        mScreenDensity = DisplayUtil.getScreenDensity(mContext);
        mParent = parent;

        mContentView = LayoutInflater.from(context).inflate(R.layout.rv_popup_picker, null);
        mRecyclerView = (RecyclerView) mContentView.findViewById(R.id.rv_popup_picker);
        mPopupWindow = new PopupWindow(mContentView,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mPopupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_picker));
        mContentView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getRepeatCount() == 1) {
                if (mPopupWindow != null && mPopupWindow.isShowing()) {
                    mPopupWindow.dismiss();
                    return true;
                }
            }
            return false;
        });
        mPopupWindow.setAnimationStyle(popupAnimStyle);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(true);
    }

    public void setAnchor(Object anchor) {
        mAnchor = anchor;
    }

    public abstract void updateAnchor();

    public abstract void show();

    public abstract void pickForUI(int index);

    public abstract int getPickedIndex();

    public void dismiss() {
        if (mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
    }

    public boolean isShowing() {
        return mPopupWindow.isShowing();
    }

}
