package com.ywwynm.everythingdone.views.recording.fablesol

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * `CALM/GROOVE/PEAK` 三态严格因果显式时长解码器，对应 Python `core/state_decoder.py`。
 *
 * 每个假设保留 0..16 秒的年龄分布。时长是软概率而非硬锁；极强观测可立即越过年轻状态先验。
 * 两块 483 元素的 primitive buffer 初始化后循环复用，更新路径没有数组或集合分配。
 */
class FableSolSoftDurationGradeDecoder {

    private val ageSteps = (MAX_AGE_S / UPDATE_DT).toInt() + 1
    private var posterior = DoubleArray(FableSolSustainedGrade.COUNT * ageSteps)
    private var next = DoubleArray(FableSolSustainedGrade.COUNT * ageSteps)
    private val baseHazardByAge = DoubleArray(ageSteps) { age ->
        0.003 + 0.052 * FableSolCausalStateEvidence.smoothstep(
            0.35,
            5.5,
            age * UPDATE_DT
        )
    }
    private val emission = DoubleArray(FableSolSustainedGrade.COUNT)
    private val sharedDecision = FableSolGradeDecision()

    private var grade = FableSolSustainedGrade.CALM
    private var peakEstablished = false
    private var accumulatorS = 0.0
    private var calmProbability = 1.0
    private var grooveProbability = 0.0
    private var peakProbability = 0.0
    private var progress = 0.0

    init {
        reset()
    }

    fun reset(initialGrade: Int = FableSolSustainedGrade.CALM) {
        val index = initialGrade.coerceIn(
            FableSolSustainedGrade.CALM,
            FableSolSustainedGrade.PEAK
        )
        Arrays.fill(posterior, 0.0)
        Arrays.fill(next, 0.0)
        posterior[index * ageSteps] = 1.0
        grade = index
        peakEstablished = index == FableSolSustainedGrade.PEAK
        accumulatorS = 0.0
        calmProbability = if (index == FableSolSustainedGrade.CALM) 1.0 else 0.0
        grooveProbability = if (index == FableSolSustainedGrade.GROOVE) 1.0 else 0.0
        peakProbability = if (index == FableSolSustainedGrade.PEAK) 1.0 else 0.0
        progress = 0.0
        writeDecision(sharedDecision)
    }

    /** 外部恢复/调试状态时同步解码器；不附加硬驻留时间。 */
    fun force(newGrade: Int) = reset(newGrade)

    fun currentGrade(): Int = grade

    fun step(
        drive01: Double,
        dt: Double,
        sensitivity: Double = 0.0,
        transitionSpeed: Double = 0.0,
        output: FableSolGradeDecision = sharedDecision
    ): FableSolGradeDecision {
        accumulatorS += max(dt, 0.0)

        observationProbabilities(drive01, sensitivity, peakThreshold(), emission)
        val winning = argMax3(emission)
        val urgent = emission[winning] >= 0.965 && winning != grade
        if (urgent && accumulatorS < UPDATE_DT) {
            tick(drive01, sensitivity, transitionSpeed)
            accumulatorS = 0.0
        }
        while (accumulatorS + 1e-12 >= UPDATE_DT) {
            tick(drive01, sensitivity, transitionSpeed)
            accumulatorS -= UPDATE_DT
        }
        return writeDecision(output)
    }

    /** 测试、checkpoint 或诊断可复制后验；生产热路径无需调用。 */
    fun copyPosteriorTo(destination: DoubleArray) {
        require(destination.size >= posterior.size)
        posterior.copyInto(destination, endIndex = posterior.size)
    }

    fun posteriorSize(): Int = posterior.size

    private fun tick(drive01: Double, sensitivity: Double, transitionSpeed: Double) {
        observationProbabilities(drive01, sensitivity, peakThreshold(), emission)
        val speedScale = 2.0.pow(0.65 * transitionSpeed.coerceIn(-1.0, 1.0))
        Arrays.fill(next, 0.0)

        for (source in 0 until FableSolSustainedGrade.COUNT) {
            val sourceOffset = source * ageSteps
            var strongestOther = 0.0
            for (candidate in 0 until FableSolSustainedGrade.COUNT) {
                if (candidate != source) strongestOther = max(strongestOther, emission[candidate])
            }
            val contradiction = strongestOther - emission[source]
            val urgent = 0.48 * FableSolCausalStateEvidence.smoothstep(
                0.20,
                0.78,
                contradiction
            )
            var leaving = 0.0
            for (age in 0 until ageSteps) {
                val mass = posterior[sourceOffset + age]
                val hazard = (baseHazardByAge[age] * speedScale + urgent).coerceIn(0.0, 0.82)
                val staying = mass * (1.0 - hazard)
                val nextAge = if (age + 1 < ageSteps) age + 1 else age
                next[sourceOffset + nextAge] += staying
                leaving += mass * hazard
            }
            if (leaving <= 0.0) continue

            var target0 = -1
            var target1 = -1
            for (candidate in 0 until FableSolSustainedGrade.COUNT) {
                if (candidate == source) continue
                if (target0 < 0) target0 = candidate else target1 = candidate
            }
            var weight0 = transitionWeight(source, target0, emission[target0])
            var weight1 = transitionWeight(source, target1, emission[target1])
            val weightSum = weight0 + weight1
            if (weightSum <= 1e-12) continue
            weight0 /= weightSum
            weight1 /= weightSum
            next[target0 * ageSteps] += leaving * weight0
            next[target1 * ageSteps] += leaving * weight1
        }

        var total = 0.0
        for (state in 0 until FableSolSustainedGrade.COUNT) {
            val scale = emission[state] + 1e-8
            val offset = state * ageSteps
            for (age in 0 until ageSteps) {
                val index = offset + age
                next[index] *= scale
                total += next[index]
            }
        }
        if (!total.isFinite() || total <= 1e-20) {
            reset(argMax3(emission))
            return
        }
        val inverseTotal = 1.0 / total
        var calm = 0.0
        var groove = 0.0
        var peak = 0.0
        for (age in 0 until ageSteps) {
            calm += next[age] * inverseTotal
            groove += next[ageSteps + age] * inverseTotal
            peak += next[2 * ageSteps + age] * inverseTotal
        }
        val swap = posterior
        posterior = next
        next = swap
        // 归一化写回实际后验，确保下一 tick 与 Python `new / total` 完全同义。
        for (index in posterior.indices) posterior[index] *= inverseTotal

        val previous = grade
        grade = when {
            calm >= groove && calm >= peak -> FableSolSustainedGrade.CALM
            groove >= peak -> FableSolSustainedGrade.GROOVE
            else -> FableSolSustainedGrade.PEAK
        }
        if (grade == FableSolSustainedGrade.PEAK) peakEstablished = true
        calmProbability = calm
        grooveProbability = groove
        peakProbability = peak
        progress = when (grade) {
            FableSolSustainedGrade.CALM -> groove + peak
            FableSolSustainedGrade.GROOVE -> max(calm, peak)
            else -> groove + calm
        }
        if (grade != previous) {
            progress = when (grade) {
                FableSolSustainedGrade.CALM -> calm
                FableSolSustainedGrade.GROOVE -> groove
                else -> peak
            }
        }
        progress = progress.coerceIn(0.0, 1.0)
    }

    private fun transitionWeight(source: Int, target: Int, probability: Double): Double {
        if (abs(target - source) <= 1) return probability
        return probability * (0.08 + 0.92 * FableSolCausalStateEvidence.smoothstep(
            0.82,
            0.97,
            probability
        ))
    }

    private fun peakThreshold(): Double = when {
        grade == FableSolSustainedGrade.PEAK -> PEAK_HOLD
        peakEstablished -> PEAK_REENTER
        else -> PEAK_FIRST_ENTER
    }

    private fun writeDecision(output: FableSolGradeDecision): FableSolGradeDecision {
        output.grade = grade
        output.calmProbability = calmProbability
        output.grooveProbability = grooveProbability
        output.peakProbability = peakProbability
        output.transitionProgress01 = progress
        return output
    }

    companion object {
        const val UPDATE_DT = 0.10
        const val MAX_AGE_S = 16.0
        const val PEAK_FIRST_ENTER = 0.74
        const val PEAK_REENTER = 0.605
        const val PEAK_HOLD = 0.54

        fun observationProbabilities(
            drive01: Double,
            sensitivity: Double,
            peakThreshold: Double,
            output: DoubleArray
        ) {
            require(output.size >= FableSolSustainedGrade.COUNT)
            val drive = (drive01 + 0.06 * sensitivity.coerceIn(-1.0, 1.0)).coerceIn(0.0, 1.0)
            val aboveCalm = sigmoid((drive - 0.36) / 0.055)
            val aboveGroove = sigmoid((drive - peakThreshold) / 0.055)
            output[0] = 1.0 - aboveCalm
            output[1] = max(aboveCalm - aboveGroove, 1e-5)
            output[2] = aboveGroove
            val inverse = 1.0 / (output[0] + output[1] + output[2])
            output[0] *= inverse
            output[1] *= inverse
            output[2] *= inverse
        }

        private fun sigmoid(value: Double): Double {
            val bounded = value.coerceIn(-40.0, 40.0)
            return 1.0 / (1.0 + exp(-bounded))
        }

        private fun argMax3(values: DoubleArray): Int = when {
            values[0] >= values[1] && values[0] >= values[2] -> 0
            values[1] >= values[2] -> 1
            else -> 2
        }
    }
}
