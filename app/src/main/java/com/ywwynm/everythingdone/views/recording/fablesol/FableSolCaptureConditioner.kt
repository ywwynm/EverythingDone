package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 固定的手机扬声器/麦克风采集域标定；参数不跟随歌曲或段落自适应。 */
class FableSolCaptureProfile(
    @JvmField val loudnessTrimDb: Double = 10.5,
    @JvmField val lowShelfGainDb: Double = 18.0,
    @JvmField val lowShelfHz: Double = 250.0,
    @JvmField val stateGradeTrim01: Double = -0.01
) {
    val boundedLoudnessTrimDb: Double get() = loudnessTrimDb.coerceIn(-12.0, 12.0)
    val boundedLowShelfGainDb: Double get() = lowShelfGainDb.coerceIn(0.0, 18.0)
    val boundedStateGradeTrim01: Double get() = stateGradeTrim01.coerceIn(-0.08, 0.08)

    companion object {
        @JvmField val PHONE_CAPTURE_V1 = FableSolCaptureProfile()
    }
}

/**
 * 无分配、流式 RBJ low-shelf。滤波结果只进入 K 计权特征、频谱与运动链；A 计权安全门始终读取原始 PCM。
 */
class FableSolCaptureConditioner(profile: FableSolCaptureProfile, sampleRate: Int) {

    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val gainDb = profile.boundedLowShelfGainDb
        if (kotlin.math.abs(gainDb) < 1e-9) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
        } else {
            val sr = sampleRate.coerceAtLeast(1).toDouble()
            val frequency = profile.lowShelfHz.coerceIn(20.0, 0.45 * sr)
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sr
            val cosW0 = cos(w0)
            val alpha = sin(w0) / sqrt(2.0)
            val sqrtA = sqrt(a)
            val rawB0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
            val rawB1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
            val rawB2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
            val rawA0 = (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha
            val rawA1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
            val rawA2 = (a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha
            b0 = rawB0 / rawA0
            b1 = rawB1 / rawA0
            b2 = rawB2 / rawA0
            a1 = rawA1 / rawA0
            a2 = rawA2 / rawA0
        }
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(input: DoubleArray, inputOffset: Int, length: Int,
                output: DoubleArray, outputOffset: Int = 0) {
        val n = min(length, min(input.size - inputOffset, output.size - outputOffset)).coerceAtLeast(0)
        for (i in 0 until n) {
            val x = input[inputOffset + i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            output[outputOffset + i] = y
        }
    }
}
