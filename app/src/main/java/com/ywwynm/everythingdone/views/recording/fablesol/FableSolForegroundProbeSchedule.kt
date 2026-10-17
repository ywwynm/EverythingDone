package com.ywwynm.everythingdone.views.recording.fablesol

/** 每次图层稳定可见后采样一次；不依赖兼容环境可能无效的呈现 fence。 */
internal class FableSolForegroundProbeSchedule {
    private var target: Long? = null
    private var visibleSince: Long? = null
    private var sampled = false

    fun shouldSample(target: Long?, visible: Boolean, nowMs: Long): Boolean {
        if (target != this.target || !visible || target == null) {
            reset()
            this.target = target
        }
        if (!visible || target == null || sampled) return false
        val since = visibleSince ?: nowMs.also { visibleSince = it }
        if (nowMs - since < 600L) return false
        sampled = true
        return true
    }

    fun reset() {
        target = null
        visibleSince = null
        sampled = false
    }
}
