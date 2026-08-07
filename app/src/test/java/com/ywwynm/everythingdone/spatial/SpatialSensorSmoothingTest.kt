package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSensorSmoothingTest {

    @Test
    fun `不同传感器采样率在相同时长后响应一致`() {
        val at60Hz = simulate(sampleRate = 60, seconds = 0.5f)
        val at200Hz = simulate(sampleRate = 200, seconds = 0.5f)

        assertTrue(abs(at60Hz - at200Hz) < 0.003f)
        assertTrue(at60Hz > 0.98f)
    }

    private fun simulate(sampleRate: Int, seconds: Float): Float {
        val steps = (sampleRate * seconds).toInt()
        val deltaNanos = (1_000_000_000L / sampleRate)
        var value = 0f
        repeat(steps) {
            val alpha = SpatialSensorSmoothing.alpha(deltaNanos)
            value += (1f - value) * alpha
        }
        return value
    }
}
