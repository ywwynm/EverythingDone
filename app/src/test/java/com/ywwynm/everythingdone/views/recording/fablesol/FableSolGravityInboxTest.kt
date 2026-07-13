package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolGravityInboxTest {

    @Test
    fun `multiple sensor writes collapse to the latest sample`() {
        val inbox = FableSolGravityInbox()
        val output = FloatArray(3)
        var consumedSequence = 0

        inbox.offer(1f, 2f, 3f)
        inbox.offer(4f, 5f, 6f)
        val next = inbox.drainLatest(consumedSequence, output)

        assertTrue(next > consumedSequence)
        assertEquals(4f, output[0], 0f)
        assertEquals(5f, output[1], 0f)
        assertEquals(6f, output[2], 0f)
        consumedSequence = next
        assertFalse(inbox.hasUpdateAfter(consumedSequence))
    }
}
