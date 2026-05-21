package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup

/**
 * Created by ywwynm on 2015/9/16.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Adapter to offer multi select for items.
 */
abstract class MultiChoiceAdapter : RecyclerView.Adapter<BaseViewHolder>() {

    @JvmField
    protected var mPicked: BooleanArray? = null

    abstract override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder

    abstract override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int)

    abstract override fun getItemCount(): Int

    open fun togglePick(position: Int) {
        mPicked!![position] = !mPicked!![position]
        notifyItemChanged(position)
    }

    open fun pick(position: Int) {
        mPicked!![position] = true
    }

    open fun pickAll() {
        for (i in mPicked!!.indices) {
            mPicked!![i] = true
        }
    }

    open fun unpickAll() {
        for (i in mPicked!!.indices) {
            mPicked!![i] = false
        }
    }

    open fun pick(positions: List<Int?>?) {
        for (position in positions!!) {
            mPicked!![position!!] = true
        }
    }

    companion object {
        const val TAG: String = "MultiChoiceAdapter"
    }
}
