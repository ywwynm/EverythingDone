package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialInpaintingMaskTest {

    @Test
    fun conditioningMaskAddsValidatedOccluderWithoutExpandingWriteMask() {
        val writeMask = booleanArrayOf(
            false, true, false,
            false, false, false,
            false, true, false
        )
        val originalWriteMask = writeMask.copyOf()
        val occluderMask = booleanArrayOf(
            false, false, false,
            true, true, true,
            false, false, false
        )

        val conditioningMask = SpatialInpaintingMask.withOccluder(
            writeMask = writeMask,
            occluderMask = occluderMask
        )

        assertArrayEquals(originalWriteMask, writeMask)
        assertArrayEquals(
            booleanArrayOf(
                false, true, false,
                true, true, true,
                false, true, false
            ),
            conditioningMask
        )
        assertTrue(writeMask.indices.all { !writeMask[it] || conditioningMask[it] })
    }

    @Test(expected = IllegalArgumentException::class)
    fun conditioningMaskRejectsDifferentSizedOccluder() {
        SpatialInpaintingMask.withOccluder(
            writeMask = BooleanArray(4),
            occluderMask = BooleanArray(3)
        )
    }

    @Test
    fun downsampleDoesNotLoseThinHiddenPixels() {
        val source = BooleanArray(8 * 4)
        source[1 * 8 + 3] = true

        val resized = SpatialInpaintingMask.conservativeResize(
            source = source,
            sourceWidth = 8,
            sourceHeight = 4,
            regionLeft = 0,
            regionTop = 0,
            regionWidth = 8,
            regionHeight = 4,
            targetWidth = 2,
            targetHeight = 1
        )

        assertArrayEquals(booleanArrayOf(true, false), resized)
    }

    @Test
    fun resizeOnlyReadsRequestedRegion() {
        val source = BooleanArray(6 * 5)
        source[0] = true
        source[2 * 6 + 3] = true

        val resized = SpatialInpaintingMask.conservativeResize(
            source = source,
            sourceWidth = 6,
            sourceHeight = 5,
            regionLeft = 2,
            regionTop = 1,
            regionWidth = 3,
            regionHeight = 3,
            targetWidth = 3,
            targetHeight = 3
        )

        assertTrue(resized[1 * 3 + 1])
        assertFalse(resized[0])
        assertFalse(resized[8])
    }

    @Test
    fun upsampleKeepsEveryChildOfHiddenSourcePixelMarked() {
        val resized = SpatialInpaintingMask.conservativeResize(
            source = booleanArrayOf(true, false),
            sourceWidth = 2,
            sourceHeight = 1,
            regionLeft = 0,
            regionTop = 0,
            regionWidth = 2,
            regionHeight = 1,
            targetWidth = 4,
            targetHeight = 2
        )

        assertArrayEquals(
            booleanArrayOf(
                true, true, false, false,
                true, true, false, false
            ),
            resized
        )
    }
}
