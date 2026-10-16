package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.*
import org.junit.Test

class ThingListContentDiffTest {
    private fun changes(before: List<Long>, after: List<Long>, changed: Set<Long>): List<String> {
        val events = mutableListOf<String>()
        DiffUtil.calculateDiff(ThingListContentDiff(before, after, changed)).dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { events += "insert:$position:$count" }
            override fun onRemoved(position: Int, count: Int) { events += "remove:$position:$count" }
            override fun onMoved(fromPosition: Int, toPosition: Int) { events += "move:$fromPosition:$toPosition" }
            override fun onChanged(position: Int, count: Int, payload: Any?) {
                assertNull("完整绑定才能保留卡片更新动画", payload)
                events += "change:$position:$count"
            }
        })
        return events
    }

    @Test fun `单条更新与多次撤销重做合并为一次单卡通知`() {
        val ids = listOf(0L, -2L, 2L, 3L)
        assertEquals(listOf("change:2:1"), changes(ids, ids, setOf(2L)))
    }

    @Test fun `类型筛选移出只删除对应位置且不改绑其后条目`() {
        assertEquals(listOf("remove:2:1"), changes(listOf(0L, -2L, 2L, 3L), listOf(0L, -2L, 3L), setOf(2L)))
    }

    @Test fun `类型筛选移入只插入对应位置`() {
        assertEquals(listOf("insert:2:1"), changes(listOf(0L, -2L, 3L), listOf(0L, -2L, 2L, 3L), setOf(2L)))
    }

    @Test fun `往返移出移入以最初显示为准仍为内容更新`() {
        assertEquals(listOf("change:1:1"), changes(listOf(0L, 2L), listOf(0L, 2L), setOf(2L)))
    }

    @Test fun `文件夹与同号记事分别匹配且只重绑受影响的预览`() {
        val ids = listOf(0L, -2L, 2L, -3L)
        assertEquals(listOf("change:1:1"), changes(ids, ids, setOf(-2L)))
    }

    @Test fun `有位置变化时同时包含移动和目标内容更新`() {
        val events = changes(listOf(0L, 2L, 3L), listOf(0L, 3L, 2L), setOf(2L))
        assertEquals(2, events.size)
        assertTrue(events.any { it.startsWith("move:") })
        assertTrue(events.any { it.startsWith("change:") })
    }

    @Test fun `不可见的记事不误更新其它卡片`() {
        assertTrue(changes(listOf(0L, 2L), listOf(0L, 2L), setOf(3L)).isEmpty())
    }
}
