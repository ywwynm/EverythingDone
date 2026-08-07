package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SpatialViewEnvelopeTest {

    @Test
    fun `directional envelope calibrates a hard depth step to thirty six pixels`() {
        val width = 96
        val height = 64
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.90f else 0.10f
        }
        val cutRight = BooleanArray(height * (width - 1))
        for (y in 0 until height) {
            cutRight[y * (width - 1) + width / 2 - 1] = true
        }
        val basis = SpatialScreenSpaceMotionBasis(
            width = width,
            height = height,
            horizontalX = FloatArray(depth.size) { depth[it] - 0.5f },
            horizontalY = FloatArray(depth.size),
            verticalX = FloatArray(depth.size),
            verticalY = FloatArray(depth.size) { depth[it] - 0.5f }
        )
        val geometry = SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = depth,
            backgroundDepth = depth.copyOf(),
            cutRight = cutRight,
            cutDown = BooleanArray((height - 1) * width),
            hiddenBackgroundMask = BooleanArray(depth.size),
            motionBasis = basis
        )

        val envelope = SpatialViewEnvelopeBuilder.build(
            geometry = geometry,
            requestedMaximumAmplitude = 0.20f,
            maximumLocalStrain = 0.015f
        )

        repeat(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
            val angle = direction * 2.0 * Math.PI / SpatialViewEnvelope.DIRECTION_COUNT
            val viewpointX = cos(angle).toFloat()
            val viewpointY = sin(angle).toFloat()
            val span = basis.robustProjectedSpanCoefficient(viewpointX, viewpointY) *
                envelope.amplitudes[direction] *
                SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE
            assertEquals(
                "方向 $direction 没有校准到目标视差",
                SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE,
                span,
                0.05f
            )
        }
    }

    @Test
    fun `方向包络连续插值且保留二维视点方向`() {
        val envelope = SpatialViewEnvelope(
            amplitudes = FloatArray(16) { index -> 0.08f + index * 0.001f },
            maximumLocalStrain = 0.04f
        )
        val right = envelope.motion(1f, 0f, 1f)
        val up = envelope.motion(0f, 1f, 1f)
        val diagonal = envelope.motion(0.6f, 0.8f, 1f)

        assertTrue(right.x > 0.079f)
        assertEquals(0f, right.y, 1e-6f)
        assertEquals(0f, up.x, 1e-6f)
        assertTrue(up.y > 0.08f)
        assertTrue(diagonal.x > 0f && diagonal.y > 0f)
        assertEquals(0.75f, diagonal.x / diagonal.y, 1e-4f)
    }

    @Test
    fun `零视点严格没有位移`() {
        val envelope = SpatialViewEnvelope.uniform(0.12f, 0.04f)
        val motion = envelope.motion(0f, 0f, 1f)
        assertEquals(0f, motion.x, 0f)
        assertEquals(0f, motion.y, 0f)
    }

    @Test
    fun `最大取景幅度不依赖当前视点半径`() {
        val envelope = SpatialViewEnvelope(
            amplitudes = floatArrayOf(0.06f, 0.12f, 0.08f, 0.09f),
            maximumLocalStrain = 0.04f
        )

        assertEquals(0.12f, envelope.maximumMotionAmplitude(1f), 1e-6f)
        assertEquals(
            0.012f + (0.12f - 0.012f) * SpatialDerivativeStore.MIN_STRENGTH,
            envelope.maximumMotionAmplitude(SpatialDerivativeStore.MIN_STRENGTH),
            1e-6f
        )
    }

    @Test
    fun `二十八像素包络在默认强度下保留约二十三像素而不是提前打满`() {
        val maximum = 0.028f
        val envelope = SpatialViewEnvelope.uniform(maximum, 0.08f)

        assertEquals(0.02352f, envelope.maximumMotionAmplitude(0.72f), 1e-6f)
        assertEquals(maximum, envelope.maximumMotionAmplitude(1f), 1e-6f)
    }
}
