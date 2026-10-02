package com.ywwynm.everythingdone.taptransfer.ui.receive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor

/**
 * 预计落点：与桌面模拟器 `sim_list.py` 的排法逐项相同。期望值由模拟器算出（两列、密度 3、列表宽 1080、
 * 左右内边距各一个外边距、头部占位项底边 400）。
 */
class TapListPredictionTest {

    private val margin = 24f

    /** 内容左缘 = 列表左缘 + 左内边距；内容宽 = 1080 − 2 × 24。 */
    private val grid = TapListPrediction.Grid(left = 24f, contentWidth = 1032, spans = 2, margin = margin, top = 400f)

    private fun assertRect(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, 1e-3f)

    @Test fun `列宽与列的左缘与模拟器相同`() {
        assertEquals(468f, grid.cardWidth, 0f)
        assertEquals(984f, grid.fullWidth, 0f)
        assertEquals(48f, grid.columnLeft(0), 0f)
        assertEquals(564f, grid.columnLeft(1), 0f)
    }

    @Test fun `没有置顶时后收到的排在左上`() {
        val slots = TapListPrediction.predict(grid, emptyList(), floatArrayOf(1f, .5f), BooleanArray(2), 2,
            visibleTop = 300f, visibleBottom = 2300f, scrollTop = 336f)
        assertRect(floatArrayOf(564f, 424f, 1032f, 892f), slots[0])
        assertRect(floatArrayOf(48f, 424f, 516f, 658f), slots[1])
    }

    @Test fun `有置顶与通栏时按瀑布流的规则排`() {
        val pinned = listOf(TapListPrediction.Entry(300f, full = false))
        val slots = TapListPrediction.predict(grid, pinned, floatArrayOf(.8f, 1.2f, .6f),
            booleanArrayOf(false, true, false), 3, visibleTop = 300f, visibleBottom = 2300f, scrollTop = 336f)
        assertRect(floatArrayOf(48f, 2000.8f, 516f, 2375.2f), slots[0])
        assertRect(floatArrayOf(48f, 772f, 1032f, 1952.8f), slots[1])
        assertRect(floatArrayOf(564f, 424f, 1032f, 704.8f), slots[2])
    }

    @Test fun `第一张放不下时按滚动之后的位置整体平移`() {
        // 置顶一张很高的卡片把第一张新卡片挤到可见范围之外：接收时列表会把它滚到 scrollTop
        val pinned = listOf(TapListPrediction.Entry(2000f, full = true))
        val slots = TapListPrediction.predict(grid, pinned, floatArrayOf(1f), BooleanArray(1), 1,
            visibleTop = 300f, visibleBottom = 2300f, scrollTop = 336f)
        assertEquals(336f, slots[0][1], 1e-3f)
        assertEquals(336f + 468f, slots[0][3], 1e-3f)
    }

    @Test fun `置顶的几项量不到时从滚动之后的位置开始估计`() {
        val slots = TapListPrediction.predict(grid, null, floatArrayOf(1f, 1f), BooleanArray(2), 2,
            visibleTop = 300f, visibleBottom = 2300f, scrollTop = 336f)
        // 后收到的在列表里排第一，落在左列顶端
        assertEquals(48f, slots[1][0], 0f)
        assertEquals(336f, slots[1][1], 1e-3f)
        assertEquals(564f, slots[0][0], 0f)
        assertEquals(336f, slots[0][1], 1e-3f)
    }

    @Test fun `三列时逐张放进最早结束的那一列`() {
        val three = TapListPrediction.Grid(left = 24f, contentWidth = 1032, spans = 3, margin = margin, top = 400f)
        val slots = TapListPrediction.predict(three, emptyList(), floatArrayOf(1f, 1f, 1f, 1f), BooleanArray(4), 4,
            visibleTop = 300f, visibleBottom = 5000f, scrollTop = 336f)
        // 列表顺序：第 3、2、1、0 条；前三条各占一列，第 0 条回到最左列之下
        val width = 1032 / 3 - 2 * margin
        assertEquals(three.columnLeft(0), slots[3][0], 0f)
        assertEquals(three.columnLeft(1), slots[2][0], 0f)
        assertEquals(three.columnLeft(2), slots[1][0], 0f)
        assertEquals(three.columnLeft(0), slots[0][0], 0f)
        assertEquals(424f + width + 2 * margin, slots[0][1], 1e-3f)
    }

    @Test fun `整像素时来件卡片的高度四舍五入，排法不变`() {
        val pinned = listOf(TapListPrediction.Entry(300f, full = false))
        val slots = TapListPrediction.predict(grid, pinned, floatArrayOf(.8f, 1.2f, .6f),
            booleanArrayOf(false, true, false), 3, visibleTop = 300f, visibleBottom = 2300f, scrollTop = 336f,
            wholePixels = true)
        // 468 × 0.8 = 374.4 → 374，984 × 1.2 = 1180.8 → 1181，468 × 0.6 = 280.8 → 281
        assertRect(floatArrayOf(48f, 2001f, 516f, 2375f), slots[0])
        assertRect(floatArrayOf(48f, 772f, 1032f, 1953f), slots[1])
        assertRect(floatArrayOf(564f, 424f, 1032f, 705f), slots[2])
        for (slot in slots) for (v in slot) assertEquals("落点必须是整像素", floor(v), v, 0f)
    }

    @Test fun `发送方没告知形状时按默认高宽比`() {
        val slots = TapListPrediction.predict(grid, emptyList(), FloatArray(0), BooleanArray(0), 1,
            visibleTop = 300f, visibleBottom = 2300f, scrollTop = 336f)
        assertEquals(468f * TapListPrediction.DEFAULT_ASPECT, slots[0][3] - slots[0][1], 1e-3f)
    }
}
