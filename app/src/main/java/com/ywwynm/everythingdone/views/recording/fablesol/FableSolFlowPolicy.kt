package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max
import kotlin.math.pow

/** 感知速度到设计空间物理流速的单调执行器，对应 Python `core/flow_policy.py`。 */
object FableSolFlowPolicy {
    const val REFERENCE_BASE_DPS = 180.0
    const val REFERENCE_GAIN = 1.0
    const val REFERENCE_CURVE = 1.0
    const val NON_SILENT_TRIM_DPS = 10.0

    private val INPUT_ANCHORS = doubleArrayOf(0.0, 0.25, 0.50, 0.70, 0.85, 1.0)
    private val ACTUATOR_ANCHORS = doubleArrayOf(
        0.0,
        24.0 / 180.0,
        62.0 / 180.0,
        105.0 / 180.0,
        145.0 / 180.0,
        1.0
    )

    fun actuatorDrive01(speed01: Double, curve: Double = REFERENCE_CURVE): Double {
        val drive = FableSolSpeed.interp(
            speed01.coerceIn(0.0, 1.0),
            INPUT_ANCHORS,
            ACTUATOR_ANCHORS
        )
        return drive.pow(max(curve, 1e-6))
    }

    /**
     * 返回设计单位 dp/s。10dp/s 的 trim 在 K=0..0.25 上 smoothstep 淡入，数字静音仍严格为零。
     */
    fun targetFlowDps(
        speed01: Double,
        baseDps: Double = REFERENCE_BASE_DPS,
        gain: Double = REFERENCE_GAIN,
        curve: Double = REFERENCE_CURVE,
        idleRatio: Double = 0.0
    ): Double {
        val idle = idleRatio.coerceIn(0.0, 1.0)
        val speed = speed01.coerceIn(0.0, 1.0)
        val drive = actuatorDrive01(speed, curve)
        val q = (speed / 0.25).coerceIn(0.0, 1.0)
        val trimGate = q * q * (3.0 - 2.0 * q)
        val flowGain = max(gain, 0.0)
        return max(baseDps, 0.0) * (idle + (1.0 - idle) * drive * flowGain) +
            NON_SILENT_TRIM_DPS * trimGate * flowGain
    }
}
