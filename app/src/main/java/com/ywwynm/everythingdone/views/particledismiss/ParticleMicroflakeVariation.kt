package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.PI
import kotlin.math.sin

/** 一次关闭固定一组平滑变换，释放和速度共同使用，不能逐帧重新抽样。 */
internal class ParticleMicroflakeVariation private constructor(val seed: Long, val values: FloatArray) {
    private val v = DoubleArray(values.size) { values[it].toDouble() }
    // 同一次关闭的时钟固定。小表提供初值，再作一次牛顿迭代，避免每片重复四次求逆。
    internal val inverseSamples = DoubleArray(1025) { solveInverseTime(it / 1024.0) }

    fun sampleX(x: Double, y: Double): Double = .5 + v[0] * x + v[2] * y + v[4] + v[6] * sin(v[9] * y + v[10])
    fun sampleY(x: Double, y: Double): Double = .5 + v[1] * y + v[3] * x + v[5] + v[7] * sin(v[8] * x + v[11])
    fun localDelay(x: Double, y: Double): Double = v[14] * sin(3.2 * x + v[10]) + v[15] * sin(3.6 * y + v[11])

    fun time(t: Double): Double = t + t * (1 - t) * (v[12] + v[13] * (2 * t - 1))
    fun rate(t: Double): Double = 1 + v[12] * (1 - 2 * t) + v[13] * (-6 * t * t + 6 * t - 1)

    fun inverseTime(value: Double): Double {
        val position = value.coerceIn(0.0, 1.0) * 1024
        val index = position.toInt().coerceAtMost(1023)
        val fraction = position - index
        val t = inverseSamples[index] * (1 - fraction) + inverseSamples[index + 1] * fraction
        return (t - (time(t) - value) / rate(t)).coerceIn(0.0, 1.0)
    }

    private fun solveInverseTime(value: Double): Double {
        var t = value
        repeat(4) { t = (t - (time(t) - value) / rate(t)).coerceIn(0.0, 1.0) }
        return t
    }

    companion object {
        fun fromSeed(seed: Long, rules: ParticleMicroflakeRules): ParticleMicroflakeVariation {
            val salt = (seed xor (seed ushr 32)).toInt() xor 0x6a09e667
            val r = DoubleArray(20) { ParticleMicroflakeModel.randomValue(it, salt).toDouble() }
            val values = doubleArrayOf(
                1 + (r[0] * 2 - 1) * rules.decimal("variation_stretch"),
                1 + (r[1] * 2 - 1) * rules.decimal("variation_stretch"),
                (r[2] * 2 - 1) * rules.decimal("variation_shear"),
                (r[3] * 2 - 1) * rules.decimal("variation_shear"),
                (r[4] * 2 - 1) * rules.decimal("variation_offset"),
                (r[5] * 2 - 1) * rules.decimal("variation_offset") * .65,
                (.45 + .55 * r[6]) * rules.decimal("variation_bend"),
                (.45 + .55 * r[7]) * rules.decimal("variation_bend"),
                3.6 + 2.1 * r[8], 3.6 + 2.1 * r[9], 2 * PI * r[10], 2 * PI * r[11],
                (r[12] * 2 - 1) * rules.decimal("variation_clock"),
                (r[13] * 2 - 1) * rules.decimal("variation_clock_shape"),
                (r[14] * 2 - 1) * rules.decimal("variation_release_delay"),
                (r[15] * 2 - 1) * rules.decimal("variation_release_delay")
            )
            val coherenceSalt = (seed xor (seed ushr 32)).toInt() xor 0x510e527f
            val t = ((ParticleMicroflakeModel.randomValue(3, coherenceSalt).toDouble() - .30) / .55).coerceIn(0.0, 1.0)
            val strength = .08 + .92 * t * t * (3 - 2 * t)
            for (index in 0..1) values[index] = 1 + (values[index] - 1) * strength
            for (index in 2..7) values[index] *= strength
            for (index in 12..15) values[index] *= strength
            if (r[16] < .5) for (index in intArrayOf(0, 2, 4, 6)) values[index] = -values[index]
            return ParticleMicroflakeVariation(seed, FloatArray(values.size) { values[it].toFloat() })
        }
    }
}
