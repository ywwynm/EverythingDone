package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpatialDisocclusionGuardBandTest {

    @Test
    fun nearestKnownSourceStabilizesOnlyTheObjectDisocclusionBoundary() {
        val width = 9
        val height = 9
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            argb(255, x * 20, y * 20, 32)
        }
        val generated = IntArray(width * height) { argb(255, 8, 16, 224) }
        val hidden = BooleanArray(width * height)
        val objectMask = BooleanArray(width * height)
        for (y in 2..6) for (x in 2..6) {
            hidden[y * width + x] = true
            objectMask[y * width + x] = true
        }
        // 另一个补洞区域不属于对象显露带，不应被本规则改写。
        hidden[1 * width + 1] = true

        val result = SpatialDisocclusionGuardBand.stabilize(
            source = source,
            generated = generated,
            hiddenMask = hidden,
            revealMask = objectMask,
            fullObjectMask = objectMask,
            width = width,
            height = height,
            radius = 2
        )

        assertNotEquals(generated[4 * width + 2], result[4 * width + 2])
        assertEquals(generated[4 * width + 4], result[4 * width + 4])
        assertEquals(generated[1 * width + 1], result[1 * width + 1])
        assertEquals(source[0], result[0])
    }

    @Test
    fun alphaChannelRemainsOpaqueWhenBlendingTheGuardBand() {
        val result = SpatialDisocclusionGuardBand.stabilize(
            source = intArrayOf(argb(255, 255, 0, 0), argb(255, 0, 255, 0), argb(255, 0, 0, 255)),
            generated = intArrayOf(argb(255, 0, 0, 0), argb(255, 0, 0, 0), argb(255, 0, 0, 0)),
            hiddenMask = booleanArrayOf(false, true, true),
            revealMask = booleanArrayOf(false, true, true),
            fullObjectMask = booleanArrayOf(false, true, true),
            width = 3,
            height = 1,
            radius = 1
        )

        assertEquals(255, result[1] ushr 24)
    }

    @Test
    fun generatedInferenceContextOutsideThePersistedRevealIsRestored() {
        val source = intArrayOf(
            argb(255, 240, 16, 16),
            argb(255, 16, 240, 16),
            argb(255, 16, 16, 240)
        )
        val generated = IntArray(3) { argb(255, 80, 80, 80) }

        val result = SpatialDisocclusionGuardBand.stabilize(
            source = source,
            generated = generated,
            hiddenMask = booleanArrayOf(false, true, false),
            revealMask = booleanArrayOf(false, true, false),
            fullObjectMask = booleanArrayOf(false, true, false),
            width = 3,
            height = 1,
            radius = 1
        )

        assertEquals(source[0], result[0])
        assertEquals(source[2], result[2])
    }

    @Test
    fun foregroundInsideTheRevealBandIsNeverUsedAsKnownBackground() {
        val background = argb(255, 16, 220, 32)
        val foreground = argb(255, 220, 16, 32)
        val generated = argb(255, 32, 48, 220)
        val source = intArrayOf(
            background,
            background,
            foreground,
            foreground,
            foreground,
            foreground,
            foreground
        )

        val result = SpatialDisocclusionGuardBand.stabilize(
            source = source,
            generated = IntArray(source.size) { generated },
            hiddenMask = booleanArrayOf(false, false, true, true, false, false, false),
            revealMask = booleanArrayOf(false, false, true, true, false, false, false),
            fullObjectMask = booleanArrayOf(false, false, true, true, true, true, true),
            width = source.size,
            height = 1,
            radius = 1
        )

        assertEquals(generated, result[3])
        assertEquals(foreground, result[4])
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
