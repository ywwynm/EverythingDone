package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialImageViewportTest {

    @Test
    fun portraitLetterboxKeepsAStableRectangularScissor() {
        val rect = SpatialImageViewport.scissor(
            surfaceWidth = 1000,
            surfaceHeight = 2000,
            scaleX = 1f,
            scaleY = 0.6f
        )

        assertEquals(0, rect.left)
        assertEquals(400, rect.bottom)
        assertEquals(1000, rect.width)
        assertEquals(1200, rect.height)
    }

    @Test
    fun landscapeLetterboxKeepsAStableRectangularScissor() {
        val rect = SpatialImageViewport.scissor(
            surfaceWidth = 1000,
            surfaceHeight = 1000,
            scaleX = 0.5f,
            scaleY = 1f
        )

        assertEquals(250, rect.left)
        assertEquals(0, rect.bottom)
        assertEquals(500, rect.width)
        assertEquals(1000, rect.height)
    }
}
