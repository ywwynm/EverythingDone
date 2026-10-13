package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticlePageFrameGateTest {
    @Test fun firstFrameCanStayOwnedWhileNextFrameStarts() {
        val gate = ParticlePageFrameGate()
        var captures = 0
        gate.retain()
        gate.submitted { captures++ }
        assertEquals(1, captures)
        assertEquals(1, gate.retained)
    }

    @Test fun replacementWaitsForGpuToReleasePreviousFrame() {
        val gate = ParticlePageFrameGate()
        var captures = 0
        gate.retain()
        gate.retain()
        gate.submitted { captures++ }
        assertEquals(0, captures)
        gate.release()
        assertEquals(1, captures)
        gate.release()
        assertEquals(1, captures)
    }

    @Test fun gpuReleaseBeforeSubmissionDoesNotLoseWakeup() {
        val gate = ParticlePageFrameGate()
        var captures = 0
        gate.retain()
        gate.retain()
        gate.release()
        assertEquals(0, captures)
        gate.submitted { captures++ }
        assertEquals(1, captures)
    }

    @Test fun closingStillAcceptsReleasesWithoutRestartingCapture() {
        val gate = ParticlePageFrameGate()
        var captures = 0
        gate.retain()
        gate.retain()
        gate.submitted { captures++ }
        gate.close()
        gate.release()
        gate.release()
        assertEquals(0, captures)
        assertEquals(0, gate.retained)
    }
}
