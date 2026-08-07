package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class SpatialBoundaryRefinementPostprocessorTest {

    @Test
    fun `低质量候选逐实例回退且不改变原始 ownership`() {
        val source = rectangleData(width = 12, height = 10, left = 3, top = 2, right = 8, bottom = 7)
        val logits = FloatArray(8 * 8) { 6f }

        val result = SpatialBoundaryRefinementPostprocessor.refine(
            source,
            listOf(candidate(label = 1, predictedIou = 0.40f, logits = logits, width = 8, height = 8))
        )

        assertNotSame(source.labels, result.labels)
        assertArrayEquals(source.labels, result.labels)
        assertArrayEquals(source.alpha, result.alpha)
        assertEquals(source.instances, result.instances)
    }

    @Test
    fun `边界细化只在窄带内扩张`() {
        val source = rectangleData(width = 14, height = 12, left = 4, top = 3, right = 9, bottom = 8)
        val logits = rectangleLogits(
            width = 14,
            height = 12,
            left = 2,
            top = 3,
            right = 9,
            bottom = 8
        )

        val result = SpatialBoundaryRefinementPostprocessor.refine(
            source,
            listOf(candidate(label = 1, predictedIou = 0.92f, logits = logits, width = 14, height = 12)),
            bandRadius = 1
        )

        assertEquals(1, labelAt(result, 3, 4))
        assertEquals(0, labelAt(result, 2, 4))
        assertEquals(1, labelAt(result, 9, 8))
        assertEquals(0, labelAt(result, 10, 8))
    }

    @Test
    fun `纹理相关内部孔洞不能打穿 RF 锁定内部`() {
        val source = rectangleData(width = 15, height = 15, left = 2, top = 2, right = 12, bottom = 12)
        val logits = rectangleLogits(
            width = 15,
            height = 15,
            left = 2,
            top = 2,
            right = 12,
            bottom = 12
        ).also {
            it[7 * 15 + 7] = -8f
            it[7 * 15 + 8] = -8f
        }

        val result = SpatialBoundaryRefinementPostprocessor.refine(
            source,
            listOf(candidate(label = 1, predictedIou = 0.95f, logits = logits, width = 15, height = 15)),
            bandRadius = 2
        )

        assertEquals(1, labelAt(result, 7, 7))
        assertEquals(1, labelAt(result, 8, 7))
        assertEquals(255, alphaAt(result, 7, 7))
    }

    @Test
    fun `面积泄漏超过质量门时保留 RF 结果`() {
        val source = rectangleData(width = 12, height = 12, left = 4, top = 4, right = 7, bottom = 7)
        val logits = FloatArray(12 * 12) { 7f }

        val result = SpatialBoundaryRefinementPostprocessor.refine(
            source,
            listOf(candidate(label = 1, predictedIou = 0.99f, logits = logits, width = 12, height = 12)),
            bandRadius = 2
        )

        assertArrayEquals(source.labels, result.labels)
        assertArrayEquals(source.alpha, result.alpha)
    }

    @Test
    fun `轮廓窄带内但不与原实例连通的孤岛不会被加入`() {
        val source = rectangleData(width = 13, height = 10, left = 3, top = 2, right = 6, bottom = 7)
        val logits = rectangleLogits(13, 10, 3, 2, 6, 7).also {
            it[4 * 13 + 8] = 8f
            it[5 * 13 + 8] = 8f
        }

        val result = SpatialBoundaryRefinementPostprocessor.refine(
            source,
            listOf(candidate(1, 0.95f, logits, 13, 10)),
            bandRadius = 2
        )

        assertEquals(1, labelAt(result, 6, 4))
        assertEquals(0, labelAt(result, 8, 4))
        assertEquals(0, labelAt(result, 8, 5))
    }

    @Test
    fun `多实例扩张重叠时仍输出互斥标签和更新后的像素计数`() {
        val width = 14
        val height = 8
        val labels = ByteArray(width * height)
        val alpha = ByteArray(labels.size)
        fillRectangle(labels, alpha, width, 2, 2, 5, 5, 1)
        fillRectangle(labels, alpha, width, 8, 2, 11, 5, 2)
        val source = SpatialSegmentationData(
            width,
            height,
            labels,
            alpha,
            listOf(
                SpatialSegmentationInstance(1, 1, 0.90f, 16),
                SpatialSegmentationInstance(2, 3, 0.80f, 16)
            )
        )
        val first = rectangleLogits(width, height, 2, 2, 7, 5)
        val second = rectangleLogits(width, height, 6, 2, 11, 5)

        val result = SpatialBoundaryRefinementPostprocessor.refine(
            source,
            listOf(
                candidate(1, 0.95f, first, width, height),
                candidate(2, 0.95f, second, width, height)
            ),
            bandRadius = 2
        )

        assertEquals(1, labelAt(result, 6, 3))
        assertEquals(
            result.labels.count { (it.toInt() and 0xff) == 1 },
            result.instances.single { it.label == 1 }.pixelCount
        )
        assertEquals(
            result.labels.count { (it.toInt() and 0xff) == 2 },
            result.instances.single { it.label == 2 }.pixelCount
        )
    }

    private fun candidate(
        label: Int,
        predictedIou: Float,
        logits: FloatArray,
        width: Int,
        height: Int
    ) = SpatialBoundaryRefinementPostprocessor.Candidate(
        label = label,
        predictedIou = predictedIou,
        maskLogits = logits,
        width = width,
        height = height
    )

    private fun rectangleData(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): SpatialSegmentationData {
        val labels = ByteArray(width * height)
        val alpha = ByteArray(labels.size)
        fillRectangle(labels, alpha, width, left, top, right, bottom, 1)
        val count = (right - left + 1) * (bottom - top + 1)
        return SpatialSegmentationData(
            width,
            height,
            labels,
            alpha,
            listOf(SpatialSegmentationInstance(1, 1, 0.9f, count))
        )
    }

    private fun fillRectangle(
        labels: ByteArray,
        alpha: ByteArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        label: Int
    ) {
        for (y in top..bottom) {
            for (x in left..right) {
                labels[y * width + x] = label.toByte()
                alpha[y * width + x] = 0xff.toByte()
            }
        }
    }

    private fun rectangleLogits(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = FloatArray(width * height) { index ->
        val x = index % width
        val y = index / width
        if (x in left..right && y in top..bottom) 7f else -7f
    }

    private fun labelAt(data: SpatialSegmentationData, x: Int, y: Int): Int =
        data.labels[y * data.width + x].toInt() and 0xff

    private fun alphaAt(data: SpatialSegmentationData, x: Int, y: Int): Int =
        data.alpha[y * data.width + x].toInt() and 0xff
}
