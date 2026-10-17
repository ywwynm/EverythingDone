package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FableSolPerfAccumulatorTest {
    @Test fun `不可用时长不计入样本 零时长仍有效`() {
        val stats = FableSolPerfAccumulator()
        stats.sample(FableSolPerfMetric.WINDOW_GPU, -1)
        stats.sample(FableSolPerfMetric.GL_SWAP, 0)
        val result = stats.drain()
        assertFalse(result.metrics.containsKey(FableSolPerfMetric.WINDOW_GPU))
        assertEquals(1L, result.metrics.getValue(FableSolPerfMetric.GL_SWAP).count)
        assertEquals(0L, result.metrics.getValue(FableSolPerfMetric.GL_SWAP).percentile(.95))
    }

    @Test fun `环形样本有界 计数均值最大值仍覆盖整段`() {
        val stats = FableSolPerfAccumulator(4)
        listOf(100L, 1, 2, 3, 4, 5).forEach { stats.sample(FableSolPerfMetric.GL_SWAP, it) }
        val result = stats.drain().metrics.getValue(FableSolPerfMetric.GL_SWAP)
        assertEquals(6L, result.count)
        assertEquals(115.0, result.sumNs, 0.0)
        assertEquals(100L, result.maxNs)
        assertArrayEquals(longArrayOf(2, 3, 4, 5), result.values.sortedArray())
        assertEquals(3L, result.percentile(.5))
        assertEquals(5L, result.percentile(.95))
        assertEquals(5L, result.percentile(.99))
    }

    @Test fun `长帧阈值与监测回调丢失分开统计`() {
        val stats = FableSolPerfAccumulator()
        listOf(16_666_667L, 16_666_668L, 33_333_333L, 33_333_334L).forEach {
            stats.sample(FableSolPerfMetric.WINDOW_TOTAL, it)
        }
        stats.increment(FableSolPerfCount.METRICS_CALLBACKS_LOST, 9)
        stats.increment(FableSolPerfCount.GL_SWAP_OK, 2)
        stats.increment(FableSolPerfCount.METRICS_CALLBACKS_LOST, -1)
        val result = stats.drain()
        val window = result.metrics.getValue(FableSolPerfMetric.WINDOW_TOTAL)
        assertEquals(3L, window.above16Ms)
        assertEquals(1L, window.above33Ms)
        assertEquals(9L, result.counts[FableSolPerfCount.METRICS_CALLBACKS_LOST.ordinal])
        assertEquals(2L, result.counts[FableSolPerfCount.GL_SWAP_OK.ordinal])
    }

    @Test fun `短会话导出能取走全部剩余样本 不重复累计`() {
        val stats = FableSolPerfAccumulator()
        stats.sample(FableSolPerfMetric.FOREGROUND_GL_QUEUE, 42)
        stats.increment(FableSolPerfCount.CAPTURE_READY)
        val first = stats.drain()
        val empty = stats.drain()
        assertEquals(1L, first.metrics.getValue(FableSolPerfMetric.FOREGROUND_GL_QUEUE).count)
        assertEquals(1L, first.counts[FableSolPerfCount.CAPTURE_READY.ordinal])
        assertTrue(empty.metrics.isEmpty())
        assertTrue(empty.counts.all { it == 0L })
        stats.sample(FableSolPerfMetric.FOREGROUND_GL_QUEUE, 16)
        assertEquals(16L, stats.drain().metrics.getValue(FableSolPerfMetric.FOREGROUND_GL_QUEUE).maxNs)
        assertEquals(42L, first.metrics.getValue(FableSolPerfMetric.FOREGROUND_GL_QUEUE).maxNs)
    }

    @Test fun `多个渲染线程采样与导出并发时总样本不丢失`() {
        val stats = FableSolPerfAccumulator(32)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        try {
            repeat(4) {
                executor.execute {
                    try {
                        start.await()
                        repeat(4000) {
                            stats.sample(FableSolPerfMetric.CAPTURE_FENCE, 12)
                            stats.increment(FableSolPerfCount.CAPTURE_READY)
                        }
                    } finally { done.countDown() }
                }
            }
            start.countDown()
            var samples = 0L; var count = 0L; var sum = 0.0
            fun read() {
                val snapshot = stats.drain()
                snapshot.metrics[FableSolPerfMetric.CAPTURE_FENCE]?.let {
                    samples += it.count; sum += it.sumNs
                    assertTrue(it.values.size <= 32)
                }
                count += snapshot.counts[FableSolPerfCount.CAPTURE_READY.ordinal]
            }
            repeat(30) { read() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            read()
            assertEquals(16000L, samples)
            assertEquals(16000L, count)
            assertEquals(192000.0, sum, 0.0)
        } finally { executor.shutdownNow() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝无效的样本容量`() { FableSolPerfAccumulator(0) }
}
