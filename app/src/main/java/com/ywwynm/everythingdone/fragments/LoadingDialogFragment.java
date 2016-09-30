package com.ywwynm.everythingdone.fragments;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;

/**
 * Created by ywwynm on 2016/3/21.
 * dialog for loading
 */
public class LoadingDialogFragment extends BaseDialogFragment {

    public static final String TAG = "LoadingDialogFragment";

    private int mAccentColor;

    private String mTitle;
    private String mContent;

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView tvTitle      = f(R.id.tv_title_loading);
        TextView tvContent    = f(R.id.tv_content_loading);
        ProgressBar pbLoading = f(R.id.pb_loading_fragment);

        tvTitle.setTextColor(mAccentColor);
        if (mTitle != null) {
            tvTitle.setText(mTitle);
        }

        if (mContent != null) {
            tvContent.setText(mContent);
        }

        pbLoading.getIndeterminateDrawable().setColorFilter(mAccentColor, PorterDuff.Mode.SRC_IN);

        setCancelable(false);
        getDialog().setCanceledOnTouchOutside(false);

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_loading;
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
