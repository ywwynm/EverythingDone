package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolPitchPolicyTest {

    @Test
    fun fullPhonePitchMapsToSafeEndpointsAndCenter() {
        val base = 38.0

        assertEquals(-55.0, FableSolPitchPolicy.motionPitchDeg(-90.0), 1e-9)
        assertEquals(0.0, FableSolPitchPolicy.motionPitchDeg(0.0), 1e-9)
        assertEquals(55.0, FableSolPitchPolicy.motionPitchDeg(90.0), 1e-9)
        assertEquals(14.0, FableSolPitchPolicy.viewElevationDeg(-90.0, base), 1e-9)
        assertEquals(base, FableSolPitchPolicy.viewElevationDeg(0.0, base), 1e-9)
        assertEquals(68.0, FableSolPitchPolicy.viewElevationDeg(90.0, base), 1e-9)
    }

    @Test
    fun motionAndViewRemainStrictlyMonotonicAcrossThePhysicalRange() {
        val base = 38.0
        var previousMotion = FableSolPitchPolicy.motionPitchDeg(-90.0)
        var previousView = FableSolPitchPolicy.viewElevationDeg(-90.0, base)

        for (angle in -89..90) {
            val motion = FableSolPitchPolicy.motionPitchDeg(angle.toDouble())
            val view = FableSolPitchPolicy.viewElevationDeg(angle.toDouble(), base)
            assertTrue("motion angle=$angle previous=$previousMotion actual=$motion",
                motion > previousMotion)
            assertTrue("view angle=$angle previous=$previousView actual=$view",
                view > previousView)
            previousMotion = motion
            previousView = view
        }
    }

    @Test
    fun oldFiftyFiveDegreeBoundaryNoLongerCreatesAPlateau() {
        val base = 38.0
        val angles = doubleArrayOf(55.0, 70.0, 90.0)
        val motion = angles.map(FableSolPitchPolicy::motionPitchDeg)
        val view = angles.map { FableSolPitchPolicy.viewElevationDeg(it, base) }

        assertTrue("motion=$motion", motion[0] < motion[1] && motion[1] < motion[2])
        assertTrue("view=$view", view[0] < view[1] && view[1] < view[2])
    }

    @Test
    fun impossibleSensorAnglesAreSafelyBounded() {
        assertEquals(-90.0, FableSolPitchPolicy.rawPitchDeg(-120.0), 0.0)
        assertEquals(90.0, FableSolPitchPolicy.rawPitchDeg(120.0), 0.0)
        assertEquals(14.0, FableSolPitchPolicy.viewElevationDeg(-120.0, 38.0), 1e-9)
        assertEquals(68.0, FableSolPitchPolicy.viewElevationDeg(120.0, 38.0), 1e-9)
    }
}
