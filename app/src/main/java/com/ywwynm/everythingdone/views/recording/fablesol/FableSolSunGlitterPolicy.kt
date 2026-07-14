package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tan

/**
 * Step E 的连续面太阳碎光路径。
 *
 * 路径只重排既有闪点的出生概率，不改音频映射、单点亮度和 D70 生命周期。近处路径略宽，
 * 远处略收束；固定光源方位只让路径沿深度轴连续偏移，不生成常亮光柱。
 */
internal object FableSolSunGlitterPolicy {

    const val OUTSIDE_PATH_WEIGHT = 0.12
    private const val NEAR_HALF_WIDTH_01 = 0.24
    private const val FAR_HALF_WIDTH_01 = 0.11
    private const val PATH_TILT_SCALE = 0.28

    fun pathCenter01(depth01: Double, lightAzimuthDeg: Double): Double {
        val depth = depth01.coerceIn(0.0, 1.0)
        val azimuth = lightAzimuthDeg.coerceIn(-55.0, 55.0)
        val pathTilt = tan(Math.toRadians(azimuth)) * PATH_TILT_SCALE
        return (0.5 + pathTilt * (depth - 0.5)).coerceIn(0.18, 0.82)
    }

    fun pathHalfWidth01(depth01: Double): Double {
        val depth = depth01.coerceIn(0.0, 1.0)
        return NEAR_HALF_WIDTH_01 + (FAR_HALF_WIDTH_01 - NEAR_HALF_WIDTH_01) * depth
    }

    fun birthWeight(x01: Double, depth01: Double, lightAzimuthDeg: Double): Double {
        val center = pathCenter01(depth01, lightAzimuthDeg)
        val normalizedDistance = abs(x01.coerceIn(0.0, 1.0) - center) /
            pathHalfWidth01(depth01)
        val lobe = exp(-0.5 * normalizedDistance * normalizedDistance)
        return OUTSIDE_PATH_WEIGHT + (1.0 - OUTSIDE_PATH_WEIGHT) * lobe
    }

    /** 闪点只向连续面的远深方向轻微展开；远层更短，避免形成纵向光柱。 */
    fun depthAxisLengthDp(layer: Int, pathWeight: Double): Double {
        val base = FableSolMaterialPolicy.glintDepthLengthDp(layer)
        return base * (0.72 + 0.28 * pathWeight.coerceIn(0.0, 1.0))
    }
}
