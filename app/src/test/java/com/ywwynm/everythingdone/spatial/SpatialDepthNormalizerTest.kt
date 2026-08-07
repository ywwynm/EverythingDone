package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialDepthNormalizerTest {

    @Test
    fun normalizeAndCrop_removesLetterboxAndMapsRobustRange() {
        val raw = FloatArray(16) { -100f }
        // 4×2 内容位于 4×4 输入的 y=1..2。
        val content = floatArrayOf(
            0f, 1f, 2f, 3f,
            4f, 5f, 6f, 100f
        )
        content.copyInto(raw, destinationOffset = 4)

        val result = SpatialDepthNormalizer.normalizeAndCrop(
            raw = raw,
            inputSize = 4,
            contentLeft = 0,
            contentTop = 1,
            contentWidth = 4,
            contentHeight = 2
        )

        assertEquals(4, result.width)
        assertEquals(2, result.height)
        assertEquals(8, result.values.size)
        assertEquals(0f, result.values.minOrNull()!!, 0.0001f)
        assertEquals(1f, result.values.maxOrNull()!!, 0.0001f)
        assertTrue(result.values[6] > result.values[1])
    }

    @Test(expected = IllegalStateException::class)
    fun normalizeAndCrop_rejectsNonFiniteOutput() {
        val raw = FloatArray(9) { it.toFloat() }
        raw[4] = Float.NaN
        SpatialDepthNormalizer.normalizeAndCrop(
            raw = raw,
            inputSize = 3,
            contentLeft = 0,
            contentTop = 0,
            contentWidth = 3,
            contentHeight = 3
        )
    }

    @Test(expected = IllegalStateException::class)
    fun normalizeAndCrop_rejectsFlatOutput() {
        SpatialDepthNormalizer.normalizeAndCrop(
            raw = FloatArray(16) { 7f },
            inputSize = 4,
            contentLeft = 0,
            contentTop = 0,
            contentWidth = 4,
            contentHeight = 4
        )
    }

    @Test
    fun normalizeAndCrop_usesStrongerDefaultsWithoutOverdrivingEdgeHeavyDepth() {
        val smooth = SpatialDepthNormalizer.normalizeAndCrop(
            raw = FloatArray(100) { it / 99f },
            inputSize = 10,
            contentLeft = 0,
            contentTop = 0,
            contentWidth = 10,
            contentHeight = 10
        )
        val edgeHeavy = SpatialDepthNormalizer.normalizeAndCrop(
            raw = FloatArray(100) { index ->
                if ((index + index / 10) % 2 == 0) 0f else 1f
            },
            inputSize = 10,
            contentLeft = 0,
            contentTop = 0,
            contentWidth = 10,
            contentHeight = 10
        )

        assertEquals(0.72f, smooth.defaultStrength, 0.0001f)
        assertEquals(0.48f, edgeHeavy.defaultStrength, 0.0001f)
    }

    @Test
    fun normalizeAndCrop_closeRadiusFillsThinFarGapsAndKeepsSilhouette() {
        // 12×12 输入的上半 12×6 为内容：左半近景（1.0）内嵌一条 2 px 宽的远景缝
        // （发丝间隙），右半远景（0.0）。
        val width = 12
        val height = 6
        val raw = FloatArray(width * width) { index ->
            val x = index % width
            val y = index / width
            when {
                y >= height -> 0f // 补边区域，不参与裁剪
                x in 3..4 -> 0f   // 近景内部的窄缝
                x < 7 -> 1f       // 近景团块
                else -> 0f        // 外部远景
            }
        }

        val closed = SpatialDepthNormalizer.normalizeAndCrop(
            raw = raw,
            inputSize = width,
            contentLeft = 0,
            contentTop = 0,
            contentWidth = width,
            contentHeight = height,
            closeRadius = 2
        )
        val plain = SpatialDepthNormalizer.normalizeAndCrop(
            raw = raw.copyOf(),
            inputSize = width,
            contentLeft = 0,
            contentTop = 0,
            contentWidth = width,
            contentHeight = height
        )

        val row = 3 * width
        // 不闭合：缝保持远景。
        assertEquals(0f, plain.values[row + 3], 0.0001f)
        // 闭合：≤2×radius 的内部缝被并入近景团块。
        assertEquals(1f, closed.values[row + 3], 0.0001f)
        assertEquals(1f, closed.values[row + 4], 0.0001f)
        // 外轮廓不外扩：近景块右缘（x=6）之外仍是远景。
        assertEquals(0f, closed.values[row + 7], 0.0001f)
        assertEquals(0f, closed.values[row + 11], 0.0001f)
        // 近景团块本体不变。
        assertEquals(1f, closed.values[row + 0], 0.0001f)
        assertEquals(1f, closed.values[row + 6], 0.0001f)
    }
}
