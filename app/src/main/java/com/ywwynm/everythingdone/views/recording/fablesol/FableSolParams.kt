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
        v("crest_width_dp", 1.25)
        // 2026-07-18 用户裁决：镜面高光/高光提亮/波峰透光（含纵深）/波冠轻纱/
        // 毛细闪光/薄峰透光/表面流光/轨道摆幅/波背自阴影/空气透视/1/f 慢调制/
        // 微法线带限/全局 1/f 呼吸/风梳微法线/朝阳次表面散射（含收束）共 17 项
        // 全部归零后画面无可感变化，参数与对应功能整体移除（Python 同步）。
        // D160：表面亮带（surface_strip_gain）随 Python 端先例整项移除，不再注册。
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
        // 闪点数量总门（2026-07-18 转正式可调项）：默认 0 关闭，调参 Dialog
        // 拉起即出闪点；出生场镜面强度固化 0.90（原 crest_glint_strength 默认）。
        v("glint_capacity_gain", 0.0)
        v("hue_temp_deg", 0.0)
        // 连续 2.5D 水面（2026-07-12 FableSol 蓝本定稿参数）。Android 默认启用，
        // 旧九层填充路径保留为内部回退，便于真机对照与低风险回滚。
        v("surface2d_on", 1.0)
        v("surface_heading_deg", 30.0); v("surface_spread_deg", 24.0)
        v("surface_decay_dp", 280.0); v("surface_view_elev_deg", 38.0)
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
        // 2026-07-17 补注册：FeatureMapper 一直在读这两个 key，但移植时漏了注册，
        // get 走 0.0 兜底后又被 max(…, 0.5)/max(…, 1.0) 钳制。按既有实效值入表以
        // 保持观感不变（Python 原版默认 3.0 / 30.0，差异记录在 followups）。
        v("swell_halflife_s", 0.5); v("deep_integral_s", 1.0)
        // 注入组已于 2026-07-18 整组固化进实现（Python 同步；机制保留，
        // 连续水面主路径均值化行波形态，调参无可感变化）。
        // 感知前端
        v("agc_window_s", 24.0); v("silence_gate_db", 6.0); v("expander_amount", 0.32)
        // 段落组已于 2026-07-18 整组移除（Python 同步）：段涌连根删，
        // mood_transition_s/mood_spread_dp 固化 1.5s/12dp 进实现。
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

    /**
     * 运行时覆盖入口（与 Python params.set 对应）：调参 Dialog 的持久化覆盖在渲染器
     * 构造时套用（[FableSolTuning.applyStored]），实时调节经渲染线程 drain 后写入。
     * key 必须已注册，防止拼写错误静默落空。
     */
    fun set(key: String, value: Double) {
        require(values.containsKey(key)) { "未注册参数：$key" }
        values[key] = value
    }

    /** 测试专用别名。 */
    fun setForTest(key: String, value: Double) = set(key, value)
}
