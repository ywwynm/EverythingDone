package com.ywwynm.everythingdone.views.recording.fablesol

/** 仅 Direct 图层使用；不调节渲染节拍或共用计算，调用者只允许无缝刷新率切换。 */
internal class FableSolDirectFrameRate {
    data class Mode(val width: Int, val height: Int, val hz: Float)
    data class Vote(val hz: Float, val minimum: Boolean)
    data class Change(val requested: Vote, val applied: Vote?, val failure: String?, val fallback: Boolean)

    private var owner: Any? = null
    private var requested: Vote? = null
    private var retryAfterMs = 0L
    var applied: Vote? = null
        private set
    var minimumRejected = false
        private set
    var failures = 0
        private set

    /** owner 是当前 Surface；失败不冒充成功，也不在每次 preDraw 中连续调用驱动。 */
    fun update(owner: Any, desiredHz: Float, active: Boolean, sdk: Int, nowMs: Long,
               submit: (Vote) -> Unit): Change? {
        if (this.owner !== owner) {
            reset()
            this.owner = owner
        }
        val hz = if (active) desiredHz else 0f
        val minimum = active && hz > 0f && sdk >= 36 && !minimumRejected
        if (applied?.hz == hz && applied?.minimum == minimum) return null
        if (requested?.hz != hz || requested?.minimum != minimum) {
            requested = Vote(hz, minimum)
            retryAfterMs = 0L
        }
        if (nowMs < retryAfterMs) return null
        val next = checkNotNull(requested)
        var firstFailure: RuntimeException? = null
        try {
            submit(next)
            applied = next
            retryAfterMs = 0L
            return Change(next, next, null, false)
        } catch (error: RuntimeException) {
            failures++
            if (next.minimum && error is IllegalArgumentException) firstFailure = error
            else {
                retryAfterMs = nowMs + RETRY_MS
                return Change(next, applied, describe(error), false)
            }
        }
        // 兼容器可能报告 API 36，却拒绝新 compatibility 值；只对明确拒绝退回 DEFAULT。
        minimumRejected = true
        val fallback = Vote(hz, false)
        requested = fallback
        try {
            submit(fallback)
            applied = fallback
            retryAfterMs = 0L
            return Change(next, fallback, describe(checkNotNull(firstFailure)), true)
        } catch (error: RuntimeException) {
            failures++
            retryAfterMs = nowMs + RETRY_MS
            return Change(next, applied, describe(error), true)
        }
    }

    fun reset() {
        owner = null
        requested = null
        applied = null
        retryAfterMs = 0L
        minimumRejected = false
        failures = 0
    }

    private fun describe(error: RuntimeException) = "${error.javaClass.simpleName}:${error.message}".take(160)

    companion object {
        private const val RETRY_MS = 1000L

        /** 不跟随当前刷新率，避免降到 60 Hz 后反过来请求 60。模式必须保持同分辨率。 */
        fun desiredRate(modes: List<Mode>, width: Int, height: Int): Float {
            var best = 0f
            for (mode in modes) {
                if (mode.width != width || mode.height != height || !mode.hz.isFinite() || mode.hz <= 0f) continue
                // 卓易通实际可用 90 Hz，而枚举仍含面板的 120；优先请求已验证的同尺寸 90 档。
                if (kotlin.math.abs(mode.hz - 90f) <= .5f) return 90f
                best = maxOf(best, mode.hz)
            }
            return if (best > 0f) best.coerceAtMost(120f) else 60f
        }
    }
}
