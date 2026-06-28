@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Activity
import android.os.Bundle
import androidx.core.util.Pair
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable

/**
 * Created by ywwynm on 2016/4/30.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * attachment info dialog fragment
 */
open class AttachmentInfoDialogFragment : BaseDialogFragment() {

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for gradient title / confirm. */
    private var mAccentBackground: ThingBackground? = null
    private var mItems: List<Pair<String, String>?>? = null

    private var mInflater: LayoutInflater? = null

    override fun getLayoutResource(): Int = R.layout.fragment_attachment_info

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreArguments()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val activity: Activity = activity!!
        mInflater = LayoutInflater.from(activity)

        val title: TextView   = f(R.id.tv_title_attachment_info)!!
        val confirm: TextView = f(R.id.tv_confirm_as_bt_attachment_info)!!
        if (mAccentBackground != null) {
            BackgroundUtil.applyTextBackground(title, mAccentBackground)
            BackgroundUtil.applyTextBackground(confirm, mAccentBackground)
        } else if (mAccentColor != 0) {
            title.setTextColor(mAccentColor)
            confirm.setTextColor(mAccentColor)
        } else {
            val fallback = ContextCompat.getColor(activity, R.color.app_chrome_on_surface_primary)
            title.setTextColor(fallback)
            confirm.setTextColor(fallback)
        }
        GradientRippleDrawable.applyAccentRipple(
            confirm, mAccentBackground,
            if (mAccentColor != 0) mAccentColor
            else ContextCompat.getColor(activity, R.color.app_chrome_on_surface_primary)
        )
        confirm.setOnClickListener { dismiss() }

        val recyclerView: RecyclerView = f(R.id.rv_attachment_info)!!
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = Adapter()

        return mContentView
    }

    open fun setAccentColor(accentColor: Int) {
        mAccentColor = accentColor
        mAccentBackground = null
        ensureArguments().putInt(ARG_ACCENT_COLOR, accentColor)
        ensureArguments().remove(ARG_ACCENT_BACKGROUND)
    }

    /** Phase 8: gradient-aware accent. PURE / GRADIENT both flow through. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.color
        val args = ensureArguments()
        args.putInt(ARG_ACCENT_COLOR, mAccentColor)
        if (bg != null) {
            args.putString(ARG_ACCENT_BACKGROUND, bg.toJson())
        } else {
            args.remove(ARG_ACCENT_BACKGROUND)
        }
    }

    open fun setItems(items: List<Pair<String, String>?>?) {
        mItems = items ?: emptyList()
        val titles = ArrayList<String>()
        val contents = ArrayList<String>()
        for (item in mItems.orEmpty()) {
            if (item == null) continue
            titles.add(item.first ?: "")
            contents.add(item.second ?: "")
        }
        val args = ensureArguments()
        args.putStringArrayList(ARG_ITEM_TITLES, titles)
        args.putStringArrayList(ARG_ITEM_CONTENTS, contents)
    }

    private fun ensureArguments(): Bundle {
        var args = arguments
        if (args == null) {
            args = Bundle()
            arguments = args
        }
        return args
    }

    private fun restoreArguments() {
        val args = arguments ?: return
        mAccentColor = args.getInt(ARG_ACCENT_COLOR, mAccentColor)
        mAccentBackground = ThingBackground.fromJson(args.getString(ARG_ACCENT_BACKGROUND))
        if (mAccentBackground != null) {
            mAccentColor = mAccentBackground!!.color
        }

        val titles = args.getStringArrayList(ARG_ITEM_TITLES)
        val contents = args.getStringArrayList(ARG_ITEM_CONTENTS)
        if (titles != null && contents != null && titles.size == contents.size) {
            val items = ArrayList<Pair<String, String>?>(titles.size)
            for (i in titles.indices) {
                items.add(Pair(titles[i], contents[i]))
            }
            mItems = items
        } else if (mItems == null) {
            mItems = emptyList()
        }
    }

    internal inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(mInflater!!.inflate(R.layout.rv_attachment_info, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item: Pair<String, String> = mItems?.getOrNull(position) ?: return
            holder.tvTitle!!.text   = item.first
            holder.tvContent!!.text = item.second
        }

        override fun getItemCount(): Int = mItems?.size ?: 0

        internal inner class Holder(itemView: View?) : BaseViewHolder(itemView) {

            val tvTitle: TextView?   = f(R.id.tv_rv_attachment_info_title)
            val tvContent: TextView? = f(R.id.tv_rv_attachment_info_content)
        }
    }

    companion object {
        const val TAG: String = "AttachmentInfoDialogFragment"
        private const val ARG_ACCENT_COLOR = "accent_color"
        private const val ARG_ACCENT_BACKGROUND = "accent_background"
        private const val ARG_ITEM_TITLES = "item_titles"
        private const val ARG_ITEM_CONTENTS = "item_contents"
    }
}
