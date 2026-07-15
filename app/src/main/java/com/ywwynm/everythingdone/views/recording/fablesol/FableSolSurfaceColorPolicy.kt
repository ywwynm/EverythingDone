package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.roundToInt

/**
 * 局部表面反射带的颜色路径：先采样当前位置的四停靠点主体色，再沿该样本的
 * OKLab 固定 hue 方向提亮。密集色带供 Canvas 与 GL 共用，避免互补渐变被单一中间色覆盖。
 */
internal object FableSolSurfaceColorPolicy {
    const val REFLECTION_DELTA_L = 0.045

    @JvmField
    val RAMP_POSITIONS = doubleArrayOf(
        0.00, 0.06, 0.12, 0.18, 0.24,
        0.30, 0.36, 0.42, 0.48, 0.54, 0.60,
        0.68, 0.76, 0.84, 0.92, 1.00
    )

    @JvmField
    val RAMP_POSITIONS_FLOAT = FloatArray(RAMP_POSITIONS.size) { RAMP_POSITIONS[it].toFloat() }

    fun bodyAt(start: IntArray, stop1: IntArray, stop2: IntArray, end: IntArray,
               qIn: Double): IntArray {
        val q = qIn.coerceIn(0.0, 1.0)
        return when {
            q <= 0.24 -> FableSolColor.mix(start, stop1, q / 0.24)
            q <= 0.60 -> FableSolColor.mix(stop1, stop2, (q - 0.24) / 0.36)
            else -> FableSolColor.mix(stop2, end, (q - 0.60) / 0.40)
        }
    }

    fun reflectionAt(start: IntArray, stop1: IntArray, stop2: IntArray, end: IntArray,
                     q: Double): IntArray = FableSolColor.lightenOklab(
        bodyAt(start, stop1, stop2, end, q),
        REFLECTION_DELTA_L
    )

    fun reflectionRamp(start: IntArray, stop1: IntArray, stop2: IntArray,
                       end: IntArray): Array<IntArray> =
        Array(RAMP_POSITIONS.size) { index ->
            reflectionAt(start, stop1, stop2, end, RAMP_POSITIONS[index])
        }

    fun sampleRampInto(ramp: Array<IntArray>, qIn: Double, target: IntArray) {
        require(ramp.size == RAMP_POSITIONS.size)
        val q = qIn.coerceIn(0.0, 1.0)
        var upper = 1
        while (upper < RAMP_POSITIONS.size - 1 && q > RAMP_POSITIONS[upper]) upper++
        val lower = upper - 1
        val span = RAMP_POSITIONS[upper] - RAMP_POSITIONS[lower]
        val fraction = if (span <= 1e-9) 0.0 else
            (q - RAMP_POSITIONS[lower]) / span
        for (channel in 0 until 3) {
            target[channel] = (ramp[lower][channel] +
                (ramp[upper][channel] - ramp[lower][channel]) * fraction).roundToInt()
        }
    }
}
