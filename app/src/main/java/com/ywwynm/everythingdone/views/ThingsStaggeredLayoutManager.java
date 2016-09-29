package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.graphics.PointF;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by qiizhang on 2016/9/19.
 * A subclass of {@link StaggeredGridLayoutManager} that ignore inconsistency detection in
 * its {@link #onLayoutChildren(RecyclerView.Recycler, RecyclerView.State)} method.
 */
public class ThingsStaggeredLayoutManager extends StaggeredGridLayoutManager {

    private ThingsSmoothScroller mSmoothScroller;

    public ThingsStaggeredLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    public ThingsStaggeredLayoutManager(int spanCount, int orientation) {
        super(spanCount, orientation);
        init(App.getApp());
    }

    private void init(Context context) {
        mSmoothScroller = new ThingsSmoothScroller(context);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        try {
            super.onLayoutChildren(recycler, state);
        } catch (IndexOutOfBoundsException ignored) { }
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        Context context = recyclerView.getContext();
        int screenHeight = DisplayUtil.getDisplaySize(context).y;
        mSmoothScroller.setScreenHeight(screenHeight);
        mSmoothScroller.setTargetPosition(position);
        startSmoothScroll(mSmoothScroller);
    }

    private class ThingsSmoothScroller extends LinearSmoothScroller {

        private int mScreenHeight;

        ThingsSmoothScroller(Context context) {
            super(context);
        }

        void setScreenHeight(int screenHeight) {
            mScreenHeight = screenHeight;
        }

        @Override
        protected int calculateTimeForScrolling(int dx) {
            if (dx > 2 * mScreenHeight) {
                dx = 2 * mScreenHeight;
            }
            return super.calculateTimeForScrolling(dx);
        }

        @Nullable
        @Override
        public PointF computeScrollVectorForPosition(int targetPosition) {
            return ThingsStaggeredLayoutManager.this
                    .computeScrollVectorForPosition(targetPosition);
        }
    }
}
