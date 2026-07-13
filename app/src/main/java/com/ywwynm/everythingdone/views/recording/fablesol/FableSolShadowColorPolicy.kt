package com.ywwynm.everythingdone.views.recording.fablesol

/** FableSol 显式阴影颜色策略；几何强度仍由各渲染路径自行控制。 */
object FableSolShadowColorPolicy {
    const val BACK_BLACK_MIX = 0.18

    private val black = intArrayOf(0, 0, 0)

    /** 波背只降低当前层记事色，不再额外冷偏或向固定灰黑色漂移。 */
    fun backShade(base: IntArray, @Suppress("UNUSED_PARAMETER") hueTemperatureDeg: Double,
                  depth01: Double): IntArray =
        FableSolColor.mixOklab(base, black, BACK_BLACK_MIX * depthScale(depth01))

    private fun depthScale(depth01: Double): Double =
        (1.0 - depth01.coerceIn(0.0, 1.0)).let { it * it }.coerceAtLeast(0.05)
}
