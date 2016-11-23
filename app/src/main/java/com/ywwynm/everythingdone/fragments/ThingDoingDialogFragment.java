package com.ywwynm.everythingdone.fragments;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2016/11/23.
 * A DialogFragment for a {@link Thing} where user can change doing strategies or start doing
 * that thing directly
 */
public class ThingDoingDialogFragment extends BaseDialogFragment {

    public static final String TAG = "ThingDoingDialogFragment";

    private Thing mThing;

    private DetailActivity mActivity;

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

        findViews();
        initUI();
        setEvents();

        return mContentView;
    }

    private void findViews() {
        mCvStartAsBt = f(R.id.cv_start_doing_as_bt_dialog);
    }

    private void initUI() {
        mCvStartAsBt.setCardBackgroundColor(mThing.getColor());
    }

    private void setEvents() {
        stimulateFeedbackForUserTouch(mCvStartAsBt);
        mCvStartAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

            }
        });
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
