package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.ywwynm.everythingdone.R;

/**
 * Created by ywwynm on 2016/3/8.
 * Habit record adapter for habit detail dialog fragment.
 */
public class HabitRecordAdapter extends RecyclerView.Adapter<HabitRecordAdapter.ImageViewHolder> {

    public static final String TAG = "HabitRecordAdapter";

    private LayoutInflater mInflater;
    private String mRecord;

    public HabitRecordAdapter(Context context, String record) {
        mInflater = LayoutInflater.from(context);
        mRecord = record;
    }

    @Override
    public ImageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ImageViewHolder(mInflater.inflate(R.layout.rv_habit_record, parent, false));
    }

    @Override
    public void onBindViewHolder(ImageViewHolder holder, int position) {
        char s = mRecord.charAt(position);
        if (s == '0') {
            holder.iv.setImageResource(R.drawable.ic_habit_record_unfinished);
        } else if (s == '1') {
            holder.iv.setImageResource(R.drawable.ic_habit_record_finished);
        } else {
            holder.iv.setImageResource(R.drawable.ic_habit_record_unknown);
        }
    }

    @Override
    public int getItemCount() {
        return mRecord.length();
    }

    static class ImageViewHolder extends BaseViewHolder {

        final ImageView iv;

        public ImageViewHolder(View itemView) {
            super(itemView);
            iv = f(R.id.iv_habit_record);
        }
    }
}
