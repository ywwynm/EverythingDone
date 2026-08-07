package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialMattingResolutionPolicyTest {

    @Test
    fun highMemoryDeviceUsesSourceClass1440LongEdge() {
        assertEquals(
            1440,
            SpatialMattingResolutionPolicy.selectLongEdge(
                sourceLongEdge = 1440,
                baseLongEdge = 512,
                totalRamMb = 16_000,
                availableRamMb = 4_000
            )
        )
    }

    @Test
    fun mediumMemoryDeviceUses1024LongEdge() {
        assertEquals(
            1024,
            SpatialMattingResolutionPolicy.selectLongEdge(
                sourceLongEdge = 1440,
                baseLongEdge = 512,
                totalRamMb = 6_500,
                availableRamMb = 1_200
            )
        )
    }

    @Test
    fun highQualityHiddenBackgroundKeeps1440LongEdge() {
        assertEquals(1440, SpatialLdiLiteBuilder.BACKGROUND_LONG_EDGE)
    }

    @Test
    fun constrainedDeviceKeepsModelReferenceSize() {
        assertEquals(
            512,
            SpatialMattingResolutionPolicy.selectLongEdge(
                sourceLongEdge = 1440,
                baseLongEdge = 512,
                totalRamMb = 4_000,
                availableRamMb = 700
            )
        )
    }

    @Test
    fun policyNeverUpscalesPastSource() {
        assertEquals(
            640,
            SpatialMattingResolutionPolicy.selectLongEdge(
                sourceLongEdge = 640,
                baseLongEdge = 512,
                totalRamMb = 16_000,
                availableRamMb = 4_000
            )
        )
    }
}
