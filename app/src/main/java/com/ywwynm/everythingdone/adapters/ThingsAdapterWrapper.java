package com.ywwynm.everythingdone.adapters;

import android.support.v7.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by qiizhang on 2016/9/19.
 * Wrapper of ThingsAdapter
 */
public class ThingsAdapterWrapper {

    private ThingsAdapter mAdapter;

    private boolean mShouldWaitNotify = false;

    interface NotifyAction {
        void notifyAdapter();
    }
    private List<NotifyAction> mNotifyActions;

    public ThingsAdapterWrapper(ThingsAdapter adapter) {
        mAdapter = adapter;
        mNotifyActions = new ArrayList<>();
    }

    public void setShouldWaitNotify(boolean shouldWaitNotify) {
        mShouldWaitNotify = shouldWaitNotify;
    }

    public void attachToRecyclerView(RecyclerView recyclerView) {
        recyclerView.setAdapter(mAdapter);
    }

    public void tryToNotify() {
        if (!mNotifyActions.isEmpty()) {
            mNotifyActions.get(mNotifyActions.size() - 1).notifyAdapter();
            mNotifyActions.clear();
        }
    }

    public void clearNotify() {
        mNotifyActions.clear();
    }

    public int getItemCount() {
        return mAdapter.getItemCount();
    }

    public boolean shouldThingsAnimWhenAppearing() {
        return mAdapter.shouldThingsAnimWhenAppearing();
    }

    public void setShouldThingsAnimWhenAppearing(boolean shouldThingsAnimWhenAppearing) {
        mAdapter.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing);
    }

    public void notifyDataSetChanged() {
        if (mShouldWaitNotify) {
            mNotifyActions.add(new NotifyAction() {
                @Override
                public void notifyAdapter() {
                    mAdapter.notifyDataSetChanged();
                }
            });
        } else {
            mAdapter.notifyDataSetChanged();
            clearNotify();
        }
    }

    public void notifyItemInserted(final int position) {
        if (mShouldWaitNotify) {
            mNotifyActions.add(new NotifyAction() {
                @Override
                public void notifyAdapter() {
                    mAdapter.notifyItemInserted(position);
                }
            });
        } else {
            mAdapter.notifyItemInserted(position);
        }
    }

    public void notifyItemChanged(final int position) {
        if (mShouldWaitNotify) {
            mNotifyActions.add(new NotifyAction() {
                @Override
                public void notifyAdapter() {
                    mAdapter.notifyItemChanged(position);
                }
            });
        } else {
            mAdapter.notifyItemChanged(position);
        }
    }

    public void notifyItemRemoved(final int position) {
        if (mShouldWaitNotify) {
            mNotifyActions.add(new NotifyAction() {
                @Override
                public void notifyAdapter() {
                    mAdapter.notifyItemRemoved(position);
                }
            });
        } else {
            mAdapter.notifyItemRemoved(position);
        }
    }

    public void notifyItemMoved(int from, int to) {
        mAdapter.notifyItemMoved(from, to);
    }

}
