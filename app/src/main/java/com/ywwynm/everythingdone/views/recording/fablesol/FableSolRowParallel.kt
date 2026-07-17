package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

internal fun interface FableSolRowBody {
    fun run(startRow: Int, endRowExclusive: Int)
}

/**
 * 渲染线程内的确定性按行并行。
 *
 * 把 [0, total) 切成连续块：工作线程执行前若干块，调用线程执行最后一块后汇合。
 * 适用前提（调用方保证）：每行的运算只写本行数据、行间无读写冲突且不依赖
 * 执行顺序，因此并行结果与串行逐位一致——并行只改变行的完成顺序。
 * 任一工作块抛错时，异常在汇合后于调用线程重抛，不静默丢帧。
 */
internal object FableSolRowParallel {

    private const val MIN_PARALLEL_ROWS = 16
    private const val CHUNK_ROWS = 8

    private val workerCount =
        max(0, min(Runtime.getRuntime().availableProcessors() - 2, 3))

    private val pool = if (workerCount > 0) {
        Executors.newFixedThreadPool(workerCount, object : ThreadFactory {
            private val index = AtomicInteger()
            override fun newThread(task: Runnable): Thread {
                val thread = Thread({
                    try {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_DISPLAY
                        )
                    } catch (_: Throwable) {
                        // 非 Android 运行时（JVM 单测）没有该服务；保持默认优先级。
                    }
                    task.run()
                }, "FableSolRow-${index.incrementAndGet()}")
                thread.isDaemon = true
                return thread
            }
        })
    } else {
        null
    }

    fun run(total: Int, body: FableSolRowBody) {
        val executor = pool
        if (executor == null || total < MIN_PARALLEL_ROWS) {
            body.run(0, total)
            return
        }
        // 工作窃取式小块分配：大小核异构下固定均分会让整帧等待被调到慢核的
        // worker（实测尾延迟可达整块行数的慢核耗时）。原子计数器按 CHUNK_ROWS
        // 发放小块，快核自然多干、慢核最多拖一小块；worker 完全未被调度时调用
        // 线程吃满全部块（安全下限 = 串行）。行结果与执行者无关，输出不变。
        val cursor = AtomicInteger(0)
        val latch = CountDownLatch(workerCount)
        val failure = AtomicReference<Throwable>()
        val drain = {
            var start = cursor.getAndAdd(CHUNK_ROWS)
            while (start < total) {
                body.run(start, min(start + CHUNK_ROWS, total))
                start = cursor.getAndAdd(CHUNK_ROWS)
            }
        }
        for (i in 0 until workerCount) {
            executor.execute {
                try {
                    drain()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            drain()
        } finally {
            latch.await()
        }
        failure.get()?.let { throw RuntimeException("FableSol 并行行任务失败", it) }
    }
}
