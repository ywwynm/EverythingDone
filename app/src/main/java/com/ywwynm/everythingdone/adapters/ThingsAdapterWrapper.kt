package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingListEntry

import java.util.ArrayList

/**
 * Created by qiizhang on 2016/9/19.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Wrapper of ThingsAdapter
 */
open class ThingsAdapterWrapper(adapter: ThingsAdapter?) {

    private var mAdapter: ThingsAdapter? = adapter

    private var mShouldWaitNotify: Boolean = false
    private var presentationHeld = false
    internal var presentationDirty = false
        private set

    internal fun holdPresentation(entries: List<ThingListEntry>) {
        presentationHeld = true
        presentationDirty = false
        mAdapter!!.holdContentUpdateEntries(entries)
    }

    internal fun publishPresentation(entries: List<ThingListEntry>, diff: DiffUtil.DiffResult,
                                     immediateRebind: Boolean = false) {
        mAdapter!!.holdContentUpdateEntries(entries)
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        clearNotify()
        presentationDirty = false
        if (immediateRebind) mAdapter!!.notifyDataSetChanged()
        else diff.dispatchUpdatesTo(mAdapter!!)
    }

    internal fun releasePresentation() {
        presentationHeld = false
        mAdapter!!.holdContentUpdateEntries(null)
        clearNotify()
    }

    /** 认证失效需立即遮蔽当前展示；不能为等待保存而继续显示已揭示的私密内容。 */
    internal fun rebindPrivacy() {
        if (!presentationHeld) { notifyDataSetChanged(); return }
        presentationDirty = true
        mAdapter!!.notifyDataSetChanged()
    }

    private fun deferPresentation(): Boolean {
        if (!presentationHeld) return false
        presentationDirty = true
        return true
    }

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
        if (deferPresentation()) return
        if (!mNotifyActions!!.isEmpty()) {
            mNotifyActions!![mNotifyActions!!.size - 1].notifyAdapter()
            mNotifyActions!!.clear()
        }
    }

    open fun clearNotify() {
        mNotifyActions!!.clear()
    }

    internal fun snapshotContentUpdateEntries() = mAdapter!!.snapshotContentUpdateEntries()

    internal fun holdContentUpdateEntries(entries: List<ThingListEntry>?) {
        if (presentationHeld) return
        mAdapter!!.holdContentUpdateEntries(entries)
    }

    /** 整批差异必须在可见时一起发布，不能进入只保留最后一项的旧延迟队列。 */
    internal fun publishContentUpdate(diff: DiffUtil.DiffResult) {
        if (deferPresentation()) return
        check(!mShouldWaitNotify)
        mAdapter!!.holdContentUpdateEntries(null)
        mAdapter!!.setShouldThingsAnimWhenAppearing(false)
        clearNotify()
        diff.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) = mAdapter!!.notifyItemRangeInserted(position, count)
            override fun onRemoved(position: Int, count: Int) = mAdapter!!.notifyItemRangeRemoved(position, count)
            override fun onMoved(fromPosition: Int, toPosition: Int) = mAdapter!!.notifyItemMoved(fromPosition, toPosition)
            override fun onChanged(position: Int, count: Int, payload: Any?) {
                if (count == 1) mAdapter!!.notifyItemChanged(position)
                else mAdapter!!.notifyItemRangeChanged(position, count)
            }
        })
    }

    open fun getItemCount(): Int = mAdapter!!.itemCount

    open fun shouldThingsAnimWhenAppearing(): Boolean = mAdapter!!.shouldThingsAnimWhenAppearing()

    open fun setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing: Boolean) {
        mAdapter!!.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing)
    }

    open fun setAppearanceRevealFolderId(id: Long?) {
        mAdapter!!.setAppearanceRevealFolderId(id)
    }

    open fun isFolderShownAsThumbnails(
        folder: ThingFolder
    ): Boolean {
        return mAdapter!!.isFolderShownAsThumbnails(folder)
    }

    open fun shouldShowPrivateContent(): Boolean {
        return mAdapter!!.shouldShowPrivateContent()
    }

    open fun setThingCardSurfaceAvailableHeight(height: Int) {
        mAdapter!!.setThingCardSurfaceAvailableHeight(height)
    }

    open fun setActivityHeaderSpacerHeightPx(heightPx: Int) {
        mAdapter!!.setActivityHeaderSpacerHeightPx(heightPx)
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
        if (deferPresentation()) return
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
        if (deferPresentation()) return
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
        if (deferPresentation()) return
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
        if (deferPresentation()) return
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
        if (deferPresentation()) return
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

    open fun setThingHidden(thingId: Long, hidden: Boolean) {
        mAdapter!!.setThingHidden(thingId, hidden)
    }

    open fun setFolderHidden(folderId: Long, hidden: Boolean) {
        mAdapter!!.setFolderHidden(folderId, hidden)
    }
}
