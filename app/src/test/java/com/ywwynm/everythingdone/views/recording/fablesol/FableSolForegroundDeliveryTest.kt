package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class FableSolForegroundDeliveryTest {
    /** 真实 retry／交付记录接到 HWUI、GL 取用及 GPU 归还三个独立事件；GPU 在用仍不能覆盖。 */
    private class CaptureLoop {
        val queue = ArrayDeque<Runnable>()
        val delivery = FableSolForegroundDelivery()
        val buffers = mutableSetOf<Long>()
        val captured = mutableListOf<Pair<Long, Int>>()
        var sequence = 0L
        var rendering = 0L
        var version = 0
        var closed = false
        val retry = FableSolCaptureRetry({ queue.addLast(it) }, { queue.remove(it) },
            { !closed && rendering == 0L }, { draw() })

        fun draw() {
            if (closed) return
            if (rendering != 0L || buffers.size == 3) { retry.defer(); return }
            retry.captured()
            rendering = ++sequence
            buffers += rendering
            captured += rendering to version
        }

        fun hwuiCompleted() {
            check(rendering > 0L)
            delivery.submitted(rendering)
            rendering = 0L
            retry.resourceAvailable()
        }

        fun consumed(id: Long) { delivery.consumedOrReturned(id) }
        fun returned(id: Long) {
            check(buffers.remove(id))
            delivery.consumedOrReturned(id)
            retry.resourceAvailable()
        }
        fun close() { closed = true; retry.close() }
        fun drain() {
            var attempts = 0
            while (queue.isNotEmpty()) {
                check(++attempts < 10) { "无新资源时不能循环捕获" }
                queue.removeFirst().run()
            }
        }
    }

    @Test fun `上一帧等待 GL 时可以完成下一次 HWUI 捕获`() {
        val loop = CaptureLoop()
        loop.draw(); loop.version = 1; loop.draw()
        loop.hwuiCompleted(); loop.drain()
        assertEquals(listOf(1L to 0, 2L to 1), loop.captured)
        loop.hwuiCompleted()
        assertEquals(1L, loop.delivery.awaitingSequence)
        loop.consumed(1)
        assertEquals(2L, loop.delivery.awaitingSequence)
    }

    @Test fun `等待 HWUI 时合并最新变化 不把消费通知作为下次捕获条件`() {
        val loop = CaptureLoop(); loop.draw()
        repeat(10) { loop.version++; loop.draw() }
        loop.hwuiCompleted(); loop.drain(); loop.hwuiCompleted()
        assertEquals(listOf(1L to 0, 2L to 10), loop.captured)
        assertTrue(loop.delivery.pending)
        assertTrue(loop.queue.isEmpty())
    }

    @Test fun `消费只更新诊断 三个 GPU 在用缓冲仍禁止再次绘制`() {
        val loop = CaptureLoop()
        repeat(3) { loop.draw(); loop.hwuiCompleted() }
        loop.draw(); loop.drain()
        assertEquals(3, loop.captured.size)
        loop.consumed(1); loop.drain()
        assertEquals(3, loop.captured.size)
        loop.returned(1); loop.drain()
        assertEquals(4, loop.captured.size)
        assertEquals(3, loop.buffers.size)
    }

    @Test fun `迟到消费及归还不能解除下一次 HWUI 在途状态`() {
        val loop = CaptureLoop(); loop.draw(); loop.hwuiCompleted()
        loop.draw(); loop.version = 1; loop.draw()
        loop.consumed(1); loop.returned(1); loop.drain()
        assertEquals(2L, loop.rendering)
        assertEquals(2, loop.captured.size)
        loop.hwuiCompleted(); loop.drain()
        assertEquals(3L, loop.rendering)
    }

    @Test fun `关闭后迟到的完成 消费及归还都不能重启捕获`() {
        val loop = CaptureLoop(); loop.draw(); loop.hwuiCompleted(); loop.draw(); loop.draw()
        loop.close(); loop.hwuiCompleted(); loop.consumed(1); loop.returned(1); loop.returned(2)
        loop.drain()
        assertEquals(2, loop.captured.size)
        assertTrue(loop.buffers.isEmpty())
        assertFalse(loop.delivery.pending)
    }

    @Test fun `静止界面的消费和归还不产生额外捕获`() {
        val loop = CaptureLoop(); loop.draw(); loop.hwuiCompleted()
        loop.consumed(1); loop.returned(1); loop.drain()
        assertEquals(1, loop.captured.size)
        assertTrue(loop.queue.isEmpty())
    }

    @Test fun `诊断按序号独立完成 旧通知不能删除另一帧`() {
        val delivery = FableSolForegroundDelivery()
        delivery.submitted(1); delivery.submitted(2)
        assertTrue(delivery.consumedOrReturned(2))
        assertFalse(delivery.consumedOrReturned(2))
        assertEquals(1L, delivery.awaitingSequence)
        assertTrue(delivery.consumedOrReturned(1))
        delivery.submitted(3)
        assertFalse(delivery.consumedOrReturned(1))
        assertEquals(3L, delivery.awaitingSequence)
    }

    @Test fun `消费前丢弃同样只完成本帧一次诊断`() {
        val loop = CaptureLoop(); loop.draw(); loop.hwuiCompleted(); loop.draw(); loop.hwuiCompleted()
        loop.returned(1)
        assertFalse(loop.delivery.consumedOrReturned(1))
        assertEquals(2L, loop.delivery.awaitingSequence)
        assertEquals(setOf(2L), loop.buffers)
    }

    @Test fun `尺寸变化的新旧交付可以并存且不接受重复序号`() {
        val delivery = FableSolForegroundDelivery()
        assertThrows(IllegalStateException::class.java) { delivery.submitted(0) }
        delivery.submitted(1)
        assertThrows(IllegalStateException::class.java) { delivery.submitted(1) }
        for (id in 2L..6L) delivery.submitted(id)
        assertEquals(6, delivery.pendingCount)
        assertTrue(delivery.consumedOrReturned(2))
        delivery.submitted(7)
        assertEquals(1L, delivery.awaitingSequence)
        for (id in listOf(1L, 3L, 4L, 5L, 6L, 7L)) assertTrue(delivery.consumedOrReturned(id))
        assertEquals(0, delivery.pendingCount)
    }

    @Test fun `长时间重叠交付保持三个缓冲上限并捕获最新版本`() {
        val loop = CaptureLoop()
        repeat(1000) {
            loop.draw(); loop.version++; loop.draw()
            loop.hwuiCompleted(); loop.drain(); loop.hwuiCompleted()
            val first = loop.sequence - 1L
            assertEquals(first + 1L to loop.version, loop.captured.last())
            assertEquals(2, loop.buffers.size)
            loop.consumed(first); loop.returned(first)
            loop.consumed(first + 1L); loop.returned(first + 1L); loop.drain()
            assertTrue(loop.buffers.isEmpty())
            assertFalse(loop.delivery.pending)
        }
        assertEquals(2000, loop.captured.size)
    }
}
