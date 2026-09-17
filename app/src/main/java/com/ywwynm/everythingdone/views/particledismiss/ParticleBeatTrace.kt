package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.BuildConfig

/**
 * 节拍级取证：把「一拍从 vsync 到上屏」这条链上的每一步都记下来。
 *
 * 第二批之后左滑仍不跟手，而滞后曲线（`ParticleSession.gestureTimings`）已经证明**画面画的
 * 就是手指要的相位**，剩下的问题只能出在「什么时候出这一帧、这一帧什么时候上屏」。既有产物
 * 只有「真的提交了的那些帧」，看不到**没提交的那些拍**：节拍到底跳没跳、pump 有没有赶上、
 * `wantsFrame` 为什么判否、栅栏等了多久、SurfaceFlinger 有没有把这一张丢掉。这份取证把这
 * 五件事都补上。
 *
 * release 包里所有记录点都被 [BuildConfig.DEBUG] 短路；debug 包里也要 [enabled] 为真才记，
 * 由观察器在 `begin` 时按配置打开、`end` 时关掉，平时零开销。
 *
 * 行的第一位是类别，其余按类别解释（单位一律纳秒，时间基准是 `System.nanoTime`）：
 *
 * | 类别 | 含义 | 其余各位 |
 * | --- | --- | --- |
 * | [TICK] | 节拍线程收到一次 vsync | 1 vsync 时刻, 2 回调实际执行时刻, 3 本拍目标（0 = 这次不出拍）, 4 预期呈现时刻, 5 截止时刻, 6 上一次 vsync 到这次的间隔 |
 * | [PUMP] | GL 线程跑一次 pump | 1 进入, 2 beatTarget, 3 consumedTarget, 4 是否出拍, 5 准备循环用时, 6 退出 |
 * | [COMPOSITE] | 一次合成 | 1 目标呈现时刻, 2 进入, 3 等栅栏用时, 4 画完, 5 swap 返回, 6 帧号, 7 画了几层 |
 * | [PRESENT] | 一帧的呈现判定 | 1 目标呈现时刻, 2 帧号, 3 驱动报的呈现时刻（负数是 PENDING/INVALID/FAILED）, 4 判定时刻, 5 从提交到现在 |
 * | [SKIP] | 出拍了但这一层没画 | 1 目标呈现时刻, 2 阶段序号, 3 原因码, 4 手指要的相位（位）, 5 这一拍能画的相位（位）, 6 上一拍画出的相位（位）, 7 可画档位 |
 */
internal object ParticleBeatTrace {

    const val TICK = 0L
    const val PUMP = 1L
    const val COMPOSITE = 2L
    const val PRESENT = 3L
    const val SKIP = 4L

    /** [SKIP] 的原因码。 */
    const val SKIP_CANCELLED = 0L
    const val SKIP_INVISIBLE = 1L
    const val SKIP_STAGE = 2L
    const val SKIP_NO_RENDERER = 3L
    const val SKIP_SAME_POSITION = 4L

    /** 一轮取证最多留多少行；超过就丢最早的（左滑一格约 1500 行）。 */
    private const val CAPACITY = 12_000

    @Volatile var enabled = false
        private set

    private val rows = ArrayDeque<LongArray>(1024)

    fun start() {
        if (!BuildConfig.DEBUG) return
        synchronized(rows) { rows.clear() }
        enabled = true
    }

    fun stop() { enabled = false }

    fun record(vararg values: Long) {
        if (!BuildConfig.DEBUG || !enabled) return
        synchronized(rows) {
            rows.addLast(values)
            while (rows.size > CAPACITY) rows.removeFirst()
        }
    }

    fun snapshot(): List<LongArray> = synchronized(rows) { rows.toList() }
}
