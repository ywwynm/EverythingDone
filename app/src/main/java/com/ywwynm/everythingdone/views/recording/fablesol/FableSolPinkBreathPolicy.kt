package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.sin

/**
 * 无状态、确定性的 1/f 慢呼吸。只调制持续运动与实体出生节奏，不读取瞬态音频。
 */
internal object FableSolPinkBreathPolicy {
    private val tauSeconds = doubleArrayOf(0.9, 3.7, 14.0, 55.0)

    fun value01(timeSeconds: Double, seed: Double): Double {
        var total = 0.0
        var weightSum = 0.0
        for (index in tauSeconds.indices) {
            val weight = 1.0 / (index + 1.0)
            val phase = timeSeconds / tauSeconds[index] + seed * (7.31 + index)
            val base = Math.floor(phase)
            var fraction = phase - base
            fraction = fraction * fraction * (3.0 - 2.0 * fraction)
            val salt = seed + index * 3.7
            total += weight * (hash01(base, salt) +
                (hash01(base + 1.0, salt) - hash01(base, salt)) * fraction)
            weightSum += weight
        }
        return (total / weightSum).coerceIn(0.0, 1.0)
    }

    fun waveAmplitudeGain(timeSeconds: Double, pinkMod: Double, strength: Double): Double =
        1.0 + 0.055 * pinkMod * strength * (2.0 * value01(timeSeconds, 23.7) - 1.0)

    fun packetCadenceRate(timeSeconds: Double, pinkMod: Double, strength: Double): Double =
        1.0 + 0.22 * pinkMod * strength * (2.0 * value01(timeSeconds, 41.2) - 1.0)

    fun glintBirthRate(timeSeconds: Double, pinkMod: Double, strength: Double): Double =
        1.0 + 0.35 * pinkMod * strength * (2.0 * value01(timeSeconds, 61.9) - 1.0)

    private fun hash01(a: Double, b: Double): Double {
        val value = sin(a * 127.1 + b * 311.7) * 43758.5453
        return value - Math.floor(value)
    }
}
