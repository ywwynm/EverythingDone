package com.ywwynm.everythingdone.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

class ChecklistEditorItemsTest {
    @Test
    fun `完成区边界在编辑插入删除重排和清空后始终与列表一致`() {
        val expected = mutableListOf<String?>("01甲", "2", "3", "4", "11乙")
        val actual = ChecklistEditorItems(expected)
        val random = Random(42)
        val values = listOf(null, "01丙", "2", "3", "4", "11丁")
        repeat(300) {
            assertEquals(expected.indexOf("4"), actual.indexOf("4"))
            when (random.nextInt(4)) {
                0 -> {
                    val index = random.nextInt(expected.size + 1)
                    val value = values.random(random)
                    expected.add(index, value); actual.add(index, value)
                }
                1 -> if (expected.isNotEmpty()) {
                    val index = random.nextInt(expected.size)
                    val value = values.random(random)
                    expected[index] = value; actual[index] = value
                }
                2 -> if (expected.isNotEmpty()) {
                    val index = random.nextInt(expected.size)
                    expected.removeAt(index); actual.removeAt(index)
                }
                3 -> if (expected.isNotEmpty()) {
                    val from = random.nextInt(expected.size)
                    val to = random.nextInt(expected.size)
                    expected.add(to, expected.removeAt(from)); actual.move(from, to)
                }
            }
            assertEquals(expected, actual)
            assertEquals(expected.indexOf("4"), actual.indexOf("4"))
        }
        actual.clear()
        assertEquals(-1, actual.indexOf("4"))
        actual.addAll(listOf("01甲", "4"))
        assertEquals(1, actual.indexOf("4"))
    }

    @Test
    fun `同文清单重排仍按身份保留选择区`() {
        val actual = ChecklistEditorItems(listOf("01重复", "01重复", "4"))
        val first = actual.entry(0)!!
        first.selectionStart = 1
        first.selectionEnd = 2
        actual.move(0, 1)
        assertSame(first, actual.entry(1))
        assertEquals(1, actual.entry(1)!!.selectionStart)
        assertEquals(-1, actual.entry(0)!!.selectionStart)
    }
}
