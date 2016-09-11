package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.widget.ImageView;

import com.ywwynm.everythingdone.R;

/**
 * Created by ywwynm on 2016/2/3.
 * present habit record
 */
public class HabitRecordPresenter {

    private ImageView[] mImageViews;

    public HabitRecordPresenter(ImageView[] imageViews) {
        mImageViews = imageViews;
    }

    public void setRecord(String record) {
        Context context = mImageViews[0].getContext();
        for (int i = 0; i < 5; i++) {
            char state = record.charAt(i);
            if (state == '0') {
                mImageViews[i].setImageResource(R.drawable.card_habit_unfinished);
                mImageViews[i].setContentDescription(
                        context.getString(R.string.cd_habit_unfinished));
            } else if (state == '1') {
                mImageViews[i].setImageResource(R.drawable.card_habit_finished);
                mImageViews[i].setContentDescription(
                        context.getString(R.string.cd_habit_finished));
            } else {
                mImageViews[i].setImageResource(R.drawable.card_habit_unknown);
                mImageViews[i].setContentDescription(
                        context.getString(R.string.cd_habit_unknown));
            }
        }
    }
}
