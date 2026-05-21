package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup

/**
 * Created by ywwynm on 2015/8/19.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Adapter to offer single select for items.
 */
abstract class SingleChoiceAdapter : RecyclerView.Adapter<BaseViewHolder>() {

    @JvmField
    protected var mPickedPosition: Int = -1

    abstract override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder

    abstract override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int)

    abstract override fun getItemCount(): Int

    open fun pick(position: Int) {
        mPickedPosition = position
        notifyDataSetChanged()
    }

    open fun getPickedPosition(): Int = mPickedPosition

    companion object {
        const val TAG: String = "SingleChoiceAdapter"
    }
}
