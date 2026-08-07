package com.ywwynm.everythingdone.spatial

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * 按视点方向保存的安全最大幅度。运行时只做环形插值，不再用一条全局常量代表所有图片。
 */
data class SpatialViewEnvelope(
    val amplitudes: FloatArray,
    val maximumLocalStrain: Float
) {
    init {
        require(amplitudes.size in 4..64) { "视点包络方向数量无效" }
        require(amplitudes.all { it.isFinite() && it > 0f && it <= MAX_AMPLITUDE }) {
            "视点包络包含无效幅度"
        }
        require(maximumLocalStrain.isFinite() && maximumLocalStrain in 0.005f..0.20f) {
            "局部形变门槛无效"
        }
    }

    fun motion(viewpointX: Float, viewpointY: Float, strength: Float): Motion {
        val radius = hypot(viewpointX, viewpointY).coerceIn(0f, 1f)
        if (radius <= MIN_NON_ZERO) return Motion(0f, 0f, 0f)
        val amplitude = amplitudeAtStrength(
            amplitudeForDirection(viewpointX, viewpointY),
            strength
        )
        return Motion(
            x = viewpointX * amplitude,
            y = viewpointY * amplitude,
            amplitude = amplitude
        )
    }

    /**
     * 当前强度下任意方向可能使用的最大幅度。它不读取当前视点半径，供恒定取景边距使用，
     * 避免手机沿圆周倾斜时因边距变化产生缩放呼吸。
     */
    fun maximumMotionAmplitude(strength: Float): Float =
        amplitudeAtStrength(amplitudes.maxOrNull() ?: 0f, strength)

    fun persistedAmplitudes(): List<Float> = amplitudes.toList()

    private fun amplitudeForDirection(x: Float, y: Float): Float {
        var angle = atan2(y, x)
        if (angle < 0f) angle += FULL_TURN
        val position = angle / FULL_TURN * amplitudes.size
        val first = floor(position).toInt() % amplitudes.size
        val second = (first + 1) % amplitudes.size
        val fraction = position - floor(position)
        return amplitudes[first] + (amplitudes[second] - amplitudes[first]) * fraction
    }

    private fun amplitudeAtStrength(maximum: Float, strength: Float): Float {
        val minimum = min(MINIMUM_AMPLITUDE, maximum)
        return minimum + (maximum - minimum) *
            strength.coerceIn(
                SpatialDerivativeStore.MIN_STRENGTH,
                SpatialDerivativeStore.MAX_STRENGTH
            )
    }

    data class Motion(
        val x: Float,
        val y: Float,
        val amplitude: Float
    )

    companion object {
        const val DIRECTION_COUNT = 16
        private const val MAX_AMPLITUDE = 0.20f
        internal const val MINIMUM_AMPLITUDE = 0.012f
        private const val MIN_NON_ZERO = 1e-6f
        private val FULL_TURN = (2.0 * PI).toFloat()

        fun uniform(amplitude: Float, maximumLocalStrain: Float): SpatialViewEnvelope =
            SpatialViewEnvelope(
                amplitudes = FloatArray(DIRECTION_COUNT) { amplitude },
                maximumLocalStrain = maximumLocalStrain
            )

        fun fromPersisted(
            amplitudes: List<Float>?,
            maximumLocalStrain: Float?
        ): SpatialViewEnvelope? {
            if (amplitudes == null || maximumLocalStrain == null) return null
            return runCatching {
                SpatialViewEnvelope(amplitudes.toFloatArray(), maximumLocalStrain)
            }.getOrNull()
        }
    }
}

/** 根据最终连续深度运动场生成方向安全包络。 */
object SpatialViewEnvelopeBuilder {

    fun build(
        geometry: SpatialLdiLiteGeometry,
        requestedMaximumAmplitude: Float,
        maximumLocalStrain: Float,
        targetParallaxSpanAtReferenceLongEdge: Float =
            TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE
    ): SpatialViewEnvelope {
        require(targetParallaxSpanAtReferenceLongEdge in 14f..48f)
        geometry.motionBasis?.let { basis ->
            val amplitudes = FloatArray(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
                val angle = direction * FULL_TURN / SpatialViewEnvelope.DIRECTION_COUNT
                val viewpointX = kotlin.math.cos(angle)
                val viewpointY = kotlin.math.sin(angle)
                val distortion = basis.distortion(
                    viewpointX = viewpointX,
                    viewpointY = viewpointY,
                    cutRight = geometry.cutRight,
                    cutDown = geometry.cutDown
                )
                var safe = requestedMaximumAmplitude
                if (distortion.nonSimilarityCoefficient > MIN_GRADIENT) {
                    safe = min(
                        safe,
                        maximumLocalStrain / distortion.nonSimilarityCoefficient
                    )
                }
                if (distortion.scaleCoefficient > MIN_GRADIENT) {
                    safe = min(
                        safe,
                        MAX_LOCAL_SCALE_STRAIN / distortion.scaleCoefficient
                    )
                }
                val spanCoefficient = basis.robustProjectedSpanCoefficient(
                    viewpointX,
                    viewpointY
                )
                if (spanCoefficient > MIN_SPAN_COEFFICIENT) {
                    val targetFraction =
                        targetParallaxSpanAtReferenceLongEdge / REFERENCE_LONG_EDGE
                    safe = min(safe, targetFraction / spanCoefficient)
                }
                safe.coerceAtLeast(MIN_SAFE_AMPLITUDE)
            }
            return SpatialViewEnvelope(amplitudes, maximumLocalStrain)
        }
        val model = SpatialChartMotionModel.fit(
            geometry.width,
            geometry.height,
            geometry.surfaceDepth,
            geometry.cutRight,
            geometry.cutDown
        )
        val affineGradients = model.affineGradientNorms()
        val residualGradients = model.residualGradientNorms(geometry.surfaceDepth)
        val maximumCombinedGradient = affineGradients.indices.maxOfOrNull { component ->
            affineGradients[component] + residualGradients[component]
        } ?: 0f
        var safeMaximum = requestedMaximumAmplitude
        if (maximumCombinedGradient > MIN_GRADIENT) {
            safeMaximum = min(
                safeMaximum,
                min(maximumLocalStrain, SpatialVNextGeometryBuilder.MAX_TOTAL_STRAIN) /
                    maximumCombinedGradient
            )
        }
        safeMaximum = safeMaximum.coerceAtLeast(MIN_SAFE_AMPLITUDE)
        return SpatialViewEnvelope.uniform(safeMaximum, maximumLocalStrain)
    }

    private const val MIN_GRADIENT = 1e-6f
    private const val MIN_SPAN_COEFFICIENT = 1e-5f
    private const val MIN_SAFE_AMPLITUDE = SpatialViewEnvelope.MINIMUM_AMPLITUDE
    internal const val MAX_LOCAL_SCALE_STRAIN = 0.10f
    const val TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE = 48f
    const val REFERENCE_LONG_EDGE = 720f
    private const val FULL_TURN = (2.0 * PI).toFloat()
}
