package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 感知速度相关函数（对应 speed.py 的实时标量路径）。S 融合打击、人声、和声、低频/节拍运动，
 * K 只在 S 上增加有界正证据；二者都不做曲内分位归一，稳定的快段仍是快。
 */
object FableSolSpeed {

    const val RATE_WINDOW_S = 3.0
    const val FAST_RATE_WINDOW_S = 1.0
    private const val FAST_RATE_WEIGHT = 0.72
    private const val TEMPO_MIN_BPM = 55.0
    private const val TEMPO_MAX_BPM = 175.0
    private const val TEMPO_MAX_BOOST = 0.12
    private const val PERCEPTUAL_CONTRAST = 0.50
    private const val SPEED_ATTACK_S = 0.35
    private const val SPEED_RELEASE_S = 1.10
    const val TEMPO_EVIDENCE_TAU_S = 7.2
    private const val ONSET_SALIENCE_EXPONENT = 1.7
    private const val ONSET_WEIGHT_FLOOR = 0.28
    private const val RAW_SUBDIVISION_RETAIN = 0.75
    private const val MOTION_POWER = 8.0
    private const val MOTION_MAX_MIX = 0.65
    private const val MOTION_MEAN_MIX = 1.0 - MOTION_MAX_MIX
    private const val MOTION_TEMPO_MAX_BOOST = 0.08
    private const val KINETIC_GROOVE_CAP = 0.15
    private const val KINETIC_INTENSITY_CAP = 0.12
    private const val KINETIC_NOVELTY_CAP = 0.08

    private val MOTION_WEIGHTS = doubleArrayOf(0.34, 0.22, 0.23, 0.21)
    private val MOTION_SCALE_X = doubleArrayOf(0.0, 0.15, 0.30, 0.45, 0.60, 0.75, 1.0)
    private val MOTION_SCALE_Y = doubleArrayOf(0.0, 0.10, 0.24, 0.40, 0.62, 0.90, 1.0)

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
        return salient + RAW_SUBDIVISION_RETAIN * maxOf(raw - salient, 0.0)
    }

    /** 1 秒通道负责及时提速，3 秒通道负责稳定与自然释放。 */
    fun surfaceEventRate(fastRateHz: Double, slowRateHz: Double): Double {
        val fast = maxOf(fastRateHz, 0.0)
        val slow = maxOf(slowRateHz, 0.0)
        val rising = FAST_RATE_WEIGHT * fast + (1.0 - FAST_RATE_WEIGHT) * slow
        return maxOf(slow, rising)
    }

    fun tempo01(bpm: Double): Double =
        smoothstep01((bpm - TEMPO_MIN_BPM) / (TEMPO_MAX_BPM - TEMPO_MIN_BPM))

    fun tempoConfidence01(confidence: Double): Double =
        smoothstep01((confidence - 0.08) / 0.70)

    /** 1.2 秒均值后的正向 log-band 变化映射到固定运动刻度。 */
    fun spectralMotion01(meanPositiveDbPerHop: Double, scaleDb: Double = 0.16): Double {
        val value = maxOf(meanPositiveDbPerHop, 0.0)
        val scale = maxOf(scaleDb, 1e-6)
        return (1.0 - exp(-(value / scale).pow(0.85))).coerceIn(0.0, 1.0)
    }

    /** 纯 DSP 音节运动；presence 单独不能把持续音判成快速。 */
    fun vocalMotion01(syllableRateHz: Double, vocalPresence01: Double,
                      harmonicMotion01: Double): Double {
        val rate = maxOf(syllableRateHz, 0.0)
        val presence = vocalPresence01.coerceIn(0.0, 1.0)
        val harmonic = harmonicMotion01.coerceIn(0.0, 1.0)
        val syllables = 1.0 - exp(-(rate / 4.2).pow(1.10))
        val articulated = maxOf(syllables, 0.82 * harmonic * presence)
        return (articulated * (0.38 + 0.62 * presence)).coerceIn(0.0, 1.0)
    }

    /** 低频运动是主证据，可靠 tempo 只提供正向佐证。 */
    fun beatMotion01(lowFrequencyMotion01: Double, tempoComponent01: Double,
                     beatConfidence: Double): Double {
        val low = lowFrequencyMotion01.coerceIn(0.0, 1.0)
        val tempoEvidence = tempoComponent01.coerceIn(0.0, 1.0) *
            tempoConfidence01(beatConfidence)
        return (low + 0.55 * tempoEvidence * (1.0 - low)).coerceIn(0.0, 1.0)
    }

    fun grooveMotion01(percussiveMotion01: Double, beatMotion01: Double): Double =
        sqrt(percussiveMotion01.coerceIn(0.0, 1.0) * beatMotion01.coerceIn(0.0, 1.0))

    /**
     * 四条独立运动表面的单调融合。八阶广义均值保留任一可靠快通道，算术均值限制噪声通道接管。
     * 纯标量实现，不在音频热路径创建数组。
     */
    fun fuseMotionChannels01(
        percussive01: Double,
        vocal01: Double,
        harmonic01: Double,
        beat01: Double,
        percussiveConfidence: Double = 1.0,
        vocalConfidence: Double = 1.0,
        harmonicConfidence: Double = 1.0,
        beatConfidence: Double = 1.0,
        tempoComponent01: Double = 0.0,
        tempoEvidenceConfidence: Double = 0.0
    ): Double {
        val p = percussive01.coerceIn(0.0, 1.0)
        val v = vocal01.coerceIn(0.0, 1.0)
        val h = harmonic01.coerceIn(0.0, 1.0)
        val b = beat01.coerceIn(0.0, 1.0)
        val wp = MOTION_WEIGHTS[0] * percussiveConfidence.coerceIn(0.0, 1.0)
        val wv = MOTION_WEIGHTS[1] * vocalConfidence.coerceIn(0.0, 1.0)
        val wh = MOTION_WEIGHTS[2] * harmonicConfidence.coerceIn(0.0, 1.0)
        val wb = MOTION_WEIGHTS[3] * beatConfidence.coerceIn(0.0, 1.0)
        val total = wp + wv + wh + wb
        if (total <= 1e-12) return 0.0
        val mean = (wp * p + wv * v + wh * h + wb * b) / total
        val generalizedMax = ((wp * p.pow(MOTION_POWER) + wv * v.pow(MOTION_POWER) +
            wh * h.pow(MOTION_POWER) + wb * b.pow(MOTION_POWER)) / total)
            .pow(1.0 / MOTION_POWER)
        var motion = MOTION_MAX_MIX * generalizedMax + MOTION_MEAN_MIX * mean
        val tempoGain = MOTION_TEMPO_MAX_BOOST * tempoConfidence01(tempoEvidenceConfidence)
        motion += tempoGain * tempoComponent01.coerceIn(0.0, 1.0) * (1.0 - motion)
        return interp(motion.coerceIn(0.0, 1.0), MOTION_SCALE_X, MOTION_SCALE_Y)
    }

    /** 状态标签不能压低输运；groove/intensity/novelty 只能提供有界正向证据。 */
    fun kineticDriveTarget01(speed01: Double, groove01: Double = 0.0,
                             intensity01: Double = 0.0,
                             positiveNovelty01: Double = 0.0): Double {
        val speed = speed01.coerceIn(0.0, 1.0)
        val addition = KINETIC_GROOVE_CAP * groove01.coerceIn(0.0, 1.0) +
            KINETIC_INTENSITY_CAP * intensity01.coerceIn(0.0, 1.0) +
            KINETIC_NOVELTY_CAP * positiveNovelty01.coerceIn(0.0, 1.0)
        return (speed + (1.0 - speed) * addition).coerceIn(0.0, 1.0)
    }

    /** 密度与（可稳定的）tempo 分量融合。 */
    fun fusePerceivedSpeed01(rateHz: Double, tempoComponent01: Double, beatConfidence: Double): Double {
        val density = onsetDensity01(rateHz)
        val tempo = tempoComponent01.coerceIn(0.0, 1.0)
        val weight = TEMPO_MAX_BOOST * tempoConfidence01(beatConfidence)
        val fused = density + weight * tempo * (1.0 - density)
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
