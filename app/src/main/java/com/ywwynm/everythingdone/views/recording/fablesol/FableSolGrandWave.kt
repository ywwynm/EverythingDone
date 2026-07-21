package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.FLOW_DIR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第 0 层的一枚有限支撑事件浪，对应 Python `core/grand_wave.py`。
 *
 * 它没有独立演出时钟：触发后从上游画外出生，只按第 0 层实时流速与波速平移，完整
 * 通过画面后自然销毁。空间轮廓在支撑边界处保持 C2 连续，避免进入/离开时产生尖角。
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
    var widthDp = WIDTH_DP
        private set
    var amplitudeDp = AMPLITUDE_DP
        private set
    var transportDps = 0.0
        private set
    var distanceDp = 0.0
        private set
    var triggerCount = 0
        private set

    private val profileDp = DoubleArray(pointCount)

    private fun currentTransport(sim: FableSolSimulation): Double =
        FLOW_DIR * sim.layerWaveSpeedDps(LAYER_INDEX) + sim.layers[LAYER_INDEX].flowDps

    /** active 期间拒绝重触发，保证屏幕上始终只有一道完整事件浪。 */
    fun trigger(
        sim: FableSolSimulation,
        expressionGain: Double = 1.0,
        prelaunchS: Double = 0.0
    ): Boolean {
        if (active) return false

        val span = sim.geometrySpan()
        val upstreamSign = if (FLOW_DIR < 0.0) 1.0 else -1.0
        val prelaunch = max(prelaunchS, 0.0)
        widthDp = WIDTH_DP
        amplitudeDp = min(
            AMPLITUDE_DP * expressionGain.coerceIn(0.5, 1.5),
            MAX_HEIGHT_WIDTH_RATIO * widthDp
        )
        transportDps = currentTransport(sim)
        centerUDp = upstreamSign * (span / 2.0 + widthDp / 2.0 + OFFSCREEN_GAP_DP)
        centerUDp += transportDps * prelaunch
        startT = sim.t - prelaunch
        lastT = sim.t
        distanceDp = abs(transportDps * prelaunch)
        active = true
        triggerCount += 1
        return true
    }

    /**
     * 返回内部复用的 C2 quintic compact profile；未激活时返回 null。
     * 调用者只应在当前帧内读取结果，不得长期持有并修改。
     */
    fun sample(uDp: DoubleArray): DoubleArray? {
        if (!active) return null
        require(uDp.size == profileDp.size) {
            "Grand-wave grid size ${uDp.size} != ${profileDp.size}"
        }
        val halfWidth = widthDp / 2.0
        for (index in uDp.indices) {
            val q = abs((uDp[index] - centerUDp) / halfWidth)
            profileDp[index] = if (q < 1.0) {
                val smoother = q * q * q * (q * (q * 6.0 - 15.0) + 10.0)
                amplitudeDp * (1.0 - smoother)
            } else {
                0.0
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
        val trailingEdge = centerUDp - FLOW_DIR * widthDp / 2.0
        val passed = if (FLOW_DIR < 0.0) {
            trailingEdge < downstreamEdge
        } else {
            trailingEdge > downstreamEdge
        }
        if (passed) active = false
    }

    companion object {
        const val LAYER_INDEX = 0
        const val WIDTH_DP = 840.0
        const val AMPLITUDE_DP = 144.0
        const val OFFSCREEN_GAP_DP = 8.0
        const val MAX_HEIGHT_WIDTH_RATIO = 0.24
        const val BACKGROUND_KEEP_AT_CREST = 0.12
    }
}
