@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Activity
import android.os.Bundle
import androidx.core.util.Pair
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

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
        } else {
            title.setTextColor(mAccentColor)
            confirm.setTextColor(mAccentColor)
        }
        confirm.setOnClickListener { dismiss() }

        val recyclerView: RecyclerView = f(R.id.rv_attachment_info)!!
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = Adapter()

        return mContentView
    }

    open fun setAccentColor(accentColor: Int) {
        mAccentColor = accentColor
        mAccentBackground = null
    }

    /** Phase 8: gradient-aware accent. PURE / GRADIENT both flow through. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.representativeColor()
    }

    open fun setItems(items: List<Pair<String, String>?>?) {
        mItems = items
    }

    internal inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(mInflater!!.inflate(R.layout.rv_attachment_info, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item: Pair<String, String> = mItems!![position]!!
            holder.tvTitle!!.text   = item.first
            holder.tvContent!!.text = item.second
        }

        override fun getItemCount(): Int = mItems!!.size

        internal inner class Holder(itemView: View?) : BaseViewHolder(itemView) {

            val tvTitle: TextView?   = f(R.id.tv_rv_attachment_info_title)
            val tvContent: TextView? = f(R.id.tv_rv_attachment_info_content)
        }
    }

    companion object {
        const val TAG: String = "AttachmentInfoDialogFragment"
    }
}
