package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialMotionGroupingPriorTest {

    @Test
    fun `depth-supported alpha proposal emits a closed contour without mutating input topology`() {
        val width = 72
        val height = 52
        val left = 18
        val right = 53
        val top = 7
        val bottom = 49
        val foreground = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in left..right && y in top..bottom
        }
        val alpha = SpatialAlphaData(
            width,
            height,
            FloatArray(width * height) { if (foreground[it]) 1f else 0f }
        )
        val depth = FloatArray(width * height) { if (foreground[it]) 0.82f else 0.16f }
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)

        // Simulate a depth contour that is correct almost everywhere but has a wide opening.
        for (y in top..bottom) {
            if (y !in 23..29) cutRight[y * (width - 1) + left - 1] = true
            cutRight[y * (width - 1) + right] = true
        }
        for (x in left..right) {
            cutDown[(top - 1) * width + x] = true
            if (bottom + 1 < height) cutDown[bottom * width + x] = true
        }
        // 同一语义主体内部也可能存在真实自遮挡；该断边和轮廓缺口都必须原样保留。
        for (y in top..bottom) {
            cutRight[y * (width - 1) + 35] = true
        }
        val originalRight = cutRight.copyOf()
        val originalDown = cutDown.copyOf()

        val result = SpatialMotionGroupingPrior.selectDepthSupportedSubjects(
            width,
            height,
            depth,
            alpha,
            cutRight,
            cutDown
        )

        assertTrue(result.applied)
        val acceptedMask = checkNotNull(result.acceptedMask)
        assertTrue(acceptedMask[(top + 4) * width + left + 4])
        assertTrue(acceptedMask[(bottom - 4) * width + right - 4])
        assertFalse(acceptedMask[2 * width + 2])
        assertTrue(
            "matting must not create, close, or erase geometric cuts",
            originalRight.contentEquals(cutRight)
        )
        assertTrue(
            "matting must not create, close, or erase geometric cuts",
            originalDown.contentEquals(cutDown)
        )
        val supportedRight = checkNotNull(result.depthSupportedCutRight)
        for (y in top..bottom) {
            assertTrue(
                "the validated subject contour must be closed at y=$y",
                supportedRight[y * (width - 1) + left - 1]
            )
            assertTrue(
                "the validated subject contour must be closed at y=$y",
                supportedRight[y * (width - 1) + right]
            )
        }
        val supportedDown = checkNotNull(result.depthSupportedCutDown)
        for (x in left..right) {
            assertTrue(supportedDown[(top - 1) * width + x])
            assertTrue(supportedDown[bottom * width + x])
        }
    }

    @Test
    fun `validated component closes soft local depth gaps`() {
        val width = 84
        val height = 60
        val left = 20
        val right = 63
        val top = 8
        val bottom = 58
        val foreground = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in left..right && y in top..bottom
        }
        val alpha = SpatialAlphaData(
            width,
            height,
            FloatArray(width * height) { if (foreground[it]) 1f else 0f }
        )
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                !foreground[index] -> 0.20f
                x == left || x == right || y == top || y == bottom -> 0.205f
                else -> 0.78f
            }
        }

        val result = SpatialMotionGroupingPrior.selectDepthSupportedSubjects(
            width,
            height,
            depth,
            alpha,
            BooleanArray(height * (width - 1)),
            BooleanArray((height - 1) * width)
        )

        assertTrue(result.applied)
        val supportedRight = checkNotNull(result.depthSupportedCutRight)
        val supportedDown = checkNotNull(result.depthSupportedCutDown)
        for (y in top..bottom) {
            assertTrue("left contour opened at y=$y", supportedRight[y * (width - 1) + left - 1])
            assertTrue("right contour opened at y=$y", supportedRight[y * (width - 1) + right])
        }
        for (x in left..right) {
            assertTrue("top contour opened at x=$x", supportedDown[(top - 1) * width + x])
            assertTrue("bottom contour opened at x=$x", supportedDown[bottom * width + x])
        }
    }

    @Test
    fun `unsupported enclosed matte hole is absorbed but real depth hole stays open`() {
        val width = 90
        val height = 64
        val foreground = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in 18..71 && y in 6..63
        }
        val shallowHole = 28..30
        val realHole = 48..54
        for (y in 26..28) for (x in shallowHole) foreground[y * width + x] = false
        for (y in 24..31) for (x in realHole) foreground[y * width + x] = false
        val depth = FloatArray(width * height) { index ->
            when {
                !foreground[index] && index % width in realHole && index / width in 24..31 -> 0.18f
                foreground[index] -> 0.76f
                else -> 0.20f
            }
        }
        // The matte miss has the same depth as the surrounding foreground and must not create a seam.
        for (y in 26..28) for (x in shallowHole) depth[y * width + x] = 0.755f
        val alpha = SpatialAlphaData(
            width,
            height,
            FloatArray(width * height) { if (foreground[it]) 1f else 0f }
        )

        val result = SpatialMotionGroupingPrior.selectDepthSupportedSubjects(
            width,
            height,
            depth,
            alpha,
            BooleanArray(height * (width - 1)),
            BooleanArray((height - 1) * width)
        )

        val accepted = checkNotNull(result.acceptedMask)
        assertTrue("depth-continuous matte hole was not absorbed", accepted[27 * width + 29])
        assertFalse("real background hole was incorrectly filled", accepted[27 * width + 51])
    }

    @Test
    fun `alpha proposal cannot create a chart without supporting depth evidence`() {
        val width = 64
        val height = 44
        val alpha = SpatialAlphaData(
            width,
            height,
            FloatArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (x in 15..48 && y in 6..42) 1f else 0f
            }
        )
        val depth = FloatArray(width * height) { index ->
            0.42f + 0.02f * (index % width).toFloat() / (width - 1)
        }
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)

        val result = SpatialMotionGroupingPrior.selectDepthSupportedSubjects(
            width,
            height,
            depth,
            alpha,
            cutRight,
            cutDown
        )

        assertFalse(result.applied)
        assertEquals(null, result.acceptedMask)
        assertFalse(cutRight.any { it })
        assertFalse(cutDown.any { it })
    }
}
