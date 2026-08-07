package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialRenderModeTest {

    @Test
    fun missingPreferenceDefaultsToBestAvailableRepresentation() {
        assertEquals(
            SpatialRenderMode.LDI_LITE,
            SpatialRenderMode.resolve(value = null, hasLdiLite = true)
        )
        assertEquals(
            SpatialRenderMode.SINGLE_LAYER,
            SpatialRenderMode.resolve(value = null, hasLdiLite = false)
        )
    }

    @Test
    fun unavailableDualLayerFallsBackToSingleLayer() {
        assertEquals(
            SpatialRenderMode.SINGLE_LAYER,
            SpatialRenderMode.resolve(
                value = SpatialRenderMode.LDI_LITE.stableId,
                hasLdiLite = false
            )
        )
    }

    @Test
    fun deprecatedHandcraftedMpiFallsBackToSourceLockedLayeredSplat() {
        assertEquals(
            SpatialRenderMode.LDI_LITE,
            SpatialRenderMode.resolve(
                value = SpatialRenderMode.MPI.stableId,
                hasLdiLite = true
            )
        )
        assertEquals(
            SpatialRenderMode.SINGLE_LAYER,
            SpatialRenderMode.resolve(
                value = SpatialRenderMode.MPI.stableId,
                hasLdiLite = false
            )
        )
    }
}
