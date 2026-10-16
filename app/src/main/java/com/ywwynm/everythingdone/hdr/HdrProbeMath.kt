package com.ywwynm.everythingdone.hdr

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** 与 Android Gainmap 的公开公式一致；亮度采用相对 SDR 白色的倍数。 */
internal object HdrProbeMath {
    fun weight(headroom: Float, minimum: Float, full: Float): Float {
        require(headroom.isFinite() && headroom >= 1f)
        require(minimum.isFinite() && full.isFinite() && minimum >= 1f && full >= minimum)
        if (headroom <= minimum) return 0f
        if (full == minimum) return 1f
        return ((ln(headroom) - ln(minimum)) / (ln(full) - ln(minimum))).coerceIn(0f, 1f)
    }

    fun apply(base: Float, gain: Float, min: Float, max: Float, gamma: Float,
              epsilonSdr: Float, epsilonHdr: Float, weight: Float): Float {
        val logGain = ln(min) + (ln(max) - ln(min)) * gain.coerceIn(0f, 1f).pow(gamma)
        return (base + epsilonSdr) * exp(logGain * weight) - epsilonHdr
    }
}
