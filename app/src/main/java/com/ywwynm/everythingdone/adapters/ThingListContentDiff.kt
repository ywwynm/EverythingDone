package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.DiffUtil

/** 相对于首页最后显示的列表合并内容更新；内容变化使用完整绑定，保留更新动画。 */
internal class ThingListContentDiff(
    private val before: List<Long>,
    private val after: List<Long>,
    private val changedIds: Set<Long>
) : DiffUtil.Callback() {
    override fun getOldListSize() = before.size
    override fun getNewListSize() = after.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        before[oldItemPosition] == after[newItemPosition]
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        after[newItemPosition] !in changedIds
}
