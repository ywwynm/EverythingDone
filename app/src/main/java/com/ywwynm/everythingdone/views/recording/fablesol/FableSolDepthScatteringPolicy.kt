package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.hypot

/** 同色相深水/次表面色板与 Gerstner 横向收拢掩码。 */
internal object FableSolDepthScatteringPolicy {

    data class Palette(val deep: IntArray, val subsurface: IntArray)

    fun derive(base: IntArray): Palette = Palette(
        deep = variant(base, DEEP_LIGHTNESS, DEEP_CHROMA),
        subsurface = variant(base, SUBSURFACE_LIGHTNESS, SUBSURFACE_CHROMA)
    )

    /** displaced-x 对原始 x 的负导数表示横向收拢；用软阈值抑制普通缓坡。 */
    fun crestPinch(orbitXDerivative: Double): Double {
        val convergence = (-orbitXDerivative).coerceAtLeast(0.0)
        val t = ((convergence - PINCH_START) / (PINCH_FULL - PINCH_START)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun variant(base: IntArray, lightnessScale: Double,
                        chromaScale: Double): IntArray {
        val lab = FableSolColor.rgbToOklab(base)
        val chroma = hypot(lab[1], lab[2])
        val scale = if (chroma < MIN_CHROMA_FOR_HUE) 0.0 else chromaScale
        return FableSolColor.oklabToRgbGamutMapped(doubleArrayOf(
            (lab[0] * lightnessScale).coerceIn(0.0, 1.0),
            lab[1] * scale,
            lab[2] * scale
        ))
    }

    private const val DEEP_LIGHTNESS = 0.70
    private const val DEEP_CHROMA = 1.15
    private const val SUBSURFACE_LIGHTNESS = 1.15
    private const val SUBSURFACE_CHROMA = 0.85
    private const val MIN_CHROMA_FOR_HUE = 0.01
    private const val PINCH_START = 0.035
    private const val PINCH_FULL = 0.22
}
