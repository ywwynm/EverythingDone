package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.max
import kotlin.math.pow

/** 感知速度到设计空间物理流速的单调执行器，对应 Python `core/flow_policy.py`。 */
object FableSolFlowPolicy {
    const val REFERENCE_BASE_DPS = 180.0
    const val REFERENCE_GAIN = 1.0
    const val REFERENCE_CURVE = 1.0
    // 可闻即有流（2026-07-21）：旧的 10dp/s 微调在 K=0..0.25 上渐入，于是安静段
    // （实测 K01 中位 0.17）L0 只有 1.8dp/s，穿屏要 176 秒——画面读作"完全不动"。
    // 现在把它抬到 20dp/s 并在 K=0..0.10 内渐入：任何可闻的声音都立刻有可见水流，
    // 数字静音（K=0）仍严格为 0。
    const val NON_SILENT_TRIM_DPS = 20.0
    private const val TRIM_FULL_AT_K = 0.10

    private val INPUT_ANCHORS = doubleArrayOf(0.0, 0.25, 0.50, 0.70, 0.85, 1.0)
    // 低端锚点整体抬高、高端基本不动：K=0.25 由 24→42dp/s，K=1.0 只从 180 → 180
    // （叠加 trim 后 190→200）。安静段明显变快，满强度的观感几乎不变。
    private val ACTUATOR_ANCHORS = doubleArrayOf(
        0.0,
        42.0 / 180.0,
        68.0 / 180.0,
        106.0 / 180.0,
        146.0 / 180.0,
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
     * 返回设计单位 dp/s。20dp/s 的 trim 在 K=0..0.10 上 smoothstep 淡入，数字静音仍严格为零。
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
        val q = (speed / TRIM_FULL_AT_K).coerceIn(0.0, 1.0)
        val trimGate = q * q * (3.0 - 2.0 * q)
        val flowGain = max(gain, 0.0)
        return max(baseDps, 0.0) * (idle + (1.0 - idle) * drive * flowGain) +
            NON_SILENT_TRIM_DPS * trimGate * flowGain
    }
}
