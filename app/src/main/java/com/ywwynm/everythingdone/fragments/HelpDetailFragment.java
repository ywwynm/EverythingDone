package com.ywwynm.everythingdone.fragments;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.HelpActivity;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

/**
 * Created by ywwynm on 2016/6/27.
 * A fragment used to show help detail information
 */
public class HelpDetailFragment extends Fragment {

    public static final String TAG = "HelpDetailFragment";

    public static HelpDetailFragment newInstance(String[] titles, String[] contents, int position) {
        Bundle args = new Bundle();
        args.putStringArray(Def.Communication.KEY_HELP_TITLES,   titles);
        args.putStringArray(Def.Communication.KEY_HELP_CONTENTS, contents);
        args.putInt(Def.Communication.KEY_POSITION, position);
        HelpDetailFragment fragment = new HelpDetailFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View contentView = inflater.inflate(R.layout.fragment_help_detail, container, false);

        HelpActivity activity = (HelpActivity) getActivity();
        activity.updateActionBarTitle(true);
        activity.setRecyclerViewFocusable(false);

        Bundle args = getArguments();
        String[] titles   = args.getStringArray(Def.Communication.KEY_HELP_TITLES);
        String[] contents = args.getStringArray(Def.Communication.KEY_HELP_CONTENTS);
        if (titles == null || contents == null || titles.length != contents.length) {
            return contentView;
        }

        int pos = args.getInt(Def.Communication.KEY_POSITION);

        final int color = ContextCompat.getColor(getActivity(), R.color.blue_deep);

        View[] pages = new View[titles.length];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = inflater.inflate(R.layout.include_help_detail_content, container, false);
            ScrollView sv = (ScrollView) pages[i].findViewById(R.id.sv_help_detail);
            EdgeEffectUtil.forScrollView(sv, color);

            TextView tvTitle = (TextView) pages[i].findViewById(R.id.tv_title_help_detail);
            tvTitle.setText(titles[i]);

            TextView tvContent = (TextView) pages[i].findViewById(R.id.tv_title_help_content);
            tvContent.setText(contents[i]);
        }

        ViewPager vp = (ViewPager) contentView.findViewById(R.id.vp_help_detail);
        vp.setAdapter(new HelpDetailPagerAdapter(pages));
        vp.setCurrentItem(pos);
        EdgeEffectUtil.forViewPager(vp, color);

        return contentView;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        HelpActivity helpActivity = (HelpActivity) getActivity();
        helpActivity.updateActionBarTitle(false);
        helpActivity.setRecyclerViewFocusable(true);
    }

    static class HelpDetailPagerAdapter extends PagerAdapter {

        View[] mPages;

        HelpDetailPagerAdapter(View[] pages) {
            mPages = pages;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            final View view = mPages[position];
            container.addView(view);
            return view;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView(mPages[position]);
        }

        @Override
        public int getCount() {
            return mPages.length;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    }
}
