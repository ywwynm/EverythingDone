package com.ywwynm.everythingdone.adapters;

import android.support.v7.widget.RecyclerView;
import android.view.ViewGroup;

import java.util.List;

/**
 * Created by ywwynm on 2015/9/16.
 * Adapter to offer multi select for items.
 */
public abstract class MultiChoiceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final String TAG = "MultiChoiceAdapter";

    protected boolean[] mPicked;

    @Override
    public abstract RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType);

    @Override
    public abstract void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position);

    @Override
    public abstract int getItemCount();

    public void togglePick(int position) {
        mPicked[position] = !mPicked[position];
        notifyItemChanged(position);
    }

    public void pickAll() {
        for (int i = 0; i < mPicked.length; i++) {
            mPicked[i] = true;
        }
    }

    public void pick(List<Integer> positions) {
        for (Integer position : positions) {
            mPicked[position] = true;
        }
    }
}
