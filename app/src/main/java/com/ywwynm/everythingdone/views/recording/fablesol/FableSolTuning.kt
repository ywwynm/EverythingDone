package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.ywwynm.everythingdone.R
import kotlin.math.abs

/**
 * FableSol 调参持久层 + 可调参数目录（设置界面"音频海浪动画参数调节"Dialog 使用）。
 *
 * - 目录只收录 Android 渲染链路（GlRenderer / GlOptics / Simulation / FeatureMapper /
 *   ContinuousSurface）实际消费、且在 [FableSolParams] 注册过的标量参数；范围、
 *   步长与 Python 模拟器 params.py 的 GUI 规格同源，标签为多语言字符串资源
 *   （fablesol_group_* / fablesol_param_*，中文文案与 Python GUI 一致）。
 * - 存储在独立 SharedPreferences 文件里，只存偏离默认值的键；未存储的键永远走
 *   [FableSolParams] 默认值，单元测试直接 new FableSolParams() 不受影响。
 * - HDR 开关也存这里（默认开）；真正能否生效仍由设备能力与录音态门控。
 */
object FableSolTuning {

    /** 单个可调参数的 UI 规格。[boolLike] 为真时渲染为开关（0/1）。 */
    class Spec(
        val key: String,
        @StringRes val labelRes: Int,
        val unit: String,
        val lo: Double,
        val hi: Double,
        val step: Double,
        val boolLike: Boolean = false
    )

    class Group(@StringRes val titleRes: Int, val specs: List<Spec>)

    val GROUPS: List<Group> = listOf(
        Group(R.string.fablesol_group_texture, listOf(
            Spec("uplift_thick_glow", R.string.fablesol_param_uplift_thick_glow, "", 0.0, 1.5, 0.02),
            Spec("uplift_glow_boost", R.string.fablesol_param_uplift_glow_boost, "×", 1.0, 1.6, 0.01),
            Spec("uplift_crest_rim", R.string.fablesol_param_uplift_crest_rim, "", 0.0, 1.6, 0.02),
            Spec("uplift_rim_width", R.string.fablesol_param_uplift_rim_width, "dp", 0.3, 2.0, 0.05),
            Spec("uplift_rim_halo", R.string.fablesol_param_uplift_rim_halo, "", 0.0, 0.5, 0.01),
            Spec("uplift_rim_peak", R.string.fablesol_param_uplift_rim_peak, "×", 1.0, 3.6, 0.05),
            Spec("uplift_rim_slide", R.string.fablesol_param_uplift_rim_slide, "", 0.0, 1.0, 0.05),
            Spec("glint_capacity_gain", R.string.fablesol_param_glint_capacity_gain, "×", 0.0, 1.0, 0.05)
        )),
        Group(R.string.fablesol_group_appearance, listOf(
            Spec("lighten_far", R.string.fablesol_param_lighten_far, "", 0.0, 0.864, 0.01),
            Spec("environment_tint", R.string.fablesol_param_environment_tint, "", 0.0, 0.5, 0.02),
            Spec("sky_reflection_strength", R.string.fablesol_param_sky_reflection_strength, "", 0.0, 1.2, 0.02),
            Spec("body_light_strength", R.string.fablesol_param_body_light_strength, "", 0.0, 1.2, 0.02),
            Spec("light_azimuth_deg", R.string.fablesol_param_light_azimuth_deg, "°", -60.0, 60.0, 1.0),
            Spec("back_shade_gain", R.string.fablesol_param_back_shade_gain, "", 0.0, 1.2, 0.02),
            Spec("color_breath", R.string.fablesol_param_color_breath, "", 0.0, 1.5, 0.02),
            Spec("hue_temp_deg", R.string.fablesol_param_hue_temp_deg, "°", -30.0, 30.0, 1.0)
        )),
        Group(R.string.fablesol_group_surface, listOf(
            Spec("surface_heading_deg", R.string.fablesol_param_surface_heading_deg, "°", 6.0, 42.0, 1.0),
            Spec("surface_spread_deg", R.string.fablesol_param_surface_spread_deg, "°", 4.0, 48.0, 1.0),
            Spec("surface_decay_dp", R.string.fablesol_param_surface_decay_dp, "dp", 80.0, 720.0, 10.0),
            Spec("surface_view_elev_deg", R.string.fablesol_param_surface_view_elev_deg, "°", 26.0, 54.0, 1.0)
        )),
        Group(R.string.fablesol_group_ambient_flow, listOf(
            Spec("ambient_gain", R.string.fablesol_param_ambient_gain, "×", 0.0, 3.0, 0.01),
            Spec("ambient_breath", R.string.fablesol_param_ambient_breath, "", 0.0, 0.6, 0.01),
            Spec("idle_flow_ratio", R.string.fablesol_param_idle_flow_ratio, "", 0.0, 1.0, 0.01),
            Spec("flow_gain", R.string.fablesol_param_flow_gain, "×", 0.0, 3.0, 0.01),
            Spec("flow_curve", R.string.fablesol_param_flow_curve, "", 0.5, 2.0, 0.05),
            Spec("flow_smooth_s", R.string.fablesol_param_flow_smooth_s, "s", 0.2, 5.0, 0.01),
            Spec("wander_gain", R.string.fablesol_param_wander_gain, "×", 0.0, 2.0, 0.01),
            Spec("wall_soft", R.string.fablesol_param_wall_soft, "", 0.0, 1.0, 0.02),
            Spec("tilt_calm", R.string.fablesol_param_tilt_calm, "", 0.0, 1.0, 0.02)
        )),
        Group(R.string.fablesol_group_hero, listOf(
            Spec("hero_gain", R.string.fablesol_param_hero_gain, "×", 0.0, 2.0, 0.01),
            Spec("hero_len_dp", R.string.fablesol_param_hero_len_dp, "dp", 160.0, 640.0, 1.0),
            Spec("hero_attack_s", R.string.fablesol_param_hero_attack_s, "s", 0.02, 1.50, 0.01),
            Spec("hero_release_s", R.string.fablesol_param_hero_release_s, "s", 0.10, 3.00, 0.01),
            Spec("hero_punch", R.string.fablesol_param_hero_punch, "", 0.0, 2.0, 0.02),
            Spec("hero_punch_decay_s", R.string.fablesol_param_hero_punch_decay_s, "s", 0.05, 1.5, 0.01),
            Spec("hero_breath", R.string.fablesol_param_hero_breath, "", 0.0, 0.9, 0.02),
            Spec("beat_gain", R.string.fablesol_param_beat_gain, "×", 0.0, 2.0, 0.02)
        )),
        Group(R.string.fablesol_group_swell, listOf(
            Spec("swell_presmooth_s", R.string.fablesol_param_swell_presmooth_s, "s", 0.10, 2.00, 0.01),
            Spec("swell_presmooth_release_s", R.string.fablesol_param_swell_presmooth_release_s, "s", 0.20, 4.00, 0.05),
            Spec("swell_halflife_s", R.string.fablesol_param_swell_halflife_s, "s", 0.5, 10.0, 0.5),
            Spec("deep_integral_s", R.string.fablesol_param_deep_integral_s, "s", 1.0, 60.0, 1.0),
            Spec("swell_deadband_pct", R.string.fablesol_param_swell_deadband_pct, "%", 0.0, 15.0, 0.5),
            Spec("swell_attack_s", R.string.fablesol_param_swell_attack_s, "s", 0.02, 1.20, 0.01),
            Spec("swell_release_s", R.string.fablesol_param_swell_release_s, "s", 0.10, 3.00, 0.01),
            Spec("swell_gain", R.string.fablesol_param_swell_gain, "×", 0.0, 2.0, 0.01)
        )),
        // 注入组已于 2026-07-18 整组固化进实现（机制保留、调参无可感变化）。
        // 段落组已于 2026-07-18 整组移除（段涌连根删、mood 两项固化进实现）。
    )

    private const val PREFS_NAME = "fablesol_tuning"
    private const val KEY_PARAM_PREFIX = "param_"
    private const val KEY_HDR_ENABLED = "hdr_enabled"

    /** 视为"等于默认值"的容差；差值小于它时删除存储而不是写入。 */
    private const val DEFAULT_EPSILON = 1e-6

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 把持久化覆盖套到一个 params 实例上；渲染器构造 params 后立即调用。 */
    fun applyStored(context: Context, params: FableSolParams) {
        val sp = prefs(context)
        for (group in GROUPS) {
            for (spec in group.specs) {
                val prefKey = KEY_PARAM_PREFIX + spec.key
                if (sp.contains(prefKey)) {
                    val value = sp.getFloat(prefKey, 0f).toDouble().coerceIn(spec.lo, spec.hi)
                    params.set(spec.key, value)
                }
            }
        }
    }

    /** 读取某个参数当前应显示的值：有存储用存储，否则用 [defaultValue]。 */
    fun storedValue(context: Context, spec: Spec, defaultValue: Double): Double {
        val sp = prefs(context)
        val prefKey = KEY_PARAM_PREFIX + spec.key
        if (!sp.contains(prefKey)) return defaultValue
        return sp.getFloat(prefKey, defaultValue.toFloat()).toDouble().coerceIn(spec.lo, spec.hi)
    }

    /** 持久化一个参数值；与默认值相同（容差内）时改为删除存储项，保持文件精简。 */
    fun putValue(context: Context, spec: Spec, value: Double, defaultValue: Double) {
        val editor = prefs(context).edit()
        val prefKey = KEY_PARAM_PREFIX + spec.key
        if (abs(value - defaultValue) < DEFAULT_EPSILON) {
            editor.remove(prefKey)
        } else {
            editor.putFloat(prefKey, value.toFloat())
        }
        editor.apply()
    }

    /** 清空全部参数覆盖（保留 HDR 开关）。 */
    fun clearAllParams(context: Context) {
        val sp = prefs(context)
        val editor = sp.edit()
        for (prefKey in sp.all.keys) {
            if (prefKey.startsWith(KEY_PARAM_PREFIX)) editor.remove(prefKey)
        }
        editor.apply()
    }

    fun isHdrEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HDR_ENABLED, true)

    fun setHdrEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HDR_ENABLED, enabled).apply()
    }
}
