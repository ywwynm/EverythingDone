package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray

class FableSolForegroundMailboxTest {
    @Test fun `网格构建期间到达的多帧合并为最新一帧`() {
        val returned = mutableListOf<Int>()
        val mailbox = FableSolForegroundMailbox<Int>(returned::add)
        mailbox.open()
        mailbox.offer(1); mailbox.offer(2); mailbox.offer(3)
        assertEquals(listOf(1, 2), returned)
        assertEquals(3, mailbox.take())
        assertNull(mailbox.take())
    }

    @Test fun `已交给 GPU 的当前帧不被后续 offer 或 close 归还`() {
        val returned = mutableListOf<Int>()
        val mailbox = FableSolForegroundMailbox<Int>(returned::add)
        mailbox.open(); mailbox.offer(1)
        val usedByGpu = mailbox.take()
        mailbox.offer(2); mailbox.close()
        assertEquals(1, usedByGpu)
        assertEquals(listOf(2), returned)
    }

    @Test fun `最终合成已取走前景时 后续排队回调不会再呈现同一份`() {
        val mailbox = FableSolForegroundMailbox<Int> { fail("不应丢弃已消费帧") }
        mailbox.open(); mailbox.offer(1)
        assertEquals(1, mailbox.take())
        assertNull(mailbox.take())
        mailbox.offer(2)
        assertEquals(2, mailbox.take())
    }

    @Test fun `关闭后拒收 所有权仍由发起者释放 重新打开无旧帧`() {
        val returned = mutableListOf<Int>()
        val mailbox = FableSolForegroundMailbox<Int>(returned::add)
        assertFalse(mailbox.offer(0))
        mailbox.open(); mailbox.offer(1); mailbox.close(); mailbox.close()
        assertFalse(mailbox.offer(2))
        assertEquals(listOf(1), returned)
        mailbox.open()
        assertNull(mailbox.take())
        assertTrue(mailbox.offer(3))
        assertEquals(3, mailbox.take())
    }

    @Test fun `不得用 open 遗失尚未处理的旧帧`() {
        val mailbox = FableSolForegroundMailbox<Int> { }
        mailbox.open(); mailbox.offer(1)
        try { mailbox.open(); fail("必须先清理旧所有权") }
        catch (_: IllegalStateException) { assertEquals(1, mailbox.take()) }
    }

    @Test fun `捕获线程与呈现线程并发时每帧只交还一次`() {
        val count = 10000
        val released = AtomicIntegerArray(count)
        val mailbox = FableSolForegroundMailbox<Int> { released.incrementAndGet(it) }
        val done = AtomicBoolean()
        val executor = Executors.newFixedThreadPool(2)
        mailbox.open()
        try {
            val writer = executor.submit {
                repeat(count) { if (!mailbox.offer(it)) released.incrementAndGet(it) }
                done.set(true)
            }
            val reader = executor.submit {
                while (!done.get()) {
                    mailbox.take()?.let { released.incrementAndGet(it) }
                    Thread.yield()
                }
                mailbox.close()
            }
            writer.get(5, TimeUnit.SECONDS); reader.get(5, TimeUnit.SECONDS)
            repeat(count) { assertEquals("frame $it", 1, released.get(it)) }
        } finally { executor.shutdownNow() }
    }
}
