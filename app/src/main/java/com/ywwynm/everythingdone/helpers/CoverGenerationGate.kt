package com.ywwynm.everythingdone.helpers

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** WorkManager 停止后，原生调用可能尚未返回。同一输出必须等旧调用释放后才能重新生成。 */
internal class CoverGenerationGate {
    private class Entry(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)
    private val entries = HashMap<String, Entry>()

    fun <T> run(key: String, isStopped: () -> Boolean, block: (() -> Unit) -> T): T {
        fun checkActive() {
            if (isStopped() || Thread.currentThread().isInterrupted) throw CancellationException("封面生成已停止")
        }
        checkActive()
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        var acquired = false
        try {
            while (!entry.lock.tryLock(200, TimeUnit.MILLISECONDS)) checkActive()
            acquired = true
            checkActive()
            return block(::checkActive)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("等待封面生成时停止").apply { initCause(e) }
        } finally {
            if (acquired) entry.lock.unlock()
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(key)
            }
        }
    }
}
