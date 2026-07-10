package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.cos
import kotlin.math.sin

/**
 * 迭代 radix-2 Cooley-Tukey FFT（就地）。用于替代 np.fft.rfft：对实信号补零虚部做全复变换，
 * 调用方取前 N/2+1 个 bin 的功率 re²+im²。要求 size 为 2 的幂（N_FFT=2048）。
 */
object FableSolFft {

    fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            var i = 0
            while (i < n) {
                var wReal = 1.0
                var wImag = 0.0
                val half = len / 2
                for (offset in 0 until half) {
                    val even = i + offset
                    val odd = even + half
                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                i += len
            }
            len = len shl 1
        }
    }
}
