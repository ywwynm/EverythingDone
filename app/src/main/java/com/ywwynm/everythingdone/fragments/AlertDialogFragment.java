package com.ywwynm.everythingdone.fragments;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2015/10/9.
 * A subclass of {@link DialogFragment} to show alert information.
 */
public class AlertDialogFragment extends BaseDialogFragment {

    public static final String TAG = "AlertDialogFragment";

    private int[] mColors = new int[] { Color.BLACK, 0, Color.BLACK };

    private String mTitle;
    private String mContent;

    private String mConfirmText;
    private String mCancelText;

    private boolean mShowCancel = true;
    private ConfirmListener mConfirmListener;
    private CancelListener mCancelListener;
    private boolean mConfirmed = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        Activity activity = getActivity();
        if (mColors[1] == 0) {
            int contentColor = ContextCompat.getColor(activity, R.color.black_54p);
            mColors[1] = contentColor;
        }

        TextView tvTitle       = f(R.id.tv_title_alert);
        TextView tvContent     = f(R.id.tv_content_alert);
        TextView tvConfirmAsBt = f(R.id.tv_confirm_as_bt_alert);
        TextView tvCancelAsBt  = f(R.id.tv_cancel_as_bt_alert);

        if (mTitle != null) {
            tvTitle.setText(mTitle);
            applyAccent(tvTitle, mTitleBg, mColors[0]);
        } else {
            tvTitle.setVisibility(View.GONE);
        }

        if (mContent != null) {
            tvContent.setTextColor(mColors[1]);
            tvContent.setText(mContent);
        } else {
            tvContent.setVisibility(View.GONE);
        }

        if (mConfirmText != null) {
            tvConfirmAsBt.setText(mConfirmText);
        }
        applyAccent(tvConfirmAsBt, mConfirmBg, mColors[2]);
        tvConfirmAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConfirmListener != null) {
                    mConfirmListener.onConfirm();
                }
                mConfirmed = true;
                dismiss();
            }
        });

        if (mShowCancel) {
            if (mCancelText != null) {
                tvCancelAsBt.setText(mCancelText);
            }
            tvCancelAsBt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        } else {
            tvCancelAsBt.setVisibility(View.GONE);
        }

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_alert;
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
            mCancelListener.onCancel();
        }
        mConfirmListener = null;
        mCancelListener  = null;
        super.onDismiss(dialog);
    }

    /**
     * Phase 8: paint {@code tv} with {@code bg} if non-null (PURE → single int,
     * GRADIENT → glyph-fill shader via {@link BackgroundUtil#applyTextBackground}).
     * Falls back to {@code fallbackColor} (the legacy int setter result) when
     * {@code bg} is null.
     */
    private void applyAccent(TextView tv, com.ywwynm.everythingdone.model.ThingBackground bg,
                             int fallbackColor) {
        if (bg != null) {
            com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(tv, bg);
        } else {
            tv.setTextColor(fallbackColor);
        }
    }

    /** Phase 8: backing fields for the ThingBackground-typed setters below. */
    private com.ywwynm.everythingdone.model.ThingBackground mTitleBg;
    private com.ywwynm.everythingdone.model.ThingBackground mConfirmBg;

    public void setTitleBackground(com.ywwynm.everythingdone.model.ThingBackground bg) {
        mTitleBg = bg;
        if (bg != null) mColors[0] = bg.representativeColor();
    }

    public void setConfirmBackground(com.ywwynm.everythingdone.model.ThingBackground bg) {
        mConfirmBg = bg;
        if (bg != null) mColors[2] = bg.representativeColor();
    }

    public void setTitleColor(int color) {
        mTitleBg = null;
        mColors[0] = color;
    }

    public void setContentColor(int color) {
        mColors[1] = color;
    }

    public void setConfirmColor(int color) {
        mConfirmBg = null;
        mColors[2] = color;
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

    public void setConfirmListener(ConfirmListener listener) {
        mConfirmListener = listener;
    }

    public void setCancelText(String cancelText) {
        mCancelText = cancelText;
    }

    public void setCancelListener(CancelListener listener) {
        mCancelListener = listener;
    }

    public void setShowCancel(boolean showCancel) {
        mShowCancel = showCancel;
    }

    public interface ConfirmListener {
        void onConfirm();
    }

    public interface CancelListener {
        void onCancel();
    }
}
