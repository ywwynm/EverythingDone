package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialOwnershipLayerTest {

    @Test
    fun `不同深度的独立对象不会被压成同一运动层`() {
        val width = 9
        val height = 5
        val depth = FloatArray(width * height) { 0.45f }
        val ownership = ByteArray(width * height)

        for (y in 1..2) for (x in 1..2) {
            val index = y * width + x
            ownership[index] = 0xff.toByte()
            depth[index] = 0.20f
        }
        for (y in 1..3) for (x in 6..7) {
            val index = y * width + x
            ownership[index] = 0xff.toByte()
            depth[index] = 0.74f
        }

        val graph = SpatialOwnershipLayer.buildGraphFromMask(
            baseDepth = depth,
            width = width,
            height = height,
            ownershipMask = ownership
        )

        assertEquals(2, graph.layers.size)
        val displacements = graph.layers
            .map { it.displacement(parallaxMotion = 0.09f) }
            .sorted()
        assertTrue(displacements[1] - displacements[0] > 0.03f)
        assertTrue(graph.excludedFromBase.count { it } >= 10)
    }

    @Test
    fun `对象层内部使用单一位移且不会修改mask外深度`() {
        val width = 11
        val height = 7
        val baseDepth = FloatArray(width * height) { index ->
            0.18f + (index % width).toFloat() / (width - 1) * 0.42f
        }
        val ownership = ByteArray(width * height)
        for (y in 2..4) for (x in 3..7) {
            ownership[y * width + x] = 0xff.toByte()
        }

        val layer = SpatialOwnershipLayer.build(
            baseDepth = baseDepth,
            width = width,
            height = height,
            ownershipMask = ownership
        )

        assertTrue(layer != null)
        val resolved = checkNotNull(layer)
        assertEquals(baseDepth.toList(), resolved.baseDepth.toList())

        val objectDisplacement = 0.09f * (resolved.representativeDepth - 0.5f)
        for (index in ownership.indices) {
            if ((ownership[index].toInt() and 0xff) >= 128) {
                assertTrue(resolved.excludedFromBase[index])
                assertEquals(
                    objectDisplacement,
                    resolved.displacement(parallaxMotion = 0.09f),
                    0f
                )
            } else {
                assertFalse(resolved.excludedFromBase[index])
            }
        }
    }

    @Test
    fun `对象alpha只在ownership邻域保留并保持软轮廓`() {
        val matte = SpatialAlphaData(
            width = 5,
            height = 3,
            values = floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0.2f, 0.7f, 1f, 0f,
                0f, 0f, 0f, 0f, 0f
            )
        )
        val ownership = byteArrayOf(
            0, 0, 0, 0, 0,
            0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0,
            0, 0, 0, 0, 0
        )

        val alpha = SpatialOwnershipLayer.buildAlpha(
            matte = matte,
            ownershipMask = ownership,
            maskWidth = 5,
            maskHeight = 3,
            targetWidth = 5,
            targetHeight = 3
        )

        assertEquals(0, alpha[0].toInt() and 0xff)
        assertTrue((alpha[6].toInt() and 0xff) in 45..60)
        assertTrue((alpha[7].toInt() and 0xff) in 170..185)
        assertEquals(255, alpha[8].toInt() and 0xff)
        assertEquals(0, alpha[9].toInt() and 0xff)
    }

    @Test
    fun `强接触且近深度的对象保持独立标签但共享运动锚点`() {
        val width = 12
        val height = 6
        val depth = FloatArray(width * height) { 0.35f }
        val labels = ByteArray(width * height)
        for (y in 1..4) for (x in 1..5) {
            val index = y * width + x
            labels[index] = 1
            depth[index] = 0.54f
        }
        for (y in 1..4) for (x in 6..10) {
            val index = y * width + x
            labels[index] = 2
            depth[index] = 0.62f
        }

        val graph = SpatialOwnershipLayer.buildGraphFromLabels(
            baseDepth = depth,
            width = width,
            height = height,
            ownershipLabels = labels,
            expandBoundary = false
        )

        assertEquals(
            setOf(1, 2),
            labels.map { it.toInt() and 0xff }.filter { it > 0 }.toSet()
        )
        assertEquals(1, graph.layers.size)
        assertEquals(
            1,
            graph.labels.map { it.toInt() and 0xff }.filter { it > 0 }.toSet().size
        )
        assertEquals(0.58f, graph.layers.single().representativeDepth, 0.01f)
    }

    @Test
    fun `只有单点接触的对象不会被耦合`() {
        val width = 9
        val height = 5
        val depth = FloatArray(width * height) { 0.35f }
        val labels = ByteArray(width * height)
        labels[2 * width + 3] = 1
        labels[2 * width + 4] = 2
        depth[2 * width + 3] = 0.54f
        depth[2 * width + 4] = 0.60f

        val graph = SpatialOwnershipLayer.buildGraphFromLabels(
            baseDepth = depth,
            width = width,
            height = height,
            ownershipLabels = labels,
            expandBoundary = false
        )

        assertEquals(2, graph.layers.size)
        assertTrue(
            kotlin.math.abs(
                graph.layers[0].representativeDepth -
                    graph.layers[1].representativeDepth
            ) > 0.04f
        )
    }

    @Test
    fun `强接触但深度差明确的对象保留层间视差`() {
        val width = 12
        val height = 6
        val depth = FloatArray(width * height) { 0.35f }
        val labels = ByteArray(width * height)
        for (y in 1..4) for (x in 1..5) {
            val index = y * width + x
            labels[index] = 1
            depth[index] = 0.34f
        }
        for (y in 1..4) for (x in 6..10) {
            val index = y * width + x
            labels[index] = 2
            depth[index] = 0.68f
        }

        val graph = SpatialOwnershipLayer.buildGraphFromLabels(
            baseDepth = depth,
            width = width,
            height = height,
            ownershipLabels = labels,
            expandBoundary = false
        )

        assertEquals(2, graph.layers.size)
        assertTrue(
            kotlin.math.abs(
                graph.layers[0].representativeDepth -
                    graph.layers[1].representativeDepth
            ) > 0.25f
        )
    }
}
