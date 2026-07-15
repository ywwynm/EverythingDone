package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolGlMeshLayoutTest {

    @Test
    fun `water vertex keeps crest pinch and adds an independent sheen slope`() {
        assertEquals(8, FableSolGlMeshLayout.COMPONENTS_PER_VERTEX)
        assertEquals(6, FableSolGlMeshLayout.SHEEN_SLOPE_X_OFFSET)
        assertEquals(7, FableSolGlMeshLayout.SHEEN_SLOPE_Z_OFFSET)
    }

    @Test
    fun `continuous mesh covers all eight depth groups`() {
        val columns = 120
        val indices = FableSolGlMeshLayout.buildIndices(columns)

        assertEquals(
            FableSolGlMeshLayout.indicesPerGroup(columns) * FableSolGlMeshLayout.GROUP_COUNT,
            indices.size
        )
        val maxVertex = indices.maxOf { it.toInt() and 0xffff }
        assertTrue(maxVertex < FableSolGlMeshLayout.vertexCount(columns))
        assertEquals(FableSolContinuousSurface.Z_ROWS * columns - 1, maxVertex)
    }

    @Test
    fun `each group begins at its far anchor and ends at its near anchor`() {
        val columns = 5
        val indices = FableSolGlMeshLayout.buildIndices(columns)
        val perGroup = FableSolGlMeshLayout.indicesPerGroup(columns)

        // 第一组是最远区间：第一只三角形连接最后一行与其近侧相邻行。
        assertEquals((FableSolContinuousSurface.Z_ROWS - 1) * columns,
            indices[0].toInt() and 0xffff)
        assertEquals((FableSolContinuousSurface.Z_ROWS - 2) * columns,
            indices[1].toInt() and 0xffff)
        // 最后一组是 layer 1，全部索引仍落在第一个产品层区间。
        val lastGroup = indices.copyOfRange(perGroup * 7, indices.size)
        assertTrue(lastGroup.all {
            (it.toInt() and 0xffff) <
                (FableSolContinuousSurface.ROWS_PER_LAYER + 1) * columns
        })
    }

    @Test
    fun `uint16 indices cover the maximum renderer column count and reject overflow`() {
        val maximumRendererColumns = FableSolSpec.N_POINTS
        val maximumVertex = FableSolGlMeshLayout.vertexCount(maximumRendererColumns) - 1

        assertTrue(maximumVertex <= 0xffff)
        FableSolGlMeshLayout.buildIndices(maximumRendererColumns)

        val overflowingColumns = 0x10000 / FableSolContinuousSurface.Z_ROWS + 1
        try {
            FableSolGlMeshLayout.buildIndices(overflowingColumns)
            throw AssertionError("应拒绝超出 GL_UNSIGNED_SHORT 范围的网格")
        } catch (_: IllegalArgumentException) {
            // 预期结果。
        }
    }

    @Test
    fun `index buffer is uploaded again after GL resources are recreated`() {
        val state = FableSolGlIndexBufferState()

        assertTrue(state.requiresUpload(120))
        state.onUploaded(120)
        assertTrue(!state.requiresUpload(120))

        state.onGlResourcesReleased()

        assertTrue(state.requiresUpload(120))
        assertEquals(0, state.indexCountPerGroup)
    }

    @Test
    fun `optical blending preserves opaque framebuffer alpha`() {
        assertEquals(1.0, FableSolGlOpticalBlendPolicy.resultingAlpha(1.0), 0.0)
    }

}
