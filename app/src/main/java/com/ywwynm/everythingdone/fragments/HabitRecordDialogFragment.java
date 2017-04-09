package com.ywwynm.everythingdone.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.adapters.HabitRecordAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.PossibleMistakeHelper;
import com.ywwynm.everythingdone.model.Habit;

/**
 * Created by 张启 on 2017/3/10.
 * DialogFragment to show/edit habit records.
 */
public class HabitRecordDialogFragment extends BaseDialogFragment {

    public static final String TAG = "HabitRecordDialogFragment";

    private Habit mHabit;
    private boolean mEditable;

    private HabitRecordAdapter mHabitRecordAdapter;

    private boolean mConfirmClicked = false;

    // not good practice but I'm so lazy that I don't want to make Habit class parcelable~
    public void setHabit(Habit habit) {
        mHabit = habit;
    }

    // not good practice, either. But I'm so lazy that I just want to use same way as above to do
    // this stuff~ on 2017/2/10.
    // copied from old HabitDetailDialogFragment on 2017/3/10
    public void setEditable(boolean editable) {
        mEditable = editable;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView title = f(R.id.tv_habit_record_title);
        DetailActivity activity = (DetailActivity) getActivity();
        int accentColor = activity.getAccentColor();
        title.setTextColor(accentColor);

        RecyclerView rvRecord = f(R.id.rv_habit_record);

        String record = mHabit.getRecord();
//        int len = record.length();
//        if (len < 30) {
//            StringBuilder sb = new StringBuilder(record);
//            int add = 30 - len;
//            for (int i = 0; i < add; i++) {
//                sb.append("?");
//            }
//            record = sb.toString();
//        } else {
//            record = record.substring(len - 30, len);
//        }
        mHabitRecordAdapter = new HabitRecordAdapter(activity, record, mEditable);
        rvRecord.setAdapter(mHabitRecordAdapter);
        GridLayoutManager glm = new GridLayoutManager(activity, 6);
        rvRecord.setLayoutManager(glm);

        TextView tvConfirm = f(R.id.tv_confirm_as_bt);
        tvConfirm.setTextColor(accentColor);
        tvConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mConfirmClicked = true;
                dismiss();
            }
        });

        f(R.id.tv_cancel_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_habit_record;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mConfirmClicked && mHabitRecordAdapter.hasRecordEdited()) {
            String record = mHabitRecordAdapter.getRecord();
            DetailActivity activity = (DetailActivity) getActivity();
            HabitDAO habitDAO = HabitDAO.getInstance(activity);
            long habitId = mHabit.getId();
            Habit habit = habitDAO.getHabitById(habitId);
            if (habit != null) {
                String recordBefore = habit.getRecord(), recordAfter = record;
                final int len1 = recordBefore.length(), len2 = record.length();
                if (len1 > len2) { // alarm time passed~
                    int gap = len1 - len2;
                    final int latestLen = recordBefore.length();
                    recordAfter = record + recordBefore.substring(latestLen - gap, latestLen);
                }
                habit.setRecord(recordAfter);
                try {
                    habitDAO.changeHabitRecordsByUser(habit, recordBefore, recordAfter);
                } catch (Exception e) {
                    PossibleMistakeHelper.outputNewMistakeInBackground(e);
                }
                habitDAO.updateRecordOfHabit(habitId, record);
            }

            activity.setHabitRecordEdited(true);
        }
        super.onDismiss(dialog);
    }
}
