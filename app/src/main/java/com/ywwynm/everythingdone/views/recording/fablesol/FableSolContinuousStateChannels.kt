package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 七境离散决策之后、Simulation 之前的连续执行通道。默认调用路径逐帧零分配。 */
internal class FableSolContinuousVisualChannels {
    @JvmField var state = FableSolVisualState.IDLE
    @JvmField var waterDrive01 = 0.0
    @JvmField var levelGoalDp = 0.0
    @JvmField var levelDp = 0.0
    @JvmField var waveScale = 0.11
    @JvmField var flow01 = 0.0
    @JvmField var targetDps = 0.0
    @JvmField var spread = 0.70
    @JvmField var rim01 = 0.0
    @JvmField var cap01 = 0.0
}

/** 对应 Python `states.py` 中五个 CriticalSpring 和连续 state actuator。 */
internal class FableSolContinuousStateChannels {
    private val level = CriticalSpring(0.0)
    private val wave = CriticalSpring(0.11)
    private val flow = CriticalSpring(0.0)
    private val spread = CriticalSpring(0.70)
    private val rim = CriticalSpring(0.0)
    private val cap = CriticalSpring(0.0)
    private val sharedOutput = FableSolContinuousVisualChannels()
    private var lastT = Double.NaN

    fun reset() {
        lastT = Double.NaN
        level.reset(0.0)
        wave.reset(0.11)
        flow.reset(0.0)
        spread.reset(0.70)
        rim.reset(0.0)
        cap.reset(0.0)
        writeOutput(sharedOutput, FableSolVisualState.IDLE, 0.0, 0.0)
    }

    fun step(
        t: Double,
        state: FableSolVisualState,
        silent: Boolean,
        waterDrive01: Double,
        kineticDrive01: Double,
        musicArousal01: Double,
        punchLu01: Double,
        punch01: Double,
        centroid01: Double,
        expressionGain: Double,
        transitionSpeed: Double,
        output: FableSolContinuousVisualChannels = sharedOutput
    ): FableSolContinuousVisualChannels {
        if (!lastT.isNaN() && t == lastT) return copyOutput(sharedOutput, output)
        val dt = if (lastT.isNaN() || t < lastT) {
            1.0 / 60.0
        } else {
            max(t - lastT, 1.0 / 240.0).coerceAtMost(0.10)
        }
        lastT = t

        val expression = expressionGain.coerceIn(0.5, 1.5)
        val water = if (silent) 0.0 else waterDrive01.coerceIn(0.0, 1.0)
        val levelGoal = 160.0 * water
        val punch = max(punchLu01, punch01).coerceIn(0.0, 1.0)
        val withinGrade = min(
            0.75 + 0.50 * musicArousal01.coerceIn(0.0, 1.0) + 0.25 * punch,
            1.25
        )
        val waveGoal = FableSolSevenStateMachine.waveMultiplier(state) * withinGrade * expression
        val flowGoal = if (state == FableSolVisualState.IDLE ||
            state == FableSolVisualState.SILENCE
        ) {
            0.0
        } else {
            kineticDrive01.coerceIn(0.0, 1.0)
        }
        val spreadGoal = 1.0 + (FableSolSevenStateMachine.spread(state) - 1.0) * expression
        val rimGoal = FableSolSevenStateMachine.rim01(state) *
            (0.5 + 0.5 * centroid01.coerceIn(0.0, 1.0)) * expression
        val capGoal = FableSolSevenStateMachine.cap01(state) * punch * expression
        val timeScale = 2.0.pow(-0.65 * transitionSpeed.coerceIn(-1.0, 1.0))

        level.step(levelGoal, dt, 0.75 * timeScale, 33.6)
        wave.step(waveGoal, dt, 0.32 * timeScale)
        flow.step(flowGoal, dt, 0.18 * timeScale, 1.50)
        spread.step(spreadGoal, dt, 1.0 * timeScale)
        rim.step(rimGoal, dt, 0.16 * timeScale)
        cap.step(capGoal, dt, 0.16 * timeScale)
        return writeOutput(output, state, water, levelGoal)
    }

    private fun writeOutput(
        output: FableSolContinuousVisualChannels,
        state: FableSolVisualState,
        water: Double,
        levelGoal: Double
    ): FableSolContinuousVisualChannels {
        output.state = state
        output.waterDrive01 = water
        output.levelGoalDp = levelGoal
        output.levelDp = max(level.value, 0.0)
        output.waveScale = max(wave.value, 0.0)
        output.flow01 = flow.value.coerceIn(0.0, 1.0)
        output.targetDps = FableSolFlowPolicy.targetFlowDps(output.flow01)
        output.spread = spread.value.coerceIn(0.65, 1.35)
        output.rim01 = rim.value.coerceIn(0.0, 1.0)
        output.cap01 = cap.value.coerceIn(0.0, 1.0)
        return output
    }

    private fun copyOutput(
        source: FableSolContinuousVisualChannels,
        destination: FableSolContinuousVisualChannels
    ): FableSolContinuousVisualChannels {
        if (source === destination) return source
        destination.state = source.state
        destination.waterDrive01 = source.waterDrive01
        destination.levelGoalDp = source.levelGoalDp
        destination.levelDp = source.levelDp
        destination.waveScale = source.waveScale
        destination.flow01 = source.flow01
        destination.targetDps = source.targetDps
        destination.spread = source.spread
        destination.rim01 = source.rim01
        destination.cap01 = source.cap01
        return destination
    }

    private class CriticalSpring(initialValue: Double) {
        var value = initialValue
            private set
        private var velocity = 0.0

        fun reset(newValue: Double) {
            value = newValue
            velocity = 0.0
        }

        /** Spring-Roll-Call 的 exact variable-dt critically damped spring。 */
        fun step(goal: Double, dt: Double, halfLife: Double): Double =
            stepInternal(goal, dt, halfLife, 0.0, false)

        fun step(goal: Double, dt: Double, halfLife: Double, slewPerS: Double): Double =
            stepInternal(goal, dt, halfLife, slewPerS, true)

        private fun stepInternal(
            goal: Double,
            dt: Double,
            halfLife: Double,
            slewPerS: Double,
            hasSlew: Boolean
        ): Double {
            val elapsed = max(dt, 0.0)
            if (elapsed <= 0.0) return value
            val old = value
            val damping = 4.0 * ln(2.0) / max(halfLife, 1e-4)
            val y = damping * 0.5
            val j0 = value - goal
            val j1 = velocity + j0 * y
            val decay = exp(-y * elapsed)
            var nextValue = decay * (j0 + j1 * elapsed) + goal
            var nextVelocity = decay * (velocity - j1 * y * elapsed)
            if (hasSlew) {
                // 软饱和，不硬钳位。硬钳位每帧把 velocity 改写成 ±slew，下一帧
                // 弹簧又从这个被压低的速度算出更大的位移，于是在"贴限速"和
                // "弹簧自由"之间逐帧来回跳（2026-07-22 实测：33% 的帧贴着限速，
                // |加速度| 峰值 434dp/s²，肉眼就是水位涨到一半"卡一下、抽搐一下"）。
                // softLimit 是 C∞ 的：常用区间几乎不动，接近上限时平滑收住，
                // 速度与位移始终一致，不会再产生逐帧交替。
                val delta = slewPerS * elapsed
                val limited = FableSolCubicResampler.softLimit(nextValue - old, delta)
                nextValue = old + limited
                nextVelocity = limited / elapsed
            }
            value = nextValue
            velocity = nextVelocity
            return value
        }
    }
}
