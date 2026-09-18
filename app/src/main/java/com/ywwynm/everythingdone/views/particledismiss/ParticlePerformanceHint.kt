package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import com.ywwynm.everythingdone.App
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process

/**
 * L3（逐帧成本专项第二阶段）：把常驻 GL 线程与材料工作线程的工作量报给调速器。
 *
 * `Process.setThreadPriority` 只改 nice 值，既不改核心放置也不改频率；ADPF 的
 * `PerformanceHintManager`（API 31+）是唯一的应用侧旋钮。两类负载形态不同，各用一个会话：
 *
 * - **周期负载**：常驻 GL 线程每拍报一次真实耗时，目标一拍（16.6 ms）。
 * - **突发负载**：左滑按下时的建材。它发生在识别之前，而 ADPF 的会话是为周期负载设计的，
 *   所以这里在**按下那一刻**就建会话并报一个明显大于目标的值，让调速器在识别发生前升频。
 *
 * **部分 ROM 是空实现**（设了没效果，不会更差）；`create` 返回 null 时整段是空操作。
 * 会话按**线程**建：`createHintSession` 的 tid 数组要求是同一个进程里真实存在的线程，
 * 所以只能在那条线程上自己建。
 */
internal object ParticlePerformanceHint {

    private const val FRAME_TARGET_NANOS = 16_600_000L

    /** 建材那一次报给调速器的「实际耗时」；比目标大即为升频请求。 */
    private const val BURST_NANOS = 30_000_000L

    @Volatile private var manager: PerformanceHintManager? = null
    @Volatile private var managerTried = false

    private val frameSession = ThreadLocal<Any?>()
    private val burstSession = ThreadLocal<Any?>()

    /** 进程级句柄；取不到（ROM 没实现）就永远是 null。 */
    private fun manager(context: Context): PerformanceHintManager? {
        if (managerTried) return manager
        managerTried = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        manager = runCatching {
            context.applicationContext.getSystemService(PerformanceHintManager::class.java)
        }.getOrNull()
        return manager
    }

    private fun session(context: Context, holder: ThreadLocal<Any?>, target: Long): PerformanceHintManager.Session? {
        if (!ParticlePerfTuning.performanceHint) return null
        holder.get()?.let { return it as? PerformanceHintManager.Session }
        val created = runCatching {
            manager(context)?.createHintSession(intArrayOf(Process.myTid()), target)
        }.getOrNull()
        // 取不到也要记住，别每帧再试一次；用 NONE 占位区分「没试过」与「试过没有」。
        holder.set(created ?: NONE)
        return created
    }

    private val NONE = Any()

    /** 常驻 GL 线程：每拍报一次本拍的真实耗时。 */
    fun frame(context: Context, elapsedNanos: Long) {
        if (elapsedNanos <= 0L) return
        val active = session(context, frameSession, FRAME_TARGET_NANOS) ?: return
        runCatching { active.reportActualWorkDuration(elapsedNanos) }
    }

    /**
     * 材料线程：按下那一刻报一个大于目标的值，让调速器在识别发生前就升上去。
     *
     * 建材那条线程手上只有 `AssetManager`，所以 Context 走进程单例；取不到就整段跳过。
     */
    fun burst() {
        val context = App.getApp() ?: return
        val active = session(context, burstSession, FRAME_TARGET_NANOS) ?: return
        runCatching { active.reportActualWorkDuration(BURST_NANOS) }
    }

    /** 本线程用完就关；线程本身是常驻的，会话不关会一直挂在调速器上。 */
    fun release() {
        for (holder in listOf(frameSession, burstSession)) {
            (holder.get() as? PerformanceHintManager.Session)?.let { runCatching { it.close() } }
            holder.remove()
        }
    }
}
