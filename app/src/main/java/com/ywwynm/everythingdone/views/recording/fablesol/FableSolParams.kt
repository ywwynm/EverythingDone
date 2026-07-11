package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 运行时参数（对应 params.py），取原版默认值硬编码。调参 UI 已裁掉，全程只读。
 * 外观类 palette/gradient_dir 在本移植中不使用（配色改接记事 ThingBackground，见 D1），
 * 但保留其 key，以便 simulation/mapping 的逐条读取与原版一比一。
 */
class FableSolParams {

    private val values = HashMap<String, Double>()
    private val layers = HashMap<String, DoubleArray>()

    init {
        // ---- 全局 ----
        // 外观
        v("palette", 0.0); v("gradient_dir", 1.0)
        v("lighten_far", 0.6); v("color_breath", 1.0)
        v("environment_tint", 0.16); v("sky_reflection_strength", 0.42)
        v("body_light_strength", 0.36); v("pearl_shift_deg", 6.0)
        v("crest_on", 1.0); v("light_azimuth_deg", 27.0)
        v("crest_glint_strength", 0.90); v("crest_width_dp", 1.25)
        v("crest_lighten", 0.40); v("crest_glow_strength", 0.42)
        v("crest_glow_depth_dp", 12.0); v("crest_veil_strength", 0.32)
        v("capillary_glint_gain", 1.0)
        // 立体感手法（2026-07-11 视觉批次，Python 面板同名参数的定稿默认值）
        v("surface_strip_gain", 1.0); v("thin_glow_gain", 0.55)
        v("flow_streak_gain", 0.70); v("orbital_sway_dp", 13.0)
        v("back_shade_gain", 0.80); v("aerial_contrast", 0.50)
        v("hue_temp_deg", 5.0); v("pink_mod", 0.80)
        // 环境波 / 流动
        v("ambient_gain", 1.2); v("ambient_breath", 0.27)
        v("idle_flow_ratio", 0.18); v("flow_gain", 1.8)
        v("flow_curve", 1.29); v("flow_smooth_s", 0.48)
        v("wander_gain", 1.0); v("wall_soft", 0.6); v("tilt_calm", 0.75)
        // 主浪
        v("hero_gain", 1.0); v("hero_len_dp", 360.0)
        v("hero_attack_s", 0.85); v("hero_release_s", 1.60)
        v("hero_punch", 0.0); v("hero_punch_decay_s", 0.42)
        v("hero_breath", 0.42); v("beat_gain", 1.0)
        // 涨落
        v("swell_presmooth_s", 0.55); v("swell_presmooth_release_s", 1.60)
        v("swell_deadband_pct", 0.0); v("swell_attack_s", 0.38)
        v("swell_release_s", 1.60); v("swell_gain", 1.0)
        // 注入
        v("inject_gain", 1.0); v("inject_amp_max_dp", 36.0)
        v("inject_width_min_dp", 96.0); v("inject_width_max_dp", 216.0)
        v("inject_ramp_ms", 120.0); v("rhythm_wave_gain", 0.85)
        v("rhythm_wave_min_strength", 0.25); v("travel_bias_max", 0.75)
        v("cascade_step_s", 0.054); v("incoming_threshold", 0.75)
        v("incoming_prob", 0.50); v("incoming_cooldown_s", 3.2)
        // 感知前端
        v("agc_window_s", 24.0); v("silence_gate_db", 6.0); v("expander_amount", 0.32)
        // 段落
        v("surge_gain", 0.0); v("surge_amp_max_dp", 75.0)
        v("surge_lift_dp", 160.0); v("surge_drawdown_dp", 16.0)
        v("section_delay_s", 1.0); v("mood_transition_s", 1.5)
        v("mood_spread_dp", 12.0); v("lookahead_s", 1.5)
        // 系统
        v("demo_mode", 0.0)

        // ---- 逐层（9 层，index 0=最近 … 8=最远）----
        l("base_level_dp", doubleArrayOf(96.0, 108.0, 114.0, 120.0, 129.0, 136.0, 145.0, 154.0, 160.0))
        l("wave_speed_dps", doubleArrayOf(216.0, 160.0, 150.0, 129.0, 120.0, 108.0, 96.0, 84.0, 75.0))
        l("flow_base_dps", doubleArrayOf(129.0, 120.0, 108.0, 96.0, 91.0, 84.0, 75.0, 72.0, 64.0))
        l("swell_max_dp", doubleArrayOf(124.0, 122.0, 120.0, 118.0, 119.0, 120.0, 120.0, 118.0, 117.0))
        l("hero_max_dp", doubleArrayOf(64.0, 50.0, 46.0, 42.0, 39.0, 37.0, 35.0, 34.0, 33.0))
        l("damp_halflife_s", doubleArrayOf(0.75, 0.96, 1.20, 1.29, 1.50, 1.60, 1.91, 2.16, 2.40))
        l("ambient_amp_dp", doubleArrayOf(5.5, 5.0, 4.6, 4.2, 3.9, 3.6, 3.3, 3.0, 2.8))
        l("ambient_len_dp", doubleArrayOf(160.0, 150.0, 141.0, 129.0, 120.0, 108.0, 96.0, 84.0, 72.0))
        l("wander_amp_dp", doubleArrayOf(6.0, 6.0, 6.0, 5.0, 5.0, 5.0, 4.0, 4.0, 4.0))
        l("wander_period_s", doubleArrayOf(12.0, 16.0, 24.0, 27.0, 32.0, 12.0, 16.0, 24.0, 27.0))
        l("alpha", doubleArrayOf(1.0, 196.0 / 255, 181.0 / 255, 166.0 / 255, 151.0 / 255,
                136.0 / 255, 121.0 / 255, 106.0 / 255, 91.0 / 255))
    }

    private fun v(k: String, d: Double) { values[k] = d }
    private fun l(k: String, a: DoubleArray) { layers[k] = a }

    fun get(key: String): Double = values[key] ?: 0.0
    fun lget(key: String, i: Int): Double = layers[key]!![i]
    fun larray(key: String): DoubleArray = layers[key]!!
}
