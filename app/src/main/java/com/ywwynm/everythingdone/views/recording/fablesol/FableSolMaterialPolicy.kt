package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.sqrt

/** SDR/HDR 共用的水体材质能量边界；不包含显示能力或 HDR 峰值。 */
internal object FableSolMaterialPolicy {

    const val LAYER_COUNT = 9

    /** 公共存在度只描述层级主次；各效果族不得把它直接当作统一乘数。 */
    val COMMON_PRESENCE = floatArrayOf(1f, 0.96f, 0.84f, 0.72f, 0.60f, 0.49f, 0.36f, 0.24f, 0.16f)

    val MACRO_LIGHT_WEIGHTS = floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.64f, 0.49f, 0.32f, 0.16f, 0f)
    val MACRO_SHADOW_WEIGHTS = floatArrayOf(1f, 0.96f, 0.84f, 0.60f, 0.42f, 0.27f, 0.12f, 0.06f, 0f)
    val MICRO_NORMAL_WEIGHTS = floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.72f, 0.64f, 0.60f, 0.56f, 0.49f)
    val SDR_SSS_WEIGHTS = floatArrayOf(1f, 0.96f, 0.84f, 0.64f, 0.49f, 0.36f, 0.24f, 0.16f, 0.12f)
    // D154：厚度透光独立权重表（4~8 层较 SDR_SSS 上提一档，用户裁决），
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
    private val SURFACE_BAND_WIDTH_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.72f, 0.64f, 0.60f, 0.56f, 0f)
    private val SURFACE_BAND_ALPHA_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.64f, 0.49f, 0.32f, 0.16f, 0.06f, 0f)
    private val BACK_SHADE_WIDTH_WEIGHTS =
        floatArrayOf(1f, 1f, 1f, 0.84f, 0.72f, 0.60f, 0.42f, 0f, 0f)
    private val BACK_SHADE_ALPHA_WEIGHTS =
        floatArrayOf(1f, 0.75f, 0.56f, 0.42f, 0.24f, 0.16f, 0.12f, 0f, 0f)
    private val GLINT_CAPACITIES = intArrayOf(4, 4, 3, 3, 2, 2, 1, 1, 0)
    private val GLINT_BIRTH_WEIGHTS =
        floatArrayOf(4.2f, 3.6f, 2.4f, 1.92f, 0.96f, 0.6f, 0.36f, 0.16f, 0f)
    private val GLINT_LENGTH_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.64f, 0.56f, 0.49f, 0.42f, 0f)
    private val GLINT_DEPTH_LENGTH_DP =
        floatArrayOf(2.56f, 2.40f, 2.24f, 1.96f, 1.60f, 1.50f, 1.36f, 1.29f, 0f)
    private val GLINT_CORE_ALPHA_WEIGHTS =
        floatArrayOf(1f, 0.96f, 0.84f, 0.75f, 0.64f, 0.60f, 0.56f, 0.49f, 0f)
    private val GLINT_HALO_ALPHA_WEIGHTS =
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val FLOW_STREAK_CAPACITIES = intArrayOf(3, 2, 2, 1, 1, 0, 0, 0, 0)
    private val FLOW_STREAK_WEIGHTS = floatArrayOf(1f, 1f, 1f, 0.45f, 0.20f, 0f, 0f, 0f, 0f)

    // 第 0～2 层保留既有 depth/0.34 结果；第 3/4 层为旧第 2 层的 45%/18%。
    private val CREST_VEIL_SOURCE_WEIGHTS = floatArrayOf(
        1f,
        (1.0 - 0.125 / 0.34).toFloat(),
        (1.0 - 0.250 / 0.34).toFloat(),
        ((1.0 - 0.250 / 0.34) * 0.45).toFloat(),
        ((1.0 - 0.250 / 0.34) * 0.18).toFloat(),
        0f, 0f, 0f, 0f
    )

    const val GLINT_MIN_SEPARATION_DP = 34.0
    const val GLINT_FIELD_FLOOR = 0.085
    const val GLINT_BIRTH_WEIGHT_TOTAL = 14.20

    const val HALO_LENGTH_SCALE = 1.18
    const val HALO_THICKNESS_SCALE = 2.25
    const val HALO_ALPHA_SCALE = 0.0

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
            sample(SURFACE_BAND_WIDTH_WEIGHTS, depth01)
    }

    fun surfaceBandAlphaWeight(layer: Int): Double = at(SURFACE_BAND_ALPHA_WEIGHTS, layer)

    fun backShadeWidthWeight(layer: Int): Double = at(BACK_SHADE_WIDTH_WEIGHTS, layer)

    fun backShadeAlphaWeight(layer: Int): Double = at(BACK_SHADE_ALPHA_WEIGHTS, layer)

    /** 与 Debug 202607130749 一致的薄峰透射宽度，满强度最大约 11dp。 */
    fun thinGlowThicknessDp(signal: Double): Double {
        val value = signal.coerceIn(0.0, 1.0)
        return (1.6 + 9.4 * value) * sqrt(value)
    }

    fun glintCapacity(layer: Int): Int = GLINT_CAPACITIES.getOrElse(layer) { 0 }

    fun glintBirthWeight(layer: Int): Double = at(GLINT_BIRTH_WEIGHTS, layer)

    fun glintLengthWeight(layer: Int): Double = at(GLINT_LENGTH_WEIGHTS, layer)

    fun glintDepthLengthDp(layer: Int): Double = at(GLINT_DEPTH_LENGTH_DP, layer)

    fun glintCoreAlphaWeight(layer: Int): Double = at(GLINT_CORE_ALPHA_WEIGHTS, layer)

    fun glintHaloAlphaWeight(layer: Int): Double = at(GLINT_HALO_ALPHA_WEIGHTS, layer)

    fun flowStreakCapacity(layer: Int): Int = FLOW_STREAK_CAPACITIES.getOrElse(layer) { 0 }

    fun flowStreakWeight(layer: Int): Double = at(FLOW_STREAK_WEIGHTS, layer)

    fun crestVeilSourceWeight(layer: Int): Double = at(CREST_VEIL_SOURCE_WEIGHTS, layer)

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
