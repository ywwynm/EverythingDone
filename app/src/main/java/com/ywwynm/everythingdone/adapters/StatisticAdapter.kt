package com.ywwynm.everythingdone.adapters

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2016/4/1.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * statistic adapter
 */
open class StatisticAdapter(
    context: Context?,
    iconRes: IntArray?,
    firstRes: IntArray?,
    firstTextSizes: FloatArray?,
    secondStrs: Array<String?>?
) : RecyclerView.Adapter<StatisticAdapter.StatisticHolder>() {

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)

    private var mIconRes: IntArray? = iconRes
    private var mFirstRes: IntArray? = firstRes
    private var mFirstTextSizes: FloatArray? = firstTextSizes
    private var mSecondStrs: Array<String?>? = secondStrs

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatisticHolder {
        return StatisticHolder(mInflater!!.inflate(R.layout.rv_statistic, parent, false))
    }

    override fun onBindViewHolder(holder: StatisticHolder, position: Int) {
        holder.tvFirst!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
            mIconRes!![position], 0, 0, 0
        )
        holder.tvFirst!!.setText(mFirstRes!![position])
        if (mFirstTextSizes == null || mFirstTextSizes!!.size <= position
            || mFirstTextSizes!![position] == 0f
        ) {
            holder.tvFirst!!.textSize = 16f
        } else {
            holder.tvFirst!!.textSize = mFirstTextSizes!![position]
        }

        holder.tvSecond!!.text = mSecondStrs!![position]
        if (position == mIconRes!!.size - 1) {
            holder.vSeparator!!.visibility = View.GONE
        } else {
            holder.vSeparator!!.visibility = View.VISIBLE
        }
    }

    override fun getItemCount(): Int = mIconRes!!.size

    class StatisticHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        var tvFirst: TextView? = null
        var tvSecond: TextView? = null
        var vSeparator: View? = null

        init {
            tvFirst    = f(R.id.tv_first_rv_statistic)
            tvSecond   = f(R.id.tv_second_rv_statistic)
            vSeparator = f(R.id.view_separator_statistic)
        }
    }
}
