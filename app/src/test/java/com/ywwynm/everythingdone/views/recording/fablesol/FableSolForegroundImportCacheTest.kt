package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

class FableSolForegroundImportCacheTest {
    private data class Buffer(val id: Int) { var closed = false; var version = 0 }
    private class Image(val id: Int, val buffer: Buffer) {
        var alive = true
        var version = buffer.version
    }
    private class Driver {
        var created = 0
        var rebound = 0
        var deleted = 0
        var failRebind = false
        var failCreate = false
        val cache = FableSolForegroundImportCache<Buffer, Image>(
            create = { check(!failCreate); Image(++created, it) },
            rebind = {
                check(it.alive); rebound++
                if (failRebind) false else { it.version = it.buffer.version; true }
            },
            destroy = { check(it.alive); it.alive = false; deleted++ },
            valid = { !it.closed }
        )
    }

    @Test fun `三个实际缓冲周转只创建三份导入资源`() {
        val d = Driver(); val buffers = List(3) { Buffer(it) }
        repeat(300) {
            val entry = d.cache.acquire(buffers[it % 3])
            d.cache.release(entry)
        }
        assertEquals(3, d.created)
        assertEquals(297, d.rebound)
        assertEquals(0, d.deleted)
        assertEquals(3, d.cache.size)
        d.cache.clearAfterFinish()
        assertEquals(3, d.deleted)
    }

    @Test fun `同一缓冲重新写入后每次都重绑新内容`() {
        val d = Driver(); val buffer = Buffer(1)
        val first = d.cache.acquire(buffer); d.cache.release(first)
        buffer.version = 2
        val second = d.cache.acquire(buffer)
        assertSame(first, second)
        assertEquals(2, second.value.version)
        assertEquals(1, d.rebound)
    }

    @Test fun `GPU 在用的相同缓冲不能再次取得`() {
        val d = Driver(); val buffer = Buffer(1)
        val current = d.cache.acquire(buffer)
        assertThrows(IllegalStateException::class.java) { d.cache.acquire(buffer) }
        assertEquals(0, d.rebound)
        assertTrue(current.value.alive)
    }

    @Test fun `容量压力只淘汰空闲项 不销毁 GPU 在用项`() {
        val d = Driver()
        val pinned = d.cache.acquire(Buffer(0))
        repeat(12) { val e = d.cache.acquire(Buffer(it + 1)); d.cache.release(e) }
        assertTrue(pinned.value.alive)
        assertEquals(3, d.cache.size)
        assertEquals(1, d.cache.activeCount)
        assertEquals(10, d.deleted)
    }

    @Test fun `尺寸切换期间所有旧项在用时允许暂存 待完成后恢复容量`() {
        val d = Driver()
        val items = List(4) { d.cache.acquire(Buffer(it)) }
        assertEquals(4, d.cache.size)
        assertEquals(0, d.deleted)
        d.cache.release(items[0])
        assertFalse(items[0].value.alive)
        assertEquals(3, d.cache.size)
        assertTrue(items.drop(1).all { it.value.alive })
    }

    @Test fun `原捕获池关闭的空闲缓冲不继续保留导入引用`() {
        val d = Driver(); val buffer = Buffer(1)
        val old = d.cache.acquire(buffer); d.cache.release(old)
        buffer.closed = true
        d.cache.acquire(Buffer(2))
        assertFalse(old.value.alive)
        assertEquals(1, d.cache.size)
        assertThrows(IllegalStateException::class.java) { d.cache.acquire(buffer) }
    }

    @Test fun `上下文重建清空全部空闲资源 当前帧在新上下文重新导入`() {
        val d = Driver(); val buffer = Buffer(1)
        val old = d.cache.acquire(buffer)
        assertThrows(IllegalStateException::class.java) { d.cache.clearAfterFinish() }
        d.cache.release(old); d.cache.clearAfterFinish()
        assertFalse(old.value.alive)
        val fresh = d.cache.acquire(buffer)
        assertNotSame(old, fresh)
        assertEquals(2, d.created)
    }

    @Test fun `明确的重绑错误本上下文回退逐次导入 重建后再允许复用`() {
        val d = Driver(); val buffer = Buffer(1)
        val old = d.cache.acquire(buffer); d.cache.release(old)
        d.failRebind = true
        val recreated = d.cache.acquire(buffer)
        assertFalse(old.value.alive)
        assertFalse(d.cache.reuseEnabled)
        assertEquals(2, d.created)
        d.cache.release(recreated)
        assertEquals(0, d.cache.size)
        val third = d.cache.acquire(buffer); d.cache.release(third)
        assertEquals(1, d.rebound)
        d.cache.clearAfterFinish(); d.failRebind = false
        assertTrue(d.cache.reuseEnabled)
        val fourth = d.cache.acquire(buffer); d.cache.release(fourth)
        assertSame(fourth, d.cache.acquire(buffer))
    }

    @Test fun `创建失败不会留下占用项`() {
        val d = Driver(); d.failCreate = true
        assertThrows(IllegalStateException::class.java) { d.cache.acquire(Buffer(1)) }
        assertEquals(0, d.cache.size)
        assertEquals(0, d.cache.activeCount)
    }

    @Test fun `重复归还或归还其它缓存的资源会被拒绝`() {
        val d = Driver(); val entry = d.cache.acquire(Buffer(1))
        assertThrows(IllegalStateException::class.java) { Driver().cache.release(entry) }
        d.cache.release(entry)
        assertThrows(IllegalStateException::class.java) { d.cache.release(entry) }
    }

    @Test fun `内容相等但不是同一硬件对象的缓冲不得共用导入`() {
        val d = Driver(); val first = d.cache.acquire(Buffer(1)); d.cache.release(first)
        val second = d.cache.acquire(Buffer(1))
        assertNotSame(first, second)
        assertEquals(0, d.rebound)
    }

    private class Gpu : FableSolGpuRetirement.Backend {
        var completed = false
        var nextFence = 1L
        override fun insertFence() = nextFence++
        override fun flush() = Unit
        override fun poll(fence: Long) = if (completed) FableSolGpuRetirement.Status.SIGNALED else FableSolGpuRetirement.Status.PENDING
        override fun deleteFence(fence: Long) = Unit
        override fun finish() { completed = true }
        override fun fallback(reason: String) = Unit
    }

    @Test fun `真实退休队列完成后才可复用 同步完成前既不归还也不删除`() {
        val d = Driver(); val gpu = Gpu(); var returned = 0
        val retirement = FableSolGpuRetirement(gpu, { entry: FableSolForegroundImportCache.Entry<Buffer, Image> ->
            assertTrue(gpu.completed); d.cache.release(entry); returned++
        })
        val buffer = Buffer(1); val original = d.cache.acquire(buffer)
        retirement.markCurrentUse(); retirement.retire(original); retirement.poll()
        assertEquals(0, returned)
        assertThrows(IllegalStateException::class.java) { d.cache.acquire(buffer) }
        assertTrue(original.value.alive)
        gpu.completed = true; retirement.poll()
        assertEquals(1, returned)
        assertSame(original, d.cache.acquire(buffer))
    }

    @Test fun `关闭时完成当前和退休项后销毁 不遗留导入对象`() {
        val d = Driver(); val gpu = Gpu()
        val retirement = FableSolGpuRetirement(gpu, { entry: FableSolForegroundImportCache.Entry<Buffer, Image> -> d.cache.release(entry) })
        val old = d.cache.acquire(Buffer(1)); retirement.markCurrentUse(); retirement.retire(old)
        val current = d.cache.acquire(Buffer(2)); retirement.markCurrentUse()
        gpu.finish(); retirement.drainAfterFinish(); d.cache.release(current); d.cache.clearAfterFinish()
        assertEquals(0, d.cache.size)
        assertEquals(2, d.deleted)
        retirement.resetForNewContext()
    }

    @Test fun `一千次三缓冲 GPU 周转保持复用且关闭后无残留`() {
        val d = Driver(); val gpu = Gpu(); var returned = 0
        val retirement = FableSolGpuRetirement(gpu, { entry: FableSolForegroundImportCache.Entry<Buffer, Image> -> d.cache.release(entry); returned++ })
        val buffers = List(3) { Buffer(it) }
        repeat(1000) {
            gpu.completed = false
            val entry = d.cache.acquire(buffers[it % 3]); retirement.markCurrentUse(); retirement.retire(entry)
            retirement.poll(); assertEquals(it, returned)
            gpu.completed = true; retirement.poll(); assertEquals(it + 1, returned)
            assertTrue(d.cache.size <= 3)
        }
        assertEquals(3, d.created)
        assertEquals(997, d.rebound)
        d.cache.clearAfterFinish(); assertEquals(3, d.deleted)
    }
}
