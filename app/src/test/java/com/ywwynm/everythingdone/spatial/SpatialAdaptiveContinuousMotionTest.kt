package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAdaptiveContinuousMotionTest {

    @Test
    fun `自适应候选必须保留比统一双尺度场更多的平缓内部体积`() {
        val width = 128
        val height = 96
        val centerX = (width - 1) * 0.5f
        val centerY = (height - 1) * 0.5f
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val dx = (x - centerX) / width
            val dy = (y - centerY) / height
            val broadVolume = 0.32f *
                exp((-(dx * dx + dy * dy) / 0.045f).toDouble()).toFloat()
            val isolatedNoise = if (x == width / 3 && y == height / 3) 0.30f else 0f
            (0.18f + broadVolume + isolatedNoise).coerceIn(0f, 1f)
        }
        val prepared = SpatialContinuousMotionBuilder.prepare(width, height, depth)
        val baseline = prepared.motionBasis(0.20f).horizontalX
        val adaptive = prepared.adaptiveMotionBasis(
            mediumSigmaFraction = 16f / 256f,
            maximumResidualWeight = 0.50f,
            riskThreshold = 0.20f
        ).horizontalX
        val center = (height / 2) * width + width / 2
        val shoulder = (height / 2) * width + width * 3 / 4

        assertTrue(
            "自适应场没有增加平缓体积响应",
            adaptive[center] - adaptive[shoulder] >
                baseline[center] - baseline[shoulder] + 0.008f
        )
        val spike = (height / 3) * width + width / 3
        assertTrue(
            "孤立深度坏点被直接写入运动场",
            abs(adaptive[spike] - adaptive[spike - 1]) < 0.02f
        )
    }

    @Test
    fun `所有候选必须保持转置等变且不得生成语义横向分量`() {
        val width = 47
        val height = 31
        val depth = FloatArray(width * height) { index ->
            val x = (index % width).toFloat() / (width - 1)
            val y = (index / width).toFloat() / (height - 1)
            0.15f + 0.46f * x + 0.21f * y + 0.04f * x * y
        }
        val transposedDepth = FloatArray(depth.size) { index ->
            val x = index % height
            val y = index / height
            depth[x * width + y]
        }
        val original = SpatialContinuousMotionBuilder.prepare(width, height, depth)
            .candidates()
        val transposed = SpatialContinuousMotionBuilder.prepare(
            height,
            width,
            transposedDepth
        ).candidates().associateBy { it.id }

        assertTrue(original.any { it.id.startsWith("adaptive-") })
        for (candidate in original) {
            val other = checkNotNull(transposed[candidate.id]).basis
            val scalar = candidate.basis.horizontalX
            val transposedScalar = FloatArray(scalar.size) { index ->
                val x = index % width
                val y = index / width
                other.horizontalX[x * height + y]
            }
            assertArrayEquals(candidate.id, scalar, transposedScalar, 2e-4f)
            assertTrue(candidate.basis.horizontalY.all { abs(it) <= 1e-7f })
            assertTrue(candidate.basis.verticalX.all { abs(it) <= 1e-7f })
        }
    }

    @Test
    fun `局部分位差异不足百分之一时优先保留更高全局视差`() {
        val withinNoise = SpatialVNextGeometryBuilder.selectNearBestCandidateIndex(
            selectionSpans = floatArrayOf(36.31f, 37.17f),
            localResponseScores = floatArrayOf(6.169f, 6.125f)
        )
        val materialLocalDifference =
            SpatialVNextGeometryBuilder.selectNearBestCandidateIndex(
                selectionSpans = floatArrayOf(36.31f, 37.17f),
                localResponseScores = floatArrayOf(6.20f, 6.05f)
            )

        assertTrue("量化噪声内没有优先更高全局视差", withinNoise == 1)
        assertTrue("显著局部响应优势被全局视差覆盖", materialLocalDifference == 0)
    }
}
