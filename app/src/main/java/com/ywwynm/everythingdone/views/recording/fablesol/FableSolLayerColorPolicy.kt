package com.ywwynm.everythingdone.views.recording.fablesol

internal class FableSolLayerBaseColors(
    @JvmField val start: IntArray,
    @JvmField val end: IntArray
)

/**
 * 水层基础色与混白量策略。与 Canvas 绘制解耦，便于验证第 0 层的 Thing 身份色约束。
 */
internal object FableSolLayerColorPolicy {

    fun baseColors(start: IntArray, gradientEnd: IntArray?): FableSolLayerBaseColors {
        return FableSolLayerBaseColors(
            start.copyOf(),
            gradientEnd?.copyOf() ?: start.copyOf()
        )
    }

    fun lightenAmount(
        depth01: Double,
        lightenFar: Double,
        moodBright: Double,
        breath: Double
    ): Double {
        val dynamicLighten = moodBright * 0.08 + breath
        return (depth01 * (lightenFar + dynamicLighten)).coerceIn(0.0, 1.0)
    }
}
