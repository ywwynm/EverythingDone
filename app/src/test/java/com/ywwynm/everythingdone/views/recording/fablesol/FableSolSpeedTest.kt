package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolSpeedTest {

    @Test
    fun reliableBeatDoesNotDiscardSurfaceSubdivisions() {
        val unmetered = FableSolSpeed.effectiveEventRate(8.0, 2.0, 0.0)
        val metered = FableSolSpeed.effectiveEventRate(8.0, 2.0, 1.0)

        assertEquals(unmetered, metered, 1e-12)
        assertTrue("metered=$metered", metered >= 6.5)
    }

    @Test
    fun fastWindowCanRaiseNewlyDenseSurfaceWithoutShorteningRelease() {
        val rising = FableSolSpeed.surfaceEventRate(6.0, 2.0)
        val falling = FableSolSpeed.surfaceEventRate(1.0, 4.0)

        assertTrue("rising=$rising", rising >= 4.5)
        assertEquals(4.0, falling, 1e-12)
    }

    @Test
    fun reliableMediumTempoCannotPullHighSurfaceDensityDown() {
        val densityOnly = FableSolSpeed.fusePerceivedSpeed01(6.0, 0.0, 0.0)
        val withReliableMediumTempo = FableSolSpeed.fusePerceivedSpeed01(6.0, 0.35, 1.0)

        assertTrue(
            "densityOnly=$densityOnly withTempo=$withReliableMediumTempo",
            withReliableMediumTempo >= densityOnly
        )
    }

    @Test
    fun defaultSpeedAttackReachesDenseTargetWithinOneSecond() {
        var state = 0.0
        repeat(100) {
            state = FableSolSpeed.smoothStep(state, 1.0, 100.0)
        }

        assertTrue("state=$state", state > 0.90)
    }

    @Test
    fun reliableFastMotionChannelCannotBeAveragedAway() {
        val baseline = FableSolSpeed.fuseMotionChannels01(0.30, 0.30, 0.30, 0.30)
        val fastVocal = FableSolSpeed.fuseMotionChannels01(0.30, 0.92, 0.30, 0.30)

        assertTrue("baseline=$baseline fastVocal=$fastVocal", fastVocal > baseline + 0.20)
    }

    @Test
    fun kineticEvidenceOnlyRaisesPerceivedSpeed() {
        val speed = 0.62
        val kinetic = FableSolSpeed.kineticDriveTarget01(
            speed, groove01 = 0.8, intensity01 = 0.9, positiveNovelty01 = 0.7)

        assertTrue("kinetic=$kinetic", kinetic >= speed)
        assertTrue(kinetic <= 1.0)
    }
}
