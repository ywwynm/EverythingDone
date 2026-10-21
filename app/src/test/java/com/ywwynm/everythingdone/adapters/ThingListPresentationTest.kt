package com.ywwynm.everythingdone.adapters

import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingListEntry
import org.junit.Assert.*
import org.junit.Test
import android.content.res.Resources
import com.ywwynm.everythingdone.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = ThingListPresentationTest.TestApp::class)
class ThingListPresentationTest {
    class TestApp : App() {
        override fun onCreate() { ReflectionHelpers.setStaticField(App::class.java, "app", this) }
        override fun getResources(): Resources = TestResources(super.getResources())
    }
    @Suppress("DEPRECATION")
    class TestResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getString(id: Int) = "test-$id"
    }
    private fun note(id: Long, content: String = "原正文") =
        Thing(id, Thing.NOTE, Thing.UNDERWAY, 0, "标题", content, "", id, 1, 1, 0)

    @Test fun `内容改变但身份未变仍通知对应项`() {
        val live = note(1)
        val before = ThingListPresentation.snapshot(listOf(ThingListEntry.ThingEntry(live)))
        live.content = "更新正文"
        val after = listOf(ThingListEntry.ThingEntry(live))
        assertEquals(setOf(1L), ThingListPresentation.changedIds(before, after))
        assertEquals("原正文", (before.single() as ThingListEntry.ThingEntry).thing.content)
    }

    @Test fun `嵌套预览和文件夹本身都不受后续模型写入影响`() {
        val folder = ThingFolder(9, null, "原文件夹", Thing.UNDERWAY, 0, 9, createTime = 1)
        val child = note(1)
        val entry = ThingListEntry.FolderEntry(folder, 1,
            thumbnailEntries = listOf(ThingListEntry.ThingEntry(child)))
        val before = ThingListPresentation.snapshot(listOf(entry))
        folder.title = "新文件夹"
        child.content = "新预览"
        assertEquals(setOf(-9L), ThingListPresentation.changedIds(before, listOf(entry)))
        val old = before.single() as ThingListEntry.FolderEntry
        assertEquals("原文件夹", old.folder.title)
        assertEquals("原正文", (old.thumbnailEntries.single() as ThingListEntry.ThingEntry).thing.content)
    }

    @Test fun `同一记录多次改动只比较最终内容`() {
        val live = note(1)
        val before = ThingListPresentation.snapshot(listOf(ThingListEntry.ThingEntry(live)))
        live.content = "中间状态"
        live.content = "原正文"
        assertTrue(ThingListPresentation.changedIds(before, listOf(ThingListEntry.ThingEntry(live))).isEmpty())
    }

    @Test fun `其他记事变化不会误标目标内容`() {
        val target = note(1)
        val other = note(2)
        val before = ThingListPresentation.snapshot(listOf(ThingListEntry.ThingEntry(target), ThingListEntry.ThingEntry(other)))
        other.content = "另一条记事更新"
        val after = listOf(ThingListEntry.ThingEntry(other), ThingListEntry.ThingEntry(target))
        assertEquals(setOf(2L), ThingListPresentation.changedIds(before, after))
    }

    @Test fun `筛选移除和新项由身份差异处理`() {
        val before = ThingListPresentation.snapshot(listOf(ThingListEntry.ThingEntry(note(1))))
        val after = listOf(ThingListEntry.ThingEntry(note(3)))
        assertEquals(setOf(3L), ThingListPresentation.changedIds(before, after))
    }
}
