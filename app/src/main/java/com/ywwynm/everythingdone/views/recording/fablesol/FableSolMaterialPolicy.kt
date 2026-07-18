package com.ywwynm.everythingdone.views.recording.fablesol

/** SDR/HDR 共用的水体材质能量边界；不包含显示能力或 HDR 峰值。 */
internal object FableSolMaterialPolicy {

    const val LAYER_COUNT = 9

    /** 公共存在度只描述层级主次；各效果族不得把它直接当作统一乘数。 */
    val COMMON_PRESENCE = floatArrayOf(1f, 0.96f, 0.84f, 0.72f, 0.60f, 0.49f, 0.36f, 0.24f, 0.16f)

    val MACRO_LIGHT_WEIGHTS = floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.64f, 0.49f, 0.32f, 0.16f, 0f)
    val MACRO_SHADOW_WEIGHTS = floatArrayOf(1f, 0.96f, 0.84f, 0.60f, 0.42f, 0.27f, 0.12f, 0.06f, 0f)
    // D154：厚度透光独立权重表（4~8 层上提一档，用户裁决），
    // 与 Python material_policy.THICKNESS_GLOW_WEIGHTS 一比一。
    val THICKNESS_GLOW_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.64f, 0.56f, 0.49f, 0.42f, 0.36f, 0.27f)
    // D156 波峰银边逐层存在度（近层重、远层近无；HDR 峰值 = 1+peakBoost×
    // weight → 第 0 层默认 3.6 = 闪点核心档；线宽另乘 0.45+0.55×weight
    // 空气透视变细），与 Python material_policy.CREST_RIM_WEIGHTS 一比一。
    // 2026-07-17 v10：中远层按用户指定值。
    val CREST_RIM_WEIGHTS = floatArrayOf(
        1f, 0.90f, 0.72f, 0.42f, 0.27f, 0.16f, 0.10f, 0.05f, 0.0129f
    )
    // 波背自阴影逐层权重（D169 恢复；与 Python material_policy 一比一）：
    // 近三层全宽，第 7/8 层归零不画。
    private val BACK_SHADE_WIDTH_WEIGHTS =
        floatArrayOf(1f, 1f, 1f, 0.84f, 0.72f, 0.60f, 0.42f, 0f, 0f)
    private val BACK_SHADE_ALPHA_WEIGHTS =
        floatArrayOf(1f, 0.75f, 0.56f, 0.42f, 0.24f, 0.16f, 0.12f, 0f, 0f)
    private val GLINT_CAPACITIES = intArrayOf(4, 4, 3, 3, 2, 2, 1, 1, 0)
    private val GLINT_LENGTH_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.64f, 0.56f, 0.49f, 0.42f, 0f)
    private val GLINT_DEPTH_LENGTH_DP =
        floatArrayOf(2.56f, 2.40f, 2.24f, 1.96f, 1.60f, 1.50f, 1.36f, 1.29f, 0f)
    private val GLINT_CORE_ALPHA_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.64f, 0.60f, 0.56f, 0.49f, 0f)
    private val GLINT_HALO_ALPHA_WEIGHTS =
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    const val GLINT_MIN_SEPARATION_DP = 34.0
    const val GLINT_FIELD_FLOOR = 0.085

    const val HALO_LENGTH_SCALE = 1.18
    const val HALO_THICKNESS_SCALE = 2.25
    const val HALO_ALPHA_SCALE = 0.0

    fun glintCapacity(layer: Int): Int = GLINT_CAPACITIES.getOrElse(layer) { 0 }

    fun backShadeWidthWeight(layer: Int): Double = at(BACK_SHADE_WIDTH_WEIGHTS, layer)

    fun backShadeAlphaWeight(layer: Int): Double = at(BACK_SHADE_ALPHA_WEIGHTS, layer)

    fun glintLengthWeight(layer: Int): Double = at(GLINT_LENGTH_WEIGHTS, layer)

    fun glintDepthLengthDp(layer: Int): Double = at(GLINT_DEPTH_LENGTH_DP, layer)

    fun glintCoreAlphaWeight(layer: Int): Double = at(GLINT_CORE_ALPHA_WEIGHTS, layer)

    fun glintHaloAlphaWeight(layer: Int): Double = at(GLINT_HALO_ALPHA_WEIGHTS, layer)

    fun macroLightWeight(depth01: Double): Double = sample(MACRO_LIGHT_WEIGHTS, depth01)

    fun macroShadowWeight(depth01: Double): Double = sample(MACRO_SHADOW_WEIGHTS, depth01)

    private fun at(values: FloatArray, layer: Int): Double =
        values.getOrElse(layer) { 0f }.toDouble()

    private fun sample(values: FloatArray, depth01: Double): Double {
        val position = depth01.coerceIn(0.0, 1.0) * (LAYER_COUNT - 1)
        val lower = position.toInt().coerceIn(0, LAYER_COUNT - 1)
        val upper = (lower + 1).coerceAtMost(LAYER_COUNT - 1)
        val fraction = position - lower
        return values[lower].toDouble() +
            (values[upper] - values[lower]).toDouble() * fraction
    }
}
