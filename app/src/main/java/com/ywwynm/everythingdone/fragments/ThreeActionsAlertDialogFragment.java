package com.ywwynm.everythingdone.fragments;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2016/2/5.
 * A subclass of {@link DialogFragment} to show alert information with three actions.
 */
public class ThreeActionsAlertDialogFragment extends BaseDialogFragment {

    public static final String TAG = "ThreeActionsAlertDialogFragment";

    private int[] mColors = new int[] { Color.BLACK, 0, Color.BLACK };

    private String mTitle;
    private String mContent;
    private String mFirstAction;
    private String mSecondAction;

    public interface OnClickListener {
        void onFirstClicked();
        void onSecondClicked();
        void onThirdClicked();
    }
    private OnClickListener mOnClickListener;

    private boolean mContinued = false;

    @Override
    public void onResume() {
        super.onResume();
        float screenDensity = DisplayUtil.getScreenDensity(getActivity());
        Window window = getDialog().getWindow();
        if (window != null) {
            window.setLayout((int) (screenDensity * 320), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        if (mColors[1] == 0) {
            Activity activity = getActivity();
            int contentColor = ContextCompat.getColor(activity, R.color.black_54p);
            mColors[1] = contentColor;
        }

        TextView tvTitle      = f(R.id.tv_title_alert);
        TextView tvContent    = f(R.id.tv_content_alert);
        TextView tvFirstAsBt  = f(R.id.tv_first_as_bt_alert);
        TextView tvSecondAsBt = f(R.id.tv_second_as_bt_alert);
        TextView tvThirdAsBt  = f(R.id.tv_third_as_bt_alert);

        if (mTitle != null) {
            tvTitle.setTextColor(mColors[0]);
            tvTitle.setText(mTitle);
        } else {
            tvTitle.setVisibility(View.GONE);
        }

        if (mContent != null) {
            tvContent.setTextColor(mColors[1]);
            tvContent.setText(mContent);
        } else {
            tvContent.setVisibility(View.GONE);
        }

        if (mFirstAction != null) {
            tvFirstAsBt.setText(mFirstAction);
            tvFirstAsBt.setTextColor(mColors[2]);
            tvFirstAsBt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnClickListener != null) {
                        mOnClickListener.onFirstClicked();
                    }
                    mContinued = true;
                    dismiss();
                }
            });
        } else {
            tvFirstAsBt.setVisibility(View.GONE);
        }

        if (mSecondAction != null) {
            tvSecondAsBt.setText(mSecondAction);
            tvSecondAsBt.setTextColor(mColors[2]);
            tvSecondAsBt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnClickListener != null) {
                        mOnClickListener.onSecondClicked();
                    }
                    mContinued = true;
                    dismiss();
                }
            });
        } else {
            tvSecondAsBt.setVisibility(View.GONE);
        }

        tvThirdAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mOnClickListener != null) {
                    mOnClickListener.onThirdClicked();
                }
                dismiss();
            }
        });

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_alert_three_actions;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (!mContinued && mOnClickListener != null) {
            mOnClickListener.onThirdClicked();
        }
        mOnClickListener = null;
        super.onDismiss(dialog);
    }

    public void setTitleColor(int color) {
        mColors[0] = color;
    }

    public void setContentColor(int color) {
        mColors[1] = color;
    }

    public void setContinueColor(int color) {
        mColors[2] = color;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public void setContent(String content) {
        mContent = content;
    }

    public void setFirstAction(String first) {
        mFirstAction = first;
    }

    public void setSecondAction(String secondAction) {
        mSecondAction = secondAction;
    }

    public void setOnClickListener(OnClickListener onClickListener) {
        mOnClickListener = onClickListener;
    }
}
