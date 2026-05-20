package com.ywwynm.everythingdone.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageView

import androidx.core.widget.ImageViewCompat

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Created by ywwynm on 2016/2/3.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * present habit record
 */
open class HabitRecordPresenter(imageViews: Array<ImageView>) {

    private val mImageViews: Array<ImageView> = imageViews

    /** Phase 8+: thing's representative colour. Used to tint the five
     *  white-side dot drawables (unfinished / finished / unknown) to black
     *  when the card background is light, matching every other card icon's
     *  luminance-adaptive behaviour. */
    private var mThingColor: Int = 0

    /** Phase 8+: feed in the host thing's representative colour so the dots
     *  pick the right foreground side on the next [setRecord] bind. */
    fun setThingColor(thingColor: Int) {
        mThingColor = thingColor
        applyTint()
    }

    fun setRecord(record: String) {
        val context: Context = mImageViews[0].getContext()!!
        for (i in 0..4) {
            val state: Char = record[i]
            if (state == '0') {
                mImageViews[i].setImageResource(R.drawable.card_habit_unfinished)
                mImageViews[i].setContentDescription(
                        context.getString(R.string.cd_habit_unfinished))
            } else if (state == '1') {
                mImageViews[i].setImageResource(R.drawable.card_habit_finished)
                mImageViews[i].setContentDescription(
                        context.getString(R.string.cd_habit_finished))
            } else {
                mImageViews[i].setImageResource(R.drawable.card_habit_unknown)
                mImageViews[i].setContentDescription(
                        context.getString(R.string.cd_habit_unknown))
            }
        }
        applyTint()
    }

    private fun applyTint() {
        val tint: ColorStateList? = if (BackgroundUtil.isLight(mThingColor))
                ColorStateList.valueOf(Color.BLACK)
                else null
        for (iv in mImageViews) {
            ImageViewCompat.setImageTintList(iv, tint)
        }
    }
}
