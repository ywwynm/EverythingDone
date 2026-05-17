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
    /** Phase 8: full ThingBackground for gradient title text. ProgressBar
     *  indeterminate drawable PorterDuff filter stays int (API limit). */
    private com.ywwynm.everythingdone.model.ThingBackground mAccentBackground;

    private String mTitle;
    private String mContent;

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView tvTitle      = f(R.id.tv_title_loading);
        TextView tvContent    = f(R.id.tv_content_loading);
        ProgressBar pbLoading = f(R.id.pb_loading_fragment);

        if (mTitle != null) {
            tvTitle.setText(mTitle);
        }
        if (mAccentBackground != null) {
            com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                    tvTitle, mAccentBackground);
        } else {
            tvTitle.setTextColor(mAccentColor);
        }

        if (mContent != null) {
            tvContent.setText(mContent);
        }

        // PorterDuff tint is single-channel — collapse to representative int
        // even when GRADIENT is supplied.
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
        mAccentBackground = null;
    }

    /** Phase 8: gradient-aware accent for the title. */
    public void setAccentBackground(com.ywwynm.everythingdone.model.ThingBackground bg) {
        mAccentBackground = bg;
        if (bg != null) mAccentColor = bg.representativeColor();
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public void setContent(String content) {
        mContent = content;
    }
}
