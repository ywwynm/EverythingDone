package com.ywwynm.everythingdone.views.particledismiss

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
 * 120 Hz 面板上仍每隔一拍提交一次——[presentationTarget] 把目标锚在「起点 + k × 16.67 ms」上，
 * 一次迟到不会把后续相位吸附到迟到的那一拍（`frameTimeNanos` 迟到超过一个 vsync 周期会被
 * 框架重新吸附，不能用差值判断隔了几拍）。
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

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // 先注册，不能等 GPU 提交之后再申请下一次垂直同步。
            Choreographer.getInstance().postFrameCallback(this)
            if (nextTarget == 0L) nextTarget = frameTimeNanos
            if (frameTimeNanos + TOLERANCE_NANOS < nextTarget) return
            val target = presentationTarget(frameTimeNanos, nextTarget, INTERVAL_NANOS)
            nextTarget = target + INTERVAL_NANOS
            listener?.invoke(target)
        }
    }

    fun setListener(action: (Long) -> Unit) { listener = action }

    /** 幂等；[stop] 之后再 [start] 会重新对相位。 */
    fun start() {
        if (running) return
        running = true
        handler.post {
            if (!running) return@post
            nextTarget = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.post { Choreographer.getInstance().removeFrameCallback(callback) }
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
}
