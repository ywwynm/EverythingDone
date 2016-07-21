package com.ywwynm.everythingdone.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.adapters.HabitRecordAdapter;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

/**
 * Created by ywwynm on 2016/3/3.
 * show habit detail in a DialogFragment
 */
public class HabitDetailDialogFragment extends BaseDialogFragment {

    public static final String TAG = "HabitDetailDialogFragment";
    private Habit mHabit;

    private LinearLayout mLlDetail;
    private LinearLayout mLlRecord;

    private TextView     mTvCr;
    private TextView     mTvTotalT;          // 总周期数
    private TextView     mTvPiTs;            // 坚持的周期数
    private TextView     mTvRecordCount;
    private TextView     mTvFinishedTimes;
    private RecyclerView mRvRecord;

    private TextView mTvToggleAsBt;

    public static HabitDetailDialogFragment newInstance() {
        Bundle args = new Bundle();
        HabitDetailDialogFragment fragment = new HabitDetailDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public void setHabit(Habit habit) {
        mHabit = habit;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView title = f(R.id.tv_habit_detail_title);
        DetailActivity activity = (DetailActivity) getActivity();
        int accentColor = activity.getAccentColor();
        title.setTextColor(accentColor);

        mLlDetail        = f(R.id.ll_habit_detail);
        mLlRecord        = f(R.id.ll_habit_record);

        mTvCr            = f(R.id.tv_habit_detail_completion_rate);
        mTvTotalT        = f(R.id.tv_habit_detail_total_t);
        mTvPiTs          = f(R.id.tv_habit_detail_persist_in);
        mTvRecordCount   = f(R.id.tv_habit_detail_record_count);
        mTvFinishedTimes = f(R.id.tv_habit_detail_times);
        mRvRecord        = f(R.id.rv_habit_detail_record);

        mTvToggleAsBt = f(R.id.tv_toggle_check_habit_detail_as_bt);
        mTvToggleAsBt.setTextColor(accentColor);
        mTvToggleAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });

        TextView getIt = f(R.id.tv_get_it_as_bt);
        getIt.setTextColor(accentColor);
        getIt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        initUI();

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_habit_detail;
    }

    @SuppressLint("SetTextI18n")
    private void initUI() {
        mTvCr.setText(mHabit.getCompletionRate());

        Context context = App.getApp();

        int totalT = mHabit.getTotalT();
        mTvTotalT.setText((totalT < 0 ? 0 : totalT) + " " +
                DateTimeUtil.getTimeTypeStr(mHabit.getType(), context));
        if (totalT > 1 && !LocaleUtil.isChinese(context)) {
            mTvTotalT.append("s");
        }

        int piT = mHabit.getPersistInT();
        mTvPiTs.setText((piT < 0 ? 0 : piT) + " " +
                    DateTimeUtil.getTimeTypeStr(mHabit.getType(), context));
        if (piT > 1 && !LocaleUtil.isChinese(context)) {
            mTvPiTs.append("s");
        }

        mTvRecordCount.setText(String.valueOf(mHabit.getRecord().length()));
        mTvFinishedTimes.setText("" + mHabit.getFinishedTimes());

        String record = mHabit.getRecord();
        int len = record.length();
        if (len < 30) {
            int add = 30 - len;
            for (int i = 0; i < add; i++) {
                record += "?";
            }
        } else {
            record = record.substring(len - 30, len);
        }

        Activity activity = getActivity();
        HabitRecordAdapter habitRecordAdapter = new HabitRecordAdapter(activity, record);
        mRvRecord.setAdapter(habitRecordAdapter);
        GridLayoutManager glm = new GridLayoutManager(activity, 6);
        mRvRecord.setLayoutManager(glm);
    }

    private void toggle() {
        if (mLlDetail.getVisibility() == View.VISIBLE) {
            mLlDetail.setVisibility(View.GONE);
            mLlRecord.setVisibility(View.VISIBLE);
            mTvToggleAsBt.setText(R.string.act_check_habit_overview);
        } else {
            mLlDetail.setVisibility(View.VISIBLE);
            mLlRecord.setVisibility(View.GONE);
            mTvToggleAsBt.setText(R.string.act_check_habit_record);
        }
    }
}
