package com.ywwynm.everythingdone.utils

import java.util.concurrent.Executor

/**
 * 写入在串行 executor 中执行，读取回调在调用方指定的线程执行。
 * 通知排队后仍可能有新写入；必须在实际读取前核对版本，再等待新增写入完成。
 */
internal class SerialWriteQueue(
    private val worker: Executor,
    private val callbacks: Executor
) {
    private var revision = 0L

    @Synchronized
    fun execute(write: () -> Unit) {
        revision++
        worker.execute(write)
    }

    @Synchronized
    fun afterWrites(action: () -> Unit) {
        val observed = revision
        worker.execute {
            callbacks.execute {
                synchronized(this) {
                    if (observed == revision) action()
                    else afterWrites(action)
                }
            }
        }
    }
}
