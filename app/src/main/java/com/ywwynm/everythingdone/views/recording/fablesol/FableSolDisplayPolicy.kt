package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.taptransfer.transport.wifi.TapGroupRolePolicy

/** 屏幕输出策略；不参与离线导出，也不把缺失的显示比例当作测得的 1 倍。 */
internal object FableSolDisplayPolicy {
    fun usesPqCompatibility(api: Int, manufacturer: String, buildDisplay: String): Boolean =
        api >= 34 && TapGroupRolePolicy.isKnownCompatibilityEnvironment(manufacturer, buildDisplay)

    fun canOfferHdr(api: Int, compatibility: Boolean, reportsHdr: Boolean, reportsRatio: Boolean): Boolean =
        api >= 34 && (compatibility || (reportsHdr && reportsRatio))

    /** 关闭档必须退出 HDR 色彩空间；降低内容增益不能代替 SDR 表面。 */
    fun needsHdrSurface(api: Int, compatibility: Boolean, pqUnavailable: Boolean,
                         screenHdr: Boolean, strength: Float): Boolean = when {
        api < 34 || strength <= FableSolHdrPolicy.STRENGTH_OFF -> false
        compatibility -> !pqUnavailable
        else -> screenHdr
    }

    fun isPqFailure(attemptedPq: Boolean, active: Boolean): Boolean = attemptedPq && !active

    /** PQ 与 SDR 共用 RGBA8，但会话颜色空间不同，不能只比较 PixelFormat。 */
    fun requestedOutput(api: Int, compatibility: Boolean, pqUnavailable: Boolean,
                        screenHdr: Boolean, strength: Float): FableSolScreenOutput = when {
        !needsHdrSurface(api, compatibility, pqUnavailable, screenHdr, strength) -> FableSolScreenOutput.SDR
        compatibility -> FableSolScreenOutput.PQ
        else -> FableSolScreenOutput.SCRGB
    }

    /** NaN 表示没有报告；兼容路径的上限只是应用内容预算，不是面板峰值测量。 */
    fun contentHeadroom(pq: Boolean, reportedRatio: Float): Float = when {
        reportedRatio.isFinite() && reportedRatio >= 1f -> reportedRatio
        pq -> FableSolHdrPolicy.MAX_STRENGTH
        else -> 1f
    }
}

internal enum class FableSolScreenOutput { SDR, SCRGB, PQ }

/** 仅 PQ 屏幕输出平滑强度，避免跨过关闭档时瞬间收掉高光；快速反向从当前值继续。 */
internal class FableSolPqStrengthTransition {
    var value = FableSolHdrPolicy.STRENGTH_OFF
        private set
    private var start = value
    private var target = value
    private var elapsed = DURATION_SECONDS

    fun update(requested: Float, deltaSeconds: Float): Float {
        val next = if (requested.isFinite()) requested.coerceIn(1f, FableSolHdrPolicy.MAX_STRENGTH) else 1f
        if (next != target) {
            start = value
            target = next
            elapsed = 0f
        }
        elapsed = (elapsed + deltaSeconds.coerceAtLeast(0f)).coerceAtMost(DURATION_SECONDS)
        val t = elapsed / DURATION_SECONDS
        value = start + (target - start) * (t * t * (3f - 2f * t))
        return value
    }

    fun reset() {
        value = 1f
        start = 1f
        target = 1f
        elapsed = DURATION_SECONDS
    }

    private companion object { const val DURATION_SECONDS = 0.18f }
}
