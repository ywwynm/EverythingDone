package com.ywwynm.everythingdone.taptransfer.ui.flight

import android.content.Context
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

    /** 天线处的光，亮度见 [glowAt]；半径 = [glowRadiusDp] 第一项 + 第二项 × 亮度（dp），不透明度 = [glowAlpha] × 亮度。 */
    val glowCurve: FloatArray
    val glowRadiusDp: FloatArray
    val glowAlpha: Float

    // ---- 接收：罩层与屏幕边框 ----

    /** 页面底色的罩层在浅色 / 深色模式下的不透明度。 */
    val receiveDimAlpha: FloatArray
    val receiveDimFadeS: Float

    /** 边框出现、退场的淡入淡出（秒）。 */
    val borderFadeS: Float

    /** 颗粒开始从四边流出时边框淡去的时长（秒）。 */
    val borderConvergeS: Float
    val borderInsetDp: Float
    val borderSpacingDp: Float
    val borderLaneDp: Float
    val borderSpeedDp: Float
    val borderWobbleDp: Float
    val borderTrailS: Float

    /** 边框粒子实心半径的范围（dp）。 */
    val borderRadiusDp: FloatArray
    val borderParticleFadeS: Float

    /** 边框的默认调色板（ARGB，不透明）。 */
    val borderPalette: IntArray
    val borderPaletteCycleS: Float
    val borderPaletteFadeS: Float
    val glowRings: Int
    val glowRingDp: Float
    val receiveGlowAlpha: Float
    val holeCloseS: Float

    // ---- 接收：颗粒流入 ----

    /** 颗粒流进空位、拼成卡片的时长（秒）。 */
    val appearS: Float

    /** 流向屏幕边框（而不是天线）的颗粒占比。 */
    val frameShare: Float

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
        glowCurve = numbers("send_glow_curve", 4)
        glowRadiusDp = numbers("send_glow_radius_dp", 2)
        glowAlpha = number("send_glow_alpha")
        receiveDimAlpha = numbers("receive_dim_alpha", 2)
        receiveDimFadeS = number("receive_dim_fade_s")
        borderFadeS = number("receive_border_fade_s")
        borderConvergeS = number("receive_border_converge_s")
        borderInsetDp = number("receive_border_inset_dp")
        borderSpacingDp = number("receive_border_spacing_dp")
        borderLaneDp = number("receive_border_lane_dp")
        borderSpeedDp = number("receive_border_speed_dp")
        borderWobbleDp = number("receive_border_wobble_dp")
        borderTrailS = number("receive_border_trail_s")
        borderRadiusDp = numbers("receive_border_radius_dp", 2)
        borderParticleFadeS = number("receive_border_particle_fade_s")
        borderPalette = colors("receive_border_palette")
        borderPaletteCycleS = number("receive_border_palette_cycle_s")
        borderPaletteFadeS = number("receive_border_palette_fade_s")
        glowRings = number("receive_glow_rings").toInt()
        glowRingDp = number("receive_glow_ring_dp")
        receiveGlowAlpha = number("receive_glow_alpha")
        holeCloseS = number("receive_hole_close_s")
        appearS = number("receive_appear_s")
        frameShare = number("receive_frame_share")

        require(sink.releaseScale > 0f && sink.releaseScale <= 1f) { "sink_release_scale 须在 (0, 1]" }
        require(sink.arrivalEnd > 0f && sink.arrivalEnd <= 1f) { "sink_arrival_end 须在 (0, 1]" }
        require(sink.flightBase > 0f && sink.flightPerSpan >= 0f && sink.minLife > 0f) { "飞行时长须为正" }
        require(sink.flightJitter in 0f..0.9f) { "sink_flight_jitter 须在 [0, 0.9]" }
        // GLSL 的 smoothstep 在起点不小于终点时没有定义
        require(sink.homingStart >= 0f && sink.homingStart < sink.homingEnd) { "sink_homing 须满足 0 ≤ 起 < 止" }
        require(listOf(outflowMs, reformMs, openMs, closeMs, liftMs, dropMs, shadowDropMs, shadowRestoreMs).all { it > 0 } &&
            sentHoldMs >= 0) { "时长须为正" }
        require(listOf(appearS, receiveDimFadeS, borderFadeS, borderConvergeS, borderTrailS, borderParticleFadeS,
            borderPaletteCycleS, borderPaletteFadeS, holeCloseS, pulsePeriodS).all { it > 0f }) { "时长须为正" }
        require(frameShare in 0f..1f) { "receive_frame_share 须在 [0, 1]" }
        require(glowCurve[0] < glowCurve[1] && glowCurve[2] < glowCurve[3]) { "send_glow_curve 的两段须各自递增" }
        for (ease in listOf(outflowEase, reformEase, liftEase, dropEase)) {
            require(ease[0] in 0f..1f && ease[2] in 0f..1f) { "缓动控制点的 x 须在 [0, 1]" }
        }
        require((sendDimAlpha + receiveDimAlpha).all { it in 0f..1f }) { "罩层不透明度须在 [0, 1]" }
        require(liftFade > 0f && liftFade <= 1f) { "send_lift_fade 须在 (0, 1]" }
        require(pulseRings in 0..8 && glowRings in 0..64) { "圈数越界" }
        require(borderSpacingDp > 0f && borderRadiusDp[0] > 0f && borderRadiusDp[0] <= borderRadiusDp[1]) { "边框粒子尺寸不合法" }
        require(borderPalette.isNotEmpty()) { "receive_border_palette 至少一个颜色" }
    }

    /** 原始键值，调试落盘用（[TapFixtureDump]）。 */
    fun asJson(): JSONObject = JSONObject().apply {
        for (key in values.stringPropertyNames().sorted()) put(key, values.getProperty(key).trim())
    }

    fun outflowInterpolator() = interpolator(outflowEase)

    fun reformInterpolator() = interpolator(reformEase)

    fun liftInterpolator() = interpolator(liftEase)

    fun dropInterpolator() = interpolator(dropEase)

    /** 天线处的光（0–1）：颗粒大量汇入的那一段最亮，开头与结尾都淡。[progress] 为流出进度。 */
    fun glowAt(progress: Float): Float =
        smooth(glowCurve[0], glowCurve[1], progress) * (1f - smooth(glowCurve[2], glowCurve[3], progress))

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
