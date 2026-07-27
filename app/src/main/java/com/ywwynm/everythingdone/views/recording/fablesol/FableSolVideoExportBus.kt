package com.ywwynm.everythingdone.views.recording.fablesol

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 导出状态的进程内广播。
 *
 * 导出**始终**跑在前台服务里，通知栏也始终有对应通知；进度对话框只是同一份状态的另一个
 * 观察者。因此对话框关掉不影响导出，导出完成时对话框若还开着就换成完成态。
 *
 * **每个状态都带 jobId**：同时只跑一个导出、第二个排队，但两个对话框会同时在听同一条总线。
 * 没有 jobId 的话，第二个任务的对话框会显示第一个任务的进度，第一个完成时还会给出它的
 * 分享/添加附件按钮——用户以为在操作第二个产物，实际动的是第一个。
 */
internal object FableSolVideoExportBus {

    sealed class State {
        abstract val jobId: Long

        /** 已提交、还没轮到它跑。 */
        data class Queued(override val jobId: Long) : State()
        data class Running(
            override val jobId: Long,
            val done: Int,
            val total: Int,
            val etaMs: Long
        ) : State()

        data class Done(
            override val jobId: Long,
            val uri: Uri?,
            val localPath: String?,
            val fileSizeBytes: Long,
            val displayLocation: String,
            val tierLabel: String,
            val hdr: Boolean,
            /** 当前 locale 的用户可见格式名，例如“HDR10+”“杜比视界 8.4”“SDR”。 */
            val formatLabel: String,
            val frameRate: Int,
            val frames: Int,
            /** 漫反射白（尼特）；0 表示不是 PQ 系，完成态不显示色彩规格那一行。 */
            val pqWhiteNits: Double = 0.0,
            val peakNits: Double = 0.0,
            /** 高光起点百分位；0 表示不是 HDR10+。 */
            val highlightStartPercent: Int = 0
        ) : State() {

            /**
             * 产物的**实际**平均码率（bps）；算不出来时为 0。
             *
             * 恒定质量档下 `KEY_BIT_RATE` 只是提示，事前给不出数字；但产物落盘之后，
             * 用文件大小除以时长就是真实码率——这才是用户想知道的那个数。
             */
            val bitrateBps: Long
                get() {
                    if (fileSizeBytes <= 0L || frames <= 0 || frameRate <= 0) return 0L
                    val seconds = frames.toDouble() / frameRate
                    if (seconds <= 0.0) return 0L
                    return (fileSizeBytes * 8.0 / seconds).toLong()
                }
        }

        data class Cancelled(override val jobId: Long) : State()
        data class Failed(override val jobId: Long, val message: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val nextJobId = AtomicLong(1L)
    private val registry = FableSolExportStateRegistry()

    /**
     * 铸出任务号时同步登记排队态。这样服务进程还没来得及处理 START、对话框又恰好发生旋转时，
     * 新实例仍能恢复这个任务，而不是依赖一条稍后才到的全局“最近消息”。
     */
    fun newJobId(): Long {
        val id = nextJobId.getAndIncrement()
        registry.initialize(id)
        return id
    }

    fun post(state: State) {
        if (!registry.accept(state)) return
        main.post dispatch@{
            // 丢弃排队期间已被更新状态取代的旧回调，避免 Running 在 Failed 后才送达。
            if (currentFor(state.jobId) != state) return@dispatch
            for (listener in listeners) listener(state)
        }
    }

    fun currentFor(jobId: Long): State? = registry.currentFor(jobId)

    fun hasActiveJobs(): Boolean = registry.hasActiveJobs()

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

}

/** 不依赖 Android Looper 的任务状态存储，供 JVM 回归测试直接验证隔离与终态不可覆盖。 */
internal class FableSolExportStateRegistry(
    private val maxRetainedStates: Int = 64
) {
    private val states = LinkedHashMap<Long, FableSolVideoExportBus.State>()

    @Synchronized
    fun initialize(jobId: Long) {
        states[jobId] = FableSolVideoExportBus.State.Queued(jobId)
        trimTerminalStates()
    }

    @Synchronized
    fun accept(state: FableSolVideoExportBus.State): Boolean {
        if (states[state.jobId]?.isTerminal() == true) return false
        states[state.jobId] = state
        trimTerminalStates()
        return true
    }

    @Synchronized
    fun currentFor(jobId: Long): FableSolVideoExportBus.State? = states[jobId]

    @Synchronized
    fun hasActiveJobs(): Boolean =
        states.values.any {
            it is FableSolVideoExportBus.State.Queued ||
                it is FableSolVideoExportBus.State.Running
        }

    private fun FableSolVideoExportBus.State.isTerminal(): Boolean =
        this is FableSolVideoExportBus.State.Done ||
            this is FableSolVideoExportBus.State.Cancelled ||
            this is FableSolVideoExportBus.State.Failed

    /** 只淘汰旧终态，绝不为了限长丢掉仍在排队或执行中的任务。 */
    private fun trimTerminalStates() {
        if (states.size <= maxRetainedStates) return
        val iterator = states.entries.iterator()
        while (states.size > maxRetainedStates && iterator.hasNext()) {
            if (iterator.next().value.isTerminal()) iterator.remove()
        }
    }
}
