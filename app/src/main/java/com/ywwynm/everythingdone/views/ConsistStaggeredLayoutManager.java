package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;

/**
 * Created by qiizhang on 2016/9/19.
 * A subclass of {@link StaggeredGridLayoutManager} that ignore inconsistency detection in
 * its {@link #onLayoutChildren(RecyclerView.Recycler, RecyclerView.State)} method.
 */
public class ConsistStaggeredLayoutManager extends StaggeredGridLayoutManager {

    public ConsistStaggeredLayoutManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        try {
            super.onLayoutChildren(recycler, state);
        } catch (IndexOutOfBoundsException ignored) { }
    }
}
