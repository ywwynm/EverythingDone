package com.ywwynm.everythingdone.fragments;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2016/11/23.
 * A DialogFragment for a {@link Thing} where user can change doing strategies or start doing
 * that thing directly
 */
public class ThingDoingDialogFragment extends BaseDialogFragment {

    public static final String TAG = "ThingDoingDialogFragment";

    private Thing mThing;

    private DetailActivity mActivity;

    ThingDoingHelper mDoingHelper;

    private LinearLayout mLlASD;
    private TextView     mTvASD;

    private LinearLayout mLlASDTime;
    private TextView     mTvASDTime;

    private LinearLayout mLlASM;
    private TextView     mTvASM;

    private CardView mCvStartAsBt;

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_thing_doing;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        mActivity = (DetailActivity) getActivity();

        mDoingHelper = new ThingDoingHelper(mActivity, mThing);

        findViews();
        initUI();
        setEvents();

        return mContentView;
    }

    private void findViews() {
        mLlASD     = f(R.id.ll_auto_start_doing_as_bt);
        mTvASD     = f(R.id.tv_auto_start_doing);
        mLlASDTime = f(R.id.ll_asd_time_as_bt);
        mTvASDTime = f(R.id.tv_asd_time);
        mLlASM     = f(R.id.ll_auto_strict_mode_as_bt);
        mTvASM     = f(R.id.tv_auto_strict_mode);

        mCvStartAsBt = f(R.id.cv_start_doing_as_bt_dialog);
    }

    private void initUI() {
        TextView tvTitle = f(R.id.tv_title_thing_doing);
        tvTitle.setTextColor(mThing.getColor());

        mTvASD.setText(mDoingHelper.getAutoStartDoingDesc());
        mTvASDTime.setText(mDoingHelper.getAutoDoingTimeDesc());
        mTvASM.setText(mDoingHelper.getAutoStrictModeDesc());

        enableOrDisableASDTimeUi();

        mCvStartAsBt.setCardBackgroundColor(mThing.getColor());
    }

    private void enableOrDisableASDTimeUi() {
        int black_54p = ContextCompat.getColor(mActivity, R.color.black_54p);
        int black_26p = ContextCompat.getColor(mActivity, R.color.black_26p);
        int black_14p = ContextCompat.getColor(mActivity, R.color.black_14p);
        int black_10p = ContextCompat.getColor(mActivity, R.color.black_10p);

        TextView tvTitle = f(R.id.tv_asd_time_title);
        if (mDoingHelper.shouldAutoStartDoing()) {
            mLlASDTime.setEnabled(true);
            tvTitle.setTextColor(black_54p);
            mTvASDTime.setTextColor(black_26p);
        } else {
            mLlASDTime.setEnabled(false);
            tvTitle.setTextColor(black_14p);
            mTvASDTime.setTextColor(black_10p);
        }
    }

    private void setEvents() {
        mLlASD.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAutoStartDoingChooser();
            }
        });

        mLlASDTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAutoStartDoingTimeChooser();
            }
        });

        mLlASM.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAutoStrictModeChooser();
            }
        });

        stimulateFeedbackForUserTouch(mCvStartAsBt);
        mCvStartAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ThingDoingHelper helper = new ThingDoingHelper(mActivity, mThing);
                helper.tryToOpenStartDoingActivityUser();
            }
        });
    }

    private void showAutoStartDoingChooser() {
        List<String> items = new ArrayList<>(3);
        items.add(mDoingHelper.getAutoStartDoingFollowGeneralStr());
        items.add(mActivity.getString(R.string.enable));
        items.add(mActivity.getString(R.string.disable));

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mThing.getColor());
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.auto_start_doing_title));
        cdf.setItems(items);
        cdf.setInitialIndex(mDoingHelper.getAutoStartDoingStrategy());
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mDoingHelper.setAutoStartDoingStrategy(cdf.getPickedIndex());
                mTvASD.setText(mDoingHelper.getAutoStartDoingDesc());
                enableOrDisableASDTimeUi();
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void showAutoStartDoingTimeChooser() {
        List<String> items = ThingDoingHelper.getStartDoingTimeItems(mActivity);
        items.add(0, mDoingHelper.getAutoStartDoingTimeFollowGeneralStr());

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mThing.getColor());
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.auto_start_doing_time_title));
        cdf.setItems(items);
        String strategy = mDoingHelper.getAutoDoingTimeStrategy();
        cdf.setInitialIndex(ThingDoingHelper.getStartDoingTimeIndex(strategy, true));
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mDoingHelper.setAutoDoingTimeStrategy(cdf.getPickedIndex());
                mTvASDTime.setText(mDoingHelper.getAutoDoingTimeDesc());
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void showAutoStrictModeChooser() {
        List<String> items = new ArrayList<>(3);
        items.add(mDoingHelper.getAutoStrictModeFollowGeneralStr());
        items.add(mActivity.getString(R.string.enable));
        items.add(mActivity.getString(R.string.disable));

        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mThing.getColor());
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.auto_strict_mode_title));
        cdf.setItems(items);
        cdf.setInitialIndex(mDoingHelper.getAutoStrictModeStrategy());
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mDoingHelper.setAutoStrictModeStrategy(cdf.getPickedIndex());
                mTvASM.setText(mDoingHelper.getAutoStrictModeDesc());
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void stimulateFeedbackForUserTouch(final CardView cv) {
        final float density = DisplayUtil.getScreenDensity(mActivity);
        final int dp2 = (int) (density * 2);
        final int dp3 = (int) (density * 3);
        cv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int action = motionEvent.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    cv.setCardElevation(dp3);
                } else if (action == MotionEvent.ACTION_UP) {
                    cv.setCardElevation(dp2);
                }
                return false;
            }
        });
    }

    public void setThing(Thing thing) {
        mThing = thing;
    }
}
