package com.ywwynm.everythingdone.views;

import android.widget.ImageView;

import com.ywwynm.everythingdone.R;

/**
 * Created by ywwynm on 2016/2/3.
 * present habit record
 */
public class HabitRecordPresenter {

    private String mRecord;
    private ImageView[] mImageViews;

    public HabitRecordPresenter(ImageView[] imageViews) {
        mImageViews = imageViews;
    }

    public void setRecord(String record) {
        mRecord = record;
        for (int i = 0; i < 5; i++) {
            char state = mRecord.charAt(i);
            if (state == '0') {
                mImageViews[i].setImageResource(R.mipmap.card_habit_unfinished);
            } else if (state == '1') {
                mImageViews[i].setImageResource(R.mipmap.card_habit_finished);
            } else {
                mImageViews[i].setImageResource(R.mipmap.card_habit_unknown);
            }
        }
    }
}
