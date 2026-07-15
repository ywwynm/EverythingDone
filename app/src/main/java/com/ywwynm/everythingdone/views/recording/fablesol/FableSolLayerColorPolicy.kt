package com.ywwynm.everythingdone.views.recording.fablesol

internal class FableSolLayerBaseColors(
    @JvmField val start: IntArray,
    @JvmField val end: IntArray
)

internal class FableSolLayerGradientStops(
    @JvmField val start: IntArray,
    @JvmField val stop1: IntArray,
    @JvmField val stop2: IntArray,
    @JvmField val end: IntArray
)

/** D135 后界面肩恒为零；保留结构以兼容 GL 与 Canvas 的共享色板接口。 */
internal class FableSolLayerInterfaceWeights(
    @JvmField val start: DoubleArray,
    @JvmField val stop1: DoubleArray,
    @JvmField val stop2: DoubleArray,
    @JvmField val end: DoubleArray
) {
    fun forContour(layer: Int): DoubleArray {
        val boundary = layer + 1
        if (boundary !in 1 until FableSolSpec.N_LAYERS) return DoubleArray(4)
        return doubleArrayOf(
            start[boundary],
            stop1[boundary],
            stop2[boundary],
            end[boundary]
        )
    }
}

internal class FableSolLayerColorPalette(
    @JvmField val layers: Array<FableSolLayerGradientStops>,
    @JvmField val interfaceWeights: FableSolLayerInterfaceWeights
)

/**
 * 旧界面肩实现只为兼容尚未移除的绘制函数保留。D135 色板永远提供零权重，
 * 因而 GL 与 Canvas 均不会再生成这种宽边。
 */
internal object FableSolInterfaceShoulderPolicy {
    const val BASE_BRIGHT_DELTA_L = 0.026
    const val DARK_ENDPOINT_BRIGHT_BOOST_L = 0.060
    const val BASE_DEEP_DELTA_L = 0.012
    const val LIGHT_ENDPOINT_DEEP_BOOST_L = 0.012
    const val MIN_WIDTH_DP = 7.0
    const val MAX_WIDTH_DP = 14.0
    const val PROFILE_PEAK = 0.66

    fun bright(color: IntArray, weight: Double = 1.0): IntArray {
        val lightness = FableSolColor.rgbToOklab(color)[0]
        val darkEndpoint = 1.0 - smoothstep(0.08, 0.28, lightness)
        val lightEndpointFade = 1.0 - smoothstep(0.78, 0.96, lightness)
        val delta = (BASE_BRIGHT_DELTA_L * lightEndpointFade +
            DARK_ENDPOINT_BRIGHT_BOOST_L * darkEndpoint) *
            weight.coerceIn(0.0, 1.0)
        return FableSolColor.lightenOklab(color, delta)
    }

    fun deep(color: IntArray, weight: Double = 1.0): IntArray {
        val lightness = FableSolColor.rgbToOklab(color)[0]
        val endpoint = smoothstep(0.78, 0.96, lightness)
        val delta = (BASE_DEEP_DELTA_L + LIGHT_ENDPOINT_DEEP_BOOST_L * endpoint) *
            weight.coerceIn(0.0, 1.0)
        return FableSolColor.darkenOklab(color, delta)
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}

internal class FableSolLayerColorRamp internal constructor(colors: Array<IntArray>) {
    private val colors = Array(colors.size) { colors[it].copyOf() }

    fun colorAt(depth01: Double): IntArray {
        val position = depth01.coerceIn(0.0, 1.0) * (colors.size - 1)
        val lower = position.toInt().coerceIn(0, colors.lastIndex)
        val upper = (lower + 1).coerceAtMost(colors.lastIndex)
        if (lower == upper) return colors[lower].copyOf()
        return FableSolColor.mixOklab(colors[lower], colors[upper], position - lower)
    }
}

/**
 * D135：九层主体恢复静态 lighten_far 混白。
 *
 * 第 0 层精确保留 Thing 身份色；其余层以 `depth × lighten_far` 在 OKLab 中直接混向白色。
 * mood、color breath、录音与 HDR 状态都不能改写主体色。最远层混白量硬封顶 86.4%。
 */
internal object FableSolLayerColorPolicy {
    const val DEFAULT_LIGHTEN_FAR = 0.864
    const val MAX_LIGHTEN_FAR = 0.864
    const val DEFAULT_FRONT_LOAD_EXPONENT = 1.0
    const val DEFAULT_MIN_CHROMA_RETENTION = 0.0

    private val WHITE = intArrayOf(255, 255, 255)

    fun baseColors(start: IntArray, gradientEnd: IntArray?): FableSolLayerBaseColors =
        FableSolLayerBaseColors(
            normalizedRgb(start),
            normalizedRgb(gradientEnd ?: start)
        )

    fun palette(
        base: FableSolLayerBaseColors,
        lightenFar: Double,
        moodBright: Double,
        breath: Double,
        @Suppress("UNUSED_PARAMETER") frontLoadExponent: Double =
            DEFAULT_FRONT_LOAD_EXPONENT,
        @Suppress("UNUSED_PARAMETER") minChromaRetention: Double =
            DEFAULT_MIN_CHROMA_RETENTION
    ): FableSolLayerColorPalette {
        val identityStops = arrayOf(
            base.start.copyOf(),
            FableSolColor.mixOklab(base.start, base.end, GRADIENT_IDENTITY_MIX_1),
            FableSolColor.mixOklab(base.start, base.end, GRADIENT_IDENTITY_MIX_2),
            base.end.copyOf()
        )
        val layers = Array(FableSolSpec.N_LAYERS) { layer ->
            val depth01 = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
            val amount = lightenAmount(depth01, lightenFar, moodBright, breath)
            FableSolLayerGradientStops(
                start = FableSolColor.mixOklab(identityStops[0], WHITE, amount),
                stop1 = FableSolColor.mixOklab(identityStops[1], WHITE, amount),
                stop2 = FableSolColor.mixOklab(identityStops[2], WHITE, amount),
                end = FableSolColor.mixOklab(identityStops[3], WHITE, amount)
            )
        }
        val zero = DoubleArray(FableSolSpec.N_LAYERS)
        return FableSolLayerColorPalette(
            layers,
            FableSolLayerInterfaceWeights(
                zero.copyOf(), zero.copyOf(), zero.copyOf(), zero.copyOf()
            )
        )
    }

    fun ramp(
        base: IntArray,
        lightenFar: Double,
        moodBright: Double,
        breath: Double,
        @Suppress("UNUSED_PARAMETER") frontLoadExponent: Double =
            DEFAULT_FRONT_LOAD_EXPONENT,
        @Suppress("UNUSED_PARAMETER") minChromaRetention: Double =
            DEFAULT_MIN_CHROMA_RETENTION
    ): FableSolLayerColorRamp {
        val identity = normalizedRgb(base)
        return FableSolLayerColorRamp(Array(FableSolSpec.N_LAYERS) { layer ->
            val depth01 = layer.toDouble() / (FableSolSpec.N_LAYERS - 1)
            FableSolColor.mixOklab(
                identity,
                WHITE,
                lightenAmount(depth01, lightenFar, moodBright, breath)
            )
        })
    }

    fun lightenAmount(
        depth01: Double,
        lightenFar: Double,
        @Suppress("UNUSED_PARAMETER") moodBright: Double,
        @Suppress("UNUSED_PARAMETER") breath: Double
    ): Double = depth01.coerceIn(0.0, 1.0) *
        lightenFar.coerceIn(0.0, MAX_LIGHTEN_FAR)

    private fun normalizedRgb(color: IntArray): IntArray = intArrayOf(
        color[0].coerceIn(0, 255),
        color[1].coerceIn(0, 255),
        color[2].coerceIn(0, 255)
    )

    private const val GRADIENT_IDENTITY_MIX_1 = 0.21
    private const val GRADIENT_IDENTITY_MIX_2 = 0.56
}
