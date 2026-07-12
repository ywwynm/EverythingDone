package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.atan

/** 将手机的完整前后俯仰平滑映射到水体惯性与安全观察角。 */
object FableSolPitchPolicy {
    const val RAW_LIMIT_DEG = 90.0
    const val MOTION_LIMIT_DEG = 55.0
    const val VIEW_MIN_DEG = 14.0
    const val VIEW_MAX_DEG = 68.0

    private const val MOTION_SOFTNESS = 2.0
    private const val VIEW_SOFTNESS = 5.0

    fun rawPitchDeg(degrees: Double): Double =
        degrees.coerceIn(-RAW_LIMIT_DEG, RAW_LIMIT_DEG)

    /**
     * 水体惯性仍以 ±55° 为最大安全幅度，但输入不再在 ±55° 截断；
     * 手机从 0° 持续倾到 ±90° 时，目标角始终单调变化并逐渐放缓。
     */
    fun motionPitchDeg(rawDegrees: Double): Double {
        val raw = rawPitchDeg(rawDegrees)
        val amount = MOTION_LIMIT_DEG * softUnit(abs(raw), MOTION_SOFTNESS)
        return if (raw < 0.0) -amount else amount
    }

    /** 将完整手机俯仰连续压缩到渲染可用的观察角范围，不产生中途平台。 */
    fun viewElevationDeg(rawDegrees: Double, baseDegrees: Double): Double {
        val base = baseDegrees.coerceIn(VIEW_MIN_DEG, VIEW_MAX_DEG)
        val raw = rawPitchDeg(rawDegrees)
        val span = if (raw < 0.0) base - VIEW_MIN_DEG else VIEW_MAX_DEG - base
        val offset = span * softUnit(abs(raw), VIEW_SOFTNESS)
        return if (raw < 0.0) base - offset else base + offset
    }

    private fun softUnit(absDegrees: Double, softness: Double): Double {
        val q = (absDegrees / RAW_LIMIT_DEG).coerceIn(0.0, 1.0)
        return atan(softness * q) / atan(softness)
    }
}
