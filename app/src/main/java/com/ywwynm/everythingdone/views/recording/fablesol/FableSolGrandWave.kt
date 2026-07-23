package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.FLOW_DIR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第 0 层的一枚有限支撑事件浪，对应 Python `core/grand_wave.py`。
 *
 * 它没有独立演出时钟：触发后从上游画外出生，只按第 0 层实时流速与波速平移，完整
 * 通过画面后自然销毁。空间轮廓是 [PLATEAU_DP] 的平顶两侧各接一段 [FLANK_DP] 的
 * quintic 侧翼，因此顶宽与侧翼陡峭度互相独立：单尺度窗（Hann、纯 quintic、高斯）
 * 把两者锁成固定比例，想要更宽的顶就只能拉长侧翼，入场也随之变慢。平顶与侧翼的
 * 接缝、以及支撑外缘都保持 C2 连续，避免进入/离开时产生尖角。
 */
class FableSolGrandWave(pointCount: Int = FableSolSpec.N_POINTS) {
    var active = false
        private set
    var startT = 0.0
        private set
    var lastT = 0.0
        private set
    var centerUDp = 0.0
        internal set
    var plateauDp = PLATEAU_DP
        internal set
    var flankDp = FLANK_DP
        internal set
    /** 支撑全宽 = 平顶 + 两侧翼。 */
    var widthDp = WIDTH_DP
        internal set
    var amplitudeDp = AMPLITUDE_DP
        private set
    var transportDps = 0.0
        private set
    var distanceDp = 0.0
        private set
    var triggerCount = 0
        private set

    private val profileDp = DoubleArray(pointCount)

    /**
     * 可感知部分的半宽，出生与离场都以它为准。
     *
     * 若改用支撑半宽，侧翼最外侧那段低于 3%A 的尾巴会被算进两端判定——它在 420dp
     * 画面上不足 4.4dp，看不见，却要在进出各多花约 0.17s。
     */
    val visibleHalfDp: Double
        get() = plateauDp / 2.0 + flankDp * VISIBLE_CUT_Q

    private fun currentTransport(sim: FableSolSimulation): Double =
        FLOW_DIR * sim.layerWaveSpeedDps(LAYER_INDEX) + sim.layers[LAYER_INDEX].flowDps

    /** active 期间拒绝重触发，保证屏幕上始终只有一道完整事件浪。 */
    fun trigger(
        sim: FableSolSimulation,
        expressionGain: Double = 1.0,
        prelaunchS: Double = 0.0,
        amplitude01: Double = 1.0
    ): Boolean {
        if (active) return false

        val span = sim.geometrySpan()
        val upstreamSign = if (FLOW_DIR < 0.0) 1.0 else -1.0
        val prelaunch = max(prelaunchS, 0.0)
        plateauDp = PLATEAU_DP
        flankDp = FLANK_DP
        widthDp = plateauDp + 2.0 * flankDp
        // D193：按触发语境分级。音乐分支恒 1.0（144dp 现状不变）；说话转变分支
        // 0.556~1.0（≈80~144dp）。低振幅永不逼近侧翼陡峭度上限。
        amplitudeDp = min(
            AMPLITUDE_DP * amplitude01.coerceIn(0.25, 1.0) *
                expressionGain.coerceIn(0.5, 1.5),
            MAX_FLANK_SLOPE * flankDp / SMOOTHERSTEP_MAX_SLOPE
        )
        transportDps = currentTransport(sim)
        centerUDp = upstreamSign * (span / 2.0 + visibleHalfDp + OFFSCREEN_GAP_DP)
        centerUDp += transportDps * prelaunch
        startT = sim.t - prelaunch
        lastT = sim.t
        distanceDp = abs(transportDps * prelaunch)
        active = true
        triggerCount += 1
        return true
    }

    /**
     * 返回内部复用的平顶 + C2 quintic 侧翼轮廓；未激活时返回 null。
     * 调用者只应在当前帧内读取结果，不得长期持有并修改。
     *
     * 峰顶在 [plateauDp] 上严格平坦，不是只在一个点上取驻值；侧翼是镜像的 quintic
     * smootherstep，因此在接缝与支撑外缘处值与一、二阶导都为零，浪得以纯靠空间平移
     * 进出画面，不需要时间包络，也不会在边缘留折痕。
     */
    fun sample(uDp: DoubleArray): DoubleArray? {
        if (!active) return null
        require(uDp.size == profileDp.size) {
            "Grand-wave grid size ${uDp.size} != ${profileDp.size}"
        }
        val halfPlateau = plateauDp / 2.0
        val flank = max(flankDp, 1e-6)
        for (index in uDp.indices) {
            val distance = abs(uDp[index] - centerUDp)
            profileDp[index] = when {
                distance <= halfPlateau -> amplitudeDp
                distance < halfPlateau + flank -> {
                    val x = (distance - halfPlateau) / flank
                    val smoother = x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
                    amplitudeDp * (1.0 - smoother)
                }
                else -> 0.0
            }
        }
        return profileDp
    }

    /** 宽冠经过处保留 12% 背景细节，轮廓外严格保持原水面。 */
    fun backgroundKeep(profileValueDp: Double): Double {
        val presence = (profileValueDp / max(amplitudeDp, 1e-9)).coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - BACKGROUND_KEEP_AT_CREST) * presence
    }

    fun advance(sim: FableSolSimulation) {
        if (!active) return
        val dt = max(sim.t - lastT, 0.0)
        lastT = sim.t
        if (dt <= 0.0) return

        transportDps = currentTransport(sim)
        val displacement = transportDps * dt
        centerUDp += displacement
        distanceDp += abs(displacement)

        val downstreamEdge = if (FLOW_DIR < 0.0) {
            -sim.geometrySpan() / 2.0
        } else {
            sim.geometrySpan() / 2.0
        }
        val trailingEdge = centerUDp - FLOW_DIR * visibleHalfDp
        val passed = if (FLOW_DIR < 0.0) {
            trailingEdge < downstreamEdge
        } else {
            trailingEdge > downstreamEdge
        }
        if (passed) active = false
    }

    companion object {
        const val LAYER_INDEX = 0
        const val FLANK_DP = 420.0
        const val PLATEAU_DP = 120.0
        const val WIDTH_DP = PLATEAU_DP + 2.0 * FLANK_DP   // 支撑全宽
        // D202 复核后维持 144：巨浪总高=水位+浪高，随音量自然涨落；满驱水位 144
        // 时 96+144+144=384 恰平 TimelyClockView 顶。
        const val AMPLITUDE_DP = 144.0
        const val OFFSCREEN_GAP_DP = 8.0

        /**
         * 侧翼上高度降到振幅 3% 的归一化位置。再往外浪高不足 4.4dp（420dp 画面的
         * 1%），即每侧最外 15.7% 的侧翼是看不见的，不该把出生点继续往画外推。
         */
        const val VISIBLE_CUT_Q = 0.8433

        /** quintic smootherstep 在归一化侧翼坐标下的最大斜率。 */
        const val SMOOTHERSTEP_MAX_SLOPE = 1.875

        /**
         * 陡峭度上限，约束对象是侧翼而非支撑宽：支撑宽随平顶增长，用支撑相对比例
         * 会让顶每宽一次、限制就悄悄松一次。数值上 `0.9 × 420 / 1.875 = 201.6dp`，
         * 与旧的 `0.24 × 840` 完全相同。
         */
        const val MAX_FLANK_SLOPE = 0.9
        const val BACKGROUND_KEEP_AT_CREST = 0.12
    }
}
