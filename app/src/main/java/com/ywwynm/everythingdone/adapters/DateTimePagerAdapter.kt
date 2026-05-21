package com.ywwynm.everythingdone.adapters

import android.content.Context
import android.content.res.Resources
import androidx.viewpager.widget.PagerAdapter
import android.view.View
import android.view.ViewGroup

import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2015/8/14.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [PagerAdapter] for [androidx.viewpager.widget.ViewPager]
 * in [com.ywwynm.everythingdone.fragments.DateTimeDialogFragment]
 */
open class DateTimePagerAdapter(context: Context?, tabs: List<View?>?) : PagerAdapter() {

    private var mContext: Context? = context
    private var mTabs: List<View?>? = tabs

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val view: View = mTabs!![position]!!
        container.addView(view, 0)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(mTabs!![position])
    }

    override fun getCount(): Int = mTabs!!.size

    override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`

    override fun getPageTitle(position: Int): CharSequence? {
        val res: Resources = mContext!!.resources
        return when (position) {
            0 -> res.getString(R.string.quick_remind_title_at)
            1 -> res.getString(R.string.quick_remind_title_after)
            else -> res.getString(R.string.quick_remind_title_recurrence)
        }
    }

    companion object {
        const val TAG: String = "DateTimePagerAdapter"
    }
}
