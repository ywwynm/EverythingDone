package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialDepthSurfelBuilderTest {

    @Test
    fun `连续标量场在最大视角保持三十六像素稳健跨度`() {
        val width = 192
        val height = 128
        val result = SpatialDepthSurfelBuilder.build(
            sourceDepth = depthFixture(width, height),
            width = width,
            height = height
        )

        val spanAt720 = robustSpan(result.surfels.motionScalars) *
            720f / max(width, height)
        assertEquals(SpatialDepthSurfelBuilder.TARGET_SPAN_PX_AT_720, spanAt720, 0.02f)
        assertEquals(SpatialDepthSurfelBuilder.GUARD_FRACTION, result.surfels.guardFraction, 0f)
        assertEquals(
            SpatialDepthSurfelBuilder.REQUESTED_MAXIMUM_PARALLAX,
            result.viewEnvelope.amplitudes.maxOrNull() ?: 0f,
            0f
        )
    }

    @Test
    fun `每个样本按自身深度移动而不是整图仿射`() {
        val width = 180
        val height = 120
        val result = SpatialDepthSurfelBuilder.build(
            sourceDepth = depthFixture(width, height),
            width = width,
            height = height
        )
        val amplitude = SpatialDepthSurfelBuilder.REQUESTED_MAXIMUM_PARALLAX
        val samples = intArrayOf(0, width / 2, width * height / 2, width * height - 1)
        val reconstructed = ArrayList<Float>()
        for (index in samples) {
            val horizontal = result.motionBasis.displacement(index, 1f, 0f, amplitude)
            val vertical = result.motionBasis.displacement(index, 0f, 1f, amplitude)
            assertEquals(
                result.surfels.motionScalars[index],
                horizontal.x * width,
                1e-4f
            )
            assertEquals(
                result.surfels.motionScalars[index],
                vertical.y * height,
                1e-4f
            )
            assertEquals(0f, horizontal.y, 0f)
            assertEquals(0f, vertical.x, 0f)
            reconstructed += horizontal.x * width
        }
        assertTrue(reconstructed.maxOrNull()!! - reconstructed.minOrNull()!! > 5f)
    }

    @Test
    fun `人物内部保留头部躯干和双臂的不同深度响应`() {
        val width = 240
        val height = 320
        val values = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                values[index] = if (x in 58..184 && y in 34..294) {
                    val head = if (y < 112) 0.16f else 0f
                    val torsoCurve = 0.12f * (1f - abs(x - 121f) / 64f).coerceAtLeast(0f)
                    val armDifference = when {
                        x < 82 -> -0.08f
                        x > 160 -> 0.09f
                        else -> 0f
                    }
                    (0.55f + head + torsoCurve + armDifference + 0.08f * y / height)
                        .coerceIn(0f, 1f)
                } else {
                    0.15f + 0.20f * y / height
                }
            }
        }
        val result = SpatialDepthSurfelBuilder.build(
            sourceDepth = depthData(width, height, values),
            width = width,
            height = height
        )
        val personMotion = ArrayList<Float>()
        for (y in 34..294) {
            for (x in 58..184) {
                personMotion += result.surfels.motionScalars[y * width + x]
            }
        }
        val internalSpanAt720 = robustSpan(personMotion.toFloatArray()) *
            720f / max(width, height)
        assertTrue("人物内部视差不足：$internalSpanAt720", internalSpanAt720 >= 7f)
    }

    @Test
    fun `隐藏底板只使用单一远景位移`() {
        val width = 160
        val height = 100
        val result = SpatialDepthSurfelBuilder.build(
            sourceDepth = depthFixture(width, height),
            width = width,
            height = height
        )
        val basis = result.backgroundMotionBasis
        for (index in 1 until width * height) {
            assertEquals(basis.horizontalX[0], basis.horizontalX[index], 0f)
            assertEquals(0f, basis.horizontalY[index], 0f)
            assertEquals(0f, basis.verticalX[index], 0f)
            assertEquals(basis.verticalY[0], basis.verticalY[index], 0f)
        }
        val expected = percentile(result.surfels.motionScalars, 0.08f)
        assertEquals(expected, result.surfels.backgroundScalar, 1e-5f)
    }

    private fun depthFixture(width: Int, height: Int): SpatialDepthData {
        val values = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                values[y * width + x] = (
                    0.10f + 0.52f * x / width + 0.18f * y / height +
                        if (x in width / 3..width / 2 && y > height / 4) 0.16f else 0f
                    ).coerceIn(0f, 1f)
            }
        }
        return depthData(width, height, values)
    }

    private fun depthData(width: Int, height: Int, values: FloatArray) = SpatialDepthData(
        width = width,
        height = height,
        values = values,
        robustRange = 1f,
        strongEdgeRatio = 0f,
        defaultStrength = 1f,
        sharpEdges = true
    )

    private fun robustSpan(values: FloatArray): Float =
        percentile(values, 0.95f) - percentile(values, 0.05f)

    private fun percentile(values: FloatArray, fraction: Float): Float {
        val sorted = values.copyOf()
        sorted.sort()
        val position = sorted.lastIndex * fraction
        val lower = position.toInt()
        val upper = ceil(position).toInt()
        val weight = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }
}
