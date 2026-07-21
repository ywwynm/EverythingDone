package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 严格因果、无模型的七境证据，对应 Python `audio/state_evidence.py`。
 *
 * 固定查表项组成一个很小的有序 GAM；全部上下文都是有界的 trailing-only 状态。对象内部持有
 * 一个可复用输出，因此 [step] 在默认用法下不产生逐帧分配。
 */
class FableSolCausalStateEvidence(frameRate: Double) {

    private val frameRate = max(frameRate, 1.0)
    private val nominalDt = 1.0 / this.frameRate
    private val sharedOutput = FableSolStateEvidence()

    private var lastT = Double.NaN
    private var audibleS = 0.0
    private var baseFast = Double.NaN
    private var baseMid = Double.NaN
    private var baseSlow = Double.NaN
    private var grade = 0.0
    private var lift = 0.0
    private var climax = 0.0
    private var highMark = Double.NaN

    fun reset() {
        lastT = Double.NaN
        audibleS = 0.0
        baseFast = Double.NaN
        baseMid = Double.NaN
        baseSlow = Double.NaN
        grade = 0.0
        lift = 0.0
        climax = 0.0
        highMark = Double.NaN
        writeOutput(sharedOutput, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    fun step(
        input: FableSolPerceptualFrame,
        output: FableSolStateEvidence = sharedOutput
    ): FableSolStateEvidence {
        val dt = elapsedDt(input.t)
        if (input.silent) {
            grade = follow(grade, 0.0, dt, 0.30)
            lift = follow(lift, 0.0, dt, 0.20)
            climax = follow(climax, 0.0, dt, 0.16)
            return writeOutput(output, grade, lift, climax, 0.0, 0.0, 0.0)
        }

        audibleS += dt
        val loud = lookup(input.waterDrive01, LOUDNESS_X, LOUDNESS_Y)
        val arousal = lookup(input.musicArousal01, AROUSAL_X, AROUSAL_Y)
        val percussive = clip01(input.percussiveMotion01)
        val harmonic = clip01(input.harmonicMotion01)
        val grooveMotion = clip01(input.grooveMotion01)
        val kinetic = min(clip01(input.kineticDrive01), 0.82)
        val texture = clip01(
            0.65 * sqrt(max(percussive * harmonic, 0.0)) +
                0.35 * (0.55 * harmonic + 0.30 * grooveMotion +
                    0.15 * (1.0 - clip01(input.punchLu01)))
        )
        val motion = clip01(0.65 * grooveMotion + 0.35 * kinetic)
        val energy = smoothstep(0.25, 0.80, input.energy01)
        val bassMass = smoothstep(0.18, 0.62, input.lowShare01)

        // 只使用 DSP vocal motion。快速但稀疏的干声 solo 可以提速，却不能仅凭语速进入 PEAK。
        val vocalSolo = smoothstep(0.38, 0.64, input.vocalMotion01) *
            (1.0 - smoothstep(0.36, 0.54, input.musicArousal01))
        val absolute = clip01(
            0.52 * loud +
                0.18 * arousal +
                0.14 * texture +
                0.10 * motion +
                0.08 * energy +
                0.06 * bassMass -
                0.125 * vocalSolo +
                0.05 * (clip01(input.intensityDrive01) - 0.55) +
                input.domainGradeTrim01
        )

        baseFast = followNullable(baseFast, absolute, dt, 0.75)
        baseMid = followNullable(baseMid, absolute, dt, 2.5)
        baseSlow = followNullable(baseSlow, absolute, dt, 12.0)

        val positiveDelta = max(max(baseFast - baseSlow, baseMid - baseSlow), 0.0)
        val negativeDelta = max(baseSlow - baseFast, 0.0)
        val arrangementGate = smoothstep(0.64, 0.73, absolute) * (1.0 - 0.75 * vocalSolo)
        val positiveContext = 0.12 * smoothstep(0.025, 0.105, positiveDelta) * arrangementGate
        val negativeContext = 0.10 * smoothstep(0.020, 0.085, negativeDelta)
        val context = positiveContext - negativeContext

        val warm = 0.55 + 0.45 * smoothstep(0.35, 3.0, audibleS)
        val noveltyPulse = smoothstep(0.65, 0.95, input.positiveNovelty01)
        val arrivalBoost = 0.075 * noveltyPulse * smoothstep(0.58, 0.70, absolute) *
            (1.0 - vocalSolo) * (1.0 - vocalSolo)
        val gradeTarget = clip01((absolute + context + arrivalBoost) * warm)
        grade = follow(grade, gradeTarget, dt, if (gradeTarget > grade) 0.32 else 1.05)

        val rise = smoothstep(0.018, 0.085, positiveDelta)
        val change = max(
            0.90 * clip01(input.buildUp01),
            max(
                0.52 * clip01(input.energyRising01),
                0.72 * rise + 0.28 * noveltyPulse
            )
        )
        val gradeGate = 0.42 + 0.58 * smoothstep(0.30, 0.66, grade)
        val liftTarget = clip01(change * gradeGate)
        lift = follow(lift, liftTarget, dt, if (liftTarget > lift) 0.16 else 0.72)

        val newHigh: Double
        if (highMark.isNaN()) {
            highMark = baseFast
            newHigh = 0.0
        } else {
            val previousHigh = highMark
            newHigh = if (audibleS >= 3.0) {
                smoothstep(0.025, 0.105, baseFast - previousHigh)
            } else {
                0.0
            }
            highMark = if (baseFast > previousHigh) {
                follow(previousHigh, baseFast, dt, 1.5)
            } else {
                val decay = 1.0 - exp(-dt / 45.0)
                previousHigh + (0.35 - previousHigh) * decay
            }
        }
        val arrival = max(
            newHigh,
            0.58 * clip01(input.positiveNovelty01) +
                0.27 * clip01(input.energyRising01) +
                0.15 * max(clip01(input.punch01), clip01(input.punchLu01))
        )
        val climaxGate = smoothstep(0.64, 0.76, min(grade, absolute + 0.02))
        val climaxTarget = climaxGate * arrival * (1.0 - 0.75 * vocalSolo)
        climax = follow(climax, climaxTarget, dt, if (climaxTarget > climax) 0.10 else 0.48)

        val decisionGrade = clip01(min(grade, absolute + 0.035) - 0.12 * vocalSolo)
        return writeOutput(
            output,
            decisionGrade,
            clip01(lift),
            clip01(climax),
            clip01(absolute),
            context.coerceIn(-1.0, 1.0),
            clip01(vocalSolo)
        )
    }

    private fun elapsedDt(t: Double): Double {
        val dt = if (lastT.isNaN() || t <= lastT) {
            nominalDt
        } else {
            (t - lastT).coerceIn(nominalDt * 0.25, 0.10)
        }
        lastT = t
        return dt
    }

    private fun writeOutput(
        output: FableSolStateEvidence,
        gradeDrive01: Double,
        liftScore01: Double,
        climaxScore01: Double,
        gradeAbsolute01: Double,
        gradeContext01: Double,
        vocalSoloPenalty01: Double
    ): FableSolStateEvidence {
        output.gradeDrive01 = gradeDrive01
        output.liftScore01 = liftScore01
        output.climaxScore01 = climaxScore01
        output.gradeAbsolute01 = gradeAbsolute01
        output.gradeContext01 = gradeContext01
        output.vocalSoloPenalty01 = vocalSoloPenalty01
        return output
    }

    private fun followNullable(value: Double, target: Double, dt: Double, tau: Double): Double =
        if (value.isNaN()) target else follow(value, target, dt, tau)

    private fun follow(value: Double, target: Double, dt: Double, tau: Double): Double {
        val alpha = 1.0 - exp(-max(dt, 0.0) / max(tau, 1e-4))
        return value + (target - value) * alpha
    }

    companion object {
        private val LOUDNESS_X = doubleArrayOf(0.00, 0.35, 0.55, 0.68, 0.76, 0.84, 1.00)
        private val LOUDNESS_Y = doubleArrayOf(0.00, 0.05, 0.28, 0.52, 0.68, 0.86, 1.00)
        private val AROUSAL_X = doubleArrayOf(0.00, 0.20, 0.30, 0.42, 0.58, 1.00)
        private val AROUSAL_Y = doubleArrayOf(0.00, 0.10, 0.35, 0.68, 0.90, 1.00)

        internal fun clip01(value: Double): Double = value.coerceIn(0.0, 1.0)

        internal fun smoothstep(lo: Double, hi: Double, value: Double): Double {
            if (hi <= lo) return if (value >= hi) 1.0 else 0.0
            val q = ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            return q * q * (3.0 - 2.0 * q)
        }

        private fun lookup(value: Double, xs: DoubleArray, ys: DoubleArray): Double {
            val x = clip01(value)
            if (x <= xs[0]) return ys[0]
            for (i in 1 until xs.size) {
                if (x <= xs[i]) {
                    val q = (x - xs[i - 1]) / max(xs[i] - xs[i - 1], 1e-9)
                    return ys[i - 1] + (ys[i] - ys[i - 1]) * q
                }
            }
            return ys[ys.lastIndex]
        }
    }
}
