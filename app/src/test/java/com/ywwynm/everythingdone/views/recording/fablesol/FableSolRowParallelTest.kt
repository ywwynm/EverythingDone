package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FableSolRowParallelTest {

    @Test
    fun repeatedHotPathVisitsEveryRowExactlyOnce() {
        val total = FableSolContinuousSurface.Z_ROWS
        val visits = AtomicIntegerArray(total)
        val body = FableSolRowBody { start, end ->
            for (row in start until end) visits.incrementAndGet(row)
        }

        // 覆盖远多于单帧的连续复用轮数；同一个 body 和同一组同步状态反复执行。
        repeat(1_000) { round ->
            FableSolRowParallel.run(total, body)
            for (row in 0 until total) {
                assertEquals("round=$round row=$row", 1, visits.getAndSet(row, 0))
            }
        }
    }

    @Test
    fun persistentWorkersAndCallingThreadBothStealEightRowChunks() {
        assumeTrue(FableSolRowParallel.parallelWorkerCountForTest > 0)
        val caller = Thread.currentThread()
        val callerEntered = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val callerChunks = AtomicInteger()
        val workerChunks = AtomicInteger()

        FableSolRowParallel.run(97) { start, end ->
            assertTrue("start=$start end=$end", start % 8 == 0)
            assertTrue("start=$start end=$end", end - start in 1..8)
            if (Thread.currentThread() === caller) {
                callerChunks.incrementAndGet()
                callerEntered.countDown()
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            } else {
                workerChunks.incrementAndGet()
                workerEntered.countDown()
                assertTrue(callerEntered.await(5, TimeUnit.SECONDS))
            }
        }

        assertTrue("callerChunks=${callerChunks.get()}", callerChunks.get() > 0)
        assertTrue("workerChunks=${workerChunks.get()}", workerChunks.get() > 0)
    }

    @Test
    fun workerFailureReturnsToCallerAndNextRoundStillWorks() {
        assumeTrue(FableSolRowParallel.parallelWorkerCountForTest > 0)
        val caller = Thread.currentThread()
        val callerEntered = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        var thrown: RuntimeException? = null

        try {
            FableSolRowParallel.run(97) { _, _ ->
                if (Thread.currentThread() === caller) {
                    callerEntered.countDown()
                    assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
                } else {
                    workerEntered.countDown()
                    assertTrue(callerEntered.await(5, TimeUnit.SECONDS))
                    throw ExpectedWorkerFailure
                }
            }
        } catch (error: RuntimeException) {
            thrown = error
        }

        assertEquals("FableSol 并行行任务失败", thrown?.message)
        assertSame(ExpectedWorkerFailure, thrown?.cause)

        val visits = AtomicIntegerArray(97)
        FableSolRowParallel.run(97) { start, end ->
            for (row in start until end) visits.incrementAndGet(row)
        }
        for (row in 0 until 97) assertEquals("recovery row=$row", 1, visits.get(row))
    }

    /**
     * 按层并行（C3/C8/C2）的入口：total=9 会被默认的 16 行阈值直接串行，
     * 因此必须走 [FableSolRowParallel.runUnits]，且每个单元自成一块。
     */
    @Test
    fun 小任务入口把九个单元逐个成块且每个恰好执行一次() {
        val total = FableSolSpec.N_LAYERS
        val visits = AtomicIntegerArray(total)
        val chunkSizes = java.util.Collections.synchronizedList(ArrayList<Int>())
        val body = FableSolRowBody { start, end ->
            chunkSizes.add(end - start)
            for (unit in start until end) visits.incrementAndGet(unit)
        }

        repeat(300) { round ->
            chunkSizes.clear()
            FableSolRowParallel.runUnits(total, body)
            for (unit in 0 until total) {
                assertEquals("round=$round unit=$unit", 1, visits.getAndSet(unit, 0))
            }
            assertEquals("round=$round chunks=$chunkSizes", total, chunkSizes.size)
            assertTrue("round=$round chunks=$chunkSizes", chunkSizes.all { it == 1 })
        }
    }

    /** 默认入口的语义不得被小任务重载改动：97 行仍是 8 行一块。 */
    @Test
    fun 默认入口仍按八行分块() {
        val chunkStarts = java.util.Collections.synchronizedList(ArrayList<Int>())
        FableSolRowParallel.run(FableSolContinuousSurface.Z_ROWS) { start, end ->
            chunkStarts.add(start)
            assertTrue("start=$start end=$end", start % 8 == 0)
            assertTrue("start=$start end=$end", end - start in 1..8)
        }
        assertEquals(13, chunkStarts.size)
    }

    /** 小任务入口的失败语义必须与默认入口一致：后台异常包装后回传，下一轮仍可用。 */
    @Test
    fun 小任务入口的后台异常同样包装回传且不污染下一轮() {
        assumeTrue(FableSolRowParallel.parallelWorkerCountForTest > 0)
        val caller = Thread.currentThread()
        val callerEntered = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        var thrown: RuntimeException? = null

        try {
            FableSolRowParallel.runUnits(FableSolSpec.N_LAYERS) { _, _ ->
                if (Thread.currentThread() === caller) {
                    callerEntered.countDown()
                    assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
                } else {
                    workerEntered.countDown()
                    assertTrue(callerEntered.await(5, TimeUnit.SECONDS))
                    throw ExpectedWorkerFailure
                }
            }
        } catch (error: RuntimeException) {
            thrown = error
        }

        assertEquals("FableSol 并行行任务失败", thrown?.message)
        assertSame(ExpectedWorkerFailure, thrown?.cause)

        // 紧接着切回默认入口：块大小必须已经复位回 8，不能残留上一轮的 1。
        val visits = AtomicIntegerArray(FableSolContinuousSurface.Z_ROWS)
        FableSolRowParallel.run(FableSolContinuousSurface.Z_ROWS) { start, end ->
            assertTrue("start=$start end=$end", end - start in 1..8)
            for (row in start until end) visits.incrementAndGet(row)
        }
        for (row in 0 until FableSolContinuousSurface.Z_ROWS) {
            assertEquals("recovery row=$row", 1, visits.get(row))
        }
    }

    /** 调用线程自身抛出的异常必须原样重抛（不包装），小任务入口同样适用。 */
    @Test
    fun 小任务入口的调用线程异常原样重抛() {
        val caller = Thread.currentThread()
        val callerEntered = CountDownLatch(1)
        var thrown: Throwable? = null
        try {
            FableSolRowParallel.runUnits(64) { _, _ ->
                if (Thread.currentThread() === caller) {
                    callerEntered.countDown()
                    throw ExpectedCallerFailure
                }
                // 后台单元先等调用线程认领到一块：块大小为 1 时若不挡一下，
                // worker 有可能把 64 块全部抢空，调用线程就没有异常可抛了。
                callerEntered.await(5, TimeUnit.SECONDS)
            }
        } catch (error: Throwable) {
            thrown = error
        }
        assertSame(ExpectedCallerFailure, thrown)
    }

    @Test
    fun runDoesNotRecreateTasksOrSynchronizationObjects() {
        val source = rowParallelSource()

        assertFalse(source.contains("import java.util.concurrent.CountDownLatch"))
        assertFalse(Regex("CountDownLatch\\s*\\(").containsMatchIn(source))
        assertFalse(source.contains("import java.util.concurrent.Executors"))
        assertFalse(source.contains("executor.execute"))
        assertFalse(source.contains("val drain ="))
        // 两个 AtomicInteger 都是 Coordinator 字段（游标 + 汇合计数），构造一次、
        // 跨轮复用；这里锁定的是"每轮不新建同步对象"，不是数量本身。
        assertEquals(2, Regex("AtomicInteger\\(").findAll(source).count())
        assertEquals(1, Regex("AtomicReference<Throwable\\?>\\(").findAll(source).count())
        assertTrue(source.contains("private val completionMonitor = Object()"))
        assertTrue(source.contains("Thread(Worker(), \"FableSolRow-"))
    }

    /**
     * 等待策略必须是"先自旋、再挂起"：纯 `wait()` 会让每次派发都付一次 futex
     * 往返（实测空 body 22.8µs），而纯自旋会在跨帧的毫秒级间隔里空转烧电。
     */
    @Test
    fun waitingSpinsBrieflyThenParks() {
        val source = rowParallelSource()

        assertTrue(source.contains("WORKER_SPIN_NANOS"))
        assertTrue(source.contains("CALLER_SPIN_NANOS"))
        // 自旋预算必须远小于一帧（120fps 下 8.3ms），否则 worker 会跨帧空转。
        assertTrue(Regex("WORKER_SPIN_NANOS = (\\d[\\d_]*)L").find(source)!!
            .groupValues[1].replace("_", "").toLong() <= 200_000L)
        assertTrue(Regex("CALLER_SPIN_NANOS = (\\d[\\d_]*)L").find(source)!!
            .groupValues[1].replace("_", "").toLong() <= 200_000L)
        // 自旋要读到别的线程的写，代次与汇合计数必须是 volatile / atomic。
        assertTrue(source.contains("@Volatile private var generation"))
        assertTrue(source.contains("completedWorkers.incrementAndGet()"))
        // 放弃自旋后仍必须落到 monitor 挂起，不能无限忙等。
        assertTrue(source.contains("completionMonitor.wait()"))
    }

    private fun rowParallelSource(): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(
                directory,
                "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                    "FableSolRowParallel.kt"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 FableSolRowParallel.kt")
    }

    private object ExpectedWorkerFailure : RuntimeException("expected worker failure")

    private object ExpectedCallerFailure : RuntimeException("expected caller failure")
}
