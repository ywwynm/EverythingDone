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
    private const val KEY_SHOW_PERF_HUD = "show_perf_hud"
    private const val KEY_LIVE_TILT = "live_tilt"
    private const val KEY_EXPORT_FRAME_RATE = "export_frame_rate"
    private const val KEY_EXPORT_BITRATE = "export_bitrate"
    private const val KEY_EXPORT_KEYFRAME = "export_keyframe"
    private const val KEY_EXPORT_PQ_WHITE = "export_pq_white"
    private const val KEY_EXPORT_HIGHLIGHT_START = "export_highlight_start"
    private const val KEY_EXPORT_TILT = "export_tilt"

    // ---- 导出请求模型（fablesol-video-export 批次 1）：一律按稳定字符串持久化 ----
    private const val KEY_EXPORT_COLOR_MODE = "export_color_mode"
    private const val KEY_EXPORT_SDR_MAPPING = "export_sdr_mapping"
    private const val KEY_EXPORT_SDR_BIT_DEPTH = "export_sdr_bit_depth"
    private const val KEY_EXPORT_HLG_RANGE = "export_hlg_range"
    private const val KEY_EXPORT_RATE_CONTROL = "export_rate_control"
    private const val KEY_EXPORT_CODEC_FAMILY = "export_codec_family"
    private const val KEY_EXPORT_REFERENCE_PEAK = "export_reference_peak"
    private const val KEY_EXPORT_B_FRAMES = "export_b_frames"
    private const val KEY_EXPORT_HIGH_COMPLEXITY = "export_high_complexity"
    private const val KEY_EXPORT_QP_GUARD = "export_qp_guard"
    /** 尚未归属到候选签名的旧版全局 CQ 原值；见 [bindLegacyQualityValue]。 */
    private const val KEY_EXPORT_QUALITY_PENDING = "export_quality_pending"
    /** 逐候选签名的 CQ 原值前缀（D146）。 */
    private const val KEY_EXPORT_QUALITY_PREFIX = "export_quality@"
    private const val KEY_EXPORT_PREFS_VERSION = "export_prefs_version"

    // ---- 旧版键，仅作迁移来源；迁移完成后删除 ----
    private const val LEGACY_KEY_EXPORT_PREFER_CQ = "export_prefer_cq"
    private const val LEGACY_KEY_EXPORT_QUALITY = "export_quality"
    private const val LEGACY_KEY_EXPORT_HDR = "export_hdr"
    private const val LEGACY_KEY_EXPORT_HDR_FORMAT = "export_hdr_format"
    private const val LEGACY_KEY_EXPORT_CODEC = "export_codec"

    /** 当前导出偏好的存储版本；每次结构性变更加一并补一段迁移。 */
    private const val EXPORT_PREFS_VERSION = 1

    @Volatile
    private var exportPreferencesMigrated = false

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

    /**
     * 录音 Dialog、音频附件 Dialog 与调参预览里的水体是否跟随设备姿态倾斜；默认开。
     *
     * 关掉之后这三处都不再注册重力传感器，水体恒按竖直渲染。它还牵动两件与画面无关的事：
     * 录音与播放对话框不再锁定宿主 Activity 的方向（详情页恢复自动旋转），录音写出的 WAV
     * 也不再带 `EDmo` 重力轨迹——既然当时的画面本就不倾斜，记下来的姿态没有可复现的对象。
     * 已经录好的音频里那条轨迹不受影响，是否重放由 [exportTiltEnabled] 单独决定。
     */
    fun liveTiltEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIVE_TILT, true)

    fun setLiveTiltEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_TILT, value).apply()
    }

    /** 「恢复默认」把它一并复位（与 HDR 强度同属画面偏好，不是调试工具偏好）。 */
    fun clearLiveTilt(context: Context) {
        prefs(context).edit().remove(KEY_LIVE_TILT).apply()
    }

    /**
     * debug 构建的屏上性能面板开关；默认关闭，独立于"恢复默认"（它是调试工具偏好，
     * 不是波浪参数）。release 构建不读它——HUD 整段被 BuildConfig.DEBUG 编译期移除。
     */
    fun isPerfHudEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_PERF_HUD, false)

    fun setPerfHudEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_PERF_HUD, enabled).apply()
    }

    // ---- 导出编码参数（fablesol-video-export D10）----
    //
    // 它们不走 param_ 键空间，也不推给 GL 线程——导出时才读一次。但参与「恢复默认」。

    /**
     * 把旧版导出偏好迁移到类型化请求模型（批次 1）。
     *
     * 幂等、只在进程内执行一次判定，并且**只迁移用户真的存过的键**：旧版缺省值与新默认值
     * 一一对应（关闭 HDR 之外都落在默认上），凭空写入反而会让「恢复默认」失去意义。
     *
     * 迁移不做任何同步能力探测。旧版全局 CQ 原值只搬进"待归属"槽位，第一次真正解析出候选
     * 时才归属给那一个签名（延迟绑定，见 [bindLegacyQualityValue]）——冷启动时能力矩阵可能
     * 尚未就绪，在迁移路径上探测会把一次设置页打开变成一串编码。
     */
    private fun ensureExportMigration(context: Context) {
        if (exportPreferencesMigrated) return
        synchronized(this) {
            if (exportPreferencesMigrated) return
            val sp = prefs(context)
            if (sp.getInt(KEY_EXPORT_PREFS_VERSION, 0) >= EXPORT_PREFS_VERSION) {
                exportPreferencesMigrated = true
                return
            }
            val editor = sp.edit()
            if (sp.contains(LEGACY_KEY_EXPORT_HDR) || sp.contains(LEGACY_KEY_EXPORT_HDR_FORMAT)) {
                val migrated = FableSolExportColorMode.migrateFromLegacy(
                    hdrEnabled = sp.getBoolean(LEGACY_KEY_EXPORT_HDR, true),
                    legacyFormatOrdinal = sp.getInt(LEGACY_KEY_EXPORT_HDR_FORMAT, 0)
                )
                editor.putString(KEY_EXPORT_COLOR_MODE, migrated.stableId)
            }
            if (sp.contains(LEGACY_KEY_EXPORT_PREFER_CQ)) {
                // 旧「恒定码率」保留的是"控制文件体积和平均码率"这个意图，迁移为 VBR；
                // 目标 Mbps 存在另一个键上，不受影响（D145）。
                val rateControl = if (sp.getBoolean(LEGACY_KEY_EXPORT_PREFER_CQ, true)) {
                    FableSolExportRateControl.CONSTANT_QUALITY
                } else {
                    FableSolExportRateControl.TARGET_BITRATE
                }
                editor.putString(KEY_EXPORT_RATE_CONTROL, rateControl.stableId)
            }
            if (sp.contains(LEGACY_KEY_EXPORT_CODEC)) {
                editor.putString(
                    KEY_EXPORT_CODEC_FAMILY,
                    FableSolExportOptions.CodecPreference
                        .fromLegacyOrdinal(sp.getInt(LEGACY_KEY_EXPORT_CODEC, 0)).stableId
                )
            }
            if (sp.contains(LEGACY_KEY_EXPORT_QUALITY)) {
                val legacy = sp.getInt(
                    LEGACY_KEY_EXPORT_QUALITY, FableSolExportOptions.UNSET_QUALITY
                )
                if (legacy != FableSolExportOptions.UNSET_QUALITY) {
                    editor.putInt(KEY_EXPORT_QUALITY_PENDING, legacy)
                }
            }
            editor.remove(LEGACY_KEY_EXPORT_HDR)
                .remove(LEGACY_KEY_EXPORT_HDR_FORMAT)
                .remove(LEGACY_KEY_EXPORT_CODEC)
                .remove(LEGACY_KEY_EXPORT_PREFER_CQ)
                .remove(LEGACY_KEY_EXPORT_QUALITY)
                .putInt(KEY_EXPORT_PREFS_VERSION, EXPORT_PREFS_VERSION)
                .apply()
            exportPreferencesMigrated = true
        }
    }

    fun exportFrameRate(context: Context): Int =
        prefs(context).getInt(KEY_EXPORT_FRAME_RATE, FableSolExportOptions.FRAME_RATE_HIGH)

    fun setExportFrameRate(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_EXPORT_FRAME_RATE, value).apply()
    }

    /** 导出色彩模式（D62）：两种 SDR、HDR 自动或具体 HDR 格式。 */
    internal fun exportColorMode(context: Context): FableSolExportColorMode {
        ensureExportMigration(context)
        return FableSolExportColorMode.fromStableId(
            prefs(context).getString(KEY_EXPORT_COLOR_MODE, null)
        )
    }

    internal fun setExportColorMode(context: Context, value: FableSolExportColorMode) {
        ensureExportMigration(context)
        prefs(context).edit().putString(KEY_EXPORT_COLOR_MODE, value.stableId).apply()
    }

    internal fun exportSdrMapping(context: Context): FableSolExportSdrMapping =
        FableSolExportSdrMapping.fromStableId(
            prefs(context).getString(KEY_EXPORT_SDR_MAPPING, null)
        )

    internal fun setExportSdrMapping(context: Context, value: FableSolExportSdrMapping) {
        prefs(context).edit().putString(KEY_EXPORT_SDR_MAPPING, value.stableId).apply()
    }

    internal fun exportSdrBitDepth(context: Context): FableSolExportSdrBitDepth =
        FableSolExportSdrBitDepth.fromStableId(
            prefs(context).getString(KEY_EXPORT_SDR_BIT_DEPTH, null)
        )

    internal fun setExportSdrBitDepth(context: Context, value: FableSolExportSdrBitDepth) {
        prefs(context).edit().putString(KEY_EXPORT_SDR_BIT_DEPTH, value.stableId).apply()
    }

    internal fun exportHlgSignalRange(context: Context): FableSolExportHlgSignalRange =
        FableSolExportHlgSignalRange.fromStableId(
            prefs(context).getString(KEY_EXPORT_HLG_RANGE, null)
        )

    internal fun setExportHlgSignalRange(
        context: Context,
        value: FableSolExportHlgSignalRange
    ) {
        prefs(context).edit().putString(KEY_EXPORT_HLG_RANGE, value.stableId).apply()
    }

    internal fun exportRateControl(context: Context): FableSolExportRateControl {
        ensureExportMigration(context)
        return FableSolExportRateControl.fromStableId(
            prefs(context).getString(KEY_EXPORT_RATE_CONTROL, null)
        )
    }

    internal fun setExportRateControl(context: Context, value: FableSolExportRateControl) {
        ensureExportMigration(context)
        prefs(context).edit().putString(KEY_EXPORT_RATE_CONTROL, value.stableId).apply()
    }

    /**
     * 逐候选签名的 CQ 原值（D146）。
     *
     * 不跨编码器、MIME/Profile 或实质不同的输入路径套用：Android 的 `KEY_QUALITY` 是各厂商
     * 自行映射的一段区间，同一个数字在两个编码器上不表示同一件事。
     */
    internal fun exportQualityValues(context: Context): Map<String, Int> {
        ensureExportMigration(context)
        val result = HashMap<String, Int>(4)
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith(KEY_EXPORT_QUALITY_PREFIX)) continue
            (value as? Int)?.let { result[key.removePrefix(KEY_EXPORT_QUALITY_PREFIX)] = it }
        }
        return result
    }

    /** 尚未归属到任何候选签名的旧版全局 CQ 原值；没有则为 null。 */
    internal fun pendingLegacyQualityValue(context: Context): Int? {
        ensureExportMigration(context)
        val sp = prefs(context)
        if (!sp.contains(KEY_EXPORT_QUALITY_PENDING)) return null
        return sp.getInt(KEY_EXPORT_QUALITY_PENDING, FableSolExportOptions.UNSET_QUALITY)
            .takeIf { it != FableSolExportOptions.UNSET_QUALITY }
    }

    internal fun exportQualityValue(context: Context, signature: String): Int {
        ensureExportMigration(context)
        val sp = prefs(context)
        val key = KEY_EXPORT_QUALITY_PREFIX + signature
        if (sp.contains(key)) {
            return sp.getInt(key, FableSolExportOptions.UNSET_QUALITY)
        }
        return pendingLegacyQualityValue(context) ?: FableSolExportOptions.UNSET_QUALITY
    }

    /**
     * @param signature null 表示尚未解析出候选（设置页刚打开、探测还没回来）。此时把值放进
     *   "待归属"槽位，第一次真正解析出候选时再绑定，不会误落到某个不相干的编码器路径上。
     */
    internal fun setExportQualityValue(context: Context, signature: String?, value: Int) {
        ensureExportMigration(context)
        val editor = prefs(context).edit()
        if (signature == null) {
            editor.putInt(KEY_EXPORT_QUALITY_PENDING, value)
        } else {
            editor.putInt(KEY_EXPORT_QUALITY_PREFIX + signature, value)
                // 用户已经为这条路径明确定了值，待归属的旧值不再有归属对象。
                .remove(KEY_EXPORT_QUALITY_PENDING)
        }
        editor.apply()
    }

    /**
     * 把待归属的旧版 CQ 原值一次性绑定到实际解析出的候选签名（延迟绑定）。
     *
     * 只归属一次，不扩散到其它编码器或输入路径；已经绑定过就什么也不做。
     */
    internal fun bindLegacyQualityValue(context: Context, signature: String) {
        val pending = pendingLegacyQualityValue(context) ?: return
        prefs(context).edit()
            .putInt(KEY_EXPORT_QUALITY_PREFIX + signature, pending)
            .remove(KEY_EXPORT_QUALITY_PENDING)
            .apply()
    }

    internal fun exportBFramesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPORT_B_FRAMES, false)

    internal fun setExportBFramesEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPORT_B_FRAMES, value).apply()
    }

    internal fun exportHighComplexityEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPORT_HIGH_COMPLEXITY, true)

    internal fun setExportHighComplexityEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPORT_HIGH_COMPLEXITY, value).apply()
    }

    internal fun exportComplexFrameGuardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPORT_QP_GUARD, true)

    internal fun setExportComplexFrameGuardEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPORT_QP_GUARD, value).apply()
    }

    /** HDR10+／HDR Vivid 参考显示峰值（D94、D11）；标准 1000 尼特。 */
    internal fun exportReferenceDisplayPeakNits(context: Context): Float =
        prefs(context).getFloat(
            KEY_EXPORT_REFERENCE_PEAK, FableSolExportOptions.DEFAULT_REFERENCE_PEAK_NITS
        ).coerceIn(
            FableSolExportOptions.MIN_REFERENCE_PEAK_NITS,
            FableSolExportOptions.MAX_REFERENCE_PEAK_NITS
        )

    internal fun setExportReferenceDisplayPeakNits(context: Context, value: Float) {
        prefs(context).edit().putFloat(
            KEY_EXPORT_REFERENCE_PEAK,
            value.coerceIn(
                FableSolExportOptions.MIN_REFERENCE_PEAK_NITS,
                FableSolExportOptions.MAX_REFERENCE_PEAK_NITS
            )
        ).apply()
    }

    /**
     * 导出时是否重放录音期间记下的重力轨迹（fablesol-video-export D13）。
     *
     * 关掉即按竖直渲染，与没有轨迹的历史录音同一条路径。它只对**本应用录制**的音频有意义
     * ——只有那些 WAV 里才有 `EDmo` chunk，导入的音频本来就没有倾斜可言。
     */
    fun exportTiltEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPORT_TILT, true)

    fun setExportTiltEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPORT_TILT, value).apply()
    }

    internal fun exportCodec(context: Context): FableSolExportOptions.CodecPreference {
        ensureExportMigration(context)
        return FableSolExportOptions.CodecPreference.fromStableId(
            prefs(context).getString(KEY_EXPORT_CODEC_FAMILY, null)
        )
    }

    internal fun setExportCodec(
        context: Context,
        value: FableSolExportOptions.CodecPreference
    ) {
        ensureExportMigration(context)
        prefs(context).edit().putString(KEY_EXPORT_CODEC_FAMILY, value.stableId).apply()
    }

    /**
     * PQ 漫反射白（尼特）。
     *
     * 用户没动过就是与导出设备**无关**的标准值 203 尼特（ITU-R BT.2408 名义 HDR Reference
     * White，D82/D83）。曾经这里返回由屏幕峰值、最大帧平均亮度与 HDR 强度共同推出的自动值，
     * 那等于让"负责导出的是哪台设备"隐式改写内容的母版意图；本机显示能力现在只作诊断、
     * 观看参考，或用户主动采用的一次性参考值。
     */
    fun exportPqWhiteNits(context: Context): Float =
        prefs(context).getFloat(
            KEY_EXPORT_PQ_WHITE, FableSolExportOptions.DEFAULT_PQ_WHITE_NITS
        ).coerceIn(
            FableSolExportOptions.MIN_PQ_WHITE_NITS,
            FableSolExportOptions.MAX_PQ_WHITE_NITS
        )

    /** `标准（203 尼特）`还是`自定义（N 尼特）`（D84）：存过这个键即自定义。 */
    internal fun exportPqWhiteMode(context: Context): FableSolExportPqWhiteMode =
        if (prefs(context).contains(KEY_EXPORT_PQ_WHITE)) {
            FableSolExportPqWhiteMode.CUSTOM
        } else {
            FableSolExportPqWhiteMode.STANDARD
        }

    fun setExportPqWhiteNits(context: Context, value: Float) {
        prefs(context).edit().putFloat(
            KEY_EXPORT_PQ_WHITE,
            value.coerceIn(
                FableSolExportOptions.MIN_PQ_WHITE_NITS,
                FableSolExportOptions.MAX_PQ_WHITE_NITS
            )
        ).apply()
    }

    fun exportHighlightStart(context: Context): Int =
        prefs(context).getInt(
            KEY_EXPORT_HIGHLIGHT_START,
            FableSolExportHdr10PlusCurve.DEFAULT_HIGHLIGHT_START_PERCENT
        ).coerceIn(
            FableSolExportHdr10PlusCurve.MIN_HIGHLIGHT_START_PERCENT,
            FableSolExportHdr10PlusCurve.MAX_HIGHLIGHT_START_PERCENT
        )

    fun setExportHighlightStart(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_EXPORT_HIGHLIGHT_START, value).apply()
    }

    /**
     * 用户设定的目标码率；**null 表示自动态**（D147）。
     *
     * 自动态就是"没有保存过这个键"。这样现有的"恢复默认"（删键）天然把滑杆送回自动值，
     * 不必为自动/自定义新增按钮、开关或标签组——那正是 D147 明令不要的东西。
     */
    fun exportBitrateMbps(context: Context): Float? {
        val store = prefs(context)
        if (!store.contains(KEY_EXPORT_BITRATE)) return null
        return store.getFloat(
            KEY_EXPORT_BITRATE, FableSolExportOptions.DEFAULT_BITRATE_MBPS
        ).coerceIn(
            FableSolExportOptions.MIN_BITRATE_MBPS,
            FableSolExportOptions.MAX_BITRATE_MBPS
        )
    }

    fun setExportBitrateMbps(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_EXPORT_BITRATE, value).apply()
    }

    fun exportKeyframeSeconds(context: Context): Float =
        prefs(context).getFloat(
            KEY_EXPORT_KEYFRAME, FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS
        ).coerceIn(
            FableSolExportOptions.MIN_KEYFRAME_SECONDS,
            FableSolExportOptions.MAX_KEYFRAME_SECONDS
        )

    fun setExportKeyframeSeconds(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_EXPORT_KEYFRAME, value).apply()
    }

    /**
     * 「恢复默认」一并清掉导出参数。
     *
     * 逐候选签名的 CQ 原值也要全部清掉：它们的键带签名后缀，漏掉的话恢复默认之后再解析到
     * 同一个编码器路径，仍会读出上一次的手动值。存储版本号保留，避免恢复默认之后又把旧版
     * 迁移跑一遍。
     */
    fun clearExportOptions(context: Context) {
        val sp = prefs(context)
        val editor = sp.edit()
            .remove(KEY_EXPORT_FRAME_RATE)
            .remove(KEY_EXPORT_BITRATE)
            .remove(KEY_EXPORT_KEYFRAME)
            .remove(KEY_EXPORT_PQ_WHITE)
            .remove(KEY_EXPORT_HIGHLIGHT_START)
            .remove(KEY_EXPORT_TILT)
            .remove(KEY_EXPORT_COLOR_MODE)
            .remove(KEY_EXPORT_SDR_MAPPING)
            .remove(KEY_EXPORT_SDR_BIT_DEPTH)
            .remove(KEY_EXPORT_HLG_RANGE)
            .remove(KEY_EXPORT_RATE_CONTROL)
            .remove(KEY_EXPORT_CODEC_FAMILY)
            .remove(KEY_EXPORT_REFERENCE_PEAK)
            .remove(KEY_EXPORT_B_FRAMES)
            .remove(KEY_EXPORT_HIGH_COMPLEXITY)
            .remove(KEY_EXPORT_QP_GUARD)
            .remove(KEY_EXPORT_QUALITY_PENDING)
        for (key in sp.all.keys) {
            if (key.startsWith(KEY_EXPORT_QUALITY_PREFIX)) editor.remove(key)
        }
        editor.apply()
    }
}
