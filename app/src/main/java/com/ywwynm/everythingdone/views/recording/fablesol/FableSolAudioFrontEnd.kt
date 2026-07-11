package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** FableSol A1 前端使用的定长 O(1) 滑动均值/方差环。 */
internal class FableSolRunningMeanRing(capacity: Int) {
    private val values = DoubleArray(max(capacity, 1))
    private var index = 0
    var count = 0
        private set
    private var sum = 0.0
    private var sumSq = 0.0

    fun reset() {
        values.fill(0.0)
        index = 0
        count = 0
        sum = 0.0
        sumSq = 0.0
    }

    fun push(value: Double) {
        val old = values[index]
        if (count == values.size) {
            sum -= old
            sumSq -= old * old
        } else {
            count++
        }
        values[index] = value
        sum += value
        sumSq += value * value
        index = (index + 1) % values.size
    }

    fun mean(): Double = if (count == 0) 0.0 else max(sum / count, 0.0)

    fun std(): Double {
        if (count < 2) return 0.0
        val mean = sum / count
        return sqrt(max(sumSq / count - mean * mean, 0.0))
    }
}

/** 转置直接 II 型双二阶；用于帧率域 4Hz 包络带通。 */
internal class FableSolBiquad(b: DoubleArray, a: DoubleArray) {
    private val b0 = b[0] / a[0]
    private val b1 = b[1] / a[0]
    private val b2 = b[2] / a[0]
    private val a1 = a[1] / a[0]
    private val a2 = a[2] / a[0]
    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(value: Double): Double {
        val out = b0 * value + z1
        z1 = b1 * value - a1 * out + z2
        z2 = b2 * value - a2 * out
        return out
    }
}

internal object FableSolAudioFrontEnd {
    private val K_SHELF_B_48K = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
    private val K_SHELF_A_48K = doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585)
    private val K_HP_B_48K = doubleArrayOf(1.0, -2.0, 1.0)
    private val K_HP_A_48K = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621)

    fun bandPass(fc: Double, q: Double, sampleRate: Double): FableSolBiquad {
        val w0 = 2.0 * PI * fc / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        return FableSolBiquad(
            doubleArrayOf(alpha, 0.0, -alpha),
            doubleArrayOf(1.0 + alpha, -2.0 * cos(w0), 1.0 - alpha)
        )
    }

    /** BS.1770-4 K 计权功率频响，48kHz 系数重映射到实际采样率。 */
    fun kWeightPower(freqs: DoubleArray, sampleRate: Double): DoubleArray {
        val (shelfB, shelfA) = rebilinear(K_SHELF_B_48K, K_SHELF_A_48K, sampleRate)
        val (hpB, hpA) = rebilinear(K_HP_B_48K, K_HP_A_48K, sampleRate)
        return DoubleArray(freqs.size) { i ->
            powerResponse(freqs[i], sampleRate, shelfB, shelfA) *
                    powerResponse(freqs[i], sampleRate, hpB, hpA)
        }
    }

    private fun rebilinear(b48: DoubleArray, a48: DoubleArray, sampleRate: Double): Pair<DoubleArray, DoubleArray> {
        val k1 = 2.0 * 48000.0
        val bs = doubleArrayOf(
            b48[0] - b48[1] + b48[2],
            2.0 * k1 * (b48[0] - b48[2]),
            k1 * k1 * (b48[0] + b48[1] + b48[2])
        )
        val as0 = doubleArrayOf(
            a48[0] - a48[1] + a48[2],
            2.0 * k1 * (a48[0] - a48[2]),
            k1 * k1 * (a48[0] + a48[1] + a48[2])
        )
        val k2 = 2.0 * sampleRate
        fun forward(v: DoubleArray) = doubleArrayOf(
            v[0] * k2 * k2 + v[1] * k2 + v[2],
            2.0 * (v[2] - v[0] * k2 * k2),
            v[0] * k2 * k2 - v[1] * k2 + v[2]
        )
        return forward(bs) to forward(as0)
    }

    private fun powerResponse(freq: Double, sampleRate: Double, b: DoubleArray, a: DoubleArray): Double {
        val w = 2.0 * PI * freq / sampleRate
        val c1 = cos(w)
        val s1 = -sin(w)
        val c2 = cos(2.0 * w)
        val s2 = -sin(2.0 * w)
        val nr = b[0] + b[1] * c1 + b[2] * c2
        val ni = b[1] * s1 + b[2] * s2
        val dr = a[0] + a[1] * c1 + a[2] * c2
        val di = a[1] * s1 + a[2] * s2
        return (nr * nr + ni * ni) / max(dr * dr + di * di, 1e-30)
    }

    fun highPassPole(hz: Double, frameRate: Double): Double = exp(-2.0 * PI * hz / frameRate)
}
