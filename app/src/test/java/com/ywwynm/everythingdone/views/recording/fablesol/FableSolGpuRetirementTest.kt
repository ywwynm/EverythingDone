package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

class FableSolGpuRetirementTest {
    private class Gpu : FableSolGpuRetirement.Backend {
        var nextFence = 1L
        var completed = 0L
        var unavailable = false
        var failed = false
        var finishFails = false
        var finishes = 0
        var flushes = 0
        val deleted = mutableListOf<Long>()
        val fallbacks = mutableListOf<String>()
        override fun insertFence(): Long = if (unavailable) 0L else nextFence++
        override fun flush() { flushes++ }
        override fun poll(fence: Long) = when {
            failed -> FableSolGpuRetirement.Status.FAILED
            fence <= completed -> FableSolGpuRetirement.Status.SIGNALED
            else -> FableSolGpuRetirement.Status.PENDING
        }
        override fun deleteFence(fence: Long) {
            check(fence <= completed) { "仍在采样时删除 fence" }
            check(fence !in deleted) { "重复删除 fence" }
            deleted += fence
        }
        override fun finish() {
            if (finishFails) error("GPU 尚未完成")
            finishes++
            completed = nextFence - 1
        }
        override fun fallback(reason: String) { fallbacks += reason }
    }

    @Test fun `在途前景不会因下一帧替换而提前归还或等待 GPU`() {
        val gpu = Gpu()
        val reusable = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, reusable::add)
        queue.retire(1)
        queue.retire(2)
        repeat(10) { queue.poll() }
        assertTrue(reusable.isEmpty())
        assertEquals(2, queue.pendingCount)
        assertEquals(0, gpu.finishes)
        assertEquals(2, gpu.flushes)
        gpu.completed = 1
        queue.poll()
        assertEquals(listOf(1), reusable)
        assertEquals(1, queue.pendingCount)
        gpu.completed = 2
        queue.poll()
        assertEquals(listOf(1, 2), reusable)
        assertEquals(0, gpu.finishes)
    }

    @Test fun `海浪暂停后单独查询也能归还最后一份旧缓冲`() {
        val gpu = Gpu()
        val returned = mutableListOf<String>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        queue.retire("暂停前的前景")
        assertEquals(1, gpu.flushes)
        gpu.completed = 1
        queue.poll() // 没有新的 retire，也不要求再画一帧。
        repeat(5) { queue.poll() }
        assertEquals(listOf("暂停前的前景"), returned)
        assertEquals(listOf(1L), gpu.deleted)
        assertEquals(0, queue.pendingCount)
    }

    @Test fun `创建 fence 失败先同步已有采样 再归还新旧缓冲`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        queue.retire(1)
        gpu.unavailable = true
        queue.retire(2)
        assertEquals(listOf(1, 2), returned)
        assertEquals(1, gpu.finishes)
        assertFalse(queue.asynchronous)
        queue.retire(3)
        assertEquals(2, gpu.finishes)
        assertEquals(listOf(1, 2, 3), returned)
        assertEquals(listOf("fence_unavailable"), gpu.fallbacks)
    }

    @Test fun `查询失败退回同步 不丢在途缓冲且当前上下文不重试 fence`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        queue.retire(1); queue.retire(2)
        gpu.failed = true
        queue.poll()
        assertEquals(listOf(1, 2), returned)
        assertEquals(1, gpu.finishes)
        assertFalse(queue.asynchronous)
        queue.retire(3)
        assertEquals(3L, gpu.nextFence)
        assertEquals(listOf(1, 2, 3), returned)
        assertEquals(listOf("fence_poll_failed"), gpu.fallbacks)
    }

    @Test fun `输出切换先完成旧 GPU 工作 再一次性清空退休资源`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        queue.retire(1); queue.retire(2)
        gpu.finish()
        queue.drainAfterFinish()
        queue.resetForNewContext()
        queue.drainAfterFinish()
        assertEquals(listOf(1, 2), returned)
        assertEquals(listOf(1L, 2L), gpu.deleted)
        assertEquals(0, queue.pendingCount)
        queue.retire(3)
        assertTrue(queue.asynchronous)
        assertEquals(1, queue.pendingCount)
    }

    @Test fun `旧上下文还有资源时禁止直接复位`() {
        val queue = FableSolGpuRetirement(Gpu(), { _: Int -> })
        queue.retire(1)
        try { queue.resetForNewContext(); fail("不得跨上下文遗失 fence") }
        catch (_: IllegalStateException) { assertEquals(1, queue.pendingCount) }
    }

    @Test fun `新上下文可以重新尝试异步回收`() {
        val gpu = Gpu().apply { unavailable = true }
        val queue = FableSolGpuRetirement(gpu, { _: Int -> })
        queue.retire(1)
        assertFalse(queue.asynchronous)
        queue.resetForNewContext()
        gpu.unavailable = false
        queue.retire(2)
        assertTrue(queue.asynchronous)
        assertEquals(1, queue.pendingCount)
        assertEquals(1, gpu.finishes)
    }

    @Test fun `连续改尺寸造成退休积压时容量有界且不会提前重写`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add, maxPending = 3)
        repeat(3) { queue.retire(it) }
        queue.retire(3)
        assertEquals(listOf(0, 1, 2, 3), returned)
        assertEquals(0, queue.pendingCount)
        assertEquals(1, gpu.finishes)
        assertEquals(listOf("retirement_capacity"), gpu.fallbacks)
        assertTrue(queue.asynchronous)
    }

    @Test fun `长期不返回完成信号时有保守回退 不会永久占满捕获池`() {
        val gpu = Gpu()
        var now = 0L
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add, clockNs = { now }, maxAgeNs = 100)
        queue.retire(1)
        now = 99; queue.poll()
        assertTrue(returned.isEmpty())
        now = 100; queue.poll()
        assertEquals(listOf(1), returned)
        assertEquals(1, gpu.finishes)
        assertEquals(listOf("fence_stalled"), gpu.fallbacks)
    }

    @Test fun `同步回退本身失败时也不能归还仍在使用的缓冲`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        queue.retire(1)
        gpu.failed = true; gpu.finishFails = true
        try { queue.poll(); fail("等待 GPU 失败必须向上传递") }
        catch (_: IllegalStateException) { assertTrue(returned.isEmpty()) }
        assertEquals(1, queue.pendingCount)
        gpu.finishFails = false
        gpu.finish(); queue.drainAfterFinish()
        assertEquals(listOf(1), returned)
    }

    @Test fun `新退休帧建 fence 与同步均失败时仍保留所有权供关闭收尾`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        queue.retire(1)
        gpu.unavailable = true; gpu.finishFails = true
        try { queue.retire(2); fail("不能遗失新交入的缓冲") }
        catch (_: IllegalStateException) { assertTrue(returned.isEmpty()) }
        assertEquals(2, queue.pendingCount)
        gpu.finishFails = false
        gpu.finish(); queue.drainAfterFinish()
        assertEquals(listOf(1, 2), returned)
    }

    @Test fun `不同 GPU 完成节奏下重复复用缓冲 不丢失不重复且正常路径无 finish`() {
        val gpu = Gpu()
        val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        repeat(1000) { index ->
            queue.retire(index)
            if (index % 2 == 1) gpu.completed = gpu.nextFence - 1
            queue.poll()
            assertTrue(queue.pendingCount <= 2)
        }
        assertEquals((0 until 1000).toList(), returned)
        assertEquals(1000, gpu.deleted.size)
        assertEquals(0, gpu.finishes)
    }
}
