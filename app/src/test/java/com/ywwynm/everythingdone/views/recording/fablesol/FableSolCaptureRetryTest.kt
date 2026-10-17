package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class FableSolCaptureRetryTest {
    /** 与实际调用点相同：遍历时 pending／无缓冲则 defer，回调与归还时通知资源变化。 */
    private class CaptureLoop {
        val queue = ArrayDeque<Runnable>()
        var pending = false
        var freeBuffers = 3
        var layoutRequested = false
        var version = 0
        var nowMs = 0
        val captured = mutableListOf<Pair<Int, Int>>()
        val retry = FableSolCaptureRetry({ queue.addLast(it) }, { queue.remove(it) },
            { !pending && !layoutRequested }, { draw() })

        fun draw() {
            if (pending || freeBuffers == 0) { retry.defer(); return }
            retry.captured()
            pending = true
            freeBuffers--
            captured += nowMs to version
        }

        fun completed() { pending = false; retry.resourceAvailable() }
        fun released() { freeBuffers++; retry.resourceAvailable() }
        fun drain() {
            var attempts = 0
            while (queue.isNotEmpty()) {
                check(++attempts < 10) { "无新资源时不能忙重试" }
                queue.removeFirst().run()
            }
        }
    }

    @Test fun `错过遍历的计时版本在捕获返回后提交 不再等下一次刷新`() {
        val loop = CaptureLoop()
        loop.draw()
        loop.nowMs = 16; loop.version = 1; loop.draw()
        loop.nowMs = 18; loop.completed(); loop.drain()
        assertEquals(listOf(0 to 0, 18 to 1), loop.captured)
        assertTrue(loop.pending)
        // 原 invalidate 重试要等 33 ms；测试覆盖资源就绪到下一遍历之间的补偿窗口。
        assertTrue(loop.captured.last().first < 33)
    }

    @Test fun `多个被跳过的更新只补捕获最新版本`() {
        val loop = CaptureLoop(); loop.draw()
        for (i in 1..3) { loop.version = i; loop.draw() }
        loop.completed()
        repeat(10) { loop.retry.resourceAvailable() }
        assertEquals(1, loop.queue.size)
        loop.drain()
        assertEquals(listOf(0 to 0, 0 to 3), loop.captured)
        assertFalse(loop.retry.waiting)
    }

    @Test fun `正常遍历先捕获时取消已排队补偿 不重复绘制`() {
        val loop = CaptureLoop(); loop.draw(); loop.draw(); loop.completed()
        assertTrue(loop.retry.queued)
        loop.version = 1; loop.draw(); loop.drain()
        assertEquals(2, loop.captured.size)
        assertFalse(loop.retry.waiting)
        assertFalse(loop.retry.queued)
    }

    @Test fun `已有捕获时缓冲归还不能发起并发 HWUI 绘制`() {
        val loop = CaptureLoop(); loop.draw(); loop.draw(); loop.released(); loop.drain()
        assertEquals(1, loop.captured.size)
        assertTrue(loop.retry.waiting)
        loop.completed(); loop.drain()
        assertEquals(2, loop.captured.size)
    }

    @Test fun `没有缓冲时只尝试一次 等 GPU 归还后再捕获`() {
        val loop = CaptureLoop(); loop.draw(); loop.draw()
        loop.freeBuffers = 0
        loop.completed(); loop.drain()
        assertEquals(1, loop.captured.size)
        assertTrue(loop.retry.waiting)
        assertFalse(loop.retry.queued)
        loop.released(); loop.drain()
        assertEquals(2, loop.captured.size)
    }

    @Test fun `布局未完成不能从异步回调捕获旧尺寸`() {
        val loop = CaptureLoop(); loop.draw(); loop.draw()
        loop.layoutRequested = true; loop.completed(); loop.drain()
        assertEquals(1, loop.captured.size)
        loop.layoutRequested = false; loop.version = 1; loop.draw()
        assertEquals(2, loop.captured.size)
        assertFalse(loop.retry.waiting)
    }

    @Test fun `任务排队后状态再次阻塞时保留更新 等下一次资源通知`() {
        val loop = CaptureLoop(); loop.draw(); loop.draw(); loop.completed()
        loop.layoutRequested = true; loop.drain()
        assertEquals(1, loop.captured.size)
        assertTrue(loop.retry.waiting)
        loop.layoutRequested = false; loop.retry.resourceAvailable(); loop.drain()
        assertEquals(2, loop.captured.size)
    }

    @Test fun `关闭后取消补偿 迟到的完成和缓冲归还不会复活捕获`() {
        val loop = CaptureLoop(); loop.draw(); loop.draw(); loop.completed()
        val staleTask = loop.queue.first()
        loop.retry.close(); loop.drain(); staleTask.run()
        loop.completed(); loop.released(); loop.retry.defer(); loop.drain()
        assertEquals(1, loop.captured.size)
        assertFalse(loop.retry.waiting)
        assertFalse(loop.retry.queued)
    }

    @Test fun `静止界面正常完成和归还不产生新绘制`() {
        val loop = CaptureLoop(); loop.draw(); loop.completed(); loop.released(); loop.drain()
        assertEquals(1, loop.captured.size)
        assertTrue(loop.queue.isEmpty())
    }

    @Test fun `长时间动画的补偿有界 且停止更新后立即停止`() {
        val loop = CaptureLoop(); loop.draw()
        repeat(1000) {
            loop.version++
            loop.draw(); loop.draw(); loop.released(); loop.completed()
            assertEquals(1, loop.queue.size)
            loop.drain()
            assertTrue(loop.pending)
            assertEquals(2, loop.freeBuffers)
        }
        loop.completed(); loop.released(); loop.drain()
        assertEquals(1001, loop.captured.size)
        assertEquals(3, loop.freeBuffers)
        assertFalse(loop.retry.waiting)
    }
}
