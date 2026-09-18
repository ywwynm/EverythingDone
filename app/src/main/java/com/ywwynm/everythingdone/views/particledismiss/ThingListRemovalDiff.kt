package com.ywwynm.everythingdone.views.particledismiss

import androidx.recyclerview.widget.DiffUtil
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingListEntry

/** 位置会随混合列表重排；身份使用 ThingListEntry 的带类型符号 ID。 */
internal class ThingListRemovalDiff(val before: List<Long>, val after: List<Long>) : DiffUtil.Callback() {
    companion object {
        /** 顶部占位项借用递增的业务 ID；差分必须把它视为始终保留的同一个占位项。 */
        fun ids(entries: List<ThingListEntry>): List<Long> = entries.map {
            if (it is ThingListEntry.ThingEntry && it.thing.type == Thing.HEADER) Long.MIN_VALUE
            else it.stableId
        }
    }

    val removed: Set<Long> = before.toSet() - after.toSet()
    override fun getOldListSize() = before.size
    override fun getNewListSize() = after.size
    override fun areItemsTheSame(old: Int, new: Int) = before[old] == after[new]
    // 保留项可能更新文件夹计数、缩略图或选择蒙层；用 payload 原位重绑，避免交叉淡入。
    override fun areContentsTheSame(old: Int, new: Int) = false
    override fun getChangePayload(old: Int, new: Int): Any = Unit
}
