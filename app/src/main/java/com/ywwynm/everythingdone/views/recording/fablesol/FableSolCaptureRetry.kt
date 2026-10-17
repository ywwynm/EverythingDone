package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 主线程上的捕获补偿：只合并被资源阻塞的更新，不主动产生连续绘制。
 * 返回资源后排一个普通主线程任务；已有遍历先捕获时会取消它，避免再次捕获同一版本。
 */
internal class FableSolCaptureRetry(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val canRetry: () -> Boolean,
    private val captureLatest: () -> Unit
) : AutoCloseable {
    var waiting = false
        private set
    var queued = false
        private set
    private var closed = false
    private val task = Runnable {
        queued = false
        if (!closed && waiting && canRetry()) {
            // 此次尝试仍可能没有空闲缓冲；由 captureLatest 再次 defer，等下一次资源返回。
            waiting = false
            captureLatest()
        }
    }

    fun defer() { if (!closed) waiting = true }

    fun resourceAvailable() {
        if (closed || !waiting || queued || !canRetry()) return
        queued = true
        post(task)
    }

    fun captured() {
        waiting = false
        if (queued) { remove(task); queued = false }
    }

    override fun close() {
        closed = true
        captured()
    }
}
