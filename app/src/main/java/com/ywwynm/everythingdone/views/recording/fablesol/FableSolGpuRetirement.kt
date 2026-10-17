package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.ArrayDeque

/** 单个 GL 队列内的资源退休。只有 fence 完成或 finish 返回后，才允许生产者重写缓冲。 */
internal class FableSolGpuRetirement<T>(
    private val gpu: Backend,
    private val release: (T) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
    private val maxPending: Int = 3,
    private val maxAgeNs: Long = 500_000_000L
) {
    enum class Status { SIGNALED, PENDING, FAILED }
    interface Backend {
        fun insertFence(): Long
        fun flush()
        /** 必须使用零超时查询，不能在这里等 GPU。 */
        fun poll(fence: Long): Status
        fun deleteFence(fence: Long)
        fun finish()
        fun fallback(reason: String)
        fun lastUseFenceReused() = Unit
    }
    private data class Entry<T>(val value: T, var fence: Long, val sinceNs: Long)
    private val pending = ArrayDeque<Entry<T>>()
    private var currentUseFence = 0L
    var asynchronous = true
        private set
    val pendingCount: Int get() = pending.size

    init { require(maxPending > 0 && maxAgeNs > 0) }

    /** 紧接当前资源的每次采样之后调用，覆盖旧标记；新标记包含同队列全部先前采样。 */
    fun markCurrentUse() {
        val previous = currentUseFence
        currentUseFence = 0L // 新采样后旧标记已不足以保护资源，创建失败也不能继续使用旧值。
        if (previous != 0L) gpu.deleteFence(previous)
        if (asynchronous) currentUseFence = gpu.insertFence()
        // 正常绘制紧接着 swap；若中途停止，retire 仍会显式 flush，关闭仍会 finish。
    }

    fun retire(value: T) {
        // 先接管所有权，避免驱动调用失败后遗漏刚替换的旧帧；销毁路径仍能将其收尾。
        val item = Entry(value, currentUseFence, clockNs())
        currentUseFence = 0L
        pending.addLast(item)
        if (!asynchronous) {
            gpu.finish()
            drainAfterFinish()
            return
        }
        // 尺寸连续变化也不能无限积累旧缓冲；容量回退不禁用下一次 fence。
        if (pending.size > maxPending) {
            drainWithFallback("retirement_capacity", disable = false)
            return
        }
        if (item.fence == 0L) item.fence = gpu.insertFence()
        else gpu.lastUseFenceReused()
        if (item.fence == 0L) {
            drainWithFallback("fence_unavailable")
            return
        }
        // 即使海浪暂停，也必须让最后一次 fence 进入 GPU 队列。
        gpu.flush()
    }

    fun poll() {
        while (pending.isNotEmpty()) {
            val first = pending.peekFirst()
            if (first.fence == 0L) { drainWithFallback("unfenced_retirement"); return }
            when (gpu.poll(first.fence)) {
                Status.SIGNALED -> releaseFirst()
                Status.FAILED -> { drainWithFallback("fence_poll_failed"); return }
                Status.PENDING -> {
                    if (clockNs() - first.sinceNs >= maxAgeNs) drainWithFallback("fence_stalled")
                    return
                }
            }
        }
    }

    private fun drainWithFallback(reason: String, disable: Boolean = true) {
        if (disable) asynchronous = false
        gpu.fallback(reason)
        gpu.finish()
        drainAfterFinish()
    }

    /** 调用者已在旧 EGL 上下文执行 finish；此后才能销毁上下文或导入新的输出。 */
    fun drainAfterFinish() {
        while (pending.isNotEmpty()) releaseFirst()
        if (currentUseFence != 0L) { gpu.deleteFence(currentUseFence); currentUseFence = 0L }
    }

    fun resetForNewContext() {
        check(pending.isEmpty() && currentUseFence == 0L) { "foreground_retirement_not_drained" }
        asynchronous = true
    }

    private fun releaseFirst() {
        val item = pending.removeFirst()
        if (item.fence != 0L) gpu.deleteFence(item.fence)
        release(item.value)
    }
}
