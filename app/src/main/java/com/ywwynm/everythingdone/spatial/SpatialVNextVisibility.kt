package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 根据最终运动场构造反遮挡背景，而不是根据原始深度差预留整块补图区。
 *
 * 深度 cut 决定前后关系，最终 motion basis 与方向包络决定运行时最多会露出多少像素；
 * 实例身份只用于告诉补景模型应完整抹除哪个遮挡物，不参与位移计算。
 */
internal object SpatialVNextVisibilityBuilder {

    data class Result(
        val backgroundDepth: FloatArray,
        val hiddenBackgroundMask: BooleanArray,
        val backgroundMotionBasis: SpatialScreenSpaceMotionBasis,
        val inpaintingOccluderMask: BooleanArray?
    )

    fun build(
        surfaceDepth: FloatArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        motionBasis: SpatialScreenSpaceMotionBasis,
        viewEnvelope: SpatialViewEnvelope,
        continuityLabels: ByteArray? = null
    ): Result {
        require(surfaceDepth.size == width * height)
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)
        require(motionBasis.width == width && motionBasis.height == height)
        require(continuityLabels == null || continuityLabels.size == surfaceDepth.size)

        val backgroundDepth = surfaceDepth.copyOf()
        val backgroundHorizontalX = motionBasis.horizontalX.copyOf()
        val backgroundHorizontalY = motionBasis.horizontalY.copyOf()
        val backgroundVerticalX = motionBasis.verticalX.copyOf()
        val backgroundVerticalY = motionBasis.verticalY.copyOf()
        val hidden = BooleanArray(surfaceDepth.size)
        val bestDistance = IntArray(surfaceDepth.size) { Int.MAX_VALUE }
        val occludingLabels = LinkedHashSet<Int>()

        fun rememberOccluder(index: Int) {
            val label = continuityLabels?.get(index)?.toInt()?.and(0xff) ?: 0
            if (label != 0) occludingLabels += label
        }

        fun offer(
            targetX: Int,
            targetY: Int,
            sourceX: Int,
            sourceY: Int,
            distance: Int,
            rampFraction: Float
        ) {
            if (targetX !in 0 until width || targetY !in 0 until height) return
            val target = targetY * width + targetX
            val source = sourceY * width + sourceX
            val candidate = lerp(surfaceDepth[source], surfaceDepth[target], rampFraction)
            if (candidate >= surfaceDepth[target] - MIN_HIDDEN_DEPTH_GAP) return
            if (candidate > backgroundDepth[target] - MIN_REPLACEMENT_GAP &&
                (candidate >= backgroundDepth[target] + MIN_REPLACEMENT_GAP ||
                    distance >= bestDistance[target])
            ) {
                return
            }
            backgroundDepth[target] = candidate
            backgroundHorizontalX[target] = lerp(
                motionBasis.horizontalX[source],
                motionBasis.horizontalX[target],
                rampFraction
            )
            backgroundHorizontalY[target] = lerp(
                motionBasis.horizontalY[source],
                motionBasis.horizontalY[target],
                rampFraction
            )
            backgroundVerticalX[target] = lerp(
                motionBasis.verticalX[source],
                motionBasis.verticalX[target],
                rampFraction
            )
            backgroundVerticalY[target] = lerp(
                motionBasis.verticalY[source],
                motionBasis.verticalY[target],
                rampFraction
            )
            bestDistance[target] = distance
            if (rampFraction <= 0f) hidden[target] = true
        }

        fun offerBand(band: Int, place: (Int, Float) -> Unit) {
            repeat(band + BACKGROUND_SEAM_RAMP_CELLS) { distance ->
                val fraction = if (distance < band) {
                    0f
                } else {
                    (distance - band + 1f) / (BACKGROUND_SEAM_RAMP_CELLS + 1f)
                }
                place(distance, fraction)
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                if (!cutRight[y * (width - 1) + x]) continue
                val leftIndex = y * width + x
                val rightIndex = leftIndex + 1
                val band = revealBandCells(
                    maximumNormalDisplacementPixels(
                        first = leftIndex,
                        second = rightIndex,
                        horizontalBoundary = true,
                        basis = motionBasis,
                        envelope = viewEnvelope
                    )
                )
                if (surfaceDepth[leftIndex] > surfaceDepth[rightIndex]) {
                    rememberOccluder(leftIndex)
                    offerBand(band) { distance, fraction ->
                        offer(x - distance, y, x + 1, y, distance, fraction)
                    }
                } else {
                    rememberOccluder(rightIndex)
                    offerBand(band) { distance, fraction ->
                        offer(x + 1 + distance, y, x, y, distance, fraction)
                    }
                }
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                if (!cutDown[y * width + x]) continue
                val topIndex = y * width + x
                val bottomIndex = topIndex + width
                val band = revealBandCells(
                    maximumNormalDisplacementPixels(
                        first = topIndex,
                        second = bottomIndex,
                        horizontalBoundary = false,
                        basis = motionBasis,
                        envelope = viewEnvelope
                    )
                )
                if (surfaceDepth[topIndex] > surfaceDepth[bottomIndex]) {
                    rememberOccluder(topIndex)
                    offerBand(band) { distance, fraction ->
                        offer(x, y - distance, x, y + 1, distance, fraction)
                    }
                } else {
                    rememberOccluder(bottomIndex)
                    offerBand(band) { distance, fraction ->
                        offer(x, y + 1 + distance, x, y, distance, fraction)
                    }
                }
            }
        }

        val occluderMask = continuityLabels?.let { labels ->
            if (occludingLabels.isEmpty()) {
                null
            } else {
                BooleanArray(labels.size) { index ->
                    (labels[index].toInt() and 0xff) in occludingLabels
                }
            }
        }
        return Result(
            backgroundDepth = backgroundDepth,
            hiddenBackgroundMask = hidden,
            backgroundMotionBasis = SpatialScreenSpaceMotionBasis(
                width = width,
                height = height,
                horizontalX = backgroundHorizontalX,
                horizontalY = backgroundHorizontalY,
                verticalX = backgroundVerticalX,
                verticalY = backgroundVerticalY
            ),
            inpaintingOccluderMask = occluderMask
        )
    }

    private fun maximumNormalDisplacementPixels(
        first: Int,
        second: Int,
        horizontalBoundary: Boolean,
        basis: SpatialScreenSpaceMotionBasis,
        envelope: SpatialViewEnvelope
    ): Float {
        var maximum = 0f
        repeat(envelope.amplitudes.size) { direction ->
            val angle = direction * FULL_TURN / envelope.amplitudes.size
            val viewpointX = cos(angle)
            val viewpointY = sin(angle)
            val amplitude = envelope.amplitudes[direction]
            val firstMotion = basis.displacement(first, viewpointX, viewpointY, amplitude)
            val secondMotion = basis.displacement(second, viewpointX, viewpointY, amplitude)
            val normalDifference = if (horizontalBoundary) {
                abs(firstMotion.x - secondMotion.x) * basis.width
            } else {
                abs(firstMotion.y - secondMotion.y) * basis.height
            }
            maximum = max(maximum, normalDifference)
        }
        return maximum
    }

    private fun revealBandCells(relativeDisplacementPixels: Float): Int =
        max(1, ceil(relativeDisplacementPixels * BACKGROUND_BAND_SAFETY).toInt() + 1)

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private const val BACKGROUND_BAND_SAFETY = 1.25f
    private const val BACKGROUND_SEAM_RAMP_CELLS = 2
    private const val MIN_HIDDEN_DEPTH_GAP = 0.002f
    private const val MIN_REPLACEMENT_GAP = 0.002f
    private const val FULL_TURN = (2.0 * Math.PI).toFloat()
}
