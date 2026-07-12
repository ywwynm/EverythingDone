package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 连续水面纵向法线受光后的颜色约束；使用 packed ARGB，避免逐顶点对象分配。 */
object FableSolLightColorPolicy {

    const val MAX_LIGHT_SHADOW_BLACK_MIX = 0.14

    /**
     * 正向受光保留物理候选色；负向受光只取其明暗强度，颜色重新落回“基色→黑色”轴，
     * 防止天空反射差把背光坡压成灰黑。远层继续使用平方淡出。
     */
    fun resolveLongitudinal(base: Int, candidate: Int, depth01: Double): Int {
        val baseL = luminance(base)
        val candidateL = luminance(candidate)
        if (candidateL >= baseL) return candidate
        val darkness = ((baseL - candidateL) / max(baseL, 1.0)).coerceIn(0.0, 1.0)
        val blackMix = MAX_LIGHT_SHADOW_BLACK_MIX * sqrt(darkness) * depthScale(depth01)
        val remain = 1.0 - blackMix
        return ((base ushr 24) shl 24) or
            ((red(base) * remain).roundToInt().coerceIn(0, 255) shl 16) or
            ((green(base) * remain).roundToInt().coerceIn(0, 255) shl 8) or
            (blue(base) * remain).roundToInt().coerceIn(0, 255)
    }

    private fun depthScale(depth01: Double): Double =
        (1.0 - depth01.coerceIn(0.0, 1.0)).let { it * it }.coerceAtLeast(0.05)

    private fun luminance(c: Int): Double =
        0.2126 * red(c) + 0.7152 * green(c) + 0.0722 * blue(c)

    private fun red(c: Int): Int = c ushr 16 and 0xff
    private fun green(c: Int): Int = c ushr 8 and 0xff
    private fun blue(c: Int): Int = c and 0xff
}
