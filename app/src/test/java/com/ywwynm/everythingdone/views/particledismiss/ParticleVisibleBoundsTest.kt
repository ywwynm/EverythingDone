package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片被容器截断之后露出来的那一块。
 *
 * 正在做事界面的列表比卡片矮，卡片被一条直线截断；粒子快照如果按整张卡片抓，接管的那一帧
 * 会把截掉的部分一起显示出来。偏移算错的表现是「粒子整体错位」或「仍然显示完整卡片」，
 * 所以在这里逐项定死。
 */
class ParticleVisibleBoundsTest {

    private val cardWidth = 1000
    private val cardHeight = 1600

    @Test fun `容器装得下时是整张卡片`() {
        val whole = ParticleVisibleBounds.whole(cardWidth, cardHeight)
        // 卡片在容器里从 (0, 0) 开始，容器 1000x1600，正好装下。
        val bounds = ParticleVisibleBounds.clipBy(whole, 0, 0, 0, 0, 1000, 1600)
        assertEquals(whole, bounds)
        assertTrue(bounds.covers(cardWidth, cardHeight))
        assertFalse(bounds.isEmpty)
    }

    @Test fun `容器比卡片矮时只截下沿，上沿与左右不动`() {
        // 正在做事界面：列表高 900，卡片 1600，从列表顶端开始排。
        val bounds = ParticleVisibleBounds.clipBy(
            ParticleVisibleBounds.whole(cardWidth, cardHeight), 0, 0, 0, 0, 1000, 900)
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(1000, bounds.right)
        assertEquals(900, bounds.bottom)
        assertEquals(cardWidth, bounds.width)
        assertEquals(900, bounds.height)
        assertFalse(bounds.covers(cardWidth, cardHeight))
    }

    @Test fun `卡片被滚上去时截上沿，偏移就是被截掉的高度`() {
        // 列表高 900，卡片左上角在列表坐标系里是 -400（已经滚上去 400）。
        val bounds = ParticleVisibleBounds.clipBy(
            ParticleVisibleBounds.whole(cardWidth, cardHeight), 0, -400, 0, 0, 1000, 900)
        assertEquals(400, bounds.top)
        assertEquals(1300, bounds.bottom)
        assertEquals(900, bounds.height)
        assertEquals(0, bounds.left)
        assertEquals(cardWidth, bounds.width)
    }

    @Test fun `多层容器逐层收紧，取的是交集`() {
        // 第一层把下沿截到 900，第二层（卡片左上角在它里面是 (0, 100)）把上沿再截 100。
        var bounds = ParticleVisibleBounds.clipBy(
            ParticleVisibleBounds.whole(cardWidth, cardHeight), 0, 0, 0, 0, 1000, 900)
        bounds = ParticleVisibleBounds.clipBy(bounds, 0, 100, 0, 200, 1000, 2000)
        assertEquals(100, bounds.top)
        assertEquals(900, bounds.bottom)
        assertEquals(800, bounds.height)
    }

    @Test fun `左右也会被截，宽度与左偏移一起变`() {
        // 卡片左上角在容器里是 (-120, 0)，容器宽 800。
        val bounds = ParticleVisibleBounds.clipBy(
            ParticleVisibleBounds.whole(cardWidth, cardHeight), -120, 0, 0, 0, 800, 1600)
        assertEquals(120, bounds.left)
        assertEquals(920, bounds.right)
        assertEquals(800, bounds.width)
    }

    @Test fun `完全被挪出容器时是空的`() {
        val bounds = ParticleVisibleBounds.clipBy(
            ParticleVisibleBounds.whole(cardWidth, cardHeight), 0, 1700, 0, 0, 1000, 900)
        assertTrue(bounds.isEmpty)
        assertFalse(bounds.covers(cardWidth, cardHeight))
    }

    @Test fun `容器比卡片大时不算被截，仍按整张走`() {
        val bounds = ParticleVisibleBounds.clipBy(
            ParticleVisibleBounds.whole(cardWidth, cardHeight), 40, 60, 0, 0, 1200, 2000)
        assertTrue(bounds.covers(cardWidth, cardHeight))
        assertEquals(cardWidth, bounds.width)
        assertEquals(cardHeight, bounds.height)
    }
}
