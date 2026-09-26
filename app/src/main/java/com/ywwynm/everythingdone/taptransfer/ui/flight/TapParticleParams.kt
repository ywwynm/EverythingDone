package com.ywwynm.everythingdone.taptransfer.ui.flight

import android.content.Context
import android.graphics.RectF
import android.view.animation.PathInterpolator
import com.ywwynm.everythingdone.views.particledismiss.ParticleSink
import org.json.JSONObject
import java.io.InputStream
import java.util.Properties

/**
 * 碰一碰的动画参数，读自 `shared/tap-transfer/particles.properties`（打包后是 assets 里的
 * [ASSET]）。桌面模拟器（`docs/features/thing-transfer/particle-sim`）读同一份文件：在模拟器里
 * 调好的值写回文件，重新编译即在手机上生效，两边不会各记一套。各项的含义见文件里的注释。
 */
internal class TapParticleParams private constructor(private val values: Properties) {

    /** 终点的剥离、飞行与转向。 */
    val sink: ParticleSink.Tuning

    // ---- 发送：打开分享界面 ----

    /** 黑色罩层在浅色 / 深色模式下的不透明度。 */
    val sendDimAlpha: FloatArray
    val openMs: Long
    val closeMs: Long
    val liftMs: Long
    val liftEase: FloatArray
    val dropMs: Long
    val dropEase: FloatArray

    /** 抬起进度的前这一段里卡片淡入。 */
    val liftFade: Float
    val detailRiseDp: Float
    val detailRiseScale: Float
    val elevationDp: Float
    val shadowDropMs: Long
    val shadowRestoreMs: Long
    val pulseRings: Int
    val pulsePeriodS: Float

    /** 扩散圈半径 = 第一项 + 第二项 × 进度（dp）。 */
    val pulseRadiusDp: FloatArray
    val pulseAlpha: Float

    // ---- 发送：流出与流回 ----

    /** 卡片流进天线的时长（毫秒，进度 0 → 1）与缓动控制点。 */
    val outflowMs: Long
    val outflowEase: FloatArray

    /** 「已发送」停留多久再流回（毫秒）。 */
    val sentHoldMs: Long

    /** 从天线流回、拼回卡片的时长（毫秒，进度 1 → 0）与缓动控制点。 */
    val reformMs: Long
    val reformEase: FloatArray

    // ---- 接收：罩层与屏幕四周的光尘带 ----

    /** 页面底色的罩层在浅色 / 深色模式下的不透明度。 */
    val receiveDimAlpha: FloatArray
    val receiveDimFadeS: Float

    /** 光尘带出现、退场、拼好之后重新出现的淡入淡出（秒）；也是天线处扩散圈淡入淡出的时长。 */
    val borderFadeS: Float

    /** 拼卡那一层开始流入时整条带的不透明度收到 0 的时长（秒）。 */
    val borderConvergeS: Float

    /** 光尘带外沿离屏幕边缘的距离（dp）；流向四周的颗粒的终点在它向里半条 [borderLaneDp] 处。 */
    val borderInsetDp: Float
    val borderLaneDp: Float

    /** 光尘带的默认调色板（ARGB，不透明）、转一圈的秒数与换色的时长。 */
    val borderPalette: IntArray
    val borderPaletteCycleS: Float
    val borderPaletteFadeS: Float
    val holeCloseS: Float

    // ---- 接收：颗粒流入 ----

    /** 颗粒流进空位、拼成卡片的时长（秒）。 */
    val appearS: Float

    /** 流向屏幕边框（而不是天线）的颗粒占比。 */
    val frameShare: Float

    // ---- 第三版（2026-09-25，设计见 docs/features/thing-transfer/design-2026-09-25-particle-motion.md）

    /** 发送用的柔性终点：没有边框，整团滑进天线。 */
    val sendField: ParticleSink.Field

    /** 扩散圈在流出时最多收缩到的比例（半径乘 1 − 这一项 × 收缩程度）。 */
    val pulseShrink: Float

    /** 倒放时间轴：传输进度的系数、最低速度（每秒）、数据到齐之后逼近的上限、进度的指数。 */
    val emergePace: FloatArray

    /** 两层交叉淡化：拼卡那层淡入的时长（秒）、进度期间那层淡出的起止（流入开始之后的秒数）。 */
    val crossfade: FloatArray

    /** 光尘接手：交接之后倒放的起、止（时间轴），只挑正向飞行时间不短于第三项的颗粒。 */
    val dustJoin: FloatArray

    // 光尘带（屏幕四周的微片）
    val dustPerDp: Float
    val dustBandDp: Float
    val dustSizeDp: FloatArray
    val dustSpeedDp: Float
    val dustWanderDp: Float
    val dustFlipHz: FloatArray
    val dustRest: Float
    val dustSpreadDp: Float
    val dustInwardHz: FloatArray

    /** 带底下的柔光：高斯宽度（dp）、峰值不透明度。 */
    val dustGlow: FloatArray

    /** 流入开始后没被接手的光尘向外退的距离（dp）与用时（秒）。 */
    val dustRetreat: FloatArray

    private val arrival: FloatArray
    private val glide: FloatArray
    private val absorbDp: FloatArray
    private val groupStagger: FloatArray
    private val handoffValues: FloatArray
    private val emergeSink: FloatArray
    private val frameBlendDp: Float

    init {
        val homing = numbers("sink_homing", 2)
        sink = ParticleSink.Tuning(
            releaseScale = number("sink_release_scale"),
            arrivalEnd = number("sink_arrival_end"),
            flightBase = number("sink_flight_base"),
            flightPerSpan = number("sink_flight_per_span"),
            flightJitter = number("sink_flight_jitter"),
            minLife = number("sink_min_life"),
            swirl = number("sink_swirl"),
            homingStart = homing[0],
            homingEnd = homing[1],
            frameJitter = number("sink_frame_jitter")
        )
        sendDimAlpha = numbers("send_dim_alpha", 2)
        openMs = number("send_open_ms").toLong()
        closeMs = number("send_close_ms").toLong()
        liftMs = number("send_lift_ms").toLong()
        liftEase = numbers("send_lift_ease", 4)
        dropMs = number("send_drop_ms").toLong()
        dropEase = numbers("send_drop_ease", 4)
        liftFade = number("send_lift_fade")
        detailRiseDp = number("send_detail_rise_dp")
        detailRiseScale = number("send_detail_rise_scale")
        elevationDp = number("send_elevation_dp")
        shadowDropMs = number("send_shadow_drop_ms").toLong()
        shadowRestoreMs = number("send_shadow_restore_ms").toLong()
        pulseRings = number("send_pulse_rings").toInt()
        pulsePeriodS = number("send_pulse_period_s")
        pulseRadiusDp = numbers("send_pulse_radius_dp", 2)
        pulseAlpha = number("send_pulse_alpha")
        outflowMs = number("send_outflow_ms").toLong()
        outflowEase = numbers("send_outflow_ease", 4)
        sentHoldMs = number("send_sent_hold_ms").toLong()
        reformMs = number("send_reform_ms").toLong()
        reformEase = numbers("send_reform_ease", 4)
        receiveDimAlpha = numbers("receive_dim_alpha", 2)
        receiveDimFadeS = number("receive_dim_fade_s")
        borderFadeS = number("receive_border_fade_s")
        borderConvergeS = number("receive_border_converge_s")
        borderInsetDp = number("receive_border_inset_dp")
        borderLaneDp = number("receive_border_lane_dp")
        borderPalette = colors("receive_border_palette")
        borderPaletteCycleS = number("receive_border_palette_cycle_s")
        borderPaletteFadeS = number("receive_border_palette_fade_s")
        holeCloseS = number("receive_hole_close_s")
        appearS = number("receive_appear_s")
        frameShare = number("receive_frame_share")

        arrival = numbers("sink_arrival", 2)
        glide = numbers("sink_glide", 3)
        absorbDp = numbers("sink_absorb_dp", 2)
        groupStagger = numbers("sink_group_stagger", 2)
        handoffValues = numbers("receive_handoff", 3)
        emergeSink = numbers("receive_emerge_sink", 4)
        frameBlendDp = number("receive_frame_blend_dp")
        sendField = field(spreadDp = number("sink_spread_dp"), absorbInnerDp = absorbDp[0],
            absorbOuterDp = absorbDp[1], absorbShare = number("sink_absorb_share"), fadeS = number("sink_fade_s"),
            bandDp = number("sink_band_dp"), cards = emptyList())
        pulseShrink = number("send_pulse_shrink")
        emergePace = numbers("receive_emerge_pace", 4)
        crossfade = numbers("receive_crossfade", 3)
        dustJoin = numbers("receive_dust_join", 3)
        dustPerDp = number("receive_dust_per_dp")
        dustBandDp = number("receive_dust_band_dp")
        dustSizeDp = numbers("receive_dust_size_dp", 2)
        dustSpeedDp = number("receive_dust_speed_dp")
        dustWanderDp = number("receive_dust_wander_dp")
        dustFlipHz = numbers("receive_dust_flip_hz", 2)
        dustRest = number("receive_dust_rest")
        dustSpreadDp = number("receive_dust_spread_dp")
        dustInwardHz = numbers("receive_dust_inward_hz", 2)
        dustGlow = numbers("receive_dust_glow", 2)
        dustRetreat = numbers("receive_dust_retreat", 2)

        require(sink.releaseScale > 0f && sink.releaseScale <= 1f) { "sink_release_scale 须在 (0, 1]" }
        require(sink.arrivalEnd > 0f && sink.arrivalEnd <= 1f) { "sink_arrival_end 须在 (0, 1]" }
        require(sink.flightBase > 0f && sink.flightPerSpan >= 0f && sink.minLife > 0f) { "飞行时长须为正" }
        require(sink.flightJitter in 0f..0.9f) { "sink_flight_jitter 须在 [0, 0.9]" }
        // GLSL 的 smoothstep 在起点不小于终点时没有定义
        require(sink.homingStart >= 0f && sink.homingStart < sink.homingEnd) { "sink_homing 须满足 0 ≤ 起 < 止" }
        require(listOf(outflowMs, reformMs, openMs, closeMs, liftMs, dropMs, shadowDropMs, shadowRestoreMs).all { it > 0 } &&
            sentHoldMs >= 0) { "时长须为正" }
        require(listOf(appearS, receiveDimFadeS, borderFadeS, borderConvergeS,
            borderPaletteCycleS, borderPaletteFadeS, holeCloseS, pulsePeriodS).all { it > 0f }) { "时长须为正" }
        require(frameShare in 0f..1f) { "receive_frame_share 须在 [0, 1]" }
        for (ease in listOf(outflowEase, reformEase, liftEase, dropEase)) {
            require(ease[0] in 0f..1f && ease[2] in 0f..1f) { "缓动控制点的 x 须在 [0, 1]" }
        }
        require((sendDimAlpha + receiveDimAlpha).all { it in 0f..1f }) { "罩层不透明度须在 [0, 1]" }
        require(liftFade > 0f && liftFade <= 1f) { "send_lift_fade 须在 (0, 1]" }
        require(pulseRings in 0..8) { "圈数越界" }
        require(borderPalette.isNotEmpty()) { "receive_border_palette 至少一个颜色" }
        // 第三版：与模拟器 `Params.problems()` 逐条对应
        require(arrival[0] > 0f && arrival[0] <= arrival[1] && arrival[1] <= 1f) { "sink_arrival 须满足 0 < 最早 ≤ 最晚 ≤ 1" }
        require(glide[0] >= 0f && glide[0] < glide[1] && glide[1] <= 1f && glide[2] > 0f) {
            "sink_glide 须满足 0 ≤ 起 < 止 ≤ 1，接近增益 > 0"
        }
        require(absorbDp[0] >= 0f && absorbDp[0] < absorbDp[1]) { "sink_absorb_dp 须满足 0 ≤ 内 < 外" }
        require(sendField.absorbShare in 0f..1f && dustRest in 0f..1f) { "比例须在 [0, 1]" }
        require(sendField.vortexDp > 0f && sendField.freeLife > 0f && sendField.fadeS > 0f) {
            "sink_vortex_dp、sink_free_life、sink_fade_s 须为正"
        }
        require(sendField.nearScale > 0f && sendField.nearScale <= 1f) { "sink_near_scale 须在 (0, 1]" }
        require(dustSizeDp[0] > 0f && dustSizeDp[0] <= dustSizeDp[1] && dustFlipHz[0] >= 0f &&
            dustFlipHz[0] <= dustFlipHz[1]) { "光尘的尺寸与翻转频率须满足 最小 ≤ 最大" }
        require(dustPerDp > 0f && dustBandDp > 0f) { "光尘的密度与带宽须为正" }
        require(emergePace[0] >= 0f && emergePace[1] > 0f && emergePace[0] < emergePace[2] && emergePace[2] < 1f &&
            emergePace[3] > 0f) { "receive_emerge_pace 须满足 系数不为负、最低速度为正、系数 < 上限 < 1、指数为正" }
        require(crossfade[0] > 0f && crossfade[1] >= 0f && crossfade[1] < crossfade[2]) {
            "receive_crossfade 须满足 淡入时长为正、淡出的起 < 止"
        }
        require(dustJoin[0] >= 0f && dustJoin[0] < dustJoin[1] && dustJoin[1] < 1f && dustJoin[2] > 0f) {
            "receive_dust_join 须满足 0 ≤ 起 < 止 < 1、最短飞行为正"
        }
        require(emergeSink.all { it >= 0f } && emergeSink[2] <= 1f && emergeSink[3] > 0f) {
            "天线处浮现的半径、占比与淡入须合法"
        }
        require(handoffValues[0] > 0f) { "交接的牵引渐入须为正" }
        require(dustRetreat[1] > 0f) { "receive_dust_retreat 的用时须为正" }
        require(frameBlendDp >= 0f) { "receive_frame_blend_dp 不能为负" }
    }

    /**
     * 接收用的柔性终点。两层（进度期间的代理卡片、拼卡的真实卡片）共用，与模拟器 `sim_emerge.receive_params`
     * 相同：天线处浮现的那一份的散开半径、贴近天线时淡去的外半径（内半径 0）、占比与寿命末端的淡出（倒放里即
     * 冒出时的淡入）取 receive_emerge_sink；流向边框的颗粒落在光尘带里（带宽 + 扩散距离 × 1.3，列表让位期间
     * 光尘还在继续扩散）。[cards] 是几张卡片各自的矩形（动画层像素），两张以上时按卡片分组。
     */
    fun receiveField(cards: List<RectF>): ParticleSink.Field = field(
        spreadDp = emergeSink[0], absorbInnerDp = 0f, absorbOuterDp = emergeSink[1], absorbShare = emergeSink[2],
        fadeS = emergeSink[3], bandDp = dustBandDp + dustSpreadDp * 1.3f, cards = cards)

    private fun field(spreadDp: Float, absorbInnerDp: Float, absorbOuterDp: Float, absorbShare: Float, fadeS: Float,
                      bandDp: Float, cards: List<RectF>) = ParticleSink.Field(
        arrivalFirst = arrival[0], arrivalLast = arrival[1],
        glideStart = glide[0], glideEnd = glide[1], glideGain = glide[2],
        spiral = number("sink_spiral"), absorbShare = absorbShare, spreadDp = spreadDp, drift = number("sink_drift"),
        freeLife = number("sink_free_life"), absorbInnerDp = absorbInnerDp, absorbOuterDp = absorbOuterDp,
        fadeS = fadeS, nearScale = number("sink_near_scale"),
        bandDp = bandDp, bandSpiral = number("sink_band_spiral"), curl = number("sink_curl"),
        vortexDp = number("sink_vortex_dp"),
        groupBorn = groupStagger[0], groupArrive = groupStagger[1], groupSwirl = number("sink_group_swirl"),
        handoffGlide = handoffValues[0], handoffCurl = handoffValues[1], handoffScale = handoffValues[2],
        blendDp = frameBlendDp, cards = cards)

    /** 原始键值，调试落盘用（[TapFixtureDump]）。 */
    fun asJson(): JSONObject = JSONObject().apply {
        for (key in values.stringPropertyNames().sorted()) put(key, values.getProperty(key).trim())
    }

    fun outflowInterpolator() = interpolator(outflowEase)

    fun reformInterpolator() = interpolator(reformEase)

    fun liftInterpolator() = interpolator(liftEase)

    fun dropInterpolator() = interpolator(dropEase)

    private fun number(key: String): Float =
        values.getProperty(key)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: error("碰一碰粒子参数缺少或不是有限数：$key")

    private fun numbers(key: String, count: Int): FloatArray {
        val parts = values.getProperty(key)?.split(',')?.map { it.trim().toFloatOrNull() }
        require(parts != null && parts.size == count && parts.all { it != null && it.isFinite() }) {
            "碰一碰粒子参数 $key 须为 $count 个有限数"
        }
        return FloatArray(count) { parts[it]!! }
    }

    private fun colors(key: String): IntArray {
        val parts = values.getProperty(key)?.split(',')?.map { it.trim() } ?: error("碰一碰粒子参数缺少：$key")
        return IntArray(parts.size) { k ->
            val rgb = parts[k].removePrefix("#")
            require(rgb.length == 6) { "碰一碰粒子参数 $key 的第 ${k + 1} 个颜色须为 6 位十六进制" }
            (rgb.toLongOrNull(16) ?: error("碰一碰粒子参数 $key 的颜色不合法：${parts[k]}")).toInt() or (0xFF shl 24)
        }
    }

    companion object {
        const val ASSET = "tap-transfer/particles.properties"

        @Volatile private var cached: TapParticleParams? = null

        fun get(context: Context): TapParticleParams = cached ?: synchronized(this) {
            cached ?: context.applicationContext.assets.open(ASSET).use { read(it) }.also { cached = it }
        }

        fun read(stream: InputStream): TapParticleParams =
            TapParticleParams(stream.bufferedReader(Charsets.UTF_8).use { Properties().apply { load(it) } })

        private fun interpolator(ease: FloatArray) = PathInterpolator(ease[0], ease[1], ease[2], ease[3])

        private fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
