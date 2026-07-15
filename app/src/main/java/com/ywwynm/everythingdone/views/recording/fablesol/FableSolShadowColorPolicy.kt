package com.ywwynm.everythingdone.views.recording.fablesol

/** FableSol 显式随层保色阴影；几何强度仍由各渲染路径自行控制。 */
object FableSolShadowColorPolicy {
    const val BACK_DARKEN_L = 0.10
    const val MACRO_DARKEN_L = 0.10

    /**
     * 从当前位置已经应用景深阶梯和横向渐变的主体色降低 OKLab 明度。
     * 不混黑、不旋转色相；sRGB 无法容纳原彩度时才沿相同色相压缩彩度。
     */
    fun shade(base: IntArray, lightnessDrop: Double): IntArray =
        FableSolColor.darkenOklab(base, lightnessDrop.coerceAtLeast(0.0))

    /** 宏观坡面阴影目标；调用方必须传入当前层当前横向停靠点的主体色。 */
    fun macroShade(base: IntArray): IntArray = shade(base, MACRO_DARKEN_L)

    /**
     * 波背阴影保持旧签名，深度只控制绘制范围/透明度，不改变阴影色本身。
     */
    fun backShade(
        base: IntArray,
        @Suppress("UNUSED_PARAMETER") hueTemperatureDeg: Double,
        @Suppress("UNUSED_PARAMETER") depth01: Double
    ): IntArray = shade(base, BACK_DARKEN_L)
}
