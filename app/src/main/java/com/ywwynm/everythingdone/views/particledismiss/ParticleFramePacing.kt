package com.ywwynm.everythingdone.views.particledismiss

import android.util.Log
import com.ywwynm.everythingdone.BuildConfig

/**
 * 仅调试包：「流体」与「飘散」两种会话共用的出帧统计，口径相同，供两种样式对比起播延迟与帧率
 * （`docs/features/harmony-particle-android-port/device/perf_compare.py` 按 [TAG] 读取）。
 *
 * 每帧记**真实上屏时刻**：驱动报了呈现时刻就用它，否则用确认上屏的回调时刻。一轮收尾时打一行：
 * 请求到第一帧上屏的毫秒数、上屏帧数、按 60 Hz 应有的帧数、相邻两帧间隔的 p90 与最大值。
 * 请求时刻是 [ParticleDismissSpec.requestedAtNanos]：弹窗关闭在触摸分发里同步发起；左滑是截图完成后
 * 建立动画规格的时刻，新建页是目标页请求出现的时刻，后两者不能直接当作完整交互延迟。
 */
internal class ParticleFramePacing(private val style: String) {

    private val presented = ArrayList<Long>(256)
    private var logged = false

    /** 驱动没报呈现时刻、只能按确认回调的时刻记的帧数；最后一帧是不是这种。 */
    private var missing = 0
    private var lastMissing = false

    fun onPresented(displayedAt: Long?) {
        if (!BuildConfig.DEBUG) return
        synchronized(presented) {
            if (presented.size >= 1024) return
            presented += displayedAt ?: System.nanoTime()
            if (displayedAt == null) missing++
            lastMissing = displayedAt == null
        }
    }

    /** 一轮收尾时调用一次（重复调用只打第一次）。[kind]：消散 / 出现 / 跟手。 */
    fun log(kind: String, requestedAtNanos: Long, particles: Int) {
        if (!BuildConfig.DEBUG || logged) return
        val stamps = synchronized(presented) { presented.toLongArray() }
        if (stamps.size < 2) return
        logged = true
        stamps.sort()
        val raw = DoubleArray(stamps.size - 1) { (stamps[it + 1] - stamps[it]) / 1e6 }
        val worstAt = raw.indices.maxByOrNull { raw[it] } ?: 0
        val gaps = raw.sorted()
        val span = stamps.last() - stamps.first()
        // 应有帧数按这一轮实际的节拍间隔算（高帧率时约 8.3 ms），读不到退回 60 Hz
        val beat = ParticleClock.outputPeriodNanos.takeIf { it > 0L } ?: BEAT_NANOS
        val expected = ((span + beat / 2) / beat).toInt() + 1
        Log.i(TAG, "出帧对比 样式=$style 类型=$kind 首帧Ms=${"%.1f".format((stamps.first() - requestedAtNanos) / 1e6)} " +
            "上屏=${stamps.size} 应有=$expected 用时Ms=${span / 1_000_000} " +
            "p90间隔Ms=${"%.1f".format(gaps[(gaps.size * .9).toInt().coerceAtMost(gaps.lastIndex)])} " +
            "最大间隔Ms=${"%.1f".format(gaps.last())} 最大间隔在=${worstAt + 1}/${raw.size} 颗粒=$particles " +
            "节拍Ms=${"%.1f".format(beat / 1e6)} " +
            "无呈现时刻=$missing 末帧无呈现时刻=$lastMissing")
    }

    companion object {
        const val TAG = "ParticlePacing"
        private const val BEAT_NANOS = 1_000_000_000L / 60
    }
}
