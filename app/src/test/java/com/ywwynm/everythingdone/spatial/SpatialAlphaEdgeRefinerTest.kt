package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialAlphaEdgeRefinerTest {

    @Test
    fun `完全不透明平面保持不变`() {
        val source = ByteArray(7 * 5) { 0xff.toByte() }

        val result = SpatialAlphaEdgeRefiner.refine(source, 7, 5)

        assertTrue(source.contentEquals(result))
    }

    @Test
    fun `孤立透明线扩展为连续覆盖且远处不受影响`() {
        val width = 9
        val height = 7
        val source = ByteArray(width * height) { 0xff.toByte() }
        for (y in 1 until height - 1) source[y * width + 4] = 0

        val result = SpatialAlphaEdgeRefiner.refine(source, width, height)

        assertEquals(255, result[3 * width].unsigned())
        assertTrue(result[3 * width + 4].unsigned() < 64)
        assertTrue(result[3 * width + 3].unsigned() in 1..254)
        assertTrue(result[3 * width + 5].unsigned() in 1..254)
        for (y in 1 until height - 1) {
            for (x in 1 until width) {
                assertTrue(
                    abs(result[y * width + x].unsigned() - result[y * width + x - 1].unsigned()) <=
                        SpatialAlphaEdgeRefiner.MAX_NEIGHBOR_ALPHA_JUMP_FOR_TEST
                )
            }
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
