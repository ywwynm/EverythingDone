package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DeviceUtil;

/**
 * Created by ywwynm on 2016/4/1.
 * statistic adapter
 */
public class StatisticAdapter extends RecyclerView.Adapter<StatisticAdapter.StatisticHolder> {

    private LayoutInflater mInflater;

    private int[] mIconRes;
    private int[] mFirstRes;
    private float[] mFirstTextSizes;
    private String[] mSecondStrs;

    public StatisticAdapter(Context context, int[] iconRes, int[] firstRes, float[] firstTextSizes,
                            String[] secondStrs) {
        mInflater = LayoutInflater.from(context);

        mIconRes = iconRes;
        mFirstRes = firstRes;
        mFirstTextSizes = firstTextSizes;
        mSecondStrs = secondStrs;
    }

    @Override
    public StatisticHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new StatisticHolder(mInflater.inflate(R.layout.rv_statistic, parent, false));
    }

    @Override
    public void onBindViewHolder(StatisticHolder holder, int position) {
        if (DeviceUtil.hasJellyBeanMR1Api()) {
            holder.tvFirst.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    mIconRes[position], 0, 0, 0);
        } else {
            holder.tvFirst.setCompoundDrawablesWithIntrinsicBounds(
                    mIconRes[position], 0, 0, 0);
        }
        holder.tvFirst.setText(mFirstRes[position]);
        if (mFirstTextSizes == null || mFirstTextSizes.length <= position
                || mFirstTextSizes[position] == 0) {
            holder.tvFirst.setTextSize(16);
        } else {
            holder.tvFirst.setTextSize(mFirstTextSizes[position]);
        }

        holder.tvSecond.setText(mSecondStrs[position]);
        if (position == mIconRes.length - 1) {
            holder.vSeparator.setVisibility(View.GONE);
        } else {
            holder.vSeparator.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return mIconRes.length;
    }

    static class StatisticHolder extends BaseViewHolder {

        TextView tvFirst;
        TextView tvSecond;
        View vSeparator;

        StatisticHolder(View itemView) {
            super(itemView);

            tvFirst    = f(R.id.tv_first_rv_statistic);
            tvSecond   = f(R.id.tv_second_rv_statistic);
            vSeparator = f(R.id.view_separator_statistic);
        }
    }

}
