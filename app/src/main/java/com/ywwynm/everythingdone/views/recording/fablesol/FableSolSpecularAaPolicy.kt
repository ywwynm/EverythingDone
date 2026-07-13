package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 把采样足迹内无法解析的毛细坡度方差并入闪点坡度分布，避免短波在列采样间跳变。
 */
internal object FableSolSpecularAaPolicy {

    fun resolvedAmplitude(wavelengthDp: Double, footprintDp: Double): Double {
        val samplesPerWave = wavelengthDp / max(footprintDp, 1e-6)
        val t = ((samplesPerWave - NYQUIST_SAMPLES) /
            (FULLY_RESOLVED_SAMPLES - NYQUIST_SAMPLES)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** `exp(-(Δs/σ)^2)` 与坡度高斯卷积后，σ² 增加两倍未解析坡度方差。 */
    fun effectiveSigma(baseSigma: Double, unresolvedSlopeVariance: Double): Double =
        sqrt(baseSigma * baseSigma + 2.0 * unresolvedSlopeVariance.coerceAtLeast(0.0))

    /** 保持一维坡度分布积分能量，展宽时同步降低峰值而非凭空增加总高光。 */
    fun peakNormalization(baseSigma: Double, effectiveSigma: Double): Double =
        (baseSigma / max(effectiveSigma, 1e-6)).coerceIn(0.0, 1.0)

    private const val NYQUIST_SAMPLES = 2.0
    private const val FULLY_RESOLVED_SAMPLES = 4.0
}
