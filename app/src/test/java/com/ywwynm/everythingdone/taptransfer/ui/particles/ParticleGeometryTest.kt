package com.ywwynm.everythingdone.taptransfer.ui.particles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** 碰一碰粒子的流场（光尘带的漂移用它）。 */
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
}
