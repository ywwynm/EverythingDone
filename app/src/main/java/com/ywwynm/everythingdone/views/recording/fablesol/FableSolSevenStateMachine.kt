package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 七境的纯决策内核，对应 Python `core/states.py` 的状态选择部分。
 *
 * `CALM/GROOVE/PEAK` 由软时长解码器长期保持，`LIFT/CLIMAX` 则是证据有界的叠加阶段。
 * 两个阶段都没有演出时长上限：只要因果证据持续，状态即可持续；真实释放后才衰减并重新武装。
 * 视觉弹簧、HDR 和具体波形不属于该类，由 Mapper/Simulation 消费 [FableSolStateDecision]。
 */
class FableSolSevenStateMachine {

    private val decoder = FableSolSoftDurationGradeDecoder()
    private val decoderDecision = FableSolGradeDecision()
    private val sharedOutput = FableSolStateDecision()

    private var state = FableSolVisualState.IDLE
    private var baseState = FableSolVisualState.CALM
    private var baseEnteredT = 0.0
    private var lastT = Double.NaN
    private var heard = false
    private var silenceS = 0.0
    private var dropPendingConfidence = 0.0
    private var liftCharge = 0.0
    private var climaxCharge = 0.0
    private var liftActive = false
    private var liftArmed = true
    private var liftRefractoryUntil = -100.0
    private var climaxActive = false
    private var climaxArmed = true
    private var climaxMinUntil = -100.0
    private var climaxRefractoryUntil = -100.0
    private var decoderProgress01 = 0.0

    init {
        reset()
    }

    fun reset() {
        state = FableSolVisualState.IDLE
        baseState = FableSolVisualState.CALM
        baseEnteredT = 0.0
        lastT = Double.NaN
        heard = false
        silenceS = 0.0
        dropPendingConfidence = 0.0
        liftCharge = 0.0
        climaxCharge = 0.0
        liftActive = false
        liftArmed = true
        liftRefractoryUntil = -100.0
        climaxActive = false
        climaxArmed = true
        climaxMinUntil = -100.0
        climaxRefractoryUntil = -100.0
        decoderProgress01 = 0.0
        decoder.reset(FableSolSustainedGrade.CALM)
        sharedOutput.state = state
        sharedOutput.baseState = baseState
        sharedOutput.changed = false
        sharedOutput.reason = FableSolStateReason.NONE
        sharedOutput.gradeDrive01 = 0.0
        sharedOutput.liftScore01 = 0.0
        sharedOutput.climaxScore01 = 0.0
        sharedOutput.calmProbability = 1.0
        sharedOutput.grooveProbability = 0.0
        sharedOutput.peakProbability = 0.0
        sharedOutput.evidenceProgress01 = 0.0
    }

    /** section 只供情绪和独立巨浪门控使用；七境分类明确不消费全段未来统计。 */
    fun notifySection() = Unit

    fun notifyDrop(confidence01: Double) {
        dropPendingConfidence = max(dropPendingConfidence, confidence01.coerceIn(0.0, 1.0))
    }

    fun currentState(): FableSolVisualState = state

    fun currentBaseState(): FableSolVisualState = baseState

    /**
     * checkpoint 或调试恢复入口。LIFT/CLIMAX 会恢复为已激活阶段；基础态会同步软时长解码器。
     */
    fun forceState(restoredState: FableSolVisualState, audioT: Double) {
        state = restoredState
        lastT = audioT
        heard = restoredState != FableSolVisualState.IDLE &&
            restoredState != FableSolVisualState.SILENCE
        when (restoredState) {
            FableSolVisualState.CALM,
            FableSolVisualState.GROOVE,
            FableSolVisualState.PEAK -> {
                baseState = restoredState
                decoder.force(gradeIndex(restoredState))
                baseEnteredT = audioT
                sharedOutput.calmProbability = if (restoredState == FableSolVisualState.CALM) 1.0 else 0.0
                sharedOutput.grooveProbability = if (restoredState == FableSolVisualState.GROOVE) 1.0 else 0.0
                sharedOutput.peakProbability = if (restoredState == FableSolVisualState.PEAK) 1.0 else 0.0
                decoderProgress01 = 0.0
                liftActive = false
                liftArmed = true
                climaxActive = false
                climaxArmed = true
            }
            FableSolVisualState.LIFT -> {
                liftActive = true
                liftArmed = false
            }
            FableSolVisualState.CLIMAX -> {
                climaxActive = true
                climaxArmed = false
                climaxMinUntil = audioT + 0.75
            }
            else -> Unit
        }
        writeOutput(sharedOutput, false, FableSolStateReason.NONE)
    }

    fun step(
        frame: FableSolPerceptualFrame,
        evidence: FableSolStateEvidence,
        stateSensitivity: Double = 0.0,
        transitionSpeed: Double = 0.0,
        output: FableSolStateDecision = sharedOutput
    ): FableSolStateDecision {
        output.changed = false
        output.reason = FableSolStateReason.NONE
        val t = frame.t
        if (!lastT.isNaN() && t == lastT) return writeOutput(output, false, FableSolStateReason.NONE)

        val dt = if (lastT.isNaN() || t < lastT) {
            1.0 / 60.0
        } else {
            min(max(t - lastT, 1.0 / 240.0), FableSolSoftDurationGradeDecoder.MAX_AGE_S)
        }
        lastT = t
        return selectState(frame, evidence, dt, stateSensitivity, transitionSpeed, output)
    }

    private fun selectState(
        frame: FableSolPerceptualFrame,
        evidence: FableSolStateEvidence,
        dt: Double,
        sensitivity: Double,
        transitionSpeed: Double,
        output: FableSolStateDecision
    ): FableSolStateDecision {
        val t = frame.t
        if (frame.silent) silenceS += dt else silenceS = 0.0

        if (frame.silent && silenceS >= 2.0 * timeScale(transitionSpeed)) {
            val target = if (heard) FableSolVisualState.SILENCE else FableSolVisualState.IDLE
            if (state != target) {
                decoder.reset(FableSolSustainedGrade.CALM)
                baseState = FableSolVisualState.CALM
                liftActive = false
                climaxActive = false
                liftArmed = true
                climaxArmed = true
                liftCharge = 0.0
                climaxCharge = 0.0
                decoderProgress01 = 0.0
                output.calmProbability = 1.0
                output.grooveProbability = 0.0
                output.peakProbability = 0.0
                enter(target, output, FableSolStateReason.SILENCE)
            }
            return writeOutput(output, output.changed, output.reason)
        }

        // 静音是证据，不是"没有证据"。旧实现在确认静音的 2 秒里直接 return，
        // 于是一次咳嗽推上 CLIMAX 后，声音停了画面仍在 CLIMAX 里挂满 2 秒才塌到
        // SILENCE（2026-07-21 实测 PEAK 段驻留 2.7s、平均 water 仅 0.17）。现在
        // 照常解码，只是喂零证据：档位顺着 semi-Markov 正常往下走，LIFT/CLIMAX
        // 的 charge 也照常释放。
        output.gradeDrive01 =
            if (frame.silent) 0.0 else evidence.gradeDrive01.coerceIn(0.0, 1.0)
        output.liftScore01 =
            if (frame.silent) 0.0 else evidence.liftScore01.coerceIn(0.0, 1.0)
        output.climaxScore01 =
            if (frame.silent) 0.0 else evidence.climaxScore01.coerceIn(0.0, 1.0)
        if (!frame.silent &&
            (!heard || state == FableSolVisualState.IDLE ||
                state == FableSolVisualState.SILENCE)
        ) {
            heard = true
            decoder.reset(FableSolSustainedGrade.CALM)
            baseState = FableSolVisualState.CALM
            baseEnteredT = t
            decoderProgress01 = 0.0
            output.calmProbability = 1.0
            output.grooveProbability = 0.0
            output.peakProbability = 0.0
            enter(FableSolVisualState.CALM, output, FableSolStateReason.HEARD)
            return writeOutput(output, output.changed, output.reason)
        }
        // 从未听到过声音：开机静默期保持 IDLE 镜面，不进解码。
        if (!heard) return writeOutput(output, false, FableSolStateReason.NONE)

        decoder.step(
            output.gradeDrive01,
            dt,
            sensitivity,
            transitionSpeed,
            decoderDecision
        )
        output.calmProbability = decoderDecision.calmProbability
        output.grooveProbability = decoderDecision.grooveProbability
        output.peakProbability = decoderDecision.peakProbability
        decoderProgress01 = decoderDecision.transitionProgress01
        val decodedBase = when (decoderDecision.grade) {
            FableSolSustainedGrade.CALM -> FableSolVisualState.CALM
            FableSolSustainedGrade.GROOVE -> FableSolVisualState.GROOVE
            else -> FableSolVisualState.PEAK
        }
        val oldBase = baseState
        baseState = decodedBase
        if (baseState != oldBase) baseEnteredT = t

        val speed = 2.0.pow(0.65 * transitionSpeed.coerceIn(-1.0, 1.0))
        val dropConfidence = dropPendingConfidence
        dropPendingConfidence = 0.0

        // 非 drop 的 CLIMAX 必须先建立至少 0.65s 的 PEAK foothold，避免短促假高潮。
        val peakSupportAge = if (baseState == FableSolVisualState.PEAK) {
            max(t - baseEnteredT, 0.0)
        } else {
            0.0
        }
        val climaxSupport =
            (baseState == FableSolVisualState.PEAK && peakSupportAge >= 0.65 / speed) ||
                (dropConfidence > 0.0 &&
                    (baseState == FableSolVisualState.PEAK || output.gradeDrive01 >= 0.82))
        var dropSeeded = false
        if (dropConfidence > 0.0 && climaxArmed && climaxSupport &&
            (output.gradeDrive01 >= 0.78 || output.climaxScore01 >= 0.55)
        ) {
            climaxCharge = max(climaxCharge, 0.82 + 0.28 * dropConfidence)
            dropSeeded = true
        }
        val climaxCharging = dropSeeded ||
            (climaxSupport && output.climaxScore01 >= 0.52 && (climaxActive || climaxArmed))
        if (climaxCharging) {
            val arrivalGain = 3.4 + 4.2 * output.climaxScore01
            climaxCharge = min(climaxCharge + dt * speed * arrivalGain, 1.5)
        } else {
            climaxCharge = max(climaxCharge - dt * speed * 1.35, 0.0)
        }

        // 只有"正在充能"才可以点亮相位。旧实现允许一段正在释放的 charge 在跌过
        // 阈值的途中重新触发：声音停下后档位一路往下走时，会凭残余电量再点一次
        // LIFT/CLIMAX（2026-07-21 实测 19.7s 处出现 water=0 的 LIFT 段）。
        if (climaxCharging && !climaxActive && climaxArmed && climaxCharge >= 0.80 &&
            t >= climaxRefractoryUntil
        ) {
            climaxActive = true
            climaxArmed = false
            climaxMinUntil = t + 0.75 / speed
        } else if (climaxActive && t >= climaxMinUntil && climaxCharge <= 0.18) {
            climaxActive = false
            climaxCharge = 0.0
            climaxRefractoryUntil = t + 2.5 / speed
        }
        if (!climaxActive && output.climaxScore01 <= 0.32 && climaxCharge <= 0.18) {
            climaxArmed = true
        }

        if (baseState == FableSolVisualState.PEAK || climaxActive) {
            liftCharge = max(liftCharge - dt * speed * 2.4, 0.0)
            liftActive = false
        } else {
            val liftCharging = output.liftScore01 >= 0.50
            if (liftCharging) {
                liftCharge = min(
                    liftCharge + dt * speed * (3.8 + 3.2 * output.liftScore01),
                    1.4
                )
            } else {
                liftCharge = max(liftCharge - dt * speed * 1.75, 0.0)
                if (output.liftScore01 <= 0.32) liftArmed = true
            }
            if (liftCharging && !liftActive && liftArmed &&
                t >= liftRefractoryUntil && liftCharge >= 0.72
            ) {
                liftActive = true
                liftArmed = false
            } else if (liftActive && liftCharge <= 0.14) {
                liftActive = false
                liftCharge = 0.0
                liftRefractoryUntil = t + 1.5 / speed
            }
        }

        val desired: FableSolVisualState
        val reason: FableSolStateReason
        when {
            climaxActive -> {
                desired = FableSolVisualState.CLIMAX
                reason = if (dropConfidence > 0.0) {
                    FableSolStateReason.DROP
                } else {
                    FableSolStateReason.CAUSAL_NEW_HIGH
                }
            }
            liftActive -> {
                desired = FableSolVisualState.LIFT
                reason = FableSolStateReason.CAUSAL_LIFT
            }
            else -> {
                desired = baseState
                reason = if (oldBase == baseState) {
                    FableSolStateReason.PHASE_RELEASE
                } else if (decoderDecision.grade > gradeIndex(oldBase)) {
                    FableSolStateReason.GRADE_SOFT_UP
                } else {
                    FableSolStateReason.GRADE_SOFT_DOWN
                }
            }
        }
        enter(desired, output, reason)
        return writeOutput(output, output.changed, output.reason)
    }

    private fun enter(
        desired: FableSolVisualState,
        output: FableSolStateDecision,
        reason: FableSolStateReason
    ) {
        if (desired == state) return
        state = desired
        output.changed = true
        output.reason = reason
    }

    private fun writeOutput(
        output: FableSolStateDecision,
        changed: Boolean,
        reason: FableSolStateReason
    ): FableSolStateDecision {
        output.state = state
        output.baseState = baseState
        output.changed = changed
        output.reason = reason
        output.evidenceProgress01 = when {
            climaxActive -> (climaxCharge / 0.80).coerceIn(0.0, 1.0)
            liftActive -> (liftCharge / 0.72).coerceIn(0.0, 1.0)
            else -> decoderProgress01.coerceIn(0.0, 1.0)
        }
        return output
    }

    companion object {
        private val WAVE_MULTIPLIER = doubleArrayOf(0.20, 0.20, 0.50, 1.00, 0.80, 1.50, 2.00)
        // D195（2026-07-23）：PEAK 是常见的顶部状态（CLIMAX 不常见），银边/白冠在
        // PEAK 即拉满；GROOVE/LIFT/CALM 同步上调，层距高档微收。RIM 与 CAP 取值一致。
        private val SPREAD = doubleArrayOf(0.70, 0.70, 0.90, 1.00, 1.08, 1.20, 1.29)
        private val RIM = doubleArrayOf(0.0, 0.0, 0.24, 0.72, 0.90, 1.0, 1.0)
        private val CAP = doubleArrayOf(0.0, 0.0, 0.24, 0.72, 0.90, 1.0, 1.0)

        fun waveMultiplier(state: FableSolVisualState): Double = WAVE_MULTIPLIER[state.ordinal]
        fun spread(state: FableSolVisualState): Double = SPREAD[state.ordinal]
        fun rim01(state: FableSolVisualState): Double = RIM[state.ordinal]
        fun cap01(state: FableSolVisualState): Double = CAP[state.ordinal]

        private fun timeScale(transitionSpeed: Double): Double =
            2.0.pow(-0.65 * transitionSpeed.coerceIn(-1.0, 1.0))

        private fun gradeIndex(state: FableSolVisualState): Int = when (state) {
            FableSolVisualState.CALM -> FableSolSustainedGrade.CALM
            FableSolVisualState.GROOVE -> FableSolSustainedGrade.GROOVE
            FableSolVisualState.PEAK -> FableSolSustainedGrade.PEAK
            else -> FableSolSustainedGrade.CALM
        }
    }
}
