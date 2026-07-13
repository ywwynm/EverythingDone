package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolFramePacerTest {

    @Test
    fun `120Hz callbacks are reduced to stable 60Hz renders`() {
        val pacer = FableSolFramePacer(60.0)
        val vsyncNs = 1_000_000_000L / 120L
        var renders = 0

        for (frame in 0..120) {
            if (pacer.shouldRender(frame * vsyncNs)) renders++
        }

        assertEquals(61, renders)
    }

    @Test
    fun `reset makes the next callback render immediately`() {
        val pacer = FableSolFramePacer(60.0)

        assertTrue(pacer.shouldRender(0L))
        assertFalse(pacer.shouldRender(8_333_333L))
        pacer.reset()
        assertTrue(pacer.shouldRender(8_333_333L))
    }
}
