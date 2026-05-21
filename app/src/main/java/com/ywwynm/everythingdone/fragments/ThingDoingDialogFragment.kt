@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.cardview.widget.CardView
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

import java.util.ArrayList

/**
 * Created by ywwynm on 2016/11/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A DialogFragment for a [Thing] where user can change doing strategies or start doing
 * that thing directly
 */
open class ThingDoingDialogFragment : BaseDialogFragment() {

    private var mThing: Thing? = null

    private var mActivity: DetailActivity? = null

    @JvmField
    internal var mDoingHelper: ThingDoingHelper? = null

    private var mLlASD: LinearLayout? = null
    private var mTvASD: TextView? = null

    private var mLlASDTime: LinearLayout? = null
    private var mTvASDTime: TextView? = null

    private var mLlASM: LinearLayout? = null
    private var mTvASM: TextView? = null

    private var mCvStartAsBt: CardView? = null

    override fun getLayoutResource(): Int = R.layout.fragment_thing_doing

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        mActivity = activity as DetailActivity

        mDoingHelper = ThingDoingHelper(mActivity, mThing)

        findViews()
        initUI()
        setEvents()

        return mContentView
    }

    private fun findViews() {
        mLlASD     = f(R.id.ll_auto_start_doing_as_bt)
        mTvASD     = f(R.id.tv_auto_start_doing)
        mLlASDTime = f(R.id.ll_asd_time_as_bt)
        mTvASDTime = f(R.id.tv_asd_time)
        mLlASM     = f(R.id.ll_auto_strict_mode_as_bt)
        mTvASM     = f(R.id.tv_auto_strict_mode)

        mCvStartAsBt = f(R.id.cv_start_doing_as_bt_dialog)
    }

    private fun initUI() {
        val tvTitle: TextView = f(R.id.tv_title_thing_doing)!!
        val bg: ThingBackground? = currentAccent()
        if (bg != null) {
            BackgroundUtil.applyTextBackground(tvTitle, bg)
        } else {
            tvTitle.setTextColor(mThing!!.getColor())
        }

        mTvASD!!.text     = mDoingHelper!!.getAutoStartDoingDesc()
        mTvASDTime!!.text = mDoingHelper!!.getAutoDoingTimeDesc()
        mTvASM!!.text     = mDoingHelper!!.getAutoStrictModeDesc()

        enableOrDisableASDTimeUi()

        BackgroundUtil.applyCardBackground(
            mCvStartAsBt, bg ?: mThing!!.getBackground()
        )
    }

    /** Live accent from DetailActivity (honouring any pending pick). */
    private fun currentAccent(): ThingBackground? {
        if (mActivity == null) return mThing!!.getBackground()
        val bg: ThingBackground? = mActivity!!.getAccentBackground()
        return bg ?: mThing!!.getBackground()
    }

    private fun enableOrDisableASDTimeUi() {
        val black_54p = ContextCompat.getColor(mActivity!!, R.color.black_54p)
        val black_26p = ContextCompat.getColor(mActivity!!, R.color.black_26p)
        val black_14p = ContextCompat.getColor(mActivity!!, R.color.black_14p)
        val black_10p = ContextCompat.getColor(mActivity!!, R.color.black_10p)

        val tvTitle: TextView = f(R.id.tv_asd_time_title)!!
        if (mDoingHelper!!.shouldAutoStartDoing()) {
            mLlASDTime!!.isEnabled = true
            tvTitle.setTextColor(black_54p)
            mTvASDTime!!.setTextColor(black_26p)
        } else {
            mLlASDTime!!.isEnabled = false
            tvTitle.setTextColor(black_14p)
            mTvASDTime!!.setTextColor(black_10p)
        }
    }

    private fun setEvents() {
        mLlASD!!.setOnClickListener { showAutoStartDoingChooser() }
        mLlASDTime!!.setOnClickListener { showAutoStartDoingTimeChooser() }
        mLlASM!!.setOnClickListener { showAutoStrictModeChooser() }

        stimulateFeedbackForUserTouch(mCvStartAsBt!!)
        mCvStartAsBt!!.setOnClickListener {
            val helper = ThingDoingHelper(mActivity, mThing)
            helper.tryToOpenStartDoingActivityUser(currentAccent())
        }
    }

    private fun showAutoStartDoingChooser() {
        val items: MutableList<String?> = ArrayList(3)
        items.add(mDoingHelper!!.getAutoStartDoingFollowGeneralStr())
        items.add(mActivity!!.getString(R.string.enable))
        items.add(mActivity!!.getString(R.string.disable))

        val cdf = ChooserDialogFragment()
        val tbg: ThingBackground? = currentAccent()
        if (tbg != null) cdf.setAccentBackground(tbg)
        else             cdf.setAccentColor(mThing!!.getColor())
        cdf.setShouldShowMore(false)
        cdf.setTitle(getString(R.string.auto_start_doing_title))
        cdf.setItems(items)
        cdf.setInitialIndex(mDoingHelper!!.getAutoStartDoingStrategy())
        cdf.setConfirmListener {
            mDoingHelper!!.setAutoStartDoingStrategy(cdf.getPickedIndex())
            mTvASD!!.text = mDoingHelper!!.getAutoStartDoingDesc()
            enableOrDisableASDTimeUi()
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun showAutoStartDoingTimeChooser() {
        val items: MutableList<String?> = ArrayList(ThingDoingHelper.getStartDoingTimeItems(mActivity))
        items.add(0, mDoingHelper!!.getAutoStartDoingTimeFollowGeneralStr())

        val cdf = ChooserDialogFragment()
        val tbg: ThingBackground? = currentAccent()
        if (tbg != null) cdf.setAccentBackground(tbg)
        else             cdf.setAccentColor(mThing!!.getColor())
        cdf.setShouldShowMore(false)
        cdf.setTitle(getString(R.string.auto_start_doing_time_title))
        cdf.setItems(items)
        val strategy: String = mDoingHelper!!.getAutoDoingTimeStrategy()!!
        cdf.setInitialIndex(ThingDoingHelper.getStartDoingTimeIndex(strategy, true))
        cdf.setConfirmListener {
            mDoingHelper!!.setAutoDoingTimeStrategy(cdf.getPickedIndex())
            mTvASDTime!!.text = mDoingHelper!!.getAutoDoingTimeDesc()
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun showAutoStrictModeChooser() {
        val items: MutableList<String?> = ArrayList(3)
        items.add(mDoingHelper!!.getAutoStrictModeFollowGeneralStr())
        items.add(mActivity!!.getString(R.string.enable))
        items.add(mActivity!!.getString(R.string.disable))

        val cdf = ChooserDialogFragment()
        val tbg: ThingBackground? = currentAccent()
        if (tbg != null) cdf.setAccentBackground(tbg)
        else             cdf.setAccentColor(mThing!!.getColor())
        cdf.setShouldShowMore(false)
        cdf.setTitle(getString(R.string.auto_strict_mode_title))
        cdf.setItems(items)
        cdf.setInitialIndex(mDoingHelper!!.getAutoStrictModeStrategy())
        cdf.setConfirmListener {
            mDoingHelper!!.setAutoStrictModeStrategy(cdf.getPickedIndex())
            mTvASM!!.text = mDoingHelper!!.getAutoStrictModeDesc()
        }
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun stimulateFeedbackForUserTouch(cv: CardView) {
        val density: Float = DisplayUtil.getScreenDensity(mActivity)
        val dp2 = (density * 2).toInt()
        val dp3 = (density * 3).toInt()
        cv.setOnTouchListener { _, motionEvent ->
            val action = motionEvent.action
            if (action == MotionEvent.ACTION_DOWN) {
                cv.cardElevation = dp3.toFloat()
            } else if (action == MotionEvent.ACTION_UP) {
                cv.cardElevation = dp2.toFloat()
            }
            false
        }
    }

    open fun setThing(thing: Thing?) {
        mThing = thing
    }

    companion object {
        const val TAG: String = "ThingDoingDialogFragment"
    }
}
