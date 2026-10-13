package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Point
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R

/** 仅用于一次批量交接。瀑布流可能把旧可见项重新标为插入，仍按其真实旧位置连续移动。 */
internal class ThingListRetainedItemAnimator(
    original: DefaultItemAnimator,
    positions: Map<Long, Point>
) : DefaultItemAnimator() {
    private val origins = positions.toMutableMap()

    init {
        addDuration = original.addDuration
        removeDuration = original.removeDuration
        moveDuration = original.moveDuration
        changeDuration = original.changeDuration
        supportsChangeAnimations = original.supportsChangeAnimations
    }

    private fun takeOrigin(holder: RecyclerView.ViewHolder): Point? =
        origins.remove(holder.itemView.getTag(R.id.tag_thing_card_bound_stable_id) as? Long)

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val origin = takeOrigin(holder) ?: return super.animateAdd(holder)
        val view = holder.itemView
        return super.animateMove(holder, origin.x - view.translationX.toInt(), origin.y - view.translationY.toInt(),
            view.left, view.top)
    }

    override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        val origin = takeOrigin(holder) ?: return super.animateMove(holder, fromX, fromY, toX, toY)
        val view = holder.itemView
        return super.animateMove(holder, origin.x - view.translationX.toInt(), origin.y - view.translationY.toInt(), toX, toY)
    }
}
