package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.sqrt

/** SDR/HDR 共用的水体材质能量边界；不包含显示能力或 HDR 峰值。 */
internal object FableSolMaterialPolicy {

    const val GLINT_MIN_SEPARATION_DP = 34.0
    const val GLINT_FIELD_FLOOR = 0.085

    const val HALO_LENGTH_SCALE = 1.18
    const val HALO_THICKNESS_SCALE = 2.25
    const val HALO_ALPHA_SCALE = 0.18

    /** 与 Debug 202607130749 一致的连续表面反射宽度，近层最大约 9.4dp。 */
    fun surfaceBandWidthDp(facing: Double, crest: Double, depth01: Double): Double =
        (1.2 + (5.8 + 2.4 * crest.coerceIn(0.0, 1.0)) * facing.coerceIn(0.0, 1.0)) *
            (1.0 - 0.45 * depth01.coerceIn(0.0, 1.0))

    /** 与 Debug 202607130749 一致的薄峰透射宽度，满强度最大约 11dp。 */
    fun thinGlowThicknessDp(signal: Double): Double {
        val value = signal.coerceIn(0.0, 1.0)
        return (1.6 + 9.4 * value) * sqrt(value)
    }

    /** 增加窄闪点数量，但只覆盖近、中六层。 */
    fun glintCapacity(layer: Int): Int = when (layer) {
        0, 1 -> 4
        in 2..5 -> 3
        else -> 0
    }
}
