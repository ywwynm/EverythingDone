package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVNextVisibilityTest {

    @Test
    fun `显露带宽由最终相对运动决定而不是原始深度差决定`() {
        val width = 40
        val height = 8
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.92f else 0.08f
        }
        val cutRight = BooleanArray(height * (width - 1))
        repeat(height) { y -> cutRight[y * (width - 1) + width / 2 - 1] = true }
        val stationary = basis(width, height) { 0f }
        val separated = basis(width, height) { index ->
            if (index % width < width / 2) 0.42f else -0.42f
        }
        val envelope = SpatialViewEnvelope.uniform(0.12f, 0.08f)

        val minimum = SpatialVNextVisibilityBuilder.build(
            depth,
            width,
            height,
            cutRight,
            BooleanArray((height - 1) * width),
            stationary,
            envelope
        )
        val revealed = SpatialVNextVisibilityBuilder.build(
            depth,
            width,
            height,
            cutRight,
            BooleanArray((height - 1) * width),
            separated,
            envelope
        )

        val minimumPixels = minimum.hiddenBackgroundMask.count { it }
        val revealedPixels = revealed.hiddenBackgroundMask.count { it }
        assertTrue("零相对运动不应预留大块补图区：$minimumPixels", minimumPixels <= height * 2)
        assertTrue("真实相对运动没有扩大显露带", revealedPixels > minimumPixels * 3)
    }

    @Test
    fun `实例标签只扩大补景条件且不改变运动基`() {
        val width = 24
        val height = 10
        val depth = FloatArray(width * height) { index ->
            if (index % width < 12) 0.8f else 0.2f
        }
        val cuts = BooleanArray(height * (width - 1))
        repeat(height) { y -> cuts[y * (width - 1) + 11] = true }
        val labels = ByteArray(width * height) { index ->
            if (index % width < 12) 7 else 0
        }
        val motion = basis(width, height) { index -> depth[index] - 0.5f }
        val result = SpatialVNextVisibilityBuilder.build(
            depth,
            width,
            height,
            cuts,
            BooleanArray((height - 1) * width),
            motion,
            SpatialViewEnvelope.uniform(0.10f, 0.08f),
            labels
        )

        val occluder = assertNotNull(result.inpaintingOccluderMask).let {
            result.inpaintingOccluderMask!!
        }
        assertTrue(occluder.indices.filter { labels[it].toInt() == 7 }.all { occluder[it] })
        assertFalse(occluder.indices.filter { labels[it].toInt() == 0 }.any { occluder[it] })
        assertTrue(motion.horizontalX.indices.all { motion.horizontalX[it] == depth[it] - 0.5f })
    }

    @Test
    fun `隐藏背景继承远侧连续运动而不是回退为原始深度运动`() {
        val width = 24
        val height = 6
        val depth = FloatArray(width * height) { index ->
            if (index % width < 12) 0.9f else 0.1f
        }
        val cuts = BooleanArray(height * (width - 1))
        repeat(height) { y -> cuts[y * (width - 1) + 11] = true }
        val motion = basis(width, height) { index ->
            if (index % width < 12) 0.18f else -0.11f
        }

        val result = SpatialVNextVisibilityBuilder.build(
            surfaceDepth = depth,
            width = width,
            height = height,
            cutRight = cuts,
            cutDown = BooleanArray((height - 1) * width),
            motionBasis = motion,
            viewEnvelope = SpatialViewEnvelope.uniform(0.12f, 0.08f)
        )

        val hiddenNearSide = (0 until height).flatMap { y ->
            (0 until 12).map { x -> y * width + x }
        }.filter { result.hiddenBackgroundMask[it] }
        assertTrue(hiddenNearSide.isNotEmpty())
        for (index in hiddenNearSide) {
            assertEquals(-0.11f, result.backgroundMotionBasis.horizontalX[index], 1e-6f)
            assertEquals(-0.11f, result.backgroundMotionBasis.verticalY[index], 1e-6f)
        }
        assertTrue(motion.horizontalX.indices.all { index ->
            motion.horizontalX[index] == if (index % width < 12) 0.18f else -0.11f
        })
    }

    private fun basis(
        width: Int,
        height: Int,
        scalar: (Int) -> Float
    ): SpatialScreenSpaceMotionBasis {
        val values = FloatArray(width * height) { scalar(it) }
        return SpatialScreenSpaceMotionBasis(
            width,
            height,
            values,
            FloatArray(values.size),
            FloatArray(values.size),
            values.copyOf()
        )
    }
}
