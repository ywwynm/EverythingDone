package com.ywwynm.everythingdone.spatial

import kotlin.math.exp

/** 与传感器采样率无关的一阶低通。 */
object SpatialSensorSmoothing {

    fun alpha(deltaNanos: Long): Float {
        if (deltaNanos <= 0L) return FALLBACK_ALPHA
        val seconds = (deltaNanos / NANOS_PER_SECOND).coerceIn(0.0001, MAX_DELTA_SECONDS)
        return (1.0 - exp(-seconds / TIME_CONSTANT_SECONDS)).toFloat().coerceIn(0f, 1f)
    }

    private const val TIME_CONSTANT_SECONDS = 0.065
    private const val MAX_DELTA_SECONDS = 0.25
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val FALLBACK_ALPHA = 0.22f
}
