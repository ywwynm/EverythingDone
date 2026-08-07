package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialOwnershipRelationResolverTest {

    @Test
    fun attachedHandbagFollowsPersonWhileIndependentPersonStaysSeparate() {
        val width = 18
        val height = 12
        val labels = ByteArray(width * height)
        fill(labels, width, 1, xRange = 2..8, yRange = 2..9)
        // 手提包位于人物整体占据范围内，并覆盖身体局部。
        fill(labels, width, 2, xRange = 6..7, yRange = 5..7)
        fill(labels, width, 3, xRange = 13..15, yRange = 2..9)
        val depth = FloatArray(labels.size) { 0.42f }
        setDepth(depth, labels, label = 1, value = 0.72f)
        // 单目深度在小配件上经常偏离父对象；关系约束应消除这条假接缝。
        setDepth(depth, labels, label = 2, value = 0.54f)
        setDepth(depth, labels, label = 3, value = 0.24f)

        val resolved = SpatialOwnershipRelationResolver.resolve(
            labels = labels,
            width = width,
            height = height,
            instances = listOf(
                SpatialSegmentationInstance(1, 1, 0.95f, 50),
                SpatialSegmentationInstance(2, 31, 0.88f, 6),
                SpatialSegmentationInstance(3, 1, 0.93f, 24)
            ),
            depth = depth
        )

        assertEquals(1, resolved[6 * width + 6].toInt() and 0xff)
        assertEquals(3, resolved[6 * width + 14].toInt() and 0xff)
        assertEquals(setOf(1, 3), nonZeroLabels(resolved))
    }

    @Test
    fun nearbyButDetachedAccessoryIsNotMerged() {
        val width = 18
        val height = 12
        val labels = ByteArray(width * height)
        fill(labels, width, 1, xRange = 2..6, yRange = 2..9)
        fill(labels, width, 2, xRange = 9..10, yRange = 7..9)
        val depth = FloatArray(labels.size) { 0.5f }
        setDepth(depth, labels, label = 1, value = 0.66f)
        setDepth(depth, labels, label = 2, value = 0.64f)

        val resolved = SpatialOwnershipRelationResolver.resolve(
            labels = labels,
            width = width,
            height = height,
            instances = listOf(
                SpatialSegmentationInstance(1, 1, 0.95f, 40),
                SpatialSegmentationInstance(2, 31, 0.88f, 6)
            ),
            depth = depth
        )

        assertTrue(2 in nonZeroLabels(resolved))
    }

    @Test
    fun foregroundGlassTouchingPersonSilhouetteIsNotMistakenForHeldObject() {
        val width = 18
        val height = 16
        val labels = ByteArray(width * height)
        fill(labels, width, 1, xRange = 2..7, yRange = 2..12)
        // 前景酒杯只与人物轮廓相切；中心落在人物包围盒外，不能因为深度接近就绑定。
        fill(labels, width, 2, xRange = 8..11, yRange = 9..12)
        val depth = FloatArray(labels.size) { 0.4f }
        setDepth(depth, labels, label = 1, value = 0.68f)
        setDepth(depth, labels, label = 2, value = 0.61f)

        val resolved = SpatialOwnershipRelationResolver.resolve(
            labels = labels,
            width = width,
            height = height,
            instances = listOf(
                SpatialSegmentationInstance(1, 1, 0.96f, 66),
                SpatialSegmentationInstance(2, 46, 0.91f, 16)
            ),
            depth = depth
        )

        assertEquals(setOf(1, 2), nonZeroLabels(resolved))
    }

    @Test
    fun touchingIndependentPeopleAreNeverCollapsed() {
        val width = 12
        val height = 8
        val labels = ByteArray(width * height)
        fill(labels, width, 1, xRange = 2..5, yRange = 1..6)
        fill(labels, width, 2, xRange = 6..9, yRange = 1..6)
        val depth = FloatArray(labels.size) { index ->
            if ((labels[index].toInt() and 0xff) == 1) 0.7f else 0.52f
        }

        val resolved = SpatialOwnershipRelationResolver.resolve(
            labels = labels,
            width = width,
            height = height,
            instances = listOf(
                SpatialSegmentationInstance(1, 1, 0.95f, 24),
                SpatialSegmentationInstance(2, 1, 0.94f, 24)
            ),
            depth = depth
        )

        assertEquals(setOf(1, 2), nonZeroLabels(resolved))
        assertFalse(resolved.contentEquals(ByteArray(resolved.size)))
    }

    private fun fill(
        labels: ByteArray,
        width: Int,
        label: Int,
        xRange: IntRange,
        yRange: IntRange
    ) {
        for (y in yRange) for (x in xRange) labels[y * width + x] = label.toByte()
    }

    private fun setDepth(
        depth: FloatArray,
        labels: ByteArray,
        label: Int,
        value: Float
    ) {
        for (index in labels.indices) {
            if ((labels[index].toInt() and 0xff) == label) depth[index] = value
        }
    }

    private fun nonZeroLabels(labels: ByteArray): Set<Int> = labels
        .map { it.toInt() and 0xff }
        .filter { it != 0 }
        .toSet()
}
