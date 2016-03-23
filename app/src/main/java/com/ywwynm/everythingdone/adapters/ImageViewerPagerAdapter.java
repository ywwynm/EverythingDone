package com.ywwynm.everythingdone.adapters;

import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * Created by ywwynm on 2015/10/11.
 * A subclass of {@link PagerAdapter} for {@link android.support.v4.view.ViewPager}
 * in {@link com.ywwynm.everythingdone.activities.ImageViewerActivity}
 */
public class ImageViewerPagerAdapter extends PagerAdapter {

    public static final String TAG = "ImageViewerPagerAdapter";

    private List<View> mTabs;

    public ImageViewerPagerAdapter(List<View> tabs) {
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
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    public void removeTab(ViewPager viewPager, int index) {
        viewPager.setAdapter(null);
        mTabs.remove(index);
        viewPager.setAdapter(this);
        viewPager.setCurrentItem(index);
    }
}
