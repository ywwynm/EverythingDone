package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.model.Thing

import java.util.ArrayList

/**
 * Created by qiizhang on 2016/9/19.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Wrapper of ThingsAdapter
 */
open class ThingsAdapterWrapper(adapter: ThingsAdapter?) {

    private var mAdapter: ThingsAdapter? = adapter

    private var mShouldWaitNotify: Boolean = false

    internal interface NotifyAction {
        fun notifyAdapter()
    }
    private var mNotifyActions: MutableList<NotifyAction>? = ArrayList()

    open fun setShouldWaitNotify(shouldWaitNotify: Boolean) {
        mShouldWaitNotify = shouldWaitNotify
    }

    open fun attachToRecyclerView(recyclerView: RecyclerView?) {
        recyclerView!!.adapter = mAdapter
    }

    open fun tryToNotify() {
        if (!mNotifyActions!!.isEmpty()) {
            mNotifyActions!![mNotifyActions!!.size - 1].notifyAdapter()
            mNotifyActions!!.clear()
        }
    }

    open fun clearNotify() {
        mNotifyActions!!.clear()
    }

    open fun getItemCount(): Int = mAdapter!!.itemCount

    open fun shouldThingsAnimWhenAppearing(): Boolean = mAdapter!!.shouldThingsAnimWhenAppearing()

    open fun setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing: Boolean) {
        mAdapter!!.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing)
    }

    open fun shouldShowPrivateContent(): Boolean {
        return mAdapter!!.shouldShowPrivateContent()
    }

    open fun setThingCardSurfaceAvailableHeight(height: Int) {
        mAdapter!!.setThingCardSurfaceAvailableHeight(height)
    }

    open fun applyThingCardMediaCropToBoundHolder(
        holder: BaseThingsAdapter.BaseThingViewHolder?,
        thing: Thing?
    ): Boolean {
        return mAdapter!!.applyThingCardMediaCropToBoundHolder(holder, thing)
    }

    open fun applyThingCardMediaBackgroundHeightToBoundHolder(
        holder: BaseThingsAdapter.BaseThingViewHolder?,
        thing: Thing?
    ): Boolean {
        return mAdapter!!.applyThingCardMediaBackgroundHeightToBoundHolder(holder, thing)
    }

    open fun notifyDataSetChanged() {
        if (mShouldWaitNotify) {
            mNotifyActions!!.add(object : NotifyAction {
                override fun notifyAdapter() {
                    mAdapter!!.notifyDataSetChanged()
                }
            })
        } else {
            mAdapter!!.notifyDataSetChanged()
            clearNotify()
        }
    }

    open fun notifyItemInserted(position: Int) {
        if (mShouldWaitNotify) {
            mNotifyActions!!.add(object : NotifyAction {
                override fun notifyAdapter() {
                    mAdapter!!.notifyItemInserted(position)
                }
            })
        } else {
            mAdapter!!.notifyItemInserted(position)
        }
    }

    open fun notifyItemChanged(position: Int) {
        if (mShouldWaitNotify) {
            mNotifyActions!!.add(object : NotifyAction {
                override fun notifyAdapter() {
                    mAdapter!!.notifyItemChanged(position)
                }
            })
        } else {
            mAdapter!!.notifyItemChanged(position)
        }
    }

    open fun notifyItemRemoved(position: Int) {
        if (mShouldWaitNotify) {
            mNotifyActions!!.add(object : NotifyAction {
                override fun notifyAdapter() {
                    mAdapter!!.notifyItemRemoved(position)
                }
            })
        } else {
            mAdapter!!.notifyItemRemoved(position)
        }
    }

    open fun notifyItemMoved(from: Int, to: Int) {
        mAdapter!!.notifyItemMoved(from, to)
    }

    open fun armNewItemAnimation(
        position: Int, thingId: Long,
        listener: ThingsAdapter.OnNewItemBoundListener?
    ) {
        mAdapter!!.armNewItemAnimation(position, thingId, listener)
    }

    open fun clearArmedNewItemAnimation() {
        mAdapter!!.clearArmedNewItemAnimation()
    }
}
