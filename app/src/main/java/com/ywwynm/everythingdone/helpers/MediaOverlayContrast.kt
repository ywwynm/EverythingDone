package com.ywwynm.everythingdone.helpers

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 局部黑白选择的纯计算部分；查表线性化，最多处理调用方给出的少量图形样点。 */
object MediaOverlayContrast {
    data class Sample(val background: Int, val underInk: Int = background,
                      val alpha: Int = 196, val weight: Double = 1.0)
    data class Result(val dark: Boolean, val whiteCoverage: Double, val blackCoverage: Double)

    private val linear = DoubleArray(256) { index ->
        val c = index / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Int): Double =
        .2126 * linear[color ushr 16 and 255] + .7152 * linear[color ushr 8 and 255] +
            .0722 * linear[color and 255]

    fun contrast(first: Int, second: Int): Double {
        val a = luminance(first) + .05
        val b = luminance(second) + .05
        return max(a, b) / min(a, b)
    }

    /** 用与 ARGB_8888 相同的编码通道混合，保留原图标有效 alpha。 */
    fun composite(ink: Int, background: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        fun channel(shift: Int): Int =
            (((ink ushr shift and 255) * a + (background ushr shift and 255) * (255 - a) + 127) / 255)
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun choose(samples: List<Sample>, minimumContrast: Double = 3.0): Result {
        var whiteCoverage = 0.0
        var blackCoverage = 0.0
        var whiteLog = 0.0
        var blackLog = 0.0
        var total = 0.0
        for (sample in samples) {
            if (sample.weight <= 0.0 || sample.alpha <= 0) continue
            val white = contrast(composite(-1, sample.underInk, sample.alpha), sample.background)
            val black = contrast(composite(0xff000000.toInt(), sample.underInk, sample.alpha), sample.background)
            val w = sample.weight
            total += w
            if (white >= minimumContrast) whiteCoverage += w
            if (black >= minimumContrast) blackCoverage += w
            whiteLog += w * ln(white)
            blackLog += w * ln(black)
        }
        if (total == 0.0) return Result(false, 0.0, 0.0)
        val difference = (blackCoverage - whiteCoverage) / total
        val dark = if (abs(difference) > 1e-9) difference > 0.0
            else (blackLog - whiteLog) / total > 1e-9
        return Result(dark, whiteCoverage / total, blackCoverage / total)
    }
}
