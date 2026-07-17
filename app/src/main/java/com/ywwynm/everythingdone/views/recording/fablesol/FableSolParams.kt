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
        // D135：lighten_far 恢复为静态 OKLab 混白比例，最远层硬封顶 86.4%。
        v("lighten_far", 0.864); v("color_breath", 1.0)
        v("environment_tint", 0.16); v("sky_reflection_strength", 0.42)
        // Step B（2026-07-13）：体光是九层平铺的中间调抬升、对比负项，且真机开关无感，改默认关闭；
        // 显式阴影从当前层当前位置派生，subsurface 身份色只供日出 SSS。三项表层光学仍为 Debug 202607130749。
        v("body_light_strength", 0.0); v("pearl_shift_deg", 0.0)
        v("crest_on", 1.0); v("light_azimuth_deg", 27.0)
        v("crest_glint_strength", 0.90); v("crest_width_dp", 1.25)
        v("crest_lighten", 0.40); v("crest_glow_strength", 0.42)
        v("crest_glow_depth_dp", 12.0); v("crest_veil_strength", 0.14)
        v("capillary_glint_gain", 1.0)
        // 立体感手法（2026-07-11 视觉批次，Python 面板同名参数的定稿默认值）
        // D152：薄峰透光实体带被材质版"厚度透光"取代，默认归零仅留回退（deprecated）。
        v("surface_strip_gain", 1.0); v("thin_glow_gain", 0.0)
        v("flow_streak_gain", 0.70); v("orbital_sway_dp", 13.0)
        // D151/D152 质感提升（2026-07-16 目测定稿）：厚度透光——薄处按迎光坡向
        // 从内部亮起，目标色 = subsurface 派生线性提亮 1.6（保色相饱和比）。
        v("uplift_thick_glow", 1.29); v("uplift_glow_boost", 1.6)
        // D156 波峰银边（2026-07-17 模拟器 v12 定稿）：剪影掠射镜面线，
        // 场强另乘音频活跃度（0.30+0.70×sparkle01）。
        v("uplift_crest_rim", 1.0)
        // 银丝四控制项（与 Python 模拟器 GUI 同名同默认）：粗细 dp、光晕
        // 幅度、HDR 峰值（3.6 = 闪点核心档）、滑动调制深度（逆流视差）。
        v("uplift_rim_width", 0.6); v("uplift_rim_halo", 0.16)
        v("uplift_rim_peak", 3.6); v("uplift_rim_slide", 1.0)
        // D156：银边评审期闪点数量整体归零（容量表不动，恢复置 1）。
        v("glint_capacity_gain", 0.0)
        v("back_shade_gain", 0.80); v("aerial_contrast", 0.50)
        v("hue_temp_deg", 0.0); v("pink_mod", 0.80)
        // 连续 2.5D 水面（2026-07-12 FableSol 蓝本定稿参数）。Android 默认启用，
        // 旧九层填充路径保留为内部回退，便于真机对照与低风险回滚。
        v("surface2d_on", 1.0)
        v("surface_heading_deg", 30.0); v("surface_spread_deg", 24.0)
        v("surface_decay_dp", 280.0); v("surface_view_elev_deg", 38.0)
        // 预滤波微法线带限；当前只服务折射、Beer、SSS 与 HDR transmission。
        v("specular_aa_strength", 1.0)
        // Stage 2-4：四项质感增强均可独立归零关闭。
        v("global_pink_breath_strength", 1.0)
        // 第二轮材质基准：增强全九层微法线，但仍由逐层权重控制远层响应。
        v("micro_normal_strength", 0.36)
        v("sun_sss_strength", 0.16); v("sun_sss_falloff", 6.0)
        // D135：离散闪点不再绘制解析外晕，避免圆环状扩张/收缩光斑。
        v("analytic_halo_strength", 0.0)
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
        // 层全不透明（无景深半透）：半透明会让环境色（纯白 dialog / 深主题背景）从半透明层底下
        // 透上来，把远层拖成灰蒙蒙（实测远层饱和度 S 从 ~35 塌到 ~27）。改为每层不透明、颜色纯由
        // D135 色板按深度直接混白；声音、录音和 HDR 状态不改写九层主体色。
        l("alpha", doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0))
    }

    private fun v(k: String, d: Double) { values[k] = d }
    private fun l(k: String, a: DoubleArray) { layers[k] = a }

    fun get(key: String): Double = values[key] ?: 0.0
    fun lget(key: String, i: Int): Double = layers[key]!![i]
    fun larray(key: String): DoubleArray = layers[key]!!

    /** 测试专用覆盖入口（与 Python params.set 对应）；产品代码不调用。 */
    fun setForTest(key: String, value: Double) {
        require(values.containsKey(key)) { "未注册参数：$key" }
        values[key] = value
    }
}
