package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2015/9/16.
 * Adapter for RecyclerView used to pick time for recurrence.
 */
public class RecurrencePickerAdapter extends MultiChoiceAdapter {

    public static final String TAG = "RecurrencePickerAdapter";

    private static final int NORMAL       = 0;
    private static final int END_OF_MONTH = 1;

    private Context mContext;

    private LayoutInflater mInflater;

    private int mType;

    private String mCdPicked;
    private String mCdUnpicked;

    private String[] mItems;
    private String[] mCds; // content descriptions

    private int mAccentColor;

    private float mScreenDensity;

    private View.OnClickListener mOnPickListener;

    public void setOnPickListener(View.OnClickListener onPickListener) {
        mOnPickListener = onPickListener;
    }

    public RecurrencePickerAdapter(Context context, int type, int accentColor) {
        mContext = context;
        mInflater = LayoutInflater.from(context);

        mType = type;

        mCdPicked = mContext.getString(R.string.cd_picked);
        mCdUnpicked = mContext.getString(R.string.cd_unpicked);

        if (type == Def.PickerType.DAY_OF_WEEK) {
            mItems = context.getResources().getStringArray(R.array.day_of_week); // 周日, Sunday
            mCds = context.getResources().getStringArray(R.array.day_of_week);
            if (LocaleUtil.isChinese(context)) {
                for (int i = 0; i < mItems.length; i++) {
                    mItems[i] = mItems[i].substring(1, 2);
                }
            } else {
                for (int i = 0; i < mItems.length; i++) {
                    mItems[i] = mItems[i].substring(0, 3);
                }
            }
        } else if (type == Def.PickerType.DAY_OF_MONTH) {
            mItems = new String[28];
            mCds = new String[28];
            for (int i = 0; i < 27; i++) {
                mItems[i] = String.valueOf(i + 1);
            }
            String day = mContext.getString(R.string.cd_day);
            if (LocaleUtil.isChinese(mContext)) {
                for (int i = 0; i < 27; i++) {
                    mCds[i] = String.valueOf(i + 1) + day;
                }
            } else {
                for (int i = 0; i < 27; i++) {
                    mCds[i] = day + String.valueOf(i + 1);
                }
            }
            mItems[27] = context.getString(R.string.end_of_month);
            mCds[27] = mItems[27];
        } else {
            mItems = context.getResources().getStringArray(R.array.month_of_year);
            mCds = context.getResources().getStringArray(R.array.month_of_year);
            if (!LocaleUtil.isChinese(context)) {
                for (int i = 0; i < mItems.length; i++) {
                    mItems[i] = mItems[i].substring(0, 3);
                }
            }
        }
        mPicked = new boolean[mItems.length];

        mAccentColor = accentColor;
        mScreenDensity = DisplayUtil.getScreenDensity(context);
    }

    @Override
    public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == NORMAL) {
            return new NormalViewHolder(mInflater.inflate(
                    R.layout.recurrence_picker_normal, parent, false));
        } else {
            return new EndOfMonthViewHolder(mInflater.inflate(
                    R.layout.recurrence_picker_end_of_month, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
        int unPickerColor = ContextCompat.getColor(mContext, R.color.bg_unpicked);
        int black_54 = ContextCompat.getColor(mContext, R.color.black_54);
        if (getItemViewType(position) == END_OF_MONTH) {
            EndOfMonthViewHolder holder = (EndOfMonthViewHolder) viewHolder;
            if (mPicked[position]) {
                holder.cv.setCardBackgroundColor(mAccentColor);
                holder.cv.setContentDescription(mCdPicked + mCds[position] + ",");
                DisplayUtil.setRippleColorForCardView(holder.cv, unPickerColor);
                holder.tv.setTextColor(Color.WHITE);
            } else {
                holder.cv.setCardBackgroundColor(unPickerColor);
                holder.cv.setContentDescription(mCdUnpicked + mCds[position] + ",");
                DisplayUtil.setRippleColorForCardView(holder.cv, mAccentColor);
                holder.tv.setTextColor(black_54);
            }
        } else {
            NormalViewHolder holder = (NormalViewHolder) viewHolder;
            if (mType == Def.PickerType.DAY_OF_MONTH) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.fab.getLayoutParams();
                params.width = (int) (mScreenDensity * 36);
                params.height = params.width;
            }
            holder.tvDate.setText(mItems[position]);
            if (mPicked[position]) {
                holder.fab.setBackgroundTintList(ColorStateList.valueOf(mAccentColor));
                holder.fab.setRippleColor(unPickerColor);
                holder.tvDate.setTextColor(Color.WHITE);
                holder.fab.setContentDescription(mCdPicked + mCds[position] + ",");
            } else {
                holder.fab.setBackgroundTintList(ColorStateList.valueOf(unPickerColor));
                holder.fab.setRippleColor(mAccentColor);
                holder.tvDate.setTextColor(black_54);
                holder.fab.setContentDescription(mCdUnpicked + mCds[position] + ",");
            }
        }
        viewHolder.itemView.setContentDescription(
                (mPicked[position] ? mCdPicked : mCdUnpicked) + mCds[position] + ",");
    }

    @Override
    public int getItemCount() {
        return mItems.length;
    }

    @Override
    public int getItemViewType(int position) {
        if (mType == Def.PickerType.DAY_OF_MONTH && position == 27) {
            return END_OF_MONTH;
        } else return NORMAL;
    }

    public List<Integer> getPickedIndexes() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < mPicked.length; i++) {
            if (mPicked[i]) {
                list.add(i);
            }
        }
        return list;
    }

    public int getPickedCount() {
        int count = 0;
        for (boolean b : mPicked) {
            if (b) count++;
        }
        return count;
    }

    private class NormalViewHolder extends BaseViewHolder {

        final FloatingActionButton fab;
        final TextView tvDate;

        NormalViewHolder(View itemView) {
            super(itemView);
            fab = f(R.id.fab_recurrence_picker);
            tvDate = f(R.id.tv_recurrence_picker);

            fab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePick(getAdapterPosition());
                    if (mOnPickListener != null) {
                        mOnPickListener.onClick(v);
                    }
                }
            });
        }
    }

    private class EndOfMonthViewHolder extends BaseViewHolder {

        final CardView cv;
        final TextView tv;

        EndOfMonthViewHolder(View itemView) {
            super(itemView);
            cv = f(R.id.cv_end_of_month_rec);
            tv = f(R.id.tv_end_of_month_rec);

            cv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePick(getAdapterPosition());
                    if (mOnPickListener != null) {
                        mOnPickListener.onClick(v);
                    }
                }
            });
        }
    }
}
