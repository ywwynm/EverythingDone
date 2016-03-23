package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.content.res.Resources;
import android.support.v4.view.PagerAdapter;
import android.view.View;
import android.view.ViewGroup;

import com.ywwynm.everythingdone.R;

import java.util.List;

/**
 * Created by ywwynm on 2015/8/14.
 * A subclass of {@link PagerAdapter} for {@link android.support.v4.view.ViewPager}
 * in {@link com.ywwynm.everythingdone.fragments.DateTimeDialogFragment}
 */
public class DateTimePagerAdapter extends PagerAdapter {

    public static final String TAG = "DateTimePagerAdapter";

    private Context mContext;
    private List<View> mTabs;

    public DateTimePagerAdapter(Context context, List<View> tabs) {
        mContext = context;
        mTabs = tabs;
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        final View view = mTabs.get(position);
        container.addView(view, 0);
        return view;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView(mTabs.get(position));
    }

    @Override
    public int getCount() {
        return mTabs.size();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        Resources res = mContext.getResources();
        if (position == 0) {
            return res.getString(R.string.quick_remind_title_at);
        } else if (position == 1) {
            return res.getString(R.string.quick_remind_title_after);
        } else {
            return res.getString(R.string.quick_remind_title_recurrence);
        }
    }
}
