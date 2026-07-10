package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FableSolContainerGeometryTest {

    @Test
    fun measuredViewWidthControlsContainerGeometry() {
        val measuredWidthDp = 276.5
        val sim = FableSolSimulation(FableSolParams())

        sim.setContainerWidthDp(measuredWidthDp)

        assertEquals(measuredWidthDp, sim.containerWidthDp, 0.0)
        assertEquals(measuredWidthDp, sim.geometrySpan(), 1e-9)

        sim.setTilt(30.0, snap = true)
        val radians = Math.toRadians(30.0)
        val expectedSpan = measuredWidthDp * abs(cos(radians)) +
                FableSolSpec.HEIGHT_DP * abs(sin(radians))
        val expectedGravityHeight = FableSolSpec.HEIGHT_DP * abs(cos(radians)) +
                measuredWidthDp * abs(sin(radians))

        assertEquals(expectedSpan, sim.geometrySpan(), 1e-9)
        assertEquals(expectedGravityHeight, sim.renderInfo().hG, 1e-9)
    }

    @Test
    fun measuredViewCenterMapsToWaterCoordinateOrigin() {
        val measuredWidthDp = 291.25
        val sim = FableSolSimulation(FableSolParams())
        sim.setContainerWidthDp(measuredWidthDp)

        sim.injectLayer(
            i = 0,
            xDp = measuredWidthDp / 2.0,
            widthDp = 48.0,
            ampDp = 8.0,
            travel = 0.0
        )

        assertEquals(0.0, sim.layers[0].pending.single().u, 1e-9)
    }
}
