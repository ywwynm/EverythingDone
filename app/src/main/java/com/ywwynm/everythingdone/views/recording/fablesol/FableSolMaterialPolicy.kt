package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.sqrt

/** SDR/HDR 共用的水体材质能量边界；不包含显示能力或 HDR 峰值。 */
internal object FableSolMaterialPolicy {

    const val GLINT_MIN_SEPARATION_DP = 34.0
    const val GLINT_FIELD_FLOOR = 0.085

    const val HALO_LENGTH_SCALE = 1.18
    const val HALO_THICKNESS_SCALE = 2.25
    const val HALO_ALPHA_SCALE = 0.18

    /** 迎光与波峰双门控；平坡和背光坡都不能形成横贯整层的表面反射。 */
    fun surfaceBandLocality(facing: Double, crest: Double): Double {
        val facing01 = facing.coerceIn(0.0, 1.0)
        val q = ((crest.coerceIn(0.0, 1.0) - 0.10) / 0.45).coerceIn(0.0, 1.0)
        val crestGate = q * q * (3.0 - 2.0 * q)
        return facing01 * crestGate
    }

    /** D86 局部表面反射宽度：无局部波峰时为 0，近层最大约 3dp。 */
    fun surfaceBandWidthDp(facing: Double, crest: Double, depth01: Double): Double {
        val crest01 = crest.coerceIn(0.0, 1.0)
        val locality = surfaceBandLocality(facing, crest01)
        return (0.35 + 2.65 * crest01) * sqrt(locality) *
            (1.0 - 0.45 * depth01.coerceIn(0.0, 1.0))
    }

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
