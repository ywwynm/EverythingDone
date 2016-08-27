package com.ywwynm.everythingdone.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView tvTitle       = f(R.id.tv_title_long_text);
        TextView tvContent     = f(R.id.tv_content_long_text);
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

        tvConfirmAsBt.setTextColor(mAccentColor);
        tvConfirmAsBt.setOnClickListener(v -> dismiss());

        final View v1 = f(R.id.view_separator_1);
        final View v2 = f(R.id.view_separator_2);
        final ScrollView sv = f(R.id.sv_long_text);
        sv.getViewTreeObserver().addOnScrollChangedListener(
                () -> {
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
        window.setLayout((int) (screenDensity * 320), WindowManager.LayoutParams.WRAP_CONTENT);
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

}
