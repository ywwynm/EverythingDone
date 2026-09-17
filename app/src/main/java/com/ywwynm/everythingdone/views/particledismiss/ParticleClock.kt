package com.ywwynm.everythingdone.views.particledismiss

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer

/**
 * 进程内唯一的粒子节拍。
 *
 * 结构沿用第一批的结论（见 batch1-results 第 5 节）：**节拍与绘制必须分属两条线程**。
 * 把 Choreographer 挂到 GL 线程自己的 Looper 上时，绘制期间那条 Looper 收不到 vsync，
 * 「比一拍稍慢」会被放大成整拍丢帧（SM-S9180 连开关：20 层里 8–9 层间隔 P90 超 20 ms，
 * 改动前是 3 / 2 / 3）。所以这里仍是一条专用的节拍线程，只做「收 vsync、算目标呈现时刻、
 * 转发给 GL 线程」，GL 线程画完接上最新一拍。
 *
 * 第二批的变化：全进程只有一条 GL 线程、一个订阅者，不再每个动画层起一条。
 *
 * 第三批（[ParticlePerfTuning.beatPhaseLock]）改掉了目标时刻的算法。此前是「起点 +
 * k × 16 666 666 ns」的自由计时：魅族 20 Infinity 触摸期面板跑 120.8 Hz、一个 vsync 只有
 * **8.28 ms**，两个 vsync 是 **16.56 ms**，而我们每拍加的是 16.67 ms——每拍多出 0.107 ms，
 * 目标相对真实 vsync 持续漂移，几十拍之后就会多等一个 vsync（一拍 24.8 ms），随后目标又
 * 停在两个 vsync 之间，帧交替早到 / 晚到。现在改成**按 vsync 计数**：每 [outputStride] 个
 * vsync 出一拍，目标直接取系统给的**预期呈现时刻**（API 33+ 的 `FrameData`，拿不到就退回
 * vsync 时刻），因此永远落在真实 vsync 上、不积累误差。
 */
internal object ParticleClock {

    /** 60 Hz 的节拍间隔；高刷屏采样到 60 Hz，避免额外提交重复的缓存帧。 */
    const val INTERVAL_NANOS = 16_666_666L

    /** 判定「这一拍属于当前目标」的容差：小于半个 120 Hz 周期，够吸收 vsync 抖动。 */
    const val TOLERANCE_NANOS = 4_000_000L

    private val thread: HandlerThread by lazy {
        HandlerThread("ParticleClock", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val handler: Handler by lazy { Handler(thread.looper) }

    @Volatile private var running = false
    private var nextTarget = 0L

    /** 收到一拍时的回调，在节拍线程上执行；参数是本拍的目标呈现时刻。 */
    @Volatile private var listener: ((Long) -> Unit)? = null

    // ---- 相位锁定用的状态，只在节拍线程访问 ----
    private var lastVsync = 0L
    private var vsyncPeriod = 0L
    private var lastBeatVsync = 0L
    private var beatPeriod = 0L

    /** 节拍线程收到的 vsync 间隔（纳秒）；还没量到时为 0。观察器读它判定面板在跑几赫兹。 */
    @Volatile var measuredVsyncNanos = 0L
        private set

    /**
     * 两拍之间的**实际**间隔，取节拍间隔自己的滑动均值；还没量到时退回 [INTERVAL_NANOS]。
     *
     * 不按「vsync 周期 × 分频数」推算。第一版就是那么写的，在魅族上翻了车：这条线程收到的
     * vsync 间隔**并不稳定**（同一次左滑里 8.28 与 16.55 交替出现，`tickGap` 直方图 101 : 83），
     * 推算出来的周期在 8.3 与 16.6 之间摆，于是预算截止时间（[ParticleGl.workDeadline]）
     * 一会儿 10 ms 一会儿 2 ms，预读被饿死——实测左滑「被卡住的帧」占比从 3.9% 涨到 12.7%。
     * 量「两拍之间实际隔了多久」没有这个问题：出拍由 [phaseLockedTarget] 的门限决定，
     * 本来就稳定在 60 Hz 附近。
     */
    val outputPeriodNanos: Long
        get() {
            val period = beatPeriod
            return if (period <= 0L) INTERVAL_NANOS else period
        }

    /**
     * 收到一次 vsync。[expected] 是系统给的预期呈现时刻（拿不到时等于 [frameTimeNanos]），
     * [deadline] 是「要赶上这一次呈现，缓冲最晚什么时候入队」（拿不到为 0）。
     */
    private fun onVsync(frameTimeNanos: Long, expected: Long, deadline: Long) {
        if (!running) return
        val delta = if (lastVsync == 0L) 0L else frameTimeNanos - lastVsync
        lastVsync = frameTimeNanos
        // 只认「像一个 vsync」的间隔：漏收的那几拍（线程被挂起、动画刚起来）不能污染周期。
        if (delta in MIN_VSYNC_NANOS..MAX_VSYNC_NANOS) {
            vsyncPeriod = if (vsyncPeriod == 0L) delta else (vsyncPeriod * 3 + delta) / 4
            measuredVsyncNanos = vsyncPeriod
        }
        val target = if (ParticlePerfTuning.beatPhaseLock) phaseLockedTarget(frameTimeNanos)
        else freeRunningTarget(frameTimeNanos)
        if (ParticleBeatTrace.enabled) ParticleBeatTrace.record(
            ParticleBeatTrace.TICK, frameTimeNanos, System.nanoTime(), target, expected, deadline, delta)
        if (target != 0L) listener?.invoke(target)
    }

    /**
     * 相位锁定：**目标就是本次 vsync 的时刻**，出不出拍看「距上一拍够不够久」。
     *
     * 两点都要紧。目标锚在真实 vsync 上，所以不会像自由计时那样每拍多出 0.117 ms
     * （魅族的两个 vsync 是 16.55 ms，不是 16.67 ms），几十拍之后目标就漂到 vsync 之间、
     * 帧交替早到晚到；门限按「上一拍到现在」判而不是按 vsync 计数，所以 vsync 投递本身
     * 不规律时也不会跟着乱（这条线程实测会把 8.28 与 16.55 交替投进来）。
     *
     * 目标不取系统给的「预期呈现时刻」：魅族上实测那个值是 vsync + **26.0 ms**（截止时刻
     * vsync + 13.7 ms），而我们实际达到的呈现时刻是 vsync + 17.7 ms——按它写目标等于主动
     * 多等一个显示周期。目标只用来定相位与算进度，真正写进 `eglPresentationTimeANDROID`
     * 的下限在 [ParticleWindowSurface] 里按 [outputPeriodNanos] 算。
     */
    private fun phaseLockedTarget(frameTimeNanos: Long): Long {
        if (lastBeatVsync == 0L) { lastBeatVsync = frameTimeNanos; return frameTimeNanos }
        val elapsed = frameTimeNanos - lastBeatVsync
        if (elapsed < beatThreshold(vsyncPeriod)) return 0L
        if (elapsed in MIN_BEAT_NANOS..MAX_BEAT_NANOS) {
            beatPeriod = if (beatPeriod == 0L) elapsed else (beatPeriod * 3 + elapsed) / 4
        }
        lastBeatVsync = frameTimeNanos
        return frameTimeNanos
    }

    /**
     * 「距上一拍多久才出下一拍」的门限。
     *
     * 取 [INTERVAL_NANOS] 减去半个 vsync 再减一点余量，语义是「离 60 Hz 最近、且不低于它的
     * 那一次 vsync」：120 Hz（8.28 ms）门限 12.0 ms，第一次 vsync 不到、第二次 16.55 ms 到，
     * 即 60 Hz；144 Hz（6.94 ms）门限 12.7 ms，第二次 13.89 ms 到，即 72 Hz；90 Hz
     * （11.11 ms）门限 10.6 ms，每次都到，即 90 Hz；60 Hz 每次都到。**不能只减半个 vsync**：
     * 90 Hz 时 11.11 恰好压在门限上，浮点与抖动会让它一半时候掉到 45 Hz。
     */
    internal fun beatThreshold(vsyncNanos: Long): Long {
        if (vsyncNanos <= 0L) return 0L
        return (INTERVAL_NANOS - vsyncNanos / 2 - THRESHOLD_SLACK_NANOS).coerceAtLeast(0L)
    }

    /** 第二批之前的自由计时；留作对照（[ParticlePerfTuning.beatPhaseLock] 关掉时走这条）。 */
    private fun freeRunningTarget(frameTimeNanos: Long): Long {
        if (nextTarget == 0L) nextTarget = frameTimeNanos
        if (frameTimeNanos + TOLERANCE_NANOS < nextTarget) return 0L
        val target = presentationTarget(frameTimeNanos, nextTarget, INTERVAL_NANOS)
        nextTarget = target + INTERVAL_NANOS
        return target
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // 先注册，不能等 GPU 提交之后再申请下一次垂直同步。
            Choreographer.getInstance().postFrameCallback(this)
            onVsync(frameTimeNanos, frameTimeNanos, 0L)
        }
    }

    /**
     * API 33+ 的 vsync 回调：除了 vsync 时刻，还给出各条帧时间线的**预期呈现时刻**与
     * **入队截止时刻**。相位锁定要的就是前者——它是 SurfaceFlinger 自己认的呈现时刻，
     * 比我们按固定间隔推出来的值准。
     */
    private val vsyncCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        object : Choreographer.VsyncCallback {
            override fun onVsync(data: Choreographer.FrameData) {
                if (!running) return
                Choreographer.getInstance().postVsyncCallback(this)
                val timeline = runCatching { data.preferredFrameTimeline }.getOrNull()
                this@ParticleClock.onVsync(data.frameTimeNanos,
                    timeline?.expectedPresentationTimeNanos ?: data.frameTimeNanos,
                    timeline?.deadlineNanos ?: 0L)
            }
        } else null

    fun setListener(action: (Long) -> Unit) { listener = action }

    /** 幂等；[stop] 之后再 [start] 会重新对相位。 */
    fun start() {
        if (running) return
        running = true
        handler.post {
            if (!running) return@post
            nextTarget = 0L
            lastVsync = 0L
            lastBeatVsync = 0L
            val vsync = vsyncCallback
            if (vsync != null) Choreographer.getInstance().postVsyncCallback(vsync)
            else Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.post {
            val vsync = vsyncCallback
            if (vsync != null) Choreographer.getInstance().removeVsyncCallback(vsync)
            else Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    /**
     * 本次 vsync 对应的目标呈现时刻：从 [nextTarget] 起按 [interval] 前进到不晚于本拍的那一个。
     * 目标始终锚在「起点 + k × interval」上。
     */
    internal fun presentationTarget(frameTimeNanos: Long, nextTarget: Long, interval: Long): Long {
        var target = nextTarget
        while (target + interval <= frameTimeNanos + TOLERANCE_NANOS) target += interval
        return target
    }

    /** 门限相对「半个 vsync」再让出来的余量；见 [beatThreshold] 里 90 Hz 那条。 */
    private const val THRESHOLD_SLACK_NANOS = 500_000L

    /** 合理的 vsync 周期区间：240 Hz 到 24 Hz。 */
    private const val MIN_VSYNC_NANOS = 3_500_000L
    private const val MAX_VSYNC_NANOS = 42_000_000L

    /** 合理的节拍间隔区间：只在这个区间内的间隔才计进 [outputPeriodNanos]。 */
    private const val MIN_BEAT_NANOS = 6_000_000L
    private const val MAX_BEAT_NANOS = 40_000_000L
}
