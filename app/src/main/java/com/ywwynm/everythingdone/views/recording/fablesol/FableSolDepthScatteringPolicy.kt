package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Stage 2-2 的同色系深水/次表面色板与 Gerstner 横向收拢掩码。 */
internal object FableSolDepthScatteringPolicy {

    data class Palette(val deep: IntArray, val subsurface: IntArray)

    fun derive(base: IntArray): Palette = Palette(
        deep = variant(base, DEEP_LIGHTNESS, DEEP_CHROMA, DEEP_TARGET_HUE_DEG, DEEP_MAX_SHIFT_DEG),
        subsurface = variant(
            base,
            SUBSURFACE_LIGHTNESS,
            SUBSURFACE_CHROMA,
            SUBSURFACE_TARGET_HUE_DEG,
            SUBSURFACE_MAX_SHIFT_DEG
        )
    )

    /** displaced-x 对原始 x 的负导数表示横向收拢；用软阈值抑制普通缓坡。 */
    fun crestPinch(orbitXDerivative: Double): Double {
        val convergence = (-orbitXDerivative).coerceAtLeast(0.0)
        val t = ((convergence - PINCH_START) / (PINCH_FULL - PINCH_START)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun variant(base: IntArray, lightnessScale: Double, chromaScale: Double,
                        targetHueDeg: Double, maxShiftDeg: Double): IntArray {
        val lab = FableSolColor.rgbToOklab(base)
        val chroma = hypot(lab[1], lab[2])
        val hue = atan2(lab[2], lab[1])
        val target = Math.toRadians(targetHueDeg)
        val maxShift = Math.toRadians(maxShiftDeg)
        val shift = if (chroma < MIN_CHROMA_FOR_HUE) 0.0 else
            wrapRadians(target - hue).coerceIn(-maxShift, maxShift)
        val adjustedHue = hue + shift
        val adjustedChroma = chroma * chromaScale
        return FableSolColor.oklabToRgbGamutMapped(doubleArrayOf(
            (lab[0] * lightnessScale).coerceIn(0.0, 1.0),
            cos(adjustedHue) * adjustedChroma,
            sin(adjustedHue) * adjustedChroma
        ))
    }

    private fun wrapRadians(value: Double): Double {
        var wrapped = (value + PI) % (2.0 * PI)
        if (wrapped < 0.0) wrapped += 2.0 * PI
        return wrapped - PI
    }

    private const val DEEP_LIGHTNESS = 0.70
    private const val DEEP_CHROMA = 1.15
    private const val DEEP_TARGET_HUE_DEG = 220.0
    private const val DEEP_MAX_SHIFT_DEG = 8.0
    private const val SUBSURFACE_LIGHTNESS = 1.15
    private const val SUBSURFACE_CHROMA = 0.85
    private const val SUBSURFACE_TARGET_HUE_DEG = 150.0
    private const val SUBSURFACE_MAX_SHIFT_DEG = 4.0
    private const val MIN_CHROMA_FOR_HUE = 0.01
    private const val PINCH_START = 0.035
    private const val PINCH_FULL = 0.22
}
