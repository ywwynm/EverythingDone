package com.ywwynm.everythingdone.views.recording.fablesol

/** 仅诊断：同一 vsync 的多位数字合并，未绘制的形变不能算成已捕获的计时画面。 */
internal data class FableSolClockStamp(val sequence: Long, val burst: Long, val morphNs: Long)

internal class FableSolClockStampTracker {
    private var latest: FableSolClockStamp? = null
    private var lastVsyncNs = Long.MIN_VALUE
    var drawn: FableSolClockStamp? = null
        private set

    fun morph(vsyncNs: Long, nowNs: Long): FableSolClockStamp? {
        val previous = latest
        if (vsyncNs == lastVsyncNs && previous != null && nowNs - previous.morphNs < BURST_GAP_NS) return null
        lastVsyncNs = vsyncNs
        val continuing = previous != null && nowNs - previous.morphNs in 0..BURST_GAP_NS
        return FableSolClockStamp((previous?.sequence ?: 0L) + 1,
            (previous?.burst ?: 0L) + if (continuing) 0 else 1, nowNs).also { latest = it }
    }

    fun draw(): FableSolClockStamp? = latest.also { drawn = it }

    companion object {
        // 数字形变约 300 ms，其间逐帧统计；整秒之间的静止期不算动画掉帧。
        const val BURST_GAP_NS = 500_000_000L
    }
}
