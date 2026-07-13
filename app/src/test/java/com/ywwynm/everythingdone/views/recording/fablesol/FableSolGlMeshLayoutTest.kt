package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolGlMeshLayoutTest {

    @Test
    fun `water vertex carries an independent crest pinch component`() {
        assertEquals(6, FableSolGlMeshLayout.COMPONENTS_PER_VERTEX)
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

        // 第一组是 layer 8：第一只三角形连接 row 24 与 row 23。
        assertEquals(24 * columns, indices[0].toInt())
        assertEquals(23 * columns, indices[1].toInt())
        // 最后一组是 layer 1，最后一个索引仍落在 row 1/0 区间。
        val lastGroup = indices.copyOfRange(perGroup * 7, indices.size)
        assertTrue(lastGroup.all { (it.toInt() and 0xffff) < 4 * columns })
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
