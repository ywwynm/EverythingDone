package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVNextGeometryTest {

    @Test
    fun `最大档目标为48像素而不是用放宽形变预算换强度`() {
        assertEquals(
            48f,
            SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE,
            0f
        )
        assertEquals(0.08f, SpatialVNextGeometryBuilder.MAX_LOCAL_STRAIN, 0f)
    }

    @Test
    fun `实例连续性可以删除伪断边但不得改变全局连续主运动场`() {
        val width = 80
        val height = 56
        val left = 18
        val right = 61
        val top = 7
        val bottom = 52
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                x !in left..right || y !in top..bottom -> 0.18f
                x in 38..41 && y in 18..45 -> 0.48f
                else -> 0.70f + 0.06f * (x - left).toFloat() / (right - left)
            }
        }
        val continuityMask = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in left..right && y in top..bottom
        }
        val labels = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in left..right && y in top..bottom) 1 else 0
        }
        val source = spatialDepth(width, height, depth)
        val withoutProposal = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = source
        )
        val withProposal = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = source,
            continuityMask = continuityMask,
            continuityLabels = labels
        )

        assertTrue(
            "实例连续性没有删除人物内部横向伪断边",
            withProposal.geometry.cutRight.count { it } <
                withoutProposal.geometry.cutRight.count { it }
        )
        assertTrue(
            "实例连续性没有删除人物内部纵向伪断边",
            withProposal.geometry.cutDown.count { it } <
                withoutProposal.geometry.cutDown.count { it }
        )
        val withoutBasis = checkNotNull(withoutProposal.geometry.motionBasis)
        val withBasis = checkNotNull(withProposal.geometry.motionBasis)
        assertArrayEquals(withoutBasis.horizontalX, withBasis.horizontalX, 0f)
        assertArrayEquals(withoutBasis.horizontalY, withBasis.horizontalY, 0f)
        assertArrayEquals(withoutBasis.verticalX, withBasis.verticalX, 0f)
        assertArrayEquals(withoutBasis.verticalY, withBasis.verticalY, 0f)
        assertEquals(withoutProposal.motionCandidateId, withProposal.motionCandidateId)
    }

    @Test
    fun `单一连续场必须保留全局深度响应而不是收敛成近刚性chart`() {
        val width = 96
        val height = 64
        val depth = FloatArray(width * height) { index ->
            val x = (index % width).toFloat() / (width - 1)
            val y = (index / width).toFloat() / (height - 1)
            val broadVolume = 0.08f *
                sin((Math.PI * x)).toFloat() *
                sin((Math.PI * y)).toFloat()
            0.16f + 0.48f * x + 0.10f * y + broadVolume
        }
        val result = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth)
        )
        val basis = checkNotNull(result.geometry.motionBasis)
        val horizontalSpan = basis.robustProjectedSpanCoefficient(1f, 0f)
        val verticalSpan = basis.robustProjectedSpanCoefficient(0f, 1f)

        assertFalse(result.geometry.cutRight.any { it })
        assertFalse(result.geometry.cutDown.any { it })
        assertTrue(
            "横向连续深度响应被压成近刚性运动：$horizontalSpan",
            horizontalSpan >= 0.30f
        )
        assertTrue(
            "纵向连续深度响应被压成近刚性运动：$verticalSpan",
            verticalSpan >= 0.24f
        )
        assertTrue(
            "横向视点不应生成语义块旋转",
            basis.horizontalY.maxOf { abs(it) } <= 1e-6f
        )
        assertTrue(
            "纵向视点不应生成语义块旋转",
            basis.verticalX.maxOf { abs(it) } <= 1e-6f
        )
    }

    @Test
    fun `遮挡台阶保留完整层间视差且连续面满足局部形变门槛`() {
        val width = 64
        val height = 40
        val depth = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.90f else 0.10f
        }
        val result = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth, depth)
        )

        for (y in 0 until height) {
            assertTrue(result.geometry.cutRight[y * (width - 1) + width / 2 - 1])
        }
        val nearMean = (0 until height).flatMap { y ->
            (0 until width / 2).map { x -> result.geometry.surfaceDepth[y * width + x] }
        }.average().toFloat()
        val farMean = (0 until height).flatMap { y ->
            (width / 2 until width).map { x -> result.geometry.surfaceDepth[y * width + x] }
        }.average().toFloat()
        val horizontalAmplitude = result.viewEnvelope.amplitudes[0]
        val basis = checkNotNull(result.geometry.motionBasis)
        val horizontalSpan = basis.robustProjectedSpanCoefficient(1f, 0f) *
            horizontalAmplitude * SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE

        assertTrue("层间深度差被压平", nearMean - farMean >= 0.72f)
        assertTrue("可信遮挡层间视差不足：$horizontalSpan", horizontalSpan >= 45f)
        assertTrue(
            "可信遮挡层间视差越过最大档目标：$horizontalSpan",
            horizontalSpan <=
                SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE + 0.5f
        )
        assertMotionBasisWithinEnvelope(result)
        assertTrue(result.geometry.hiddenBackgroundMask.any { it })
    }

    @Test
    fun `连续斜面不被切成语义纸片且非仿射残差受控`() {
        val width = 72
        val height = 48
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            0.2f + 0.48f * (
                0.55f * x / (width - 1) + 0.45f * y / (height - 1)
                )
        }
        val result = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth)
        )

        assertFalse("平滑斜面不应被切碎", result.geometry.cutRight.any { it })
        assertFalse("平滑斜面不应被切碎", result.geometry.cutDown.any { it })
        val basis = checkNotNull(result.geometry.motionBasis)
        val achievedSpan = basis.robustProjectedSpanCoefficient(1f, 0f) *
            result.viewEnvelope.amplitudes[0] *
            SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE
        assertMotionBasisWithinEnvelope(result)
        assertTrue(
            "连续斜面被保形约束完全压成纸片：span720=$achievedSpan",
            achievedSpan >= 8f
        )
        assertTrue(
            "连续斜面不应突破中等视角目标：span720=$achievedSpan",
            achievedSpan <=
                SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE + 0.15f
        )
        val span = (result.geometry.surfaceDepth.maxOrNull() ?: 0f) -
            (result.geometry.surfaceDepth.minOrNull() ?: 0f)
        assertTrue("连续表面仍需保留受控的低频立体层次", span >= 0.09f)
    }

    @Test
    fun `低频深扫掠必须保留为全局连续空间响应`() {
        val width = 96
        val height = 64
        val depth = FloatArray(width * height) { index ->
            val x = (index % width).toFloat() / (width - 1)
            val y = (index / width).toFloat() / (height - 1)
            0.12f + 0.58f * x + 0.12f * y
        }
        val result = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = spatialDepth(width, height, depth)
        )
        val geometry = result.geometry

        assertFalse(geometry.cutRight.any { it })
        assertFalse(geometry.cutDown.any { it })
        val leftMean = (0 until height).map { y ->
            geometry.surfaceDepth[y * width]
        }.average().toFloat()
        val rightMean = (0 until height).map { y ->
            geometry.surfaceDepth[y * width + width - 1]
        }.average().toFloat()
        val basis = checkNotNull(geometry.motionBasis)
        val achievedSpan = basis.robustProjectedSpanCoefficient(1f, 0f) *
            result.viewEnvelope.amplitudes[0] *
            SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE

        assertTrue(
            "低频场景深度被旧 chart 预算压平：depthSpan=${rightMean - leftMean}",
            rightMean - leftMean >= 0.50f
        )
        assertTrue(
            "低频连续场的空间响应不足：span720=$achievedSpan",
            achievedSpan >= 24f
        )
        assertMotionBasisWithinEnvelope(result)
    }

    @Test
    fun `局部高频褶皱只压非仿射残差且不吞掉低频空间层次`() {
        val width = 96
        val height = 64
        val depth = FloatArray(width * height) { index ->
            val x = (index % width).toFloat() / (width - 1)
            val y = (index / width).toFloat() / (height - 1)
            val wrinkle = 0.018f *
                sin((16.0 * PI * x)).toFloat() *
                sin((12.0 * PI * y)).toFloat()
            0.16f + 0.52f * x + 0.10f * y + wrinkle
        }
        val result = SpatialVNextGeometryBuilder.build(
            width,
            height,
            spatialDepth(width, height, depth)
        )
        val geometry = result.geometry

        assertFalse(geometry.cutRight.any { it })
        assertFalse(geometry.cutDown.any { it })
        assertMotionBasisWithinEnvelope(result)
        val leftMean = (0 until height).map { geometry.surfaceDepth[it * width] }
            .average().toFloat()
        val rightMean = (0 until height).map {
            geometry.surfaceDepth[it * width + width - 1]
        }.average().toFloat()
        assertTrue(
            "局部褶皱吞掉了低频前后景相对运动",
            (rightMean - leftMean) *
                SpatialVNextGeometryBuilder.GEOMETRY_REGULARIZATION_REFERENCE_PARALLAX >= 0.008f
        )
    }

    @Test
    fun `双尺度选择必须达到空间目标或退回纯全局主场`() {
        val width = 96
        val height = 64
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val normalizedX = x.toFloat() / (width - 1)
            val normalizedY = y.toFloat() / (height - 1)
            val broad = 0.12f + 0.40f * normalizedX + 0.26f * normalizedY
            val uncertainMediumScale = 0.30f *
                sin(6.0 * Math.PI * normalizedX).toFloat() *
                sin(4.0 * Math.PI * normalizedY).toFloat()
            (broad + uncertainMediumScale).coerceIn(0f, 1f)
        }
        val result = SpatialVNextGeometryBuilder.build(
            width,
            height,
            spatialDepth(width, height, depth)
        )
        val basis = checkNotNull(result.geometry.motionBasis)
        val minimumSpan = result.viewEnvelope.amplitudes.indices.minOf { direction ->
            val angle = direction * 2.0 * Math.PI /
                result.viewEnvelope.amplitudes.size
            val x = kotlin.math.cos(angle).toFloat()
            val y = kotlin.math.sin(angle).toFloat()
            basis.robustProjectedSpanCoefficient(x, y) *
                result.viewEnvelope.amplitudes[direction] *
                SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE
        }

        assertTrue(
            "中尺度残差权重越界：${result.mediumResidualWeight}",
            result.mediumResidualWeight in
                0f..SpatialContinuousMotionBuilder.MAXIMUM_MEDIUM_RESIDUAL_WEIGHT
        )
        assertTrue(
            "仍有可退让残差时整幅空间跨度被局部误差拖低：$minimumSpan",
            minimumSpan >= 27f || result.mediumResidualWeight == 0f
        )
    }

    @Test
    fun `宽深度过渡带收敛为单一遮挡ridge而不是多层纸片`() {
        val width = 80
        val height = 30
        val transitionStart = 34
        val transitionEnd = 45
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            when {
                x < transitionStart -> 0.86f
                x > transitionEnd -> 0.14f
                else -> 0.86f - 0.72f *
                    (x - transitionStart).toFloat() / (transitionEnd - transitionStart)
            }
        }
        val buildResult = SpatialVNextGeometryBuilder.build(
            width,
            height,
            spatialDepth(width, height, depth)
        )
        val result = buildResult.geometry

        for (y in 0 until height) {
            val cuts = (transitionStart - 2..transitionEnd + 1).count { x ->
                result.cutRight[y * (width - 1) + x]
            }
            assertTrue("宽过渡带没有形成遮挡断边", cuts >= 1)
            assertTrue("宽过渡带被切成多个纸片", cuts <= 1)
        }
        assertMotionBasisWithinEnvelope(buildResult)
    }

    @Test
    fun `孤立深度坏点不会形成漂浮图元或压平整张斜面`() {
        val width = 64
        val height = 40
        val depth = FloatArray(width * height) { index ->
            0.25f + 0.32f * (index % width).toFloat() / (width - 1)
        }
        val spike = (height / 2) * width + width / 2
        depth[spike] = 0.95f
        val result = SpatialVNextGeometryBuilder.build(
            width,
            height,
            spatialDepth(width, height, depth)
        ).geometry

        assertFalse("孤立坏点形成横向漂浮断层", result.cutRight.any { it })
        assertFalse("孤立坏点形成纵向漂浮断层", result.cutDown.any { it })
        val center = result.surfaceDepth[spike]
        val neighbor = result.surfaceDepth[spike - 1]
        assertTrue("孤立坏点没有被稳健去除", abs(center - neighbor) < 0.02f)
        val span = (result.surfaceDepth.maxOrNull() ?: 0f) -
            (result.surfaceDepth.minOrNull() ?: 0f)
        assertTrue("单个坏点额外压平了受控斜面", span >= 0.075f)
    }

    @Test
    fun `几何构建在转置后保持等变`() {
        val width = 31
        val height = 19
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val base = 0.18f + 0.22f * x / (width - 1) + 0.14f * y / (height - 1)
            if (x + y < 23) base + 0.35f else base
        }
        val original = SpatialVNextGeometryBuilder.build(
            width,
            height,
            spatialDepth(width, height, depth, depth)
        ).geometry
        val transposedDepth = FloatArray(depth.size) { index ->
            val tx = index % height
            val ty = index / height
            depth[tx * width + ty]
        }
        val transposed = SpatialVNextGeometryBuilder.build(
            height,
            width,
            spatialDepth(height, width, transposedDepth, transposedDepth)
        ).geometry

        for (y in 0 until height) {
            for (x in 0 until width) {
                val first = original.surfaceDepth[y * width + x]
                val second = transposed.surfaceDepth[x * height + y]
                assertTrue("转置后深度不等变：($x,$y) $first / $second", abs(first - second) < 2e-4f)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                assertTrue(
                    "横向断边转置不等变",
                    original.cutRight[y * (width - 1) + x] ==
                        transposed.cutDown[x * height + y]
                )
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                assertTrue(
                    "纵向断边转置不等变",
                    original.cutDown[y * width + x] ==
                        transposed.cutRight[x * (height - 1) + y]
                )
            }
        }
    }

    private fun assertMotionBasisWithinEnvelope(
        result: SpatialVNextGeometryBuilder.Result
    ) {
        val geometry = result.geometry
        val basis = checkNotNull(geometry.motionBasis)
        repeat(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
            val angle = direction * 2.0 * Math.PI / SpatialViewEnvelope.DIRECTION_COUNT
            val distortion = basis.distortion(
                viewpointX = kotlin.math.cos(angle).toFloat(),
                viewpointY = kotlin.math.sin(angle).toFloat(),
                cutRight = geometry.cutRight,
                cutDown = geometry.cutDown
            )
            val amplitude = result.viewEnvelope.amplitudes[direction]
            assertTrue(
                "方向 $direction 的非相似形变越界：" +
                    amplitude * distortion.nonSimilarityCoefficient,
                amplitude * distortion.nonSimilarityCoefficient <=
                    result.viewEnvelope.maximumLocalStrain + 2e-4f
            )
            assertTrue(
                "方向 $direction 的局部等比缩放越界：" +
                    amplitude * distortion.scaleCoefficient,
                amplitude * distortion.scaleCoefficient <=
                    SpatialViewEnvelopeBuilder.MAX_LOCAL_SCALE_STRAIN + 2e-4f
            )
        }
    }

    private fun spatialDepth(
        width: Int,
        height: Int,
        values: FloatArray,
        rawInverseDepth: FloatArray? = null
    ) = SpatialDepthData(
        width = width,
        height = height,
        values = values,
        robustRange = 1f,
        strongEdgeRatio = 0f,
        defaultStrength = 0.72f,
        rawInverseDepth = rawInverseDepth
    )
}
