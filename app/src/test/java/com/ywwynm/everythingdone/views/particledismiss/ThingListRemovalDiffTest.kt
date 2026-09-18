package com.ywwynm.everythingdone.views.particledismiss

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.*
import org.junit.Test

class ThingListRemovalDiffTest {
    @Test fun `混合选择按最终投影移除且保留仍有内容的文件夹`() {
        // 正负 ID 分别是记事和文件夹；2 与 -2 不能混淆。-4 为仍有内容的选中文件夹。
        val before = listOf(0L, 1L, -2L, 2L, -4L, 5L, 6L)
        val after = listOf(0L, 2L, -4L, 6L)
        val diff = ThingListRemovalDiff(before, after)
        assertEquals(setOf(1L, -2L, 5L), diff.removed)
        val replay = before.toMutableList()
        DiffUtil.calculateDiff(diff).dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onRemoved(position: Int, count: Int) { repeat(count) { replay.removeAt(position) } }
            override fun onInserted(position: Int, count: Int) { fail("纯状态操作不应插入新卡片") }
            override fun onMoved(from: Int, to: Int) { replay.add(to, replay.removeAt(from)) }
            override fun onChanged(position: Int, count: Int, payload: Any?) { assertNotNull(payload) }
        })
        assertEquals(after, replay)
    }

    @Test fun `移空保留标题占位并且空操作不误判移除`() {
        assertEquals(setOf(1L, -1L), ThingListRemovalDiff(listOf(0L, 1L, -1L), listOf(0L)).removed)
        assertTrue(ThingListRemovalDiff(listOf(0L, -1L), listOf(0L, -1L)).removed.isEmpty())
    }
}
