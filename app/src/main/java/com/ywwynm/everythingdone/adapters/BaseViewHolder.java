package com.ywwynm.everythingdone.adapters;

import android.support.v7.widget.RecyclerView;
import android.view.View;

/**
 * Created by ywwynm on 2016/6/23.
 * basic ViewHolder which provides f() for a simpler way to findViewById
 */
public class BaseViewHolder extends RecyclerView.ViewHolder {

    protected View mContentView;

    public BaseViewHolder(View itemView) {
        super(itemView);
        mContentView = itemView;
    }

    @SuppressWarnings("unchecked")
    protected final <T extends View> T f(int id) {
        return (T) mContentView.findViewById(id);
    }
}
