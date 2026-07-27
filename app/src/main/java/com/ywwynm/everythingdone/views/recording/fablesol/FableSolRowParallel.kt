package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.concurrent.CopyOnWriteArrayList
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
 * 把 [0, total) 切成 8 行连续块，由常驻 worker 与调用线程共同窃取。调用方必须保证
 * 每行只写本行数据、行间没有读写冲突且结果不依赖执行顺序；调度只改变完成顺序，
 * 不改变逐行结果。后台块抛错时，会在本轮所有 worker 汇合后回传到调用线程。
 *
 * 同步器、游标、失败槽和 worker 都只初始化一次。正常 [run] 不创建 latch、任务
 * Runnable 或 drain lambda，避免一帧多次构面时在渲染热路径制造短命对象。
 */
internal object FableSolRowParallel {

    private const val MIN_PARALLEL_ROWS = 16
    private const val CHUNK_ROWS = 8

    // 按层并行（9 个单元）的阈值与块大小。默认的 16 行阈值会把 total=9 直接串行，
    // 8 行块也会把 9 个单元切成不均的两块；这里每个单元自成一块，2 个单元起并行。
    private const val MIN_PARALLEL_UNITS = 2
    private const val UNIT_CHUNK = 1

    // 自旋预算。帧内两次派发的间隔（串行预备段）在几十微秒量级，跨帧间隔在
    // 8.3ms 量级，因此 80µs 足以覆盖帧内空档而不会跨帧空转：三个 worker 每帧
    // 最多多烧 3×80µs，即单核约 3% 占用。
    private const val WORKER_SPIN_NANOS = 80_000L
    private const val CALLER_SPIN_NANOS = 60_000L
    private const val TIME_CHECK_MASK = 127

    private val workerCount =
        max(0, min(Runtime.getRuntime().availableProcessors() - 2, 3))

    private val coordinator = if (workerCount > 0) Coordinator(workerCount) else null

    // worker 启动后自行登记内核 tid；非 Android 运行时（JVM 单测）登记被跳过，保持为空。
    private val workerTids = CopyOnWriteArrayList<Int>()

    internal val parallelWorkerCountForTest: Int
        get() = workerCount

    /** 供 debug 性能仪表显示：实际常驻 worker 数与运行时可见核数。 */
    val activeWorkerCount: Int get() = workerCount
    val visibleCoreCount: Int get() = Runtime.getRuntime().availableProcessors()

    /**
     * 供 ADPF 提示会话绑定：常驻 worker 的内核线程 id 快照。
     *
     * **必须先取一次原子快照再转数组。** Kotlin 的 `Collection<Int>.toIntArray()` 是
     * "先读 size 建数组、再迭代填充"两步；而这个列表正被 worker 线程在启动时并发写入，
     * 两步之间恰好完成一次注册，就会拿着 length=0 的数组去写 index=0——
     * OPPO PMA110 上实际崩过一次（`ArrayIndexOutOfBoundsException: length=0; index=0`）。
     * `ArrayList(collection)` 走的是 `CopyOnWriteArrayList.toArray()`，那是对当前数组的
     * 一次原子拷贝，长度与内容必然自洽。
     */
    fun workerThreadIds(): IntArray {
        val snapshot = ArrayList(workerTids)
        return IntArray(snapshot.size) { snapshot[it] }
    }

    fun run(total: Int, body: FableSolRowBody) {
        run(total, MIN_PARALLEL_ROWS, CHUNK_ROWS, body)
    }

    /**
     * 小任务入口：调用方自定阈值与块大小。
     *
     * 97 行的显示重建用默认的「≥16 行才并行、8 行一块」；而按层并行的任务只有
     * 9 个单元，用默认阈值会被直接串行，块大小 8 也会把 9 个单元切成 2 块。
     * 合同与默认入口完全一致：调用方保证每个单元只写自身数据、结果不依赖执行
     * 顺序；调度只改变完成顺序，不改变逐单元结果。异常语义也不变——调用线程
     * 自身的异常原样重抛，后台异常包装后回传。
     */
    /**
     * 九层这类「单元少、每个单元很重」的任务入口：每个单元独立成块。
     * 合同与 [run] 完全一致，body 收到的仍是 `[start, end)` 半开区间。
     */
    fun runUnits(total: Int, body: FableSolRowBody) {
        run(total, MIN_PARALLEL_UNITS, UNIT_CHUNK, body)
    }

    fun run(total: Int, minParallelRows: Int, chunkRows: Int, body: FableSolRowBody) {
        require(chunkRows >= 1) { "chunkRows 必须 ≥ 1：$chunkRows" }
        val activeCoordinator = coordinator
        if (activeCoordinator == null || total < minParallelRows) {
            body.run(0, total)
            return
        }
        activeCoordinator.run(total, chunkRows, body)
    }

    /**
     * 单一 GL 渲染器通常是唯一调用者；runLock 同时让 JVM 测试或 Canvas/GL 切换时的
     * 偶发并发调用安全串行化。锁、monitor 和原子槽均跨轮复用。
     */
    private class Coordinator(private val workerCount: Int) {
        private val runLock = Object()
        private val completionMonitor = Object()
        private val cursor = AtomicInteger(0)
        private val failure = AtomicReference<Throwable?>()
        private val completedWorkers = AtomicInteger(0)

        // generation 是发布屏障：它的 volatile 写把 totalRows/chunkRows/currentBody
        // 一并发布，worker 读到新代次后才会去读这三项，因此自旋路径不必进 monitor
        // 也安全。三者必须在同一个 runLock 临界区内、代次推进之前写完。
        @Volatile private var generation = 0
        @Volatile private var totalRows = 0
        @Volatile private var chunkRows = CHUNK_ROWS
        @Volatile private var currentBody: FableSolRowBody? = null

        init {
            repeat(workerCount) { index ->
                Thread(Worker(), "FableSolRow-${index + 1}").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        fun run(total: Int, chunk: Int, body: FableSolRowBody) {
            synchronized(runLock) {
                totalRows = total
                chunkRows = chunk
                currentBody = body
                cursor.set(0)
                failure.set(null)
                completedWorkers.set(0)
                synchronized(completionMonitor) {
                    // 仍在 monitor 内推进代次：已经放弃自旋、正准备 wait() 的 worker
                    // 必须不能漏掉这次通知。
                    generation += 1
                    completionMonitor.notifyAll()
                }

                var callerFailure: Throwable? = null
                try {
                    drainCurrentRun()
                } catch (error: Throwable) {
                    // 保持旧语义：调用线程自身的异常原样重抛；后台异常才包装。
                    callerFailure = error
                }

                val interruption = awaitWorkers()
                currentBody = null
                val workerFailure = failure.getAndSet(null)

                if (interruption != null) throw interruption
                if (callerFailure != null) throw callerFailure
                workerFailure?.let { error ->
                    throw RuntimeException("FableSol 并行行任务失败", error)
                }
            }
        }

        /** 按本轮发布的块大小工作窃取；调用线程和所有常驻 worker 共用同一个游标。 */
        private fun drainCurrentRun() {
            val body = currentBody
                ?: throw IllegalStateException("FableSol 并行行任务尚未发布")
            val total = totalRows
            val chunk = chunkRows
            var start = cursor.getAndAdd(chunk)
            while (start < total) {
                body.run(start, min(start + chunk, total))
                start = cursor.getAndAdd(chunk)
            }
        }

        /**
         * 先自旋一小段再挂起。剩余块通常在几微秒内做完，而一次 futex 往返就要
         * 十几微秒——实测空 body 的整轮同步成本 22.8µs，绝大部分是唤醒与汇合。
         * 即使调用线程被 interrupt，也必须先等 worker 离开当前 body 才能安全复用
         * 字段；随后再把首次中断原样回传。
         */
        private fun awaitWorkers(): InterruptedException? {
            if (spinUntilComplete(CALLER_SPIN_NANOS)) return null
            var interruption: InterruptedException? = null
            synchronized(completionMonitor) {
                while (completedWorkers.get() < workerCount) {
                    try {
                        completionMonitor.wait()
                    } catch (error: InterruptedException) {
                        if (interruption == null) interruption = error
                    }
                }
            }
            return interruption
        }

        private fun spinUntilComplete(budgetNanos: Long): Boolean {
            if (completedWorkers.get() >= workerCount) return true
            val deadline = System.nanoTime() + budgetNanos
            var spins = 0
            while (true) {
                if (completedWorkers.get() >= workerCount) return true
                // nanoTime 本身约 25ns，不必每次都读。
                if (++spins and TIME_CHECK_MASK == 0 && System.nanoTime() >= deadline) {
                    return completedWorkers.get() >= workerCount
                }
            }
        }

        private inner class Worker : Runnable {
            override fun run() {
                setDisplayPriorityIfAvailable()
                recordWorkerTid()
                var observedGeneration = 0
                while (true) {
                    // 同一帧内相邻两次派发只隔几十微秒（串行预备段），自旋能整段
                    // 避开挂起/唤醒；跨帧的毫秒级间隔仍然落到 wait()，不空转烧电。
                    if (generation == observedGeneration) {
                        val deadline = System.nanoTime() + WORKER_SPIN_NANOS
                        var spins = 0
                        while (generation == observedGeneration &&
                            (++spins and TIME_CHECK_MASK != 0 || System.nanoTime() < deadline)
                        ) {
                            // 忙等：只读 volatile 代次。
                        }
                    }
                    if (generation == observedGeneration) {
                        synchronized(completionMonitor) {
                            while (generation == observedGeneration) {
                                try {
                                    completionMonitor.wait()
                                } catch (_: InterruptedException) {
                                    // 常驻 daemon 没有关闭协议；外部中断只负责唤醒后重新检查代次。
                                }
                            }
                        }
                    }
                    // run() 持 runLock 且必须等全部 worker 汇合才返回，因此代次
                    // 不可能连跳两次，这里不会漏掉任何一轮。
                    observedGeneration = generation

                    try {
                        drainCurrentRun()
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        if (completedWorkers.incrementAndGet() == workerCount) {
                            synchronized(completionMonitor) {
                                completionMonitor.notifyAll()
                            }
                        }
                    }
                }
            }
        }

        private fun setDisplayPriorityIfAvailable() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            } catch (_: Throwable) {
                // 非 Android 运行时（JVM 单测）没有该服务；保持默认优先级。
            }
        }

        private fun recordWorkerTid() {
            try {
                workerTids.add(android.os.Process.myTid())
            } catch (_: Throwable) {
                // 非 Android 运行时没有内核 tid；ADPF 绑定只在真机上有意义。
            }
        }
    }
}
