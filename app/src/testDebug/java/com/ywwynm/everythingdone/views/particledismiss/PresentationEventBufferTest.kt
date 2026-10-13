package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.*
import org.junit.Test

class PresentationEventBufferTest {
    @Test fun capturedReportRemainsStableWhileRenderingAddsMoreEvents() {
        val events = PresentationEventBuffer<String>(frameCapacity = 2, lifecycleCapacity = 1)
        events.add("created", false); events.add("first", true); events.add("second", true)
        val captured = events.capture()
        events.add("third", true); events.add("restored", false)
        assertEquals(listOf("created", "first", "second"), captured.values())
        assertEquals(0, captured.droppedFrames)
        assertEquals(0, captured.droppedLifecycle)
        assertEquals(listOf("first", "third", "restored"), events.snapshot())
    }

    @Test fun continuousFramesDoNotEraseHiddenOrRestoredEvidence() {
        val events = PresentationEventBuffer<String>(frameCapacity = 6, lifecycleCapacity = 4)
        events.add("hidden", frame = false)
        repeat(10000) { events.add("frame-$it", frame = true) }
        events.add("restored", frame = false)
        events.add("detached", frame = false)
        assertEquals(listOf("hidden", "frame-0", "frame-1", "frame-2", "frame-9997", "frame-9998",
            "frame-9999", "restored", "detached"), events.snapshot())
        assertEquals(9994, events.droppedFrames)
        assertEquals(0, events.droppedLifecycle)
    }

    @Test fun interleavedCallbacksKeepTemporalOrderAndBoundedStorage() {
        val events = PresentationEventBuffer<String>(frameCapacity = 3, lifecycleCapacity = 2)
        events.add("created", false); events.add("frame-0", true); events.add("hidden", false)
        events.add("frame-1", true); events.add("frame-2", true); events.add("frame-3", true)
        events.add("restored", false)
        assertEquals(listOf("frame-0", "hidden", "frame-2", "frame-3", "restored"), events.snapshot())
        assertEquals(1, events.droppedLifecycle)
        assertEquals(1, events.droppedFrames)
    }
}
