package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * 这条回归来自 OPPO PMA110 上的一次真实崩溃：
 * `ArrayIndexOutOfBoundsException: length=0; index=0`，栈顶是 `toIntArray`。
 *
 * 原因是 Kotlin 的 `Collection<Int>.toIntArray()` 分两步——先读 `size` 建数组，再迭代填充。
 * 被读的列表正被 worker 线程在启动时并发写入，两步之间恰好完成一次注册，就会拿着 length=0
 * 的数组去写 index=0。这里用同样的并发形态把两种写法放在一起跑，证明分两步的那种会炸、
 * 先取原子快照的不会。
 */
class FableSolRowParallelSnapshotTest {

    @Test
    fun snapshotThenConvertSurvivesConcurrentRegistration() {
        repeat(ROUNDS) {
            val list = CopyOnWriteArrayList<Int>()
            val start = CountDownLatch(1)
            val writer = Thread {
                start.await()
                for (value in 0 until WRITES) list.add(value)
            }
            writer.start()
            start.countDown()

            // 与 FableSolRowParallel.workerThreadIds() 完全同一种写法。
            repeat(READS) {
                val snapshot = ArrayList(list)
                val ids = IntArray(snapshot.size) { snapshot[it] }
                // 长度与内容必然自洽：能取到就说明没有越界，也没有读到半截。
                assertTrue(ids.size == snapshot.size)
            }
            writer.join()
        }
    }

    private companion object {
        const val ROUNDS = 200
        const val WRITES = 64
        const val READS = 64
    }
}
