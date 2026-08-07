package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSegmentationPostprocessorTest {

    private val model = SpatialSegmentationModel.RF_DETR_SEG_NANO

    @Test
    fun twoIndependentPeopleProduceTwoMutuallyExclusiveLabels() {
        val logits = emptyLogits()
        setClassLogit(logits, query = 0, classId = 1, value = 6f)
        setClassLogit(logits, query = 1, classId = 1, value = 5f)
        val masks = emptyMasks()
        fillMask(masks, query = 0) { x, _ -> x < model.maskSize / 2 }
        fillMask(masks, query = 1) { x, _ -> x >= model.maskSize / 2 }

        val result = SpatialSegmentationPostprocessor.process(logits, masks, model)

        assertEquals(2, result.instances.size)
        assertEquals(setOf(1, 2), result.labels.map { it.toInt() and 0xff }.filter { it > 0 }.toSet())
        assertTrue(result.personLabels == setOf(1, 2))
    }

    @Test
    fun reservedCocoZeroSlotNeverCreatesOwnership() {
        val logits = emptyLogits()
        setClassLogit(
            logits,
            query = 0,
            classId = 0,
            value = 12f
        )
        val masks = emptyMasks()
        fillMask(masks, query = 0) { x, y ->
            x in 8 until 28 && y in 8 until 28
        }

        val result = SpatialSegmentationPostprocessor.process(logits, masks, model)

        assertTrue(result.instances.isEmpty())
        assertFalse(result.labels.any { (it.toInt() and 0xff) != 0 })
    }

    @Test
    fun finalCocoCategorySlotRemainsAvailable() {
        val logits = emptyLogits()
        setClassLogit(
            logits,
            query = 0,
            classId = 90,
            value = 12f
        )
        val masks = emptyMasks()
        fillMask(masks, query = 0) { x, y ->
            x in 8 until 28 && y in 8 until 28
        }

        val result = SpatialSegmentationPostprocessor.process(logits, masks, model)

        assertEquals(1, result.instances.size)
        assertEquals(90, result.instances.single().classId)
        assertTrue(result.labels.any { (it.toInt() and 0xff) == 1 })
    }

    @Test
    fun selectedQueryRetainsNormalizedDetectionBoxForPromptRefinement() {
        val logits = emptyLogits()
        setClassLogit(logits, query = 3, classId = 1, value = 8f)
        val masks = emptyMasks()
        fillMask(masks, query = 3) { x, y -> x in 12 until 48 && y in 10 until 60 }
        val boxes = FloatArray(model.queryCount * 4)
        boxes[3 * 4] = 0.50f
        boxes[3 * 4 + 1] = 0.45f
        boxes[3 * 4 + 2] = 0.40f
        boxes[3 * 4 + 3] = 0.50f

        val result = SpatialSegmentationPostprocessor.process(logits, masks, model, boxes)

        val box = requireNotNull(result.instances.single().box)
        assertEquals(0.30f, box.left, 1e-6f)
        assertEquals(0.20f, box.top, 1e-6f)
        assertEquals(0.70f, box.right, 1e-6f)
        assertEquals(0.70f, box.bottom, 1e-6f)
    }

    @Test
    fun hugeDiningTableIsNotTurnedIntoAFullFrameRigidPlane() {
        val logits = emptyLogits()
        setClassLogit(logits, query = 0, classId = 67, value = 8f)
        val masks = emptyMasks()
        fillMask(masks, query = 0) { _, y -> y >= model.maskSize / 4 }

        val result = SpatialSegmentationPostprocessor.process(logits, masks, model)

        assertTrue(result.instances.isEmpty())
    }

    @Test
    fun viewDependentTablewareDoesNotBecomeAnOpaqueRigidPlane() {
        for (classId in listOf(44, 46, 47, 48, 49, 50, 51)) {
            val logits = emptyLogits()
            setClassLogit(logits, query = 0, classId = classId, value = 8f)
            val masks = emptyMasks()
            fillMask(masks, query = 0) { x, y ->
                x in 12 until 48 && y in 12 until 48
            }

            val result = SpatialSegmentationPostprocessor.process(logits, masks, model)

            assertTrue("COCO class $classId 不应成为不透明刚性层", result.instances.isEmpty())
        }
    }

    @Test
    fun tinyDistantInstanceStaysInContinuousDepthSurface() {
        val logits = emptyLogits()
        setClassLogit(logits, query = 0, classId = 1, value = 8f)
        setClassLogit(logits, query = 1, classId = 1, value = 8f)
        val masks = emptyMasks()
        fillMask(masks, query = 0) { x, _ -> x < model.maskSize / 2 }
        fillMask(masks, query = 1) { x, y ->
            x in model.maskSize - 8 until model.maskSize - 1 &&
                y in 8 until 15
        }

        val result = SpatialSegmentationPostprocessor.process(logits, masks, model)

        assertEquals(1, result.instances.size)
        assertEquals(1, result.instances.single().classId)
        assertEquals(
            setOf(1),
            result.labels.map { it.toInt() and 0xff }.filter { it > 0 }.toSet()
        )
    }

    private fun emptyLogits() = FloatArray(model.queryCount * model.classLogitCount) { -12f }

    private fun emptyMasks() = FloatArray(
        model.queryCount * model.maskSize * model.maskSize
    ) { -8f }

    private fun setClassLogit(logits: FloatArray, query: Int, classId: Int, value: Float) {
        logits[query * model.classLogitCount + classId] = value
    }

    private fun fillMask(
        masks: FloatArray,
        query: Int,
        include: (x: Int, y: Int) -> Boolean
    ) {
        val offset = query * model.maskSize * model.maskSize
        for (y in 0 until model.maskSize) for (x in 0 until model.maskSize) {
            masks[offset + y * model.maskSize + x] = if (include(x, y)) 8f else -8f
        }
    }
}
