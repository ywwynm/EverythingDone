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

        /**
         * 正在做一件正式渲染开始前必须完成的准备（D138）。
         *
         * [stageId] 是**稳定标识**，不是本地化文案：状态要跨进程边界与通知栏，展示时才按当前
         * locale 取字符串；不认识的标识退回通用的"正在准备"，不显示一个内部代号。
         */
        data class Preparing(
            override val jobId: Long,
            val stageId: String,
            val retryNotice: FableSolExportRetryNotice? = null
        ) : State()
        data class Running(
            override val jobId: Long,
            val done: Int,
            val total: Int,
            val etaMs: Long,
            val retryNotice: FableSolExportRetryNotice? = null
        ) : State()

        /** 当前公开规格失败，等待用户确认建议规格；此状态不占用编码与渲染资源。 */
        data class AwaitingConfirmation(
            override val jobId: Long,
            val failedSpec: FableSolExportPublicSpec,
            val reason: FableSolExportRetryReason,
            val suggestedSpec: FableSolExportPublicSpec,
            val attemptedSpecCount: Int,
            /** 已本地化的补充说明；确认文案在分类行之后追加显示（D115）。 */
            val detail: String? = null
        ) : State() {
            val notice: FableSolExportRetryNotice
                get() = FableSolExportRetryNotice(
                    failedSpec = failedSpec,
                    reason = reason,
                    currentSpec = suggestedSpec,
                    attemptedSpecCount = attemptedSpecCount,
                    sameSpec = false,
                    detail = detail
                )
        }

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
            /** 实际使用的编码器族，例如“HEVC”“AV1”“H.264”。 */
            val codecLabel: String,
            /** 实际使用的编码器实现是否为纯软件。 */
            val softwareCodec: Boolean,
            val frameRate: Int,
            val frames: Int,
            /** 漫反射白（尼特）；0 表示不是 PQ 系，完成态不显示色彩规格那一行。 */
            val pqWhiteNits: Double = 0.0,
            val peakNits: Double = 0.0,
            /** 高光起点百分位；0 表示不是 HDR10+。 */
            val highlightStartPercent: Int = 0,
            /** 高光起点百分位查询所得的膝点亮度（尼特）；0 表示不适用（D115）。 */
            val hdr10PlusRequestedKneeNits: Double = 0.0,
            /** 实际采用的膝点亮度（尼特）；与请求值不同即发生了可行域调整（D115）。 */
            val hdr10PlusKneeNits: Double = 0.0,
            /** FBP 因代理帧缺失写了规范零值（未计算，D109）。 */
            val hdr10PlusFbpUnavailable: Boolean = false,
            /** 仅 HDR10+：携带 ST 2094-40 SEI 的视频样本数（D91 第 4 条修订）。 */
            val hdr10PlusSeiSamples: Long = 0L,
            /** 仅 HDR10+：视频样本总数；部分覆盖时完成信息如实说明。 */
            val hdr10PlusSeiTotal: Long = 0L,
            /** SDR 语义降级的本地化说明（D77、D78）；null 表示没有发生降级。 */
            val sdrFallbackNotice: String? = null,
            /**
             * 正式产物的诊断性解析发现的容器静态元数据冲突摘要（D166）；null 表示一致、
             * 未携带或不是 PQ 系。只进完成信息的补充说明，不改变成功终态。
             */
            val staticMetadataConflict: String? = null,
            /**
             * HDR10+ 的曲线是否全片恒等（D176）；null 表示不是 HDR10+。恒等时动态层在画面上
             * 没有作用，完成信息如实说明"与 HDR10 一致"。
             */
            val hdr10PlusIdentity: Boolean? = null,
            /** 全片静态亮度统计；null 表示不是 PQ 系，完成态不显示这一行（D90）。 */
            val luminance: FableSolExportLuminanceStats? = null,
            /**
             * HLG 系产物**实际**使用的信号范围；null 表示不是 HLG 系（D135、D136、D144）。
             */
            val hlgRange: FableSolExportHlgRange? = null,
            /** 本次实际下发的码控形态与编码工具（D145、D148～D151）。 */
            val encoding: FableSolExportSpecText.Encoding? = null,
            val attemptedSpecCount: Int = 1,
            val retryNotice: FableSolExportRetryNotice? = null
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
        data class Failed(
            override val jobId: Long,
            val message: String,
            val failedSpec: FableSolExportPublicSpec? = null,
            val reason: FableSolExportRetryReason? = null,
            val attemptedSpecCount: Int = 0,
            /**
             * 失败与导出设置相关（规格候选耗尽、无完整规格等），Dialog 应提供
             * "调整导出设置"操作（D107）；空间不足、超时、服务被杀等环境性失败为 false。
             */
            val adjustSettingsActionable: Boolean = false
        ) : State()
    }

    /** 正在做 HLG 扩展信号范围的编码—解码回环验证（D138）。 */
    const val STAGE_HLG_RANGE = "hlg-range"

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

    /**
     * 这个任务号是否由**本进程**铸出。
     *
     * 号是从 1 开始单调递增的，所以"号 ≥ 下一个待发号"等价于"本进程从未铸过它"——只可能
     * 来自被杀之前的那个进程。用来区分 [currentFor] 返回 null 的两种情形：进程重启（任务
     * 与服务都已不存在），还是终态被 registry 限长淘汰（任务确实跑完过）。
     */
    fun isKnownJobId(jobId: Long): Boolean = jobId in 1L until nextJobId.get()

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
                it is FableSolVideoExportBus.State.Preparing ||
                it is FableSolVideoExportBus.State.Running ||
                it is FableSolVideoExportBus.State.AwaitingConfirmation
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
