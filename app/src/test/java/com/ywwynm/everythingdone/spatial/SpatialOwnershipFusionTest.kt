package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialOwnershipFusionTest {

    @Test
    fun personBoundaryUsesMatteWhileTrustedInteriorSurvivesMatteHole() {
        val width = 7
        val height = 7
        val labels = ByteArray(width * height)
        for (y in 1..5) for (x in 1..5) labels[y * width + x] = 1
        val segmentation = SpatialSegmentationData(
            width,
            height,
            labels,
            ByteArray(labels.size) { if (labels[it].toInt() != 0) 0xff.toByte() else 0 },
            listOf(SpatialSegmentationInstance(1, 1, 0.95f, 25))
        )
        val matteValues = FloatArray(width * height)
        for (y in 1..5) for (x in 1..5) matteValues[y * width + x] = 1f
        matteValues[3 * width + 1] = 0.25f
        matteValues[3 * width + 3] = 0f

        val result = checkNotNull(
            SpatialOwnershipFusion.build(
                segmentation,
                SpatialAlphaData(width, height, matteValues),
                width,
                height,
                width,
                height
            )
        )

        assertEquals(64, result.alpha[3 * width + 1].toInt() and 0xff)
        assertEquals(255, result.alpha[3 * width + 3].toInt() and 0xff)
        assertEquals(1, result.continuityLabels[3 * width + 3].toInt() and 0xff)
    }

    @Test
    fun personMatteExpandsBoundaryWithoutMergingTwoPersonIdentities() {
        val width = 20
        val height = 12
        val labels = ByteArray(width * height)
        for (y in 4..7) for (x in 4..6) labels[y * width + x] = 1
        for (y in 4..7) for (x in 14..16) labels[y * width + x] = 2
        val segmentation = SpatialSegmentationData(
            width = width,
            height = height,
            labels = labels,
            alpha = ByteArray(labels.size) { if (labels[it].toInt() != 0) 0xff.toByte() else 0 },
            instances = listOf(
                SpatialSegmentationInstance(1, 1, 0.9f, 12),
                SpatialSegmentationInstance(2, 1, 0.8f, 12)
            )
        )
        val matte = SpatialAlphaData(width, height, FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (y in 2..9 && x in 1..18) 1f else 0f
        })

        val result = checkNotNull(
            SpatialOwnershipFusion.build(
                segmentation,
                matte,
                width,
                height,
                width,
                height
            )
        )

        assertEquals(setOf(1, 2), result.labels.map { it.toInt() and 0xff }.filter { it > 0 }.toSet())
        assertEquals(1, result.labels[5 * width + 2].toInt() and 0xff)
        assertEquals(2, result.labels[5 * width + 17].toInt() and 0xff)
        assertTrue(result.subjectMask.count { (it.toInt() and 0xff) != 0 } > 24)
    }

    @Test
    fun nonPersonObjectIsNotOverwrittenByPersonMatte() {
        val labels = byteArrayOf(
            1, 0, 2,
            1, 0, 2,
            1, 0, 2
        )
        val segmentation = SpatialSegmentationData(
            3,
            3,
            labels,
            ByteArray(9) { 0xff.toByte() },
            listOf(
                SpatialSegmentationInstance(1, 1, 0.9f, 3),
                SpatialSegmentationInstance(2, 44, 0.9f, 3)
            )
        )
        val matte = SpatialAlphaData(3, 3, FloatArray(9) { 1f })

        val result = checkNotNull(
            SpatialOwnershipFusion.build(segmentation, matte, 3, 3, 3, 3)
        )

        assertEquals(2, result.labels[2].toInt() and 0xff)
        assertEquals(2, result.labels[5].toInt() and 0xff)
        assertEquals(2, result.labels[8].toInt() and 0xff)
        assertEquals(
            "非人物实例不能获得人物内部断边抑制权限",
            0,
            result.continuityLabels[5].toInt() and 0xff
        )
    }

    @Test
    fun personMatteCannotFloodIntoDistantConnectedFalsePositive() {
        val width = 40
        val height = 12
        val labels = ByteArray(width * height)
        for (y in 4..7) for (x in 4..7) labels[y * width + x] = 1
        val segmentation = SpatialSegmentationData(
            width,
            height,
            labels,
            ByteArray(labels.size) { if (labels[it].toInt() != 0) 0xff.toByte() else 0 },
            listOf(SpatialSegmentationInstance(1, 1, 0.95f, 16))
        )
        // 模拟高分辨率 MODNet 把人物、桌面和杯子错误连成一个前景分量。
        val matte = SpatialAlphaData(
            width,
            height,
            FloatArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (y in 3..8 && x in 2..36) 1f else 0f
            }
        )

        val result = checkNotNull(
            SpatialOwnershipFusion.build(segmentation, matte, width, height, width, height)
        )

        assertEquals(1, result.labels[5 * width + 2].toInt() and 0xff)
        assertEquals(0, result.labels[5 * width + 20].toInt() and 0xff)
        assertEquals(0, result.labels[5 * width + 35].toInt() and 0xff)
        assertEquals(0, result.alpha[5 * width + 20].toInt() and 0xff)
    }

    @Test
    fun subMeshPersonMatteEdgeIsNotClippedByCategoricalLabels() {
        val meshWidth = 4
        val meshHeight = 4
        val alphaWidth = 8
        val alphaHeight = 8
        val labels = ByteArray(meshWidth * meshHeight)
        for (y in 1..2) for (x in 1..2) labels[y * meshWidth + x] = 1
        val segmentation = SpatialSegmentationData(
            meshWidth,
            meshHeight,
            labels,
            ByteArray(labels.size) { if (labels[it].toInt() != 0) 0xff.toByte() else 0 },
            listOf(SpatialSegmentationInstance(1, 1, 0.95f, 4))
        )
        val matteValues = FloatArray(alphaWidth * alphaHeight)
        for (y in 2..5) for (x in 2..5) matteValues[y * alphaWidth + x] = 1f
        // 该亚网格软边位于 categorical label 的外侧，但仍属于同一人物轮廓。
        matteValues[3 * alphaWidth + 1] = 0.3f

        val result = checkNotNull(
            SpatialOwnershipFusion.build(
                segmentation,
                SpatialAlphaData(alphaWidth, alphaHeight, matteValues),
                meshWidth,
                meshHeight,
                alphaWidth,
                alphaHeight
            )
        )

        assertEquals(0, result.labels[1 * meshWidth].toInt() and 0xff)
        assertTrue(result.alpha[3 * alphaWidth + 1].toInt() and 0xff > 0)
    }

    @Test
    fun personMatteExpansionRemainsANarrowBoundaryRefinementAtLargeMeshSizes() {
        val width = 400
        val height = 8
        val labels = ByteArray(width * height)
        for (y in 2..5) for (x in 100..103) labels[y * width + x] = 1
        val segmentation = SpatialSegmentationData(
            width,
            height,
            labels,
            ByteArray(labels.size) { if (labels[it].toInt() != 0) 0xff.toByte() else 0 },
            listOf(SpatialSegmentationInstance(1, 1, 0.95f, 16))
        )
        val matte = SpatialAlphaData(
            width,
            height,
            FloatArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (y in 1..6 && x in 96..112) 1f else 0f
            }
        )

        val result = checkNotNull(
            SpatialOwnershipFusion.build(segmentation, matte, width, height, width, height)
        )

        assertEquals(1, result.labels[3 * width + 105].toInt() and 0xff)
        assertEquals(0, result.labels[3 * width + 106].toInt() and 0xff)
        assertTrue(result.alpha[3 * width + 106].toInt() and 0xff > 0)
        assertEquals(0, result.alpha[3 * width + 107].toInt() and 0xff)
    }
}
