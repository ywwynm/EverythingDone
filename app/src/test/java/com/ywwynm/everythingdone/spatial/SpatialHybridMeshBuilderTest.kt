package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialHybridMeshBuilderTest {

    @Test
    fun `无断层时整张表面保持连通且不生成边界splat`() {
        val width = 4
        val height = 3
        val mesh = SpatialHybridMeshBuilder.build(
            width = width,
            height = height,
            surfaceDepth = FloatArray(width * height) { it / 20f },
            cutRight = BooleanArray(height * (width - 1)),
            cutDown = BooleanArray((height - 1) * width)
        )

        assertEquals((width - 1) * (height - 1) * 6, mesh.connected.sumOf { it.indices.size })
        assertTrue(mesh.boundarySplats.isEmpty())
    }

    @Test
    fun `断层两侧不建跨层三角形且由边界splat补齐样本`() {
        val width = 4
        val height = 3
        val cutX = 1
        val cutRight = BooleanArray(height * (width - 1))
        for (y in 0 until height) cutRight[y * (width - 1) + cutX] = true
        val mesh = SpatialHybridMeshBuilder.build(
            width = width,
            height = height,
            surfaceDepth = FloatArray(width * height) { index ->
                if (index % width <= cutX) 0.9f else 0.1f
            },
            cutRight = cutRight,
            cutDown = BooleanArray((height - 1) * width)
        )

        val leftU = cutX.toFloat() / (width - 1)
        val rightU = (cutX + 1).toFloat() / (width - 1)
        mesh.connected.forEach { chunk ->
            chunk.indices.asList().chunked(3).forEach { triangle ->
                val coordinates = triangle.map { index ->
                    chunk.vertices[
                        (index.toInt() and 0xffff) *
                            SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
                    ]
                }
                assertFalse(
                    "三角形跨过显式遮挡断层：$coordinates",
                    coordinates.min() <= leftU && coordinates.max() >= rightU
                )
            }
        }
        val boundarySplatCount = mesh.boundarySplats.sumOf { it.indices.size } / 6
        assertEquals(height * 2, boundarySplatCount)
        assertTrue(mesh.connected.sumOf { it.indices.size } > 0)
    }

    @Test
    fun `base表面不再引用对象ownership区域`() {
        val width = 5
        val height = 5
        val excluded = BooleanArray(width * height)
        for (y in 1..3) for (x in 1..3) excluded[y * width + x] = true
        val mesh = SpatialHybridMeshBuilder.build(
            width = width,
            height = height,
            surfaceDepth = FloatArray(width * height) { 0.4f },
            cutRight = BooleanArray(height * (width - 1)),
            cutDown = BooleanArray((height - 1) * width),
            excludedSamples = excluded
        )

        mesh.connected.forEach { chunk ->
            chunk.indices.forEach { rawIndex ->
                val vertex = rawIndex.toInt() and 0xffff
                val offset = vertex * SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
                val x = (chunk.vertices[offset] * (width - 1)).toInt()
                val y = (chunk.vertices[offset + 1] * (height - 1)).toInt()
                assertFalse(excluded[y * width + x])
            }
        }
        assertTrue(mesh.connected.sumOf { it.indices.size } > 0)
    }
}
