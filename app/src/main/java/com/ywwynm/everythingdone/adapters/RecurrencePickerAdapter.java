package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
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

    public static final int NORMAL       = 0;
    public static final int END_OF_MONTH = 1;

    private Context mContext;

    private LayoutInflater mInflater;

    private int mType;

    private String[] mItems;

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

        if (type == Def.PickerType.DAY_OF_WEEK) {
            mItems = context.getResources().getStringArray(R.array.day_of_week);
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
            for (int i = 0; i < 27; i++) {
                mItems[i] = String.valueOf(i + 1);
            }
            mItems[27] = context.getString(R.string.end_of_month);
        } else {
            mItems = context.getResources().getStringArray(R.array.month_of_year);
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
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == NORMAL) {
            return new NormalViewHolder(mInflater.inflate(
                    R.layout.recurrence_picker_normal, parent, false));
        } else {
            return new EndOfMonthViewHolder(mInflater.inflate(
                    R.layout.recurrence_picker_end_of_month, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
        int unPickerColor = ContextCompat.getColor(mContext, R.color.bg_unpicked);
        int black_54 = ContextCompat.getColor(mContext, R.color.black_54);
        if (getItemViewType(position) == END_OF_MONTH) {
            EndOfMonthViewHolder holder = (EndOfMonthViewHolder) viewHolder;
            if (mPicked[position]) {
                holder.cv.setCardBackgroundColor(mAccentColor);
                DisplayUtil.setRippleColorForCardView(holder.cv, unPickerColor);
                holder.tv.setTextColor(Color.WHITE);
            } else {
                holder.cv.setCardBackgroundColor(unPickerColor);
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
            } else {
                holder.fab.setBackgroundTintList(ColorStateList.valueOf(unPickerColor));
                holder.fab.setRippleColor(mAccentColor);
                holder.tvDate.setTextColor(black_54);
            }
        }
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

    class NormalViewHolder extends RecyclerView.ViewHolder {

        final FloatingActionButton fab;
        final TextView tvDate;

        public NormalViewHolder(View itemView) {
            super(itemView);
            fab = (FloatingActionButton) itemView.findViewById(R.id.fab_recurrence_picker);
            tvDate = (TextView) itemView.findViewById(R.id.tv_recurrence_picker);

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

    class EndOfMonthViewHolder extends RecyclerView.ViewHolder {

        final CardView cv;
        final TextView tv;

        public EndOfMonthViewHolder(View itemView) {
            super(itemView);
            cv = (CardView) itemView.findViewById(R.id.cv_end_of_month_rec);
            tv = (TextView) itemView.findViewById(R.id.tv_end_of_month_rec);
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
