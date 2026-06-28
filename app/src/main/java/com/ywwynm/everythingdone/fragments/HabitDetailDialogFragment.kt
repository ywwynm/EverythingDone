@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

/**
 * Created by ywwynm on 2016/3/3.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * show habit detail in a DialogFragment
 */
open class HabitDetailDialogFragment : BaseDialogFragment() {

    private var mHabit: Habit? = null

    private var mTvCr: TextView? = null
    private var mTvTotalT: TextView? = null          // 总周期数
    private var mTvPiTs: TextView? = null            // 坚持的周期数
    private var mTvRecordCount: TextView? = null
    private var mTvFinishedTimes: TextView? = null

    // not good practice but I'm so lazy that I don't want to make Habit class parcelable~
    open fun setHabit(habit: Habit?) {
        mHabit = habit
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val title: TextView = f(R.id.tv_habit_detail_title)!!
        val activity: DetailActivity = activity as DetailActivity
        val accentBg: ThingBackground? = activity.getAccentBackground()
        if (accentBg != null) {
            BackgroundUtil.applyTextBackground(title, accentBg)
        } else {
            title.setTextColor(activity.getAccentColor())
        }

        mTvCr            = f(R.id.tv_habit_detail_completion_rate)
        mTvTotalT        = f(R.id.tv_habit_detail_total_t)
        mTvPiTs          = f(R.id.tv_habit_detail_persist_in)
        mTvRecordCount   = f(R.id.tv_habit_detail_record_count)
        mTvFinishedTimes = f(R.id.tv_habit_detail_times)

        val tvGetIt: TextView = f(R.id.tv_get_it_as_bt)!!
        if (accentBg != null) {
            BackgroundUtil.applyTextBackground(tvGetIt, accentBg)
        } else {
            tvGetIt.setTextColor(activity.getAccentColor())
        }
        GradientRippleDrawable.applyAccentRipple(tvGetIt, accentBg, activity.getAccentColor())
        tvGetIt.setOnClickListener { dismiss() }

        initUI()

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_habit_detail

    override fun getDialogWindowWidthPx(): Int {
        return (DisplayUtil.getScreenDensity(activity) * 320).toInt()
    }

    @SuppressLint("SetTextI18n")
    private fun initUI() {
        mTvCr!!.text = mHabit!!.getCompletionRate()

        val context: Context = App.getApp()!!

        val totalT = mHabit!!.getTotalT()
        mTvTotalT!!.text = (if (totalT < 1) "<1" else totalT.toString()) + " " +
                DateTimeUtil.getTimeTypeStr(mHabit!!.type, context)
        if (totalT > 1 && !LocaleUtil.isChinese(context)) {
            mTvTotalT!!.append("s")
        }

        val piT = mHabit!!.getPersistInT()
        mTvPiTs!!.text = (if (piT < 1) "<1" else piT.toString()) + " " +
                DateTimeUtil.getTimeTypeStr(mHabit!!.type, context)
        if (piT > 1 && !LocaleUtil.isChinese(context)) {
            mTvPiTs!!.append("s")
        }

        mTvRecordCount!!.text = mHabit!!.record!!.length.toString()
        mTvFinishedTimes!!.text = "" + mHabit!!.getFinishedTimes()
    }

    companion object {
        const val TAG: String = "HabitDetailDialogFragment"

        @JvmStatic
        fun newInstance(): HabitDetailDialogFragment {
            val args = Bundle()
            val fragment = HabitDetailDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
