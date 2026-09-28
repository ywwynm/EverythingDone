package com.ywwynm.everythingdone.thingfile

import com.ywwynm.everythingdone.utils.ThingsSorter

import org.junit.Assert.assertEquals
import org.junit.Test

import java.util.Random

/**
 * 记事文件里的层级怎么还原成本机列表里的先后（决策 2026-09-28 第 7 条）。
 */
class ThingFileTreeTest {

    private class Item(
        val name: String,
        val parent: Int?,
        val order: Int,
        val index: Int,
        val sticky: Boolean = false
    ) {
        val placement = ThingFileTree.Placement(parent, order, index)
    }

    @Test
    fun group_sortsEachLevelByOrderThenIndex() {
        val items = listOf(
            Item("b", null, 1, 0),
            Item("x2", 7, 1, 1),
            Item("a", null, 0, 2),
            Item("x1", 7, 0, 3),
            Item("c", null, 2, 4)
        )
        val levels = ThingFileTree.group(items) { it.placement }
        assertEquals(listOf("a", "b", "c"), ThingFileTree.topLevel(levels).map { it.name })
        assertEquals(listOf("x1", "x2"), levels[7].orEmpty().map { it.name })
    }

    /** 只含记事的旧文件没有先后字段（都是 0）：按清单里的顺序。 */
    @Test
    fun thingsOnlyFile_keepsManifestOrder() {
        val items = List(5) { Item("t$it", null, 0, it) }
        val levels = ThingFileTree.group(items) { it.placement }
        assertEquals(items.map { it.name }, ThingFileTree.topLevel(levels).map { it.name })
    }

    /**
     * 文件夹里的项倒着建，每一项都放到所在区段（置顶或普通）的最前面——与
     * `ThingFolderDAO.getFirstChildLocation` 相同的规则——建完按列表的排序规则排，得到的就是发送方的先后。
     */
    @Test
    fun creationOrder_restoresSenderOrder_withStickyAndPlainItems() {
        val sender = listOf(
            Item("s1", 3, 0, 0, sticky = true),
            Item("s2", 3, 1, 1, sticky = true),
            Item("p1", 3, 2, 2),
            Item("p2", 3, 3, 3),
            Item("p3", 3, 4, 4)
        )
        // 打乱清单里的顺序，先后只由 order 决定
        val levels = ThingFileTree.group(sender.shuffled(Random(7))) { it.placement }
        val locations = LinkedHashMap<String, Long>()
        for (item in ThingFileTree.creationOrder(levels, 3)) {
            locations[item.name] = firstChildLocation(locations.values, item.sticky)
        }
        val listed = locations.entries.sortedWith { a, b ->
            ThingsSorter.compareByLocationAndSticky(a.value, b.value)
        }.map { it.key }
        assertEquals(sender.map { it.name }, listed)
    }

    /** 与 `ThingFolderDAO.getFirstChildLocation` 相同：置顶取最小值 − 1，普通取最大值 + 1。 */
    private fun firstChildLocation(existing: Collection<Long>, sticky: Boolean): Long {
        return if (sticky) {
            val min = existing.filter { it < 0 }.minOrNull()
            if (min == null) -1L else min - 1L
        } else {
            val max = existing.filter { it >= 0 }.maxOrNull()
            if (max == null) 1L else max + 1L
        }
    }
}
