package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolLayerColorPolicyTest {

    @Test
    fun pureThingUsesItsOriginalColorAtBothNearestLayerEndpoints() {
        val thingColor = intArrayOf(0x60, 0x7D, 0x8B)

        val colors = FableSolLayerColorPolicy.baseColors(thingColor, gradientEnd = null)

        assertArrayEquals(thingColor, colors.start)
        assertArrayEquals(thingColor, colors.end)
    }

    @Test
    fun gradientThingKeepsItsOriginalEndpointColors() {
        val start = intArrayOf(0x36, 0x66, 0x86)
        val end = intArrayOf(0xAE, 0x60, 0x60)

        val colors = FableSolLayerColorPolicy.baseColors(start, end)

        assertArrayEquals(start, colors.start)
        assertArrayEquals(end, colors.end)
    }

    @Test
    fun nearestLayerIsNeverLightenedByAudioOrMood() {
        val amount = FableSolLayerColorPolicy.lightenAmount(
            depth01 = 0.0,
            lightenFar = 0.6,
            moodBright = 1.0,
            breath = 0.18
        )

        assertEquals(0.0, amount, 0.0)
    }

    @Test
    fun fartherLayersStillKeepDepthAndAudioLightening() {
        val amount = FableSolLayerColorPolicy.lightenAmount(
            depth01 = 0.5,
            lightenFar = 0.6,
            moodBright = 0.5,
            breath = 0.1
        )

        assertEquals(0.37, amount, 1e-9)
    }
}
