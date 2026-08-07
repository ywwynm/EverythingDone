package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVNextMotionGroupingIntegrationTest {

    @Test
    fun `实例连续性只删除人物内部伪断边且不改写深度运动基`() {
        val width = 84
        val height = 60
        val left = 18
        val right = 65
        val top = 7
        val bottom = 58
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                x !in left..right || y !in top..bottom -> 0.14f
                x in 32..50 && y in 25..43 -> 0.18f
                else -> 0.78f
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
        val source = SpatialDepthData(
            width,
            height,
            depth,
            robustRange = 1f,
            strongEdgeRatio = 0f,
            defaultStrength = 0.72f,
            rawInverseDepth = null
        )
        val baseline = SpatialVNextGeometryBuilder.build(width, height, source)
        val proposed = SpatialVNextGeometryBuilder.build(
            width,
            height,
            source,
            continuityMask = continuityMask,
            continuityLabels = labels
        )

        assertFalse(proposed.motionGroupingApplied)
        assertTrue(proposed.motionGroupCount == 0)
        assertNotNull(proposed.inpaintingOccluderMask)
        assertTrue(
            proposed.geometry.cutRight.count { it } < baseline.geometry.cutRight.count { it }
        )
        assertTrue(
            proposed.geometry.cutDown.count { it } < baseline.geometry.cutDown.count { it }
        )
        val first = checkNotNull(baseline.geometry.motionBasis)
        val second = checkNotNull(proposed.geometry.motionBasis)
        assertArrayEquals(first.horizontalX, second.horizontalX, 0f)
        assertArrayEquals(first.verticalY, second.verticalY, 0f)
    }

    @Test
    fun `前景与背景都由同一连续深度场产生中等视角响应`() {
        val width = 80
        val height = 56
        val foreground = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in 20..59 && y in 8..55
        }
        val depth = FloatArray(width * height) { index ->
            val x = (index % width).toFloat() / (width - 1)
            val y = (index / width).toFloat() / (height - 1)
            if (foreground[index]) {
                0.62f + 0.16f * x - 0.07f * y
            } else {
                0.12f + 0.12f * x + 0.04f * y
            }
        }
        val result = SpatialVNextGeometryBuilder.build(
            width = width,
            height = height,
            sourceDepth = SpatialDepthData(
                width = width,
                height = height,
                values = depth,
                robustRange = 1f,
                strongEdgeRatio = 0f,
                defaultStrength = 0.72f,
                rawInverseDepth = null
            )
        )
        val geometry = result.geometry
        val basis = checkNotNull(geometry.motionBasis)
        val foregroundCoefficients = basis.horizontalX.indices
            .filter { foreground[it] }
            .map { basis.horizontalX[it] }
            .sorted()
        val internalSpan = foregroundCoefficients[
            (foregroundCoefficients.lastIndex * 0.95f).toInt()
        ] - foregroundCoefficients[
            (foregroundCoefficients.lastIndex * 0.05f).toInt()
        ]
        val horizontalAmplitude = result.viewEnvelope.amplitudes[0]
        val achievedSpan = basis.robustProjectedSpanCoefficient(1f, 0f) *
            horizontalAmplitude * SpatialViewEnvelopeBuilder.REFERENCE_LONG_EDGE

        assertTrue("主体内部深度响应被压成纸片：$internalSpan", internalSpan >= 0.08f)
        assertTrue("全局空间响应过弱：$achievedSpan px", achievedSpan >= 20f)
        assertTrue(
            "全局空间响应超出有限视角：$achievedSpan px",
            achievedSpan <=
                SpatialViewEnvelopeBuilder.TARGET_PARALLAX_SPAN_AT_REFERENCE_LONG_EDGE + 0.15f
        )
        assertTrue(geometry.hiddenBackgroundMask.any { it })
        assertTrue(basis.horizontalY.all { abs(it) <= 1e-6f })
        assertTrue(basis.verticalX.all { abs(it) <= 1e-6f })
    }
}
