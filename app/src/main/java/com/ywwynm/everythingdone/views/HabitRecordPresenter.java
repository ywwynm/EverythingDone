package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.ImageView;

import androidx.core.widget.ImageViewCompat;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.BackgroundUtil;

/**
 * Created by ywwynm on 2016/2/3.
 * present habit record
 */
public class HabitRecordPresenter {

    private ImageView[] mImageViews;
    /** Phase 8+: thing's representative colour. Used to tint the five
     *  white-side dot drawables (unfinished / finished / unknown) to black
     *  when the card background is light, matching every other card icon's
     *  luminance-adaptive behaviour. */
    private int mThingColor;

    public HabitRecordPresenter(ImageView[] imageViews) {
        mImageViews = imageViews;
    }

    /** Phase 8+: feed in the host thing's representative colour so the dots
     *  pick the right foreground side on the next {@link #setRecord} bind. */
    public void setThingColor(int thingColor) {
        mThingColor = thingColor;
        applyTint();
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
        applyTint();
    }

    private void applyTint() {
        ColorStateList tint = BackgroundUtil.isLight(mThingColor)
                ? ColorStateList.valueOf(Color.BLACK)
                : null;
        for (ImageView iv : mImageViews) {
            ImageViewCompat.setImageTintList(iv, tint);
        }
    }
}
