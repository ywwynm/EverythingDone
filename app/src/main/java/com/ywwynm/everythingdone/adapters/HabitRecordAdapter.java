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
    private String mOriRecord;

    private boolean mEditable;

    public HabitRecordAdapter(Context context, String record, boolean editable) {
        mInflater = LayoutInflater.from(context);
        mRecord = record;
        mOriRecord = record;
        mEditable = editable;
    }

    public String getRecord() {
        return mRecord;
    }

    public boolean hasRecordEdited() {
        return !mRecord.equals(mOriRecord);
    }

    @Override
    public ImageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ImageViewHolder(mInflater.inflate(R.layout.rv_habit_record, parent, false));
    }

    @Override
    public void onBindViewHolder(final ImageViewHolder holder, int position) {
        final int len = mRecord.length();
        final int correctPos;
        if (len <= 30) {
            correctPos = position;
        } else {
            correctPos = len - 30 + position;
        }

        if (correctPos >= len) {
            holder.iv.setImageResource(R.drawable.ic_habit_record_unknown);
            holder.iv.setOnClickListener(null);
        } else {
            char s = mRecord.charAt(correctPos);
            if (s == '0') {
                holder.iv.setImageResource(R.drawable.ic_habit_record_unfinished);
            } else if (s == '1') {
                holder.iv.setImageResource(R.drawable.ic_habit_record_finished);
            }

            if (mEditable && (len - correctPos <= 6) && (s == '0' || s == '1')) {
                holder.iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        char s = mRecord.charAt(correctPos);
                        StringBuilder sb = new StringBuilder(mRecord);
                        if (s == '0') {
                            sb.setCharAt(correctPos, '1');
                        } else if (s == '1') {
                            sb.setCharAt(correctPos, '0');
                        }
                        mRecord = sb.toString();
                        notifyItemChanged(holder.getAdapterPosition());
                    }
                });
            }
        }
    }

    @Override
    public int getItemCount() {
        return 30;
    }

    static class ImageViewHolder extends BaseViewHolder {

        final ImageView iv;

        ImageViewHolder(View itemView) {
            super(itemView);
            iv = f(R.id.iv_habit_record);
        }
    }
}
