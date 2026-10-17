package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

/** GPU 命令进度与 fence 分开：删除未完成的 fence 本身合法，但不能提前归还其资源。 */
class FableSolLastUseRetirementTest {
    private class Gpu : FableSolGpuRetirement.Backend {
        var submitted = 0
        var completed = 0
        var next = 1L
        var unavailable = false
        var failed = false
        var finishes = 0
        val fences = mutableMapOf<Long, Int>()
        val deleted = mutableSetOf<Long>()
        fun command() { submitted++ }
        override fun insertFence(): Long = if (unavailable) 0L else next++.also { fences[it] = submitted }
        override fun flush() = Unit
        override fun poll(fence: Long) = when {
            failed -> FableSolGpuRetirement.Status.FAILED
            checkNotNull(fences[fence]) <= completed -> FableSolGpuRetirement.Status.SIGNALED
            else -> FableSolGpuRetirement.Status.PENDING
        }
        override fun deleteFence(fence: Long) { check(fences.remove(fence) != null); check(deleted.add(fence)) }
        override fun finish() { finishes++; completed = submitted }
        override fun fallback(reason: String) = Unit
    }

    @Test fun `旧前景完成后立即归还 不等待下一帧无关海浪命令`() {
        val gpu = Gpu(); val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        gpu.command(); queue.markCurrentUse() // 第一次最终合成使用前景。
        gpu.completed = gpu.submitted
        gpu.command() // 下一轮海浪已经排队，但不再使用旧前景。
        queue.retire(1); queue.poll()
        assertEquals(listOf(1), returned)
        assertEquals(0, gpu.finishes)
        assertEquals(0, queue.pendingCount)
    }

    @Test fun `重复采样必须等待最后一次使用 而不是较早的已完成标记`() {
        val gpu = Gpu(); val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        gpu.command(); queue.markCurrentUse(); gpu.completed = 1
        gpu.command(); queue.markCurrentUse()
        queue.retire(1); queue.poll()
        assertTrue(returned.isEmpty())
        gpu.completed = 2; queue.poll()
        assertEquals(listOf(1), returned)
        assertTrue(gpu.fences.isEmpty())
        assertEquals(0, gpu.finishes)
    }

    @Test fun `记录新使用失败时不能继续依赖旧标记`() {
        val gpu = Gpu(); val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        gpu.command(); queue.markCurrentUse(); gpu.completed = 1
        gpu.command(); gpu.unavailable = true; queue.markCurrentUse()
        queue.retire(1)
        assertEquals(listOf(1), returned)
        assertEquals(1, gpu.finishes)
        assertFalse(queue.asynchronous)
        assertTrue(gpu.fences.isEmpty())
    }

    @Test fun `查询最后使用标记失败时仍完整同步后归还`() {
        val gpu = Gpu(); val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        gpu.command(); queue.markCurrentUse(); queue.retire(1)
        gpu.failed = true; queue.poll()
        assertEquals(1, gpu.finishes)
        assertEquals(listOf(1), returned)
        assertTrue(gpu.fences.isEmpty())
    }

    @Test fun `上下文关闭回收当前与退休标记 新上下文不继承旧 fence`() {
        val gpu = Gpu(); val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, returned::add)
        gpu.command(); queue.markCurrentUse(); queue.retire(1)
        gpu.command(); queue.markCurrentUse()
        gpu.finish(); queue.drainAfterFinish(); queue.resetForNewContext()
        assertEquals(listOf(1), returned)
        assertTrue(gpu.fences.isEmpty())
        gpu.command(); queue.markCurrentUse(); queue.retire(2); queue.poll()
        assertEquals(listOf(1), returned)
        gpu.completed = gpu.submitted; queue.poll()
        assertEquals(listOf(1, 2), returned)
    }

    @Test fun `暂停复用和一千次新前景交替 不提前归还也不泄漏标记`() {
        val gpu = Gpu(); val returned = mutableListOf<Int>()
        val queue = FableSolGpuRetirement(gpu, { id: Int ->
            assertTrue(gpu.completed >= id * 3 + 3)
            returned += id
        })
        repeat(1000) { id ->
            repeat(3) { gpu.command(); queue.markCurrentUse() }
            queue.retire(id); queue.poll()
            assertEquals(id, returned.size)
            gpu.completed = gpu.submitted; queue.poll()
            assertEquals(id + 1, returned.size)
        }
        assertTrue(gpu.fences.isEmpty())
        assertEquals(0, gpu.finishes)
    }
}
