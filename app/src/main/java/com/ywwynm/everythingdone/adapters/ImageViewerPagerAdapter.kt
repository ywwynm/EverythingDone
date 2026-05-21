package com.ywwynm.everythingdone.adapters

import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import android.view.View
import android.view.ViewGroup

/**
 * Created by ywwynm on 2015/10/11.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [PagerAdapter] for [androidx.viewpager.widget.ViewPager]
 * in [com.ywwynm.everythingdone.activities.ImageViewerActivity]
 */
open class ImageViewerPagerAdapter(tabs: List<View?>?) : PagerAdapter() {

    private var mTabs: MutableList<View?>? = tabs as MutableList<View?>?

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val view: View = mTabs!![position]!!
        container.addView(view, 0)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(mTabs!![position])
    }

    override fun getCount(): Int = mTabs!!.size

    override fun getItemPosition(`object`: Any): Int = POSITION_NONE

    override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`

    open fun removeTab(viewPager: ViewPager?, index: Int) {
        viewPager!!.adapter = null
        mTabs!!.removeAt(index)
        viewPager.adapter = this
        viewPager.currentItem = index
    }

    companion object {
        const val TAG: String = "ImageViewerPagerAdapter"
    }
}
