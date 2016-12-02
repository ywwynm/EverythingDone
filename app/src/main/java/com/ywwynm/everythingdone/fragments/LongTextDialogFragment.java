package com.ywwynm.everythingdone.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

/**
 * Created by ywwynm on 2016/4/23.
 * long text dialog fragment
 */
public class LongTextDialogFragment extends BaseDialogFragment {

    public static final String TAG = "LongTextDialogFragment";

    private int mAccentColor;

    private String mTitle;
    private String mContent;
    private String mConfirmText = null;

    private boolean mShowCancel = false;

    private View.OnClickListener mConfirmListener;
    private View.OnClickListener mCancelListener;

    private boolean mConfirmed = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView tvTitle       = f(R.id.tv_title_long_text);
        TextView tvContent     = f(R.id.tv_content_long_text);
        TextView tvCancelAsBt  = f(R.id.tv_cancel_as_bt_long_text);
        TextView tvConfirmAsBt = f(R.id.tv_confirm_as_bt_long_text);

        if (mTitle != null) {
            tvTitle.setTextColor(mAccentColor);
            tvTitle.setText(mTitle);
        } else {
            tvTitle.setVisibility(View.GONE);
        }

        if (mContent != null) {
            tvContent.setText(mContent);
        } else {
            tvContent.setVisibility(View.GONE);
        }

        if (mShowCancel) {
            tvCancelAsBt.setVisibility(View.VISIBLE);
            tvCancelAsBt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }

        if (mConfirmText != null) {
            tvConfirmAsBt.setText(mConfirmText);
        }
        tvConfirmAsBt.setTextColor(mAccentColor);
        tvConfirmAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConfirmListener != null) {
                    mConfirmListener.onClick(v);
                }
                mConfirmed = true;
                dismiss();
            }
        });

        final View v1 = f(R.id.view_separator_1);
        final View v2 = f(R.id.view_separator_2);
        final ScrollView sv = f(R.id.sv_long_text);
        sv.getViewTreeObserver().addOnScrollChangedListener(
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        int scrollY = sv.getScrollY();
                        if (scrollY <= 0) {
                            v1.setVisibility(View.INVISIBLE);
                            v2.setVisibility(View.VISIBLE);
                        } else if (scrollY >= sv.getChildAt(0).getHeight() - sv.getHeight()) {
                            v1.setVisibility(View.VISIBLE);
                            v2.setVisibility(View.INVISIBLE);
                        } else {
                            v1.setVisibility(View.VISIBLE);
                            v2.setVisibility(View.VISIBLE);
                        }
                    }
                }
        );
        EdgeEffectUtil.forScrollView(sv, mAccentColor);

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_long_text;
    }

    @Override
    public void onResume() {
        super.onResume();
        float screenDensity = DisplayUtil.getScreenDensity(getActivity());
        Window window = getDialog().getWindow();
        if (window != null) {
            window.setLayout((int) (screenDensity * 320), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (!mConfirmed && mCancelListener != null) {
            mCancelListener.onClick(f(R.id.tv_cancel_as_bt_long_text));
        }
        mConfirmListener = null;
        mCancelListener  = null;
        super.onDismiss(dialog);
    }

    public void setAccentColor(int accentColor) {
        mAccentColor = accentColor;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public void setContent(String content) {
        mContent = content;
    }

    public void setConfirmText(String confirmText) {
        mConfirmText = confirmText;
    }

    public void setShowCancel(boolean showCancel) {
        mShowCancel = showCancel;
    }

    public void setConfirmListener(View.OnClickListener confirmListener) {
        mConfirmListener = confirmListener;
    }

    public void setCancelListener(View.OnClickListener cancelListener) {
        mCancelListener = cancelListener;
    }
}
