package com.ywwynm.everythingdone.views.recording.fablesol

/** 同色相深水/次表面色板与 Gerstner 横向收拢掩码。 */
internal object FableSolDepthScatteringPolicy {

    /** 只描述透射/次表面散射分瓣；阴影必须由当前层当前位置的主体色另行派生。 */
    data class Palette(val subsurface: IntArray)

    private val paletteCache = object : LinkedHashMap<Int, Palette>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Palette>?): Boolean =
            size > PALETTE_CACHE_CAPACITY
    }

    @Synchronized
    fun derive(base: IntArray): Palette {
        val key = (base[0].coerceIn(0, 255) shl 16) or
            (base[1].coerceIn(0, 255) shl 8) or base[2].coerceIn(0, 255)
        return paletteCache[key] ?: Palette(
            subsurface = subsurfaceVariant(base)
        ).also { paletteCache[key] = it }
    }

    /** displaced-x 对原始 x 的负导数表示横向收拢；用软阈值抑制普通缓坡。 */
    fun crestPinch(orbitXDerivative: Double): Double {
        val convergence = (-orbitXDerivative).coerceAtLeast(0.0)
        val t = ((convergence - PINCH_START) / (PINCH_FULL - PINCH_START)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * 从当前层、当前位置的主体色派生局部 SSS 颜色。只增加 OKLab L，保持原始 hue 与绝对
     * chroma；仅在目标超出 sRGB 时由统一 gamut mapping 沿同一 hue 压缩 chroma。
     * 高明度输入的增量连续收敛，避免浅粉、浅黄和浅青被推成中性白。
     */
    private fun subsurfaceVariant(base: IntArray): IntArray {
        val lab = FableSolColor.rgbToOklab(base)
        val lightness = lab[0].coerceIn(0.0, 1.0)
        val darkLift = BASE_LIGHTNESS_DELTA + DARK_LIGHTNESS_BOOST *
            (1.0 - smoothstep(DARK_FADE_START, DARK_FADE_END, lightness))
        val delta = darkLift *
            (1.0 - smoothstep(LIGHT_FADE_START, LIGHT_FADE_END, lightness))
        val targetLightness = (lightness + delta)
            .coerceAtMost(MAX_TARGET_LIGHTNESS)
            .coerceAtLeast(lightness)
        return FableSolColor.oklabToRgbGamutMapped(
            doubleArrayOf(targetLightness, lab[1], lab[2])
        )
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private const val BASE_LIGHTNESS_DELTA = 0.045
    private const val DARK_LIGHTNESS_BOOST = 0.025
    private const val DARK_FADE_START = 0.35
    private const val DARK_FADE_END = 0.75
    private const val LIGHT_FADE_START = 0.90
    private const val LIGHT_FADE_END = 0.985
    private const val MAX_TARGET_LIGHTNESS = 0.965
    private const val PALETTE_CACHE_CAPACITY = 64
    private const val PINCH_START = 0.035
    private const val PINCH_FULL = 0.22
}
