package com.ywwynm.everythingdone.views.recording.fablesol

/** FableSol 显式阴影颜色策略；几何强度仍由各渲染路径自行控制。 */
object FableSolShadowColorPolicy {
    const val BACK_DARKEN_L = 0.10

    /**
     * 波背只**降低当前层记事色的明度、保住色相与彩度**（darkenOklab），得到"更深的记事色"，
     * 而不是向黑混（同时掉明度和彩度=发黑发脏）。远层衰减不变。
     */
    fun backShade(base: IntArray, @Suppress("UNUSED_PARAMETER") hueTemperatureDeg: Double,
                  depth01: Double): IntArray =
        FableSolColor.darkenOklab(base, BACK_DARKEN_L * depthScale(depth01))

    private fun depthScale(depth01: Double): Double =
        (1.0 - depth01.coerceIn(0.0, 1.0)).let { it * it }.coerceAtLeast(0.05)
}
