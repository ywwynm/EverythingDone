package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSplatMeshBuilderTest {

    @Test
    fun `参考视点的独立splat无缝覆盖完整纹理域`() {
        val width = 3
        val height = 2
        val depth = FloatArray(width * height) { 0.5f }

        val chunks = SpatialSplatMeshBuilder.build(
            width = width,
            height = height,
            surfaceDepth = depth,
            cutRight = BooleanArray(height * (width - 1)),
            cutDown = BooleanArray((height - 1) * width)
        )

        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        assertEquals(width * height * 4 * 3, chunk.vertices.size)
        assertEquals(width * height * 6, chunk.indices.size)

        val first = splatBounds(chunk.vertices, splat = 0)
        val second = splatBounds(chunk.vertices, splat = 1)
        val third = splatBounds(chunk.vertices, splat = 2)
        assertEquals(0f, first.left, 0f)
        assertEquals(first.right, second.left, 0f)
        assertEquals(second.right, third.left, 0f)
        assertEquals(1f, third.right, 0f)
        assertEquals(0f, first.top, 0f)
        assertEquals(0.5f, first.bottom, 0f)

        val lower = splatBounds(chunk.vertices, splat = width)
        assertEquals(first.bottom, lower.top, 0f)
        assertEquals(1f, lower.bottom, 0f)
    }

    @Test
    fun `每个splat四角使用同一深度且不跨断层插值`() {
        val depth = floatArrayOf(0.9f, 0.1f)
        val chunk = SpatialSplatMeshBuilder.build(
            width = 2,
            height = 1,
            surfaceDepth = depth,
            cutRight = booleanArrayOf(true),
            cutDown = BooleanArray(0)
        ).single()

        for (splat in depth.indices) {
            val base = splat * 4 * SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
            repeat(4) { vertex ->
                assertEquals(
                    depth[splat],
                    chunk.vertices[
                        base + vertex * SpatialSplatMeshBuilder.FLOATS_PER_VERTEX + 2
                    ],
                    0f
                )
            }
        }
        assertTrue(chunk.indices.all { it.toInt() >= 0 })
    }

    @Test
    fun `连续表面的splat足迹按最坏相对位移重叠`() {
        val chunk = SpatialSplatMeshBuilder.build(
            width = 2,
            height = 1,
            surfaceDepth = floatArrayOf(1f, 0f),
            cutRight = booleanArrayOf(false),
            cutDown = BooleanArray(0)
        ).single()

        val left = splatBounds(chunk.vertices, splat = 0)
        val right = splatBounds(chunk.vertices, splat = 1)
        assertTrue(left.right > 0.5f)
        assertTrue(right.left < 0.5f)
        assertTrue(
            left.right - right.left >=
                SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
        )
    }

    @Test
    fun `可信遮挡断边不扩张足迹以保留真实显露区`() {
        val chunk = SpatialSplatMeshBuilder.build(
            width = 2,
            height = 1,
            surfaceDepth = floatArrayOf(1f, 0f),
            cutRight = booleanArrayOf(true),
            cutDown = BooleanArray(0)
        ).single()

        val left = splatBounds(chunk.vertices, splat = 0)
        val right = splatBounds(chunk.vertices, splat = 1)
        assertEquals(0.5f, left.right, 0f)
        assertEquals(0.5f, right.left, 0f)
    }

    @Test
    fun `有matting时只把横向断边近侧扩张半个采样间距`() {
        val chunk = SpatialSplatMeshBuilder.build(
            width = 2,
            height = 1,
            surfaceDepth = floatArrayOf(1f, 0f),
            cutRight = booleanArrayOf(true),
            cutDown = BooleanArray(0),
            nearCutPaddingSamples = 0.5f
        ).single()

        val near = splatBounds(chunk.vertices, splat = 0)
        val far = splatBounds(chunk.vertices, splat = 1)
        assertEquals(1f, near.right, 0f)
        assertEquals(0.5f, far.left, 0f)
    }

    @Test
    fun `有matting时只把纵向断边近侧扩张半个采样间距`() {
        val chunk = SpatialSplatMeshBuilder.build(
            width = 1,
            height = 2,
            surfaceDepth = floatArrayOf(1f, 0f),
            cutRight = BooleanArray(0),
            cutDown = booleanArrayOf(true),
            nearCutPaddingSamples = 0.5f
        ).single()

        val near = splatBounds(chunk.vertices, splat = 0)
        val far = splatBounds(chunk.vertices, splat = 1)
        assertEquals(1f, near.bottom, 0f)
        assertEquals(0.5f, far.top, 0f)
    }

    private fun splatBounds(vertices: FloatArray, splat: Int): Bounds {
        val stride = SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
        val base = splat * 4 * stride
        return Bounds(
            left = vertices[base],
            top = vertices[base + 1],
            right = vertices[base + stride],
            bottom = vertices[base + stride * 2 + 1]
        )
    }

    private data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )
}
