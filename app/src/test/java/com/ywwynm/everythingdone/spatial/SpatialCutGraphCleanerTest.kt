package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCutGraphCleanerTest {

    @Test
    fun `单像素深度孤岛会并回相邻表面`() {
        val width = 5
        val height = 5
        val depth = FloatArray(width * height) { 0.4f }
        depth[2 * width + 2] = 0.9f
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)
        cutRight[2 * (width - 1) + 1] = true
        cutRight[2 * (width - 1) + 2] = true
        cutDown[1 * width + 2] = true
        cutDown[2 * width + 2] = true

        SpatialCutGraphCleaner.healSmallIslands(
            width, height, depth, cutRight, cutDown
        )

        assertFalse(cutRight[2 * (width - 1) + 1])
        assertFalse(cutRight[2 * (width - 1) + 2])
        assertFalse(cutDown[1 * width + 2])
        assertFalse(cutDown[2 * width + 2])
    }

    @Test
    fun `两块有面积的表面之间断边保持不变`() {
        val width = 8
        val height = 6
        val cutRight = BooleanArray(height * (width - 1))
        for (y in 0 until height) cutRight[y * (width - 1) + 3] = true

        SpatialCutGraphCleaner.healSmallIslands(
            width = width,
            height = height,
            depth = FloatArray(width * height) { index ->
                if (index % width <= 3) 0.8f else 0.2f
            },
            cutRight = cutRight,
            cutDown = BooleanArray((height - 1) * width)
        )

        for (y in 0 until height) {
            assertTrue(cutRight[y * (width - 1) + 3])
        }
    }
}
