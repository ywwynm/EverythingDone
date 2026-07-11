package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FableSolContainerGeometryTest {

    @Test
    fun fullInversionKeepsOneHundredEightyDegreeRenderAngle() {
        val sim = FableSolSimulation(FableSolParams())

        sim.setTilt(180.0, snap = true)

        assertEquals(180.0, abs(Math.toDegrees(sim.renderInfo().thetaRad)), 1e-9)
        assertEquals(FableSolSpec.REFERENCE_WIDTH_DP, sim.geometrySpan(), 1e-9)
        assertEquals(FableSolSpec.HEIGHT_DP, sim.renderInfo().hG, 1e-9)
    }

    @Test
    fun crossingOneHundredEightyDegreesUsesShortestRotation() {
        val sim = FableSolSimulation(FableSolParams())
        sim.setTilt(179.0, snap = true)

        sim.setTilt(-179.0)

        assertEquals(181.0, sim.thetaDeg, 1e-9)
    }

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
