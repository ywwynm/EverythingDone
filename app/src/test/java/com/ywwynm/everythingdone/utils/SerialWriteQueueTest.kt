package com.ywwynm.everythingdone.utils

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialWriteQueueTest {
    private class Tasks : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    @Test fun `已发出完成通知但尚未读取时的新保存不能遗漏`() {
        val worker = Tasks()
        val main = Tasks()
        val queue = SerialWriteQueue(worker, main)
        var saved = "原值"
        val published = mutableListOf<String>()
        queue.execute { saved = "第一次修改" }
        queue.afterWrites { published += saved }
        worker.drain()

        queue.execute { saved = "第二次修改" }
        main.drain()
        assertTrue(published.isEmpty())
        worker.drain()
        main.drain()
        assertEquals(listOf("第二次修改"), published)
    }

    @Test fun `多个等待者各自只发布一次且看到全部已提交的保存`() {
        val worker = Tasks()
        val main = Tasks()
        val queue = SerialWriteQueue(worker, main)
        val saved = mutableListOf<Int>()
        val published = mutableListOf<List<Int>>()
        queue.execute { saved += 1 }
        queue.afterWrites { published += saved.toList() }
        worker.drain()
        queue.execute { saved += 2 }
        queue.afterWrites { published += saved.toList() }
        main.drain()
        worker.drain()
        main.drain()
        assertEquals(listOf(listOf(1, 2), listOf(1, 2)), published)
    }

    @Test fun `保存和读取回调追加的写入继续保持顺序`() {
        val worker = Tasks()
        val main = Tasks()
        val queue = SerialWriteQueue(worker, main)
        val saved = mutableListOf<Int>()
        val published = mutableListOf<List<Int>>()
        queue.execute {
            saved += 1
            queue.execute { saved += 2 }
        }
        queue.afterWrites {
            published += saved.toList()
            queue.execute { saved += 3 }
            queue.afterWrites { published += saved.toList() }
        }
        repeat(4) { worker.drain(); main.drain() }
        assertEquals(listOf(listOf(1, 2), listOf(1, 2, 3)), published)
    }
}
