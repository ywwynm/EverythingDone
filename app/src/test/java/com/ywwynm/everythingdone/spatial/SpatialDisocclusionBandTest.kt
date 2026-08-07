package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialDisocclusionBandTest {

    @Test
    fun keepsOnlyTheObjectInteriorThatCanBeRevealed() {
        val width = 9
        val height = 9
        val objectMask = BooleanArray(width * height)
        for (y in 2..6) for (x in 2..6) {
            objectMask[y * width + x] = true
        }

        val band = SpatialDisocclusionBand.inside(
            objectMask = objectMask,
            width = width,
            height = height,
            radius = 1
        )

        assertTrue(band[2 * width + 4])
        assertTrue(band[4 * width + 2])
        assertFalse(band[4 * width + 4])
        assertFalse(band[1 * width + 4])
    }

    @Test
    fun internalOpeningsAlsoSeedARevealBand() {
        val width = 9
        val height = 9
        val objectMask = BooleanArray(width * height) { true }
        objectMask[4 * width + 4] = false

        val band = SpatialDisocclusionBand.inside(
            objectMask = objectMask,
            width = width,
            height = height,
            radius = 2
        )

        assertTrue(band[4 * width + 5])
        assertTrue(band[4 * width + 6])
        assertFalse(band[4 * width + 7])
        assertFalse(band[4 * width + 4])
    }

    @Test
    fun requiredRadiusCoversWorstCaseRelativeParallaxAndSamplingSafety() {
        assertEquals(
            150,
            SpatialDisocclusionBand.requiredRadius(width = 900, height = 1200)
        )
        assertEquals(
            179,
            SpatialDisocclusionBand.requiredRadius(width = 1080, height = 1440)
        )
    }

    @Test
    fun measuredLayerSeparationShrinksTheRevealBudgetWithoutClippingMotion() {
        assertEquals(
            37,
            SpatialDisocclusionBand.requiredRadius(
                width = 1080,
                height = 1440,
                maximumRelativeDepth = 0.176f
            )
        )
    }

    @Test
    fun maximumRelativeDepthUsesEachRuntimeMotionLayer() {
        val labels = byteArrayOf(1, 1, 0, 2, 2, 0)
        val backgroundDepth = floatArrayOf(0.42f, 0.70f, 0.2f, 0.75f, 0.82f, 0.1f)
        val layers = listOf(
            SpatialOwnershipLayer.ObjectLayer(1, 0.50f, 2),
            SpatialOwnershipLayer.ObjectLayer(2, 0.90f, 2)
        )

        assertEquals(
            0.20f,
            SpatialDisocclusionBand.maximumRelativeDepth(
                labels = labels,
                backgroundDepth = backgroundDepth,
                layers = layers
            ),
            0.0001f
        )
    }
}
