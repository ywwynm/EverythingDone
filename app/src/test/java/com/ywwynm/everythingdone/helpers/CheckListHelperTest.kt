package com.ywwynm.everythingdone.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList
import kotlin.random.Random

class CheckListHelperTest {

    @Test
    fun `分隔符保持原持久化编码`() {
        assertEquals("\u0060\u542fQ\u743c", CheckListHelper.SIGNAL)
        assertEquals(CheckListHelper.SIGNAL_LENGTH, CheckListHelper.SIGNAL.length)
        assertEquals("\u0060\u542fQ\u743c01甲\u0060\u542fQ\u743c12乙",
            CheckListHelper.toCheckListStr(listOf("01甲", "12乙", "2", "3", "4")))
    }

    @Test
    fun `长清单层级归一化的访问次数随项数线性增长`() {
        // 同时覆盖大量一级项，以及一个根下的大量同级子项。
        // 只给一级项的父项查询加速，仍无法通过第二种输入。
        for (size in listOf(256, 1024, 2048)) {
            for (level in listOf(1, 2)) {
                val source = List(size) { if (it == 0) "01根" else "0${level}项$it" }
                val items = CountingList(source)
                CheckListHelper.normalizeLevels(items)
                assertTrue("size=$size level=$level reads=${items.reads}", items.reads <= size * 12)
                assertEquals(source, items)
            }
        }
    }

    @Test
    fun `一级项查找父项不扫描前面的清单`() {
        val items = CountingList(List(2048) { "01项$it" })
        assertEquals(-1, CheckListHelper.ownerIndexOf(items, items.lastIndex))
        assertTrue("reads=${items.reads}", items.reads <= 4)
    }

    @Test
    fun `复现首滑长清单结构并保持完整解析结果`() {
        // 来自性能诊断样本的层级和状态；不包含标题或正文。
        val levels = "1111223222122222222222222221111222221222222112111111111122211122111111222231222122221111111111111111111111111111111111111111111111111111111111111111"
        val states = "0000100000011111010110111110000000000110111001000000001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111"
        assertEquals(148, levels.length)
        assertEquals(levels.length, states.length)
        val source = levels.indices.map { "${states[it]}${levels[it]}项$it" }
        val expected = source.toMutableList<String?>()
        legacyNormalizeLevels(expected)
        val items = CountingList(source)
        CheckListHelper.normalizeLevels(items)
        assertTrue("reads=${items.reads}", items.reads <= source.size * 12)
        assertEquals(expected, items)
        val stored = encode(*source.toTypedArray())
        assertEquals(legacyParse(stored), CheckListHelper.toCheckListItems(stored, false))
    }

    @Test
    fun `跳级孤儿和控制行边界原地修正且保留文本及状态`() {
        val source = listOf("03甲", "13乙", "03丙", "01丁", "03戊", "03己", "02庚",
            "2", "13辛", "03壬", "3", "03癸", "4", "02末")
        val expected = listOf("01甲", "12乙", "03丙", "01丁", "02戊", "03己", "02庚",
            "2", "11辛", "02壬", "3", "01癸", "4", "01末")
        // 详情编辑传入可变列表；也验证无随机访问能力的列表。
        for (items in listOf(source.toMutableList<String?>(), LinkedList<String?>(source))) {
            CheckListHelper.normalizeLevels(items)
            assertEquals(expected, items)
            CheckListHelper.normalizeLevels(items)
            assertEquals(expected, items)
        }
    }

    @Test
    fun `空项和非清单内容仍为边界且无效层级保持原有兼容行为`() {
        val items = mutableListOf("01甲", null, "03乙", "", "02丙", "其他", "13丁",
            "0", "1", "0x文本", "19文本", "02尾")
        CheckListHelper.normalizeLevels(items)
        assertEquals(listOf("01甲", null, "01乙", "", "01丙", "其他", "11丁",
            "0", "1", "0x文本", "19文本", "02尾"), items)
    }

    @Test
    fun `归一化和父项查询与旧算法在混合输入上保持一致`() {
        val random = Random(20261009)
        val tokens = listOf(null, "", "2", "3", "4", "其他", "0", "1", "0x异常", "19异常",
            "01甲", "02乙", "03丙", "11丁", "12戊", "13己")
        repeat(5000) { sample ->
            val source = List(random.nextInt(0, 100)) { tokens[random.nextInt(tokens.size)] }
            val expected = source.toMutableList()
            legacyNormalizeLevels(expected)
            val actual = source.toMutableList()
            CheckListHelper.normalizeLevels(actual)
            assertEquals("sample=$sample", expected, actual)
            for (index in -1..source.size) {
                assertEquals("sample=$sample index=$index", legacyOwnerIndexOf(source, index),
                    CheckListHelper.ownerIndexOf(source, index))
            }
        }
    }

    @Test
    fun `完成的深层项不成为底部完成区边界`() {
        assertEquals(listOf("01甲", "12乙", "13丙", "02丁", "2", "3", "4", "11戊", "12己"),
            parse("01甲", "12乙", "13丙", "02丁", "11戊", "12己"))
        assertEquals(listOf("01甲", "12乙", "13丙", "2"), parse("01甲", "12乙", "13丙"))
    }

    @Test
    fun `未完成后代取消祖先完成态但保留已完成旁系`() {
        assertEquals(listOf("01甲", "02乙", "03丙", "12丁", "2", "3", "4", "11戊"),
            parse("11甲", "12乙", "03丙", "12丁", "11戊"))
    }

    @Test
    fun `解析异常层级后仍按修正后的组根插入完成区`() {
        assertEquals(listOf("2", "3", "4", "11甲", "12乙", "13丙"), parse("13甲", "13乙", "13丙"))
        assertEquals(listOf("01甲", "12乙", "2", "3", "4", "11丙"), parse("01甲", "13乙", "11丙"))
    }

    @Test
    fun `解析对照保留空分段前缀控制行和多级完成行为`() {
        val random = Random(9261009)
        val tokens = listOf("", "2", "3", "4", "其他", "0", "1", "0x异常", "19异常",
            "01甲", "02乙", "03丙", "11丁", "12戊", "13己")
        repeat(3000) { sample ->
            val parts = List(random.nextInt(0, 100)) { tokens[random.nextInt(tokens.size)] }
            val prefix = if (sample % 2 == 0) "" else "兼容旧前缀"
            val source = prefix + parts.joinToString("") { CheckListHelper.SIGNAL + it }
            assertEquals("sample=$sample", legacyParse(source), CheckListHelper.toCheckListItems(source, false))
        }
    }

    @Test
    fun `纯文本转换保留空行末尾空行和空清单`() {
        assertEquals(listOf("01甲", "01", "01乙", "01", "2"), CheckListHelper.toCheckListItems("甲\n\n乙\n", true))
        assertEquals(listOf("01", "2"), CheckListHelper.toCheckListItems("", true))
        assertEquals(listOf("2"), CheckListHelper.toCheckListItems("", false))
        assertEquals(listOf("", "2"), CheckListHelper.toCheckListItems(CheckListHelper.SIGNAL, false))
    }

    @Test
    fun `解析返回独立可编辑列表且序列化可往返`() {
        val source = encode("01甲", "12乙", "11丙")
        val first = CheckListHelper.toCheckListItems(source, false)
        val second = CheckListHelper.toCheckListItems(source, false)
        assertEquals(source, CheckListHelper.toCheckListStr(first))
        first[0] = "01已编辑"
        first.removeAt(1)
        assertEquals(listOf("01甲", "12乙", "2", "3", "4", "11丙"), second)
    }

    @Test
    fun `文本导出保留层级缩进和拍平模式`() {
        val source = encode("01甲", "02乙", "13丙", "11丁")
        assertEquals("甲\n乙\n丙\n丁", CheckListHelper.toContentStr(source, "", ""))
        assertEquals("- [ ] 甲\n  - [ ] 乙\n    - [x] 丙\n- [x] 丁",
            CheckListHelper.toContentStr(source, "- [ ] ", "- [x] "))
    }

    @Test
    fun `完成根项时整组迁移且再次解析保留完成区`() {
        val toggled = CheckListHelper.toggleChecklistItem(encode("01甲", "02乙", "01丙", "11丁"), 0)
        assertEquals(encode("01丙", "11甲", "12乙", "11丁"), toggled)
        assertEquals(listOf("01丙", "2", "3", "4", "11甲", "12乙", "11丁"),
            CheckListHelper.toCheckListItems(toggled, false))
    }

    @Test
    fun `取消完成区深层项后整组回顶部且保留完成旁系`() {
        val toggled = CheckListHelper.toggleChecklistItem(encode("01甲", "11乙", "12丙", "13丁", "12戊"), 3)
        assertEquals(encode("01乙", "02丙", "03丁", "12戊", "01甲"), toggled)
        val items = CheckListHelper.toCheckListItems(toggled, false)
        assertFalse(items.contains("3"))
        assertEquals(listOf("01乙", "02丙", "03丁", "12戊", "01甲", "2"), items)
    }

    @Test
    fun `删除根项后的孤儿修正和缩进后的完成态修正可共用`() {
        val items = parse("01甲", "02乙", "03丙", "11丁")
        items.removeAt(0)
        CheckListHelper.normalizeLevels(items)
        assertEquals(listOf("01乙", "02丙", "2", "3", "4", "11丁"), items)

        val indented = mutableListOf<String?>("11甲", "03乙", "12丙", "2")
        CheckListHelper.normalizeLevels(indented)
        CheckListHelper.normalizeCompletion(indented)
        assertEquals(listOf("01甲", "02乙", "12丙", "2"), indented)
    }

    private fun encode(vararg items: String): String = items.joinToString("") { CheckListHelper.SIGNAL + it }

    private fun parse(vararg items: String): MutableList<String?> = CheckListHelper.toCheckListItems(encode(*items), false)

    // 保留优化前的扫描规则作为兼容性对照，不使用优化后的父项查询或归一化。
    private fun legacyOwnerIndexOf(items: List<String?>, index: Int): Int {
        if (index !in items.indices || !CheckListHelper.isItem(items[index])) return -1
        val level = CheckListHelper.levelOf(items[index])
        for (i in index - 1 downTo 0) {
            if (!CheckListHelper.isItem(items[i])) break
            if (CheckListHelper.levelOf(items[i]) < level) return i
        }
        return -1
    }

    private fun legacyNormalizeLevels(items: MutableList<String?>) {
        for (i in items.indices) {
            if (!CheckListHelper.isItem(items[i])) continue
            val owner = legacyOwnerIndexOf(items, i)
            val maxLevel = if (owner == -1) 1 else CheckListHelper.levelOf(items[owner]) + 1
            if (CheckListHelper.levelOf(items[i]) > maxLevel) {
                items[i] = CheckListHelper.withLevel(items[i]!!, maxLevel)
            }
        }
    }

    private fun legacyParse(source: String): MutableList<String?> {
        val items = source.split(CheckListHelper.SIGNAL.toRegex()).drop(1).toMutableList<String?>()
        legacyNormalizeLevels(items)
        CheckListHelper.normalizeCompletion(items)
        val boundary = items.indices.firstOrNull {
            CheckListHelper.isFinished(items[it]) && legacyOwnerIndexOf(items, it) == -1
        }
        if (boundary == null) items.add("2") else items.addAll(boundary, listOf("2", "3", "4"))
        return items
    }

    private class CountingList(source: List<String?>) : AbstractMutableList<String?>() {
        private val items = source.toMutableList()
        var reads = 0
            private set
        override val size: Int get() = items.size
        override fun get(index: Int): String? {
            reads++
            return items[index]
        }
        override fun set(index: Int, element: String?): String? = items.set(index, element)
        override fun add(index: Int, element: String?) { items.add(index, element) }
        override fun removeAt(index: Int): String? = items.removeAt(index)
    }
}
