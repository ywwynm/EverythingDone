package com.ywwynm.everythingdone.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.HelpActivity
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

/**
 * Created by ywwynm on 2016/6/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A fragment used to show help detail information
 */
open class HelpDetailFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val contentView: View = inflater.inflate(R.layout.fragment_help_detail, container, false)

        val activity: HelpActivity = activity as HelpActivity
        activity.updateActionBarTitle(true)
        activity.setRecyclerViewFocusable(false)

        val args: Bundle = requireArguments()
        val titles: Array<String?>?   = args.getStringArray(Def.Communication.KEY_HELP_TITLES) as Array<String?>?
        val contents: Array<String?>? = args.getStringArray(Def.Communication.KEY_HELP_CONTENTS) as Array<String?>?
        if (titles == null || contents == null || titles.size != contents.size) {
            return contentView
        }

        val pos: Int = args.getInt(Def.Communication.KEY_POSITION)

        val color: Int = App.defaultAccentBackground.color

        val pages: Array<View?> = arrayOfNulls(titles.size)
        for (i in pages.indices) {
            pages[i] = inflater.inflate(R.layout.include_help_detail_content, container, false)
            val sv: ScrollView = pages[i]!!.findViewById(R.id.sv_help_detail)
            EdgeEffectUtil.forScrollView(sv, color)

            val tvTitle: TextView = pages[i]!!.findViewById(R.id.tv_title_help_detail)
            tvTitle.text = titles[i]

            val tvContent: TextView = pages[i]!!.findViewById(R.id.tv_title_help_content)
            tvContent.text = contents[i]
        }

        val vp: ViewPager = contentView.findViewById(R.id.vp_help_detail)
        vp.adapter = HelpDetailPagerAdapter(pages)
        vp.currentItem = pos
        EdgeEffectUtil.forViewPager(vp, color)

        return contentView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val helpActivity: HelpActivity = activity as HelpActivity
        helpActivity.updateActionBarTitle(false)
        helpActivity.setRecyclerViewFocusable(true)
    }

    internal class HelpDetailPagerAdapter(pages: Array<View?>?) : PagerAdapter() {

        var mPages: Array<View?>? = pages

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val view: View = mPages!![position]!!
            container.addView(view)
            return view
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(mPages!![position])
        }

        override fun getCount(): Int = mPages!!.size

        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`
    }

    companion object {
        const val TAG: String = "HelpDetailFragment"

        @JvmStatic
        fun newInstance(titles: Array<String?>?, contents: Array<String?>?, position: Int): HelpDetailFragment {
            val args = Bundle()
            args.putStringArray(Def.Communication.KEY_HELP_TITLES, titles)
            args.putStringArray(Def.Communication.KEY_HELP_CONTENTS, contents)
            args.putInt(Def.Communication.KEY_POSITION, position)
            val fragment = HelpDetailFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
