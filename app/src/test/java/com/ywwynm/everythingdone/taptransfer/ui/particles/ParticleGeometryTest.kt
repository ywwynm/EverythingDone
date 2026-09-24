package com.ywwynm.everythingdone.taptransfer.ui.particles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class ParticleGeometryTest {

    @Test
    fun noiseDerivativeMatchesFiniteDifference() {
        val noise = FlowNoise(3)
        val d = FloatArray(2)
        val ignored = FloatArray(2)
        val h = 1e-3f
        var worst = 0f
        for (i in 0 until 200) {
            val x = i * 0.137f + 0.05f
            val y = i * 0.291f + 0.11f
            noise.sample(x, y, d)
            val dx = (noise.sample(x + h, y, ignored) - noise.sample(x - h, y, ignored)) / (2 * h)
            val dy = (noise.sample(x, y + h, ignored) - noise.sample(x, y - h, ignored)) / (2 * h)
            worst = max(worst, max(abs(dx - d[0]), abs(dy - d[1])))
        }
        assertTrue("worst=$worst", worst < 2e-2f)
    }

    @Test
    fun curlFieldIsDivergenceFree() {
        val noise = FlowNoise(5)
        val a = FloatArray(2)
        val b = FloatArray(2)
        val h = 1e-2f
        var worst = 0f
        for (i in 0 until 100) {
            val x = i * 0.173f
            val y = i * 0.311f + 1f
            noise.curl(x + h, y, 0.4f, a)
            noise.curl(x - h, y, 0.4f, b)
            val dvx = (a[0] - b[0]) / (2 * h)
            noise.curl(x, y + h, 0.4f, a)
            noise.curl(x, y - h, 0.4f, b)
            val dvy = (a[1] - b[1]) / (2 * h)
            worst = max(worst, abs(dvx + dvy))
        }
        assertTrue("worst=$worst", worst < 0.1f)
    }

    @Test
    fun sameSeedGivesSameField() {
        val a = FloatArray(2)
        val b = FloatArray(2)
        FlowNoise(11).curl(3.3f, 1.7f, 0.2f, a)
        FlowNoise(11).curl(3.3f, 1.7f, 0.2f, b)
        assertEquals(a[0], b[0], 0f)
        assertEquals(a[1], b[1], 0f)
    }

    @Test
    fun perimeterLengthAndContinuity() {
        val shape = RoundRectPerimeter()
        shape.set(10f, 20f, 310f, 620f, 24f)
        val expected = 2 * (300f - 48f) + 2 * (600f - 48f) + 2 * Math.PI.toFloat() * 24f
        assertEquals(expected, shape.length, 1e-2f)

        val p = FloatArray(4)
        val q = FloatArray(4)
        val step = shape.length / 2000f
        var worstGap = 0f
        for (k in 0 until 2000) {
            shape.pointAt(k * step, p)
            shape.pointAt((k + 1) * step, q)
            worstGap = max(worstGap, hypot(q[0] - p[0], q[1] - p[1]))
            // 切线是单位向量
            assertEquals(1f, hypot(p[2], p[3]), 1e-3f)
            // 点在外框上或内侧
            assertTrue(p[0] >= 10f - 1e-3f && p[0] <= 310f + 1e-3f)
            assertTrue(p[1] >= 20f - 1e-3f && p[1] <= 620f + 1e-3f)
        }
        // 相邻两点的距离不超过步长太多：拐角处没有跳变
        assertTrue("worstGap=$worstGap step=$step", worstGap <= step * 1.01f)
    }

    @Test
    fun perimeterWrapsAroundAndInwardNormalPointsInside() {
        val shape = RoundRectPerimeter()
        shape.set(0f, 0f, 200f, 100f, 16f)
        val a = FloatArray(4)
        val b = FloatArray(4)
        shape.pointAt(-5f, a)
        shape.pointAt(shape.length - 5f, b)
        assertEquals(a[0], b[0], 1e-3f)
        assertEquals(a[1], b[1], 1e-3f)
        for (k in 0 until 50) {
            shape.pointAt(shape.length * k / 50f, a)
            val nx = -a[3]
            val ny = a[2]
            val insideX = a[0] + nx * 5f
            val insideY = a[1] + ny * 5f
            assertTrue(insideX > 0f && insideX < 200f && insideY > 0f && insideY < 100f)
        }
    }
}
