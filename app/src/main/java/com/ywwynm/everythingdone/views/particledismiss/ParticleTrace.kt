package com.ywwynm.everythingdone.views.particledismiss

import android.os.Trace
import com.ywwynm.everythingdone.BuildConfig

/**
 * atrace / Perfetto 里的自定义分段（debug 专用，默认关闭）。
 *
 * 第四批 ③ 要回答的问题是「三星弹窗窗口在涟漪期的长帧到底在等谁」：`DRAW` 只有 0.2–0.4 ms，
 * 而 `COMMAND_ISSUE` p90 11.9 ms、`GPU` p90 15.9 ms——像是排在别人后面，而不是自己画得慢。
 * 要把这件事在时间轴上直接看出来，必须让粒子这一侧的 GPU 提交在同一条 trace 上可见。
 *
 * 用 `files/particle-perf-tuning.json` 的 `atrace` 打开，采集：
 * ```
 * adb shell atrace -t 8 -b 40000 gfx view sched freq app
 * ```
 * release 包里每个调用都在第一行返回，且 [ParticlePerfTuning.atrace] 恒为 false。
 */
internal object ParticleTrace {

    val enabled get() = BuildConfig.DEBUG && ParticlePerfTuning.atrace

    inline fun <T> section(name: String, body: () -> T): T {
        if (!enabled) return body()
        Trace.beginSection(name)
        return try { body() } finally { Trace.endSection() }
    }

    fun begin(name: String) {
        if (enabled) Trace.beginSection(name)
    }

    fun end() {
        if (enabled) Trace.endSection()
    }

    fun instant(name: String) {
        if (!enabled) return
        Trace.beginSection(name)
        Trace.endSection()
    }
}
