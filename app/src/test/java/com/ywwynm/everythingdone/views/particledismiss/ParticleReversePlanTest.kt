package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleReversePlanTest {
    @Test fun `所有可见采样必须落入各自独立的历史范围`() {
        val values = FloatArray(1200)
        for (i in 0 until 100) {
            values[i * 12 + 2] = i / 120f
            values[i * 12 + 6] = .11f + (i % 9) / 30f
        }
        for (samples in listOf(18, 36, 60)) {
            val plan = ParticleReversePlan(values, samples)
            var next = 0
            for (i in 0 until 100) {
                val first = plan.ranges[i * 4]
                val last = plan.ranges[i * 4 + 1]
                assertEquals(next, plan.ranges[i * 4 + 2])
                for (frame in 0..samples) {
                    val age = plan.time(frame) - values[i * 12 + 2]
                    if (age > 0 && age < values[i * 12 + 6]) assertTrue(frame in first..last)
                }
                next += last - first + 1
            }
            assertEquals(next * 20, plan.bytes)
            assertTrue(plan.bytes < 100 * (samples + 1) * 20)
        }
    }

    @Test fun `逆向从完全消失开始并以完整原图结束且不回跳`() {
        val plan = ParticleReversePlan(FloatArray(0))
        assertEquals(36, plan.frame(0f))
        assertEquals(0, plan.frame(1f))
        assertEquals(36, plan.frame(-1f))
        assertEquals(0, plan.frame(2f))
        var previous = plan.samples
        for (i in 0..240) {
            val frame = plan.frame(i / 240f)
            assertTrue(frame <= previous)
            previous = frame
        }
    }

    @Test fun `快速播放减少历史副本而系统慢放仍保留完整采样`() {
        val values = FloatArray(12).apply { this[2] = .2f; this[6] = .5f }
        assertEquals(36, ParticleReversePlan.samplesFor(.6f, 120f))
        assertEquals(18, ParticleReversePlan.samplesFor(.6f, 30f))
        assertEquals(60, ParticleReversePlan.samplesFor(1.2f, 60f))
        val fast = ParticleReversePlan(values, 36)
        val slow = ParticleReversePlan(values, 60)
        assertTrue(fast.bytes < slow.bytes * .7)
        assertEquals(1f, fast.time(36), 0f)
        assertEquals(0f, fast.time(0), 0f)
    }
}
