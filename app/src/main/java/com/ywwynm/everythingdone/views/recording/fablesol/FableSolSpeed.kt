package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.pow

/**
 * 感知速度相关函数（对应 speed.py 的实时标量路径）。flow01 是绝对的、缓变的感知速度估计，
 * 不做相对分位排名（稳定的快段仍是快）。离线向量版本（local_tempo_curve 等）不移植。
 */
object FableSolSpeed {

    const val RATE_WINDOW_S = 3.0
    private const val TEMPO_MIN_BPM = 55.0
    private const val TEMPO_MAX_BPM = 175.0
    private const val TEMPO_MAX_WEIGHT = 0.32
    private const val PERCEPTUAL_CONTRAST = 0.50
    private const val SPEED_ATTACK_S = 0.65
    private const val SPEED_RELEASE_S = 1.10
    const val TEMPO_EVIDENCE_TAU_S = 7.2
    private const val ONSET_SALIENCE_EXPONENT = 1.7
    private const val ONSET_WEIGHT_FLOOR = 0.28

    private val SCALE_X = doubleArrayOf(0.0, 0.074, 0.316, 0.365, 0.503, 0.639, 0.739, 0.792, 1.0)
    private val SCALE_Y = doubleArrayOf(0.0, 0.076, 0.320, 0.360, 0.499, 0.639, 0.749, 0.841, 1.0)

    private fun smoothstep01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** 感知加权 onset 率 → 绝对 0..1 表面速度。 */
    fun onsetDensity01(rateHz: Double): Double {
        val rate = if (rateHz > 0.0) rateHz else 0.0
        return (1.0 - exp(-(rate / 5.0).pow(1.18))).coerceIn(0.0, 1.0)
    }

    fun onsetSalienceWeight(strength01: Double): Double {
        val strength = strength01.coerceIn(0.0, 1.0)
        val salient = strength.pow(ONSET_SALIENCE_EXPONENT)
        return ONSET_WEIGHT_FLOOR + (1.0 - ONSET_WEIGHT_FLOOR) * salient
    }

    fun effectiveEventRate(rawRateHz: Double, salientRateHz: Double, beatConfidence: Double): Double {
        val raw = if (rawRateHz > 0.0) rawRateHz else 0.0
        val salient = if (salientRateHz > 0.0) salientRateHz else 0.0
        val unmetered = 1.0 - tempoConfidence01(beatConfidence)
        return salient + 0.45 * unmetered * maxOf(raw - salient, 0.0)
    }

    fun tempo01(bpm: Double): Double =
        smoothstep01((bpm - TEMPO_MIN_BPM) / (TEMPO_MAX_BPM - TEMPO_MIN_BPM))

    fun tempoConfidence01(confidence: Double): Double =
        smoothstep01((confidence - 0.08) / 0.70)

    /** 密度与（可稳定的）tempo 分量融合。 */
    fun fusePerceivedSpeed01(rateHz: Double, tempoComponent01: Double, beatConfidence: Double): Double {
        val density = onsetDensity01(rateHz)
        val tempo = tempoComponent01.coerceIn(0.0, 1.0)
        val weight = TEMPO_MAX_WEIGHT * tempoConfidence01(beatConfidence)
        val fused = density + weight * (tempo - density)
        val contrasted = fused + PERCEPTUAL_CONTRAST * fused * (1.0 - fused) * (2.0 * fused - 1.0)
        return interp(contrasted.coerceIn(0.0, 1.0), SCALE_X, SCALE_Y)
    }

    /** 缓慢积分节拍 tempo 证据，避免半/倍频切换跳变。 */
    fun tempoEvidenceStep(state: Double, tempoComponent01: Double, confidence: Double,
                          fps: Double, tauS: Double = TEMPO_EVIDENCE_TAU_S): Double {
        val gain = confidence.coerceIn(0.0, 1.0) *
                (1.0 - exp(-(1.0 / maxOf(fps, 1e-6)) / maxOf(tauS, 1e-3)))
        return state + (tempoComponent01 - state) * gain
    }

    /** 共享因果速度平滑一步。 */
    fun smoothStep(state: Double, target: Double, fps: Double,
                   attackS: Double = SPEED_ATTACK_S, releaseS: Double = SPEED_RELEASE_S): Double {
        val tau = if (target > state) attackS else releaseS
        return state + (target - state) * (1.0 - exp(-(1.0 / maxOf(fps, 1e-6)) / maxOf(tau, 1e-3)))
    }

    /** np.interp：xs 升序，边界外 clamp 到端点值。 */
    fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var i = 1
        while (i < n && xs[i] < x) i++
        val x0 = xs[i - 1]; val x1 = xs[i]
        val y0 = ys[i - 1]; val y1 = ys[i]
        val t = (x - x0) / (x1 - x0)
        return y0 + (y1 - y0) * t
    }
}
