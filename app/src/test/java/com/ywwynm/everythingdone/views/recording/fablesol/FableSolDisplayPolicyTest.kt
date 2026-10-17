package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.*
import org.junit.Test

class FableSolDisplayPolicyTest {
    @Test fun compatibilityCanTryHdrWithoutReportedCapabilities() {
        val compatibility = FableSolDisplayPolicy.usesPqCompatibility(36, "HUAWEI", "System 104.3.5.001(00HP)")
        assertTrue(compatibility)
        assertTrue(FableSolDisplayPolicy.canOfferHdr(36, compatibility, false, false))
    }

    @Test fun otherDevicesKeepTheirExistingCapabilityGate() {
        assertFalse(FableSolDisplayPolicy.usesPqCompatibility(36, "HUAWEI", "HarmonyOS 6"))
        assertFalse(FableSolDisplayPolicy.usesPqCompatibility(37, "OPPO", "System test"))
        assertFalse(FableSolDisplayPolicy.usesPqCompatibility(33, "HUAWEI", "System test"))
        for (hdr in listOf(false, true)) for (ratio in listOf(false, true)) {
            assertEquals(hdr && ratio, FableSolDisplayPolicy.canOfferHdr(34, false, hdr, ratio))
        }
        assertFalse(FableSolDisplayPolicy.canOfferHdr(33, true, true, true))
    }

    @Test fun unknownHeadroomIsNotAMeasuredOneTimesLimit() {
        assertEquals(9.6f, FableSolDisplayPolicy.contentHeadroom(true, Float.NaN), 0f)
        assertEquals(1f, FableSolDisplayPolicy.contentHeadroom(false, Float.NaN), 0f)
        assertEquals(1f, FableSolDisplayPolicy.contentHeadroom(true, 1f), 0f)
        assertEquals(2.4f, FableSolDisplayPolicy.contentHeadroom(true, 2.4f), 0f)
    }

    @Test fun offAlwaysReturnsToSdrEvenWithoutReportedCapabilities() {
        for (strength in listOf(1f, 9.6f, 1f, 1.01f, 3.6f, 1f, 9.6f)) {
            assertEquals(strength > 1f,
                FableSolDisplayPolicy.needsHdrSurface(36, true, false, false, strength))
            assertFalse(FableSolDisplayPolicy.needsHdrSurface(36, true, true, false, strength))
            assertEquals(strength > 1f,
                FableSolDisplayPolicy.needsHdrSurface(36, false, false, true, strength))
            assertFalse(FableSolDisplayPolicy.needsHdrSurface(36, false, false, false, strength))
            assertFalse(FableSolDisplayPolicy.needsHdrSurface(33, false, false, true, strength))
        }
    }

    @Test fun togglingOffAndReversingNeverJumpsToTheNewEndpoint() {
        val ramp = FableSolPqStrengthTransition()
        repeat(24) { ramp.update(9.6f, 1f / 120f) }
        assertEquals(9.6f, ramp.value, 0.00001f)
        val firstOff = ramp.update(1f, 1f / 120f)
        assertTrue(firstOff > 9f && firstOff < 9.6f)
        var previous = firstOff
        repeat(6) {
            val next = ramp.update(1f, 1f / 120f)
            assertTrue(next <= previous && next >= 1f)
            previous = next
        }
        val reverse = ramp.update(9.6f, 1f / 120f)
        assertTrue(reverse >= previous && reverse - previous < 0.1f)
        repeat(24) { ramp.update(1f, 1f / 120f) }
        assertEquals(1f, ramp.value, 0.00001f)
    }

    @Test fun strengthRampUsesElapsedTimeAndResetsOnNewSession() {
        val ramp = FableSolPqStrengthTransition()
        ramp.update(9.6f, 0f)
        assertEquals(1f, ramp.value, 0f)
        assertEquals(5.3f, ramp.update(9.6f, 0.09f), 0.00001f)
        assertEquals(9.6f, ramp.update(9.6f, 1f), 0.00001f)
        ramp.reset()
        assertEquals(1f, ramp.value, 0f)
        assertEquals(1f, ramp.update(Float.NaN, 1f), 0f)
    }

    @Test fun intentionalSdrDoesNotDisableHdrForTheRestOfTheDialog() {
        assertFalse(FableSolDisplayPolicy.isPqFailure(attemptedPq = false, active = false))
        assertFalse(FableSolDisplayPolicy.isPqFailure(attemptedPq = true, active = true))
        assertTrue(FableSolDisplayPolicy.isPqFailure(attemptedPq = true, active = false))
    }

    @Test fun rgba8SessionStillChangesColorSpaceAcrossOffAndBackOn() {
        val strengths = listOf(1f, 1.01f, 9.6f, 1f, 3.6f, 1f)
        val expected = listOf(FableSolScreenOutput.SDR, FableSolScreenOutput.PQ,
            FableSolScreenOutput.PQ, FableSolScreenOutput.SDR, FableSolScreenOutput.PQ,
            FableSolScreenOutput.SDR)
        assertEquals(expected, strengths.map {
            FableSolDisplayPolicy.requestedOutput(36, true, false, false, it)
        })
        for (strength in strengths) {
            assertEquals(FableSolScreenOutput.SDR,
                FableSolDisplayPolicy.requestedOutput(36, true, true, false, strength))
            assertEquals(if (strength > 1f) FableSolScreenOutput.SCRGB else FableSolScreenOutput.SDR,
                FableSolDisplayPolicy.requestedOutput(36, false, false, true, strength))
        }
    }
}
