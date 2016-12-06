package com.ywwynm.everythingdone.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.DrawableRes;
import android.support.annotation.StringRes;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.KeyboardUtil;

/**
 * Created by ywwynm on 2015/9/20.
 * Shown when user clicks {@link android.text.style.URLSpan}s in
 * {@link com.ywwynm.everythingdone.activities.DetailActivity}.
 */
public class TwoOptionsDialogFragment extends BaseDialogFragment {

    public static final String TAG = "TwoOptionsDialogFragment";

    private boolean mShouldShowKeyboardAfterDismiss = false;
    private View mViewToFocusAfterDismiss;

    private int mIconResStart, mIconResEnd;
    private int mActionResStart, mActionResEnd;
    private View.OnClickListener mListenerStart, mListenerEnd;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView tvStart = f(R.id.tv_action_start);
        TextView tvEnd   = f(R.id.tv_action_end);

        if (mIconResStart != 0) {
            tvStart.setCompoundDrawablesWithIntrinsicBounds(0, mIconResStart, 0, 0);
        }
        if (mActionResStart != 0) {
            tvStart.setText(mActionResStart);
        }
        tvStart.setOnClickListener(mListenerStart);

        if (mIconResEnd != 0) {
            tvEnd.setCompoundDrawablesWithIntrinsicBounds(0, mIconResEnd, 0, 0);
        }
        if (mActionResEnd != 0) {
            tvEnd.setText(mActionResEnd);
        }
        tvEnd.setOnClickListener(mListenerEnd);

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_two_action_picker;
    }

    public void setStartAction(@DrawableRes int iconRes, @StringRes int actionRes,
                               View.OnClickListener listener) {
        mIconResStart   = iconRes;
        mActionResStart = actionRes;
        mListenerStart  = listener;
    }

    public void setEndAction(@DrawableRes int iconRes, @StringRes int actionRes,
                             View.OnClickListener listener) {
        mIconResEnd   = iconRes;
        mActionResEnd = actionRes;
        mListenerEnd  = listener;
    }

    public void setShouldShowKeyboardAfterDismiss(boolean shouldShowKeyboardAfterDismiss) {
        mShouldShowKeyboardAfterDismiss = shouldShowKeyboardAfterDismiss;
    }

    public void setViewToFocusAfterDismiss(View viewToFocusAfterDismiss) {
        mViewToFocusAfterDismiss = viewToFocusAfterDismiss;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mShouldShowKeyboardAfterDismiss && mViewToFocusAfterDismiss != null) {
            mViewToFocusAfterDismiss.postDelayed(new Runnable() {
                @Override
                public void run() {
                    KeyboardUtil.showKeyboard(mViewToFocusAfterDismiss);
                }
            }, 60);
        }
        mListenerStart = null;
        mListenerEnd   = null;
        super.onDismiss(dialog);
    }
}
