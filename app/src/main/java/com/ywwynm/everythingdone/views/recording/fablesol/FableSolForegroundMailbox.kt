package com.ywwynm.everythingdone.views.recording.fablesol

/** 主线程到 GL 的最新帧交接；未交给 GPU 的过期帧可以立即归还，正在采样的帧不在此处。 */
internal class FableSolForegroundMailbox<T : Any>(private val discard: (T) -> Unit) {
    private var accepting = false
    private var pending: T? = null

    @Synchronized fun open() { check(pending == null); accepting = true }

    fun offer(value: T): Boolean {
        val previous = synchronized(this) {
            if (!accepting) return false // 拒收时所有权仍属于调用者。
            pending.also { pending = value }
        }
        previous?.let(discard)
        return true
    }

    @Synchronized fun take(): T? = pending.also { pending = null }

    fun close() {
        val previous = synchronized(this) {
            accepting = false
            pending.also { pending = null }
        }
        previous?.let(discard)
    }
}
