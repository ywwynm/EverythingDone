package com.ywwynm.everythingdone.helpers

import com.ywwynm.everythingdone.helpers.CheckListHelper.STATE_FINISHED
import com.ywwynm.everythingdone.helpers.CheckListHelper.STATE_UNFINISHED
import com.ywwynm.everythingdone.helpers.CheckListHelper.isFinished
import com.ywwynm.everythingdone.helpers.CheckListHelper.isItem
import com.ywwynm.everythingdone.helpers.CheckListHelper.isUnfinished
import com.ywwynm.everythingdone.helpers.CheckListHelper.ownerIndexOf
import com.ywwynm.everythingdone.helpers.CheckListHelper.subtreeEndIndexOf
import com.ywwynm.everythingdone.helpers.CheckListHelper.withState

/**
 * 多级清单项的"组感知"完成状态机 —— 规则 3 的唯一真理来源。
 * 详情页、首页列表、DoingActivity、单一/列表 widget、通知动作全部共享它。
 * 见 docs/features/multi-level-checklist/decisions.md。
 *
 * 入参 [items] 是去掉控制标记（2/3/4）后的真实项列表，按存储顺序排列：
 * 顶部未完成组在前、底部已完成组在后。函数原地修改该列表。
 */
object ChecklistCompletion {

    /**
     * 切换第 [pos] 个真实项的完成态。
     *
     * - 完成任意项 → 它连同整棵子树都置为已完成；若它是组根则整组迁移到底部已完成区最上方，
     *   否则就地完成。
     * - 取消组根 → 它连同整棵子树都置为未完成，整组回流到顶部未完成区最上方。
     * - 取消深层项 → 它连同整棵子树置为未完成；若该项当时在底部（组根已完成），则把组根强制改为
     *   未完成（不横扫组根的其它分支）、整组回流到顶部，其余原本完成的项保持完成；若它本就在顶部
     *   则原位取消。
     */
    fun toggle(items: MutableList<String?>, pos: Int) {
        if (pos < 0 || pos >= items.size || !isItem(items[pos])) return

        val isRoot = ownerIndexOf(items, pos) == -1
        val end = subtreeEndIndexOf(items, pos)

        if (isUnfinished(items[pos])) {
            // ---- 完成 ----
            if (isRoot) {
                val block = extractStated(items, pos, end, STATE_FINISHED)
                val insertAt = firstFinishedRootIndex(items).let { if (it == -1) items.size else it }
                items.addAll(insertAt, block)
            } else {
                for (i in pos..end) items[i] = withState(items[i]!!, STATE_FINISHED)
            }
        } else {
            // ---- 取消 ----
            if (isRoot) {
                val block = extractStated(items, pos, end, STATE_UNFINISHED)
                items.addAll(0, block)
            } else {
                // 先把被点项的子树整体取消
                for (i in pos..end) items[i] = withState(items[i]!!, STATE_UNFINISHED)
                val rootIdx = CheckListHelper.groupRootIndexOf(items, pos)
                if (isFinished(items[rootIdx])) {
                    // 该项在底部：强制组根未完成（不级联组根其它分支），整组回流到顶部
                    items[rootIdx] = withState(items[rootIdx]!!, STATE_UNFINISHED)
                    val rootEnd = subtreeEndIndexOf(items, rootIdx)
                    val block = ArrayList<String?>()
                    for (i in rootIdx..rootEnd) block.add(items[i])
                    for (i in rootEnd downTo rootIdx) items.removeAt(i)
                    items.addAll(0, block)
                }
                // 否则：顶部就地完成的深层项，原位取消即可，不迁移。
            }
        }

        // 维持不变量「已完成项的所有下属都已完成」：取消一个子项后，它的已完成祖先链自动改回未完成
        // （旁系已完成项不受影响）。组根的迁移已在上面处理，此处只会翻转就地完成的中间祖先。
        CheckListHelper.normalizeCompletion(items)
    }

    /** 移除 [from]..[to]（含），返回这些项统一改成 [state] 后的副本块（保留层级与文本）。 */
    private fun extractStated(
        items: MutableList<String?>, from: Int, to: Int, state: Char
    ): List<String?> {
        val block = ArrayList<String?>()
        for (i in from..to) block.add(withState(items[i]!!, state))
        for (i in to downTo from) items.removeAt(i)
        return block
    }

    /** 第一个"已完成组根"的下标 —— 底部已完成区的起点；没有则 -1。 */
    private fun firstFinishedRootIndex(items: List<String?>): Int {
        for (i in items.indices) {
            if (isItem(items[i]) && isFinished(items[i]) && ownerIndexOf(items, i) == -1) {
                return i
            }
        }
        return -1
    }
}
