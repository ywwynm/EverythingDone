@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.HabitRecordAdapter
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.PossibleMistakeHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Created by 张启 on 2017/3/10.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * DialogFragment to show/edit habit records.
 */
open class HabitRecordDialogFragment : BaseDialogFragment() {

    private var mHabit: Habit? = null
    private var mEditable: Boolean = false

    private var mHabitRecordAdapter: HabitRecordAdapter? = null

    private var mConfirmClicked: Boolean = false

    // not good practice but I'm so lazy that I don't want to make Habit class parcelable~
    open fun setHabit(habit: Habit?) {
        mHabit = habit
    }

    open fun setEditable(editable: Boolean) {
        mEditable = editable
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val title: TextView = f(R.id.tv_habit_record_title)!!
        val activity: DetailActivity = getActivity() as DetailActivity
        val accentBg: ThingBackground? = activity.getAccentBackground()
        if (accentBg != null) {
            BackgroundUtil.applyTextBackground(title, accentBg)
        } else {
            title.setTextColor(activity.getAccentColor())
        }

        val rvRecord: RecyclerView = f(R.id.rv_habit_record)!!

        val record: String? = mHabit!!.record
        mHabitRecordAdapter = HabitRecordAdapter(activity, record, mEditable)
        rvRecord.adapter = mHabitRecordAdapter
        val glm = GridLayoutManager(activity, 6)
        rvRecord.layoutManager = glm

        val tvConfirm: TextView = f(R.id.tv_confirm_as_bt)!!
        if (accentBg != null) {
            BackgroundUtil.applyTextBackground(tvConfirm, accentBg)
        } else {
            tvConfirm.setTextColor(activity.getAccentColor())
        }
        tvConfirm.setOnClickListener {
            mConfirmClicked = true
            dismiss()
        }

        f<TextView>(R.id.tv_cancel_as_bt)!!.setOnClickListener { dismiss() }

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_habit_record

    override fun onDismiss(dialog: DialogInterface) {
        if (mConfirmClicked && mHabitRecordAdapter!!.hasRecordEdited()) {
            val record: String = mHabitRecordAdapter!!.getRecord()!!
            val activity: DetailActivity = getActivity() as DetailActivity
            val habitDAO: HabitDAO = HabitDAO.getInstance(activity)!!
            val habitId: Long = mHabit!!.id
            val habit: Habit? = habitDAO.getHabitById(habitId)
            if (habit != null) {
                val recordBefore: String = habit.record!!
                var recordAfter: String = record
                val len1: Int = recordBefore.length
                val len2: Int = record.length
                if (len1 > len2) { // alarm time passed~
                    val gap = len1 - len2
                    val latestLen = recordBefore.length
                    recordAfter = record + recordBefore.substring(latestLen - gap, latestLen)
                }
                habit.record = recordAfter
                try {
                    habitDAO.changeHabitRecordsByUser(habit, recordBefore, recordAfter)
                } catch (e: Exception) {
                    PossibleMistakeHelper.outputNewMistakeInBackground(e)
                }
                habitDAO.updateRecordOfHabit(habitId, record)
            }

            activity.setHabitRecordEdited(true)
        }
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG: String = "HabitRecordDialogFragment"
    }
}
