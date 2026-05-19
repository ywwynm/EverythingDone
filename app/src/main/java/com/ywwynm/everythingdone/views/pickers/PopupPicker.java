package com.ywwynm.everythingdone.views.pickers;

import android.app.Activity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
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

    protected Activity mActivity;
    protected float mScreenDensity;

    protected PopupWindow mPopupWindow;
    protected View mParent;
    /**
     * The on-screen view this picker anchors to. Subclasses position the popup
     * based on this view's location relative to {@link #mParent}: the same
     * "compute anchor's screen coordinates, derive popup offset" path applies
     * to ColorPicker (right-aligned to a toolbar menu item) and DateTimePicker
     * (above a bottom-bar text button). The chosen Gravity flags and any
     * fine-tuning constants stay subclass-private; mAnchor is just the "where".
     */
    protected View mAnchor;
    protected View mContentView;
    protected RecyclerView mRecyclerView;

    public PopupPicker(Activity activity, View parent, int popupAnimStyle) {
        mActivity = activity;
        mScreenDensity = DisplayUtil.getScreenDensity(mActivity);
        mParent = parent;

        mContentView = LayoutInflater.from(activity).inflate(R.layout.rv_popup_picker, null);
        mRecyclerView = (RecyclerView) mContentView.findViewById(R.id.rv_popup_picker);
        mPopupWindow = new PopupWindow(mContentView,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mPopupWindow.setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_picker));
        mContentView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getRepeatCount() == 1) {
                    if (mPopupWindow != null && mPopupWindow.isShowing()) {
                        mPopupWindow.dismiss();
                        return true;
                    }
                }
                return false;
            }
        });
        mPopupWindow.setAnimationStyle(popupAnimStyle);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(true);
        // Keep the soft keyboard visible when this popup is shown on top of
        // an active EditText. INPUT_METHOD_NOT_NEEDED tells the framework
        // "this popup doesn't participate in IME — don't change the IME's
        // visibility because of me." Without this, setFocusable(true)
        // forces the IME to hide as the popup grabs window focus, which on
        // an edge-to-edge activity also re-triggers the bottom-bar inset
        // chain and produces a visible flicker (IME drops, bottom bar
        // drops, popup auto-dismisses mid-show). Matches the pre-edge-to-
        // edge behaviour where the IME and these pickers happily coexisted.
        mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    }

    public void setAnchor(View anchor) {
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
