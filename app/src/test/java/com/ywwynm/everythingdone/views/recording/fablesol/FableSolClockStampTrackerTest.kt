package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

class FableSolClockStampTrackerTest {
    @Test fun `同一拍多个数字变化只计一个计时动画帧`() {
        val tracker = FableSolClockStampTracker()
        val first = tracker.morph(10L, 20L)!!
        repeat(5) { assertNull(tracker.morph(10L, 21L + it)) }
        val next = tracker.morph(30L, 40L)!!
        assertEquals(first.sequence + 1, next.sequence)
        assertEquals(first.burst, next.burst)
    }

    @Test fun `动画更新但未绘制时不能冒充捕获了新的数字`() {
        val tracker = FableSolClockStampTracker()
        val first = tracker.morph(1L, 1L)
        assertNull(tracker.drawn)
        assertEquals(first, tracker.draw())
        val captured = tracker.drawn
        val second = tracker.morph(2L, 2L)
        assertEquals(first, tracker.drawn)
        tracker.draw()
        assertEquals(second, tracker.drawn)
        assertEquals(first, captured) // 已交给 GL 的快照不随主线程变化。
    }

    @Test fun `整秒间的静止期分组 不当作同一形变的长帧`() {
        val tracker = FableSolClockStampTracker()
        val first = tracker.morph(0, 0)!!
        val end = tracker.morph(300_000_000, 300_000_000)!!
        val next = tracker.morph(1_000_000_000, 1_000_000_000)!!
        assertEquals(first.burst, end.burst)
        assertEquals(end.burst + 1, next.burst)
        assertEquals(3, next.sequence)
    }

    @Test fun `形变内五十毫秒迟到仍属于同组 必须留给间隔统计`() {
        val tracker = FableSolClockStampTracker()
        val first = tracker.morph(0, 0)!!
        val slow = tracker.morph(50_000_000, 50_000_000)!!
        assertEquals(first.burst, slow.burst)
        assertEquals(50_000_000, slow.morphNs - first.morphNs)
    }

    @Test fun `没有形变的重复绘制不虚构计时新帧`() {
        val tracker = FableSolClockStampTracker()
        assertNull(tracker.draw())
        val step = tracker.morph(1, 1)
        repeat(10) { assertEquals(step, tracker.draw()) }
    }
}
