package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ParticleAppearanceDirectionTest {
    @Test fun `全部出现方向来自下方且覆盖左右两侧`() {
        for (i in 0..100) {
            val degrees = ParticleAppearanceDirection.degrees(i / 100.0)
            // 共享模型的屏幕 y 轴向下，因此原消散的 y 速度为 -sin(angle)。
            assertTrue(-sin(Math.toRadians(degrees.toDouble())) > .70)
        }
        assertEquals(225f, ParticleAppearanceDirection.degrees(0.0))
        assertEquals(270f, ParticleAppearanceDirection.degrees(.5))
        assertEquals(315f, ParticleAppearanceDirection.degrees(1.0))
    }
}
