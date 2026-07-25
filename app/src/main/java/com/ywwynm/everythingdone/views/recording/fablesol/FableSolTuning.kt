package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.ywwynm.everythingdone.R
import kotlin.math.abs

/**
 * FableSol 调参持久层 + 可调参数目录（设置界面"音频海浪动画参数调节"Dialog 使用）。
 *
 * - 目录收录 Android 渲染链路（GlRenderer / GlOptics / Simulation / FeatureMapper /
 *   ContinuousSurface）或音频分析链实际消费、且在 [FableSolParams] 注册过的标量参数；范围、
 *   步长与 Python 模拟器 params.py 的 GUI 规格同源，标签为多语言字符串资源
 *   （fablesol_group_* / fablesol_param_*，中文文案与 Python GUI 一致）。
 * - 存储在独立 SharedPreferences 文件里，只存偏离默认值的键；未存储的键永远走
 *   [FableSolParams] 默认值，单元测试直接 new FableSolParams() 不受影响。
 * - HDR 强度也存这里（1.0=关闭，默认=上限 9.6，标定档 3.6；旧布尔开关自动迁移）；
 *   真正能否生效仍由设备能力与录音态门控。
 */
object FableSolTuning {

    enum class Target {
        RENDERER,
        AUDIO_FRONT_END
    }

    /** 单个可调参数的 UI 规格。[boolLike] 为真时渲染为开关（0/1）。 */
    class Spec(
        val key: String,
        @StringRes val labelRes: Int,
        val unit: String,
        val lo: Double,
        val hi: Double,
        val step: Double,
        val boolLike: Boolean = false,
        val target: Target = Target.RENDERER
    )

    class Group(@StringRes val titleRes: Int, val specs: List<Spec>)

    val GROUPS: List<Group> = listOf(
        // 2026-07-25 用户裁决：每种特效独立成组（原"质感"大组拆分），
        // "眩光"命名统一改"星芒"。
        Group(R.string.fablesol_group_thickness_glow, listOf(
            Spec("uplift_thick_glow", R.string.fablesol_param_uplift_thick_glow, "", 0.0, 1.5, 0.02),
            Spec("uplift_glow_boost", R.string.fablesol_param_uplift_glow_boost, "×", 1.0, 1.6, 0.01)
        )),
        Group(R.string.fablesol_group_silver_thread, listOf(
            Spec("uplift_crest_rim", R.string.fablesol_param_uplift_crest_rim, "", 0.0, 1.6, 0.02),
            // 2026-07-24 下限 0.3→0.16（shader 侧有 0.5px 采样地板，低密度屏自动钳住）；
            // 步长同步 0.05→0.04；同日默认 0.6→0.28（第 3 格），上限 2.0 仍精确在栅格上。
            Spec("uplift_rim_width", R.string.fablesol_param_uplift_rim_width, "dp", 0.16, 2.0, 0.04),
            Spec("uplift_rim_halo", R.string.fablesol_param_uplift_rim_halo, "", 0.0, 0.5, 0.01),
            Spec("uplift_rim_peak", R.string.fablesol_param_uplift_rim_peak, "×", 1.0, 3.6, 0.05),
            Spec("uplift_rim_slide", R.string.fablesol_param_uplift_rim_slide, "", 0.0, 1.0, 0.05)
        )),
        // 星芒（D206~D213）：范围/步长与 Python 模拟器 GUI 同源。
        Group(R.string.fablesol_group_starburst, listOf(
            Spec("glare_strength", R.string.fablesol_param_glare_strength, "", 0.0, 2.0, 0.05),
            Spec("glare_halo", R.string.fablesol_param_glare_halo, "", 0.0, 0.8, 0.01),
            Spec("glare_threshold", R.string.fablesol_param_glare_threshold, "×", 1.6, 4.0, 0.1),
            // D211：上限放宽（96dp/16 线）供继续探索；参差 = 芒长因子幂指数。
            Spec("glare_needle_length", R.string.fablesol_param_glare_needle_length, "dp", 6.0, 129.0, 1.0),
            Spec("glare_needle_count", R.string.fablesol_param_glare_needle_count, "", 3.0, 16.0, 1.0),
            Spec("glare_needle_variance", R.string.fablesol_param_glare_needle_variance, "", 0.0, 4.0, 0.05),
            Spec("glare_depth_falloff", R.string.fablesol_param_glare_depth_falloff, "", 0.0, 2.0, 0.05)
        )),
        Group(R.string.fablesol_group_glints, listOf(
            Spec("glint_capacity_gain", R.string.fablesol_param_glint_capacity_gain, "×", 0.0, 1.0, 0.05)
        )),
        Group(R.string.fablesol_group_appearance, listOf(
            Spec("lighten_far", R.string.fablesol_param_lighten_far, "", 0.0, 0.864, 0.01),
            Spec("environment_tint", R.string.fablesol_param_environment_tint, "", 0.0, 0.5, 0.02),
            Spec("sky_reflection_strength", R.string.fablesol_param_sky_reflection_strength, "", 0.0, 1.2, 0.02),
            Spec("light_azimuth_deg", R.string.fablesol_param_light_azimuth_deg, "°", -60.0, 60.0, 1.0),
            Spec("back_shade_gain", R.string.fablesol_param_back_shade_gain, "", 0.0, 1.2, 0.02),
            Spec("color_breath", R.string.fablesol_param_color_breath, "", 0.0, 1.5, 0.02),
            Spec("hue_temp_deg", R.string.fablesol_param_hue_temp_deg, "°", -30.0, 30.0, 1.0)
        )),
        Group(R.string.fablesol_group_surface, listOf(
            Spec("surface_view_elev_deg", R.string.fablesol_param_surface_view_elev_deg, "°", 26.0, 54.0, 1.0)
        )),
        Group(R.string.fablesol_group_wave_shape, listOf(
            Spec("hero_gain", R.string.fablesol_param_hero_gain, "×", 0.0, 2.0, 0.01),
            Spec("hero_len_dp", R.string.fablesol_param_hero_len_dp, "dp", 160.0, 640.0, 1.0),
            Spec("hero_attack_s", R.string.fablesol_param_hero_attack_s, "s", 0.02, 1.50, 0.01),
            Spec("hero_release_s", R.string.fablesol_param_hero_release_s, "s", 0.10, 3.00, 0.01),
            Spec("hero_breath", R.string.fablesol_param_hero_breath, "", 0.0, 0.9, 0.02),
            Spec("ambient_gain", R.string.fablesol_param_ambient_gain, "×", 0.0, 3.0, 0.01),
            Spec("ambient_breath", R.string.fablesol_param_ambient_breath, "", 0.0, 0.6, 0.01),
            Spec("ambient_shape_stability", R.string.fablesol_param_ambient_shape_stability, "", 0.0, 1.0, 0.02),
            Spec("surface_heading_deg", R.string.fablesol_param_surface_heading_deg, "°", 6.0, 42.0, 1.0),
            Spec("surface_spread_deg", R.string.fablesol_param_surface_spread_deg, "°", 4.0, 48.0, 1.0),
            Spec("surface_spectrum_gain", R.string.fablesol_param_surface_spectrum_gain, "×", 0.0, 1.5, 0.02),
            Spec("surface_spectrum_audio_response", R.string.fablesol_param_surface_spectrum_audio_response, "", 0.0, 1.0, 0.02),
            Spec("surface_shape_stability", R.string.fablesol_param_surface_shape_stability, "", 0.0, 1.0, 0.02),
            Spec("surface_decay_dp", R.string.fablesol_param_surface_decay_dp, "dp", 80.0, 720.0, 10.0),
            Spec("wall_soft", R.string.fablesol_param_wall_soft, "", 0.0, 1.0, 0.02)
        )),
        Group(R.string.fablesol_group_ambient_flow, listOf(
            Spec("idle_flow_ratio", R.string.fablesol_param_idle_flow_ratio, "", 0.0, 1.0, 0.01),
            Spec("flow_gain", R.string.fablesol_param_flow_gain, "×", 0.0, 3.0, 0.01),
            Spec("flow_curve", R.string.fablesol_param_flow_curve, "", 0.5, 2.0, 0.05),
            Spec("flow_smooth_s", R.string.fablesol_param_flow_smooth_s, "s", 0.2, 5.0, 0.01),
            Spec("wander_gain", R.string.fablesol_param_wander_gain, "×", 0.0, 2.0, 0.01),
            Spec("tilt_calm", R.string.fablesol_param_tilt_calm, "", 0.0, 1.0, 0.02),
            Spec("beat_gain", R.string.fablesol_param_beat_gain, "×", 0.0, 2.0, 0.02)
        )),
        Group(R.string.fablesol_group_swell, listOf(
            Spec("swell_presmooth_s", R.string.fablesol_param_swell_presmooth_s, "s", 0.10, 2.00, 0.01),
            Spec("swell_presmooth_release_s", R.string.fablesol_param_swell_presmooth_release_s, "s", 0.20, 4.00, 0.05),
            Spec("swell_halflife_s", R.string.fablesol_param_swell_halflife_s, "s", 1.5, 10.0, 0.5),
            Spec("deep_integral_s", R.string.fablesol_param_deep_integral_s, "s", 6.0, 60.0, 1.0),
            Spec("swell_deadband_pct", R.string.fablesol_param_swell_deadband_pct, "%", 0.0, 15.0, 0.5),
            Spec("swell_attack_s", R.string.fablesol_param_swell_attack_s, "s", 0.02, 1.20, 0.01),
            Spec("swell_release_s", R.string.fablesol_param_swell_release_s, "s", 0.10, 3.00, 0.01),
            Spec("swell_gain", R.string.fablesol_param_swell_gain, "×", 0.0, 2.0, 0.01)
        )),
        Group(R.string.fablesol_group_audio_visual_coupling, listOf(
            Spec("expression_gain", R.string.fablesol_param_expression_gain, "×", 0.5, 1.5, 0.02),
            Spec("state_sensitivity", R.string.fablesol_param_state_sensitivity, "", -1.0, 1.0, 0.05),
            Spec("transition_speed", R.string.fablesol_param_transition_speed, "", -1.0, 1.0, 0.05)
        )),
        Group(R.string.fablesol_group_sound_analysis_sensitivity, listOf(
            Spec(
                FableSolFrontEndTuning.KEY_AGC_WINDOW_S,
                R.string.fablesol_param_agc_window_s,
                "s", 3.0, 30.0, 1.0,
                target = Target.AUDIO_FRONT_END
            ),
            Spec(
                FableSolFrontEndTuning.KEY_SILENCE_GATE_DB,
                R.string.fablesol_param_silence_gate_db,
                "dB", 0.0, 18.0, 0.5,
                target = Target.AUDIO_FRONT_END
            ),
            Spec(
                FableSolFrontEndTuning.KEY_EXPANDER_AMOUNT,
                R.string.fablesol_param_expander_amount,
                "", 0.0, 1.0, 0.01,
                target = Target.AUDIO_FRONT_END
            ),
            Spec(
                FableSolFrontEndTuning.KEY_RELATIVE_LOUDNESS_MIX,
                R.string.fablesol_param_relative_loudness_mix,
                "", 0.0, 0.6, 0.02,
                target = Target.AUDIO_FRONT_END
            )
        )),
        // 注入组已于 2026-07-18 整组固化进实现（机制保留、调参无可感变化）。
        // 段落组已于 2026-07-18 整组移除（段涌连根删、mood 两项固化进实现）。
    )

    private const val PREFS_NAME = "fablesol_tuning"
    private const val KEY_PARAM_PREFIX = "param_"
    /** D157 时代的布尔开关，仅作迁移来源；写入新强度时一并删除。 */
    private const val KEY_HDR_ENABLED = "hdr_enabled"
    private const val KEY_HDR_STRENGTH = "hdr_strength"

    /** 视为"等于默认值"的容差；差值小于它时删除存储而不是写入。 */
    private const val DEFAULT_EPSILON = 1e-6

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val FRONT_END_SPECS: List<Spec> by lazy {
        GROUPS.flatMap { it.specs }.filter { it.target == Target.AUDIO_FRONT_END }
    }

    /** 把持久化覆盖套到一个 params 实例上；渲染器构造 params 后立即调用。 */
    fun applyStored(context: Context, params: FableSolParams) {
        val sp = prefs(context)
        for (group in GROUPS) {
            for (spec in group.specs) {
                if (spec.target != Target.RENDERER) continue
                val prefKey = KEY_PARAM_PREFIX + spec.key
                if (sp.contains(prefKey)) {
                    val value = sp.getFloat(prefKey, 0f).toDouble().coerceIn(spec.lo, spec.hi)
                    params.set(spec.key, value)
                }
            }
        }
    }

    /** AudioRecorder 构造时调用：把持久化的声音分析参数载入线程安全快照。 */
    fun applyFrontEndStored(context: Context, tuning: FableSolFrontEndTuning) {
        for (spec in FRONT_END_SPECS) {
            tuning.set(
                spec.key,
                storedValue(context, spec, FableSolFrontEndTuning.defaultValue(spec.key))
            )
        }
        // state_sensitivity 同时驱动 mapper 的档位阈值与实时 Foote fire_z；虽然 UI
        // 归属 renderer 组，音频线程也必须收到同一持久化值。
        val stateSpec = GROUPS.asSequence().flatMap { it.specs.asSequence() }
            .first { it.key == FableSolFrontEndTuning.KEY_STATE_SENSITIVITY }
        tuning.set(
            stateSpec.key,
            storedValue(context, stateSpec, FableSolFrontEndTuning.DEFAULT_STATE_SENSITIVITY)
        )
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

    /** 清空全部参数覆盖；HDR 强度不在 param_ 键空间，由 [clearHdrStrength] 单独清除。 */
    fun clearAllParams(context: Context) {
        val sp = prefs(context)
        val editor = sp.edit()
        for (prefKey in sp.all.keys) {
            if (prefKey.startsWith(KEY_PARAM_PREFIX)) editor.remove(prefKey)
        }
        editor.apply()
    }

    /** 清除 HDR 强度存储（含旧布尔键），回落默认档；恢复默认按钮用（2026-07-24 用户裁定）。 */
    fun clearHdrStrength(context: Context) {
        prefs(context).edit()
            .remove(KEY_HDR_STRENGTH)
            .remove(KEY_HDR_ENABLED)
            .apply()
    }

    /**
     * 用户 HDR 强度（D204）：1.0 = 关闭，[FableSolHdrPolicy.MAX_STRENGTH] 封顶。
     * 旧布尔开关按 true→默认档（2026-07-24 起 = 上限）、false→1.0 迁移读取。
     */
    fun hdrStrength(context: Context): Float {
        val sp = prefs(context)
        if (sp.contains(KEY_HDR_STRENGTH)) {
            return sp.getFloat(KEY_HDR_STRENGTH, FableSolHdrPolicy.DEFAULT_STRENGTH)
                .coerceIn(FableSolHdrPolicy.STRENGTH_OFF, FableSolHdrPolicy.MAX_STRENGTH)
        }
        if (sp.contains(KEY_HDR_ENABLED)) {
            return if (sp.getBoolean(KEY_HDR_ENABLED, true)) {
                FableSolHdrPolicy.DEFAULT_STRENGTH
            } else {
                FableSolHdrPolicy.STRENGTH_OFF
            }
        }
        return FableSolHdrPolicy.DEFAULT_STRENGTH
    }

    fun setHdrStrength(context: Context, strength: Float) {
        prefs(context).edit()
            .putFloat(
                KEY_HDR_STRENGTH,
                strength.coerceIn(FableSolHdrPolicy.STRENGTH_OFF, FableSolHdrPolicy.MAX_STRENGTH)
            )
            .remove(KEY_HDR_ENABLED)
            .apply()
    }

    /** 录音态 HDR 门控便捷判断：强度高于 1.0 才请求 HDR。 */
    fun isHdrEnabled(context: Context): Boolean =
        hdrStrength(context) > FableSolHdrPolicy.STRENGTH_OFF
}
