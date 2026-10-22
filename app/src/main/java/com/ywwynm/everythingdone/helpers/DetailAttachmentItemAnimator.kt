package com.ywwynm.everythingdone.helpers

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter

/** 统一详情列表只为附件保留位移动画，编辑区不做变化或增删淡入淡出。 */
open class DetailAttachmentItemAnimator : DefaultItemAnimator() {
    init { supportsChangeAnimations = false }

    protected open fun shouldAnimateMove(holder: RecyclerView.ViewHolder): Boolean =
        holder.bindingAdapter is ImageAttachmentAdapter || holder.bindingAdapter is AudioAttachmentAdapter

    override fun animateMove(holder: RecyclerView.ViewHolder,
        fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        if (shouldAnimateMove(holder)) return super.animateMove(holder, fromX, fromY, toX, toY)
        holder.itemView.translationX = 0f
        holder.itemView.translationY = 0f
        dispatchMoveFinished(holder)
        return false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        dispatchAddFinished(holder)
        return false
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        dispatchRemoveFinished(holder)
        return false
    }
}
