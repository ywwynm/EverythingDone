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
    data class Group(val samples: List<Sample>, val minimumContrast: Double = 3.0)
    data class Result(val dark: Boolean, val whiteCoverage: Double, val blackCoverage: Double,
                      val logContrastAdvantage: Double = 0.0) {
        /** 滚动临界区域保持原色；覆盖率差 6% 或同等覆盖下对比度差 12% 才换色。 */
        fun withHysteresis(previousDark: Boolean?): Boolean {
            if (previousDark == null || previousDark == dark) return dark
            val coverage = blackCoverage - whiteCoverage
            if (abs(coverage) > 1e-9) return if (abs(coverage) >= .06) dark else previousDark
            return if (abs(logContrastAdvantage) >= ln(1.12)) dark else previousDark
        }
    }

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

    /** 每组对应一个按钮，有效样点内部归一化后共同评分，图形大小不改变按钮权重。 */
    fun chooseEqualGroups(groups: List<List<Sample>>, minimumContrast: Double = 3.0): Result =
        chooseGroups(groups.map { Group(it, minimumContrast) })

    /** 各元素等权，文字与普通图形可保留不同的参考对比度。 */
    fun chooseGroups(groups: List<Group>): Result {
        val results = groups.filter { group -> group.samples.any { it.alpha > 0 && it.weight > 0.0 } }
            .map { choose(it.samples, it.minimumContrast) }
        if (results.isEmpty()) return Result(false, 0.0, 0.0)
        val whiteCoverage = results.sumOf { it.whiteCoverage } / results.size
        val blackCoverage = results.sumOf { it.blackCoverage } / results.size
        val advantage = results.sumOf { it.logContrastAdvantage } / results.size
        val difference = blackCoverage - whiteCoverage
        return Result(if (abs(difference) > 1e-9) difference > 0.0 else advantage > 1e-9,
            whiteCoverage, blackCoverage, advantage)
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
        return Result(dark, whiteCoverage / total, blackCoverage / total,
            (blackLog - whiteLog) / total)
    }
}
