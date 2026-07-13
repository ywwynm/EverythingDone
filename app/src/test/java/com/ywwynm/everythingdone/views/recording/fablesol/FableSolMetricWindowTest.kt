package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolMetricWindowTest {

    @Test
    fun `rolling window reports deterministic percentiles`() {
        val window = FableSolMetricWindow(100)
        for (value in 1..100) window.add(value.toDouble())

        assertEquals(50.5, window.percentile(50.0), 0.0001)
        assertEquals(95.05, window.percentile(95.0), 0.0001)
        assertEquals(99.01, window.percentile(99.0), 0.0001)
    }

    @Test
    fun `old values are overwritten without growing the window`() {
        val window = FableSolMetricWindow(3)
        window.add(1.0)
        window.add(2.0)
        window.add(3.0)
        window.add(100.0)

        assertEquals(3, window.size)
        assertEquals(2.0, window.percentile(0.0), 0.0001)
        assertEquals(100.0, window.percentile(100.0), 0.0001)
    }
}
