package com.ywwynm.everythingdone.views

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MediaPreviewReturnMotionTest {
    private fun motion(from: Float, to: Float, speed: Float, density: Float = 3f, limit: Float = 36f * density) =
        MediaPreviewReturnMotion.Spring(from, to, speed, limit, .5f * density, 12f * density)

    @Test fun `真实快甩外移不超过36dp`() {
        val spring = motion(1583.9446f, 456f, 13375.58f)
        val positions = (0..3600).map { spring.position(it / 10f) }
        val outward = positions.max() - spring.from
        assertTrue("外移 $outward px", outward in 12f..108f)
        assertTrue(positions.indexOf(positions.max()) / 10f < 32f)
    }

    @Test fun `真实快甩300毫秒内完成主要回收`() {
        val spring = motion(1583.9446f, 456f, 13375.58f)
        assertTrue(abs(spring.position(300f) - spring.to) <= (spring.from - spring.to) * .01f)
        assertTrue(spring.durationMs <= 360)
    }

    @Test fun `接续任意方向初速度并在可见误差内停稳`() {
        for (speed in listOf(-2400f, -600f, 0f, 600f, 2400f)) {
            val spring = motion(600f, 100f, speed, density = 1f)
            assertEquals(600f, spring.position(0f), 0f)
            assertEquals(speed, spring.velocity(0f), 0f)
            assertEquals(speed, (spring.position(.01f) - spring.position(0f)) / .00001f, 9f)
            assertTrue(abs(spring.position(spring.durationMs.toFloat()) - 100f) <= .501f)
            assertTrue(abs(spring.velocity(spring.durationMs.toFloat())) <= 12.01f)
        }
    }

    @Test fun `上下方向镜像且没有终点反复振荡`() {
        val down = motion(1584f, 456f, 13376f)
        val up = motion(-1584f, -456f, -13376f)
        for (t in 0..500) {
            assertEquals(down.position(t.toFloat()), -up.position(t.toFloat()), .001f)
            assertTrue(down.position(t.toFloat()) >= down.to)
        }
    }

    @Test fun `静止松手直接收回且无额外外移`() {
        val spring = motion(800f, 100f, 0f)
        val values = (0..360).map { spring.position(it.toFloat()) }
        assertTrue(values.zipWithNext().all { it.second <= it.first })
        assertTrue(values.all { it in 100f..800f })
    }

    @Test fun `不同目标与极端速度仍限制外移并按时停稳`() {
        for (distance in listOf(-12000f, -1200f, -12f, 0f, 12f, 1200f, 12000f)) {
            for (speed in listOf(-60000f, -12000f, 0f, 12000f, 60000f)) {
                for (density in listOf(1f, 3f, 4f)) {
                    val spring = motion(distance, 0f, speed, density)
                    val values = (0..3600).map { spring.position(it / 10f) }
                    assertTrue(values.min() >= min(distance, 0f) - 36f * density - .01f)
                    assertTrue(values.max() <= max(distance, 0f) + 36f * density + .01f)
                    assertTrue(spring.durationMs <= 360)
                    assertTrue(abs(spring.position(360f)) <= .5f * density + .01f)
                    assertTrue(abs(spring.velocity(360f)) <= 12f * density + .01f)
                    assertEquals(speed, spring.velocity(0f), 0f)
                }
            }
        }
    }

    @Test fun `尺寸惯性不使媒体塌缩且密度缩放保持相同节奏`() {
        val a = motion(360f, 480f, -18000f, 1f, limit = 21.6f)
        val b = motion(1080f, 1440f, -54000f, 3f, limit = 64.8f)
        for (t in 0..360) {
            assertTrue(a.position(t.toFloat()) >= 338.39f)
            assertEquals(a.position(t.toFloat()) * 3f, b.position(t.toFloat()), .01f)
        }
        assertEquals(a.durationMs, b.durationMs)
    }
}
