package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSourceLockTest {

    @Test
    fun `边距是每档幅度的常量且覆盖单位圆满偏移`() {
        val amplitude = SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
        val margin = SpatialSourceLock.coverMargin(amplitude)
        val required = amplitude * 0.5f +
            SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE

        assertTrue(margin.x > required)
        assertEquals(margin.x, margin.y, 0f)
        assertTrue(margin.x <= SpatialRenderDepthStabilizer.MAX_COVER_MARGIN)
    }

    @Test
    fun `边距随幅度单调且低幅度裁切更小`() {
        val full = SpatialSourceLock.coverMargin(
            SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
        )
        val minimum = SpatialSourceLock.coverMargin(
            SpatialRenderDepthStabilizer.MIN_PARALLAX_AMPLITUDE
        )

        assertTrue(minimum.x < full.x)
        assertTrue(minimum.x > 0f)
    }

    @Test
    fun `同一幅度下边距与视点无关`() {
        // P1 契约：倾斜过程零取景呼吸——边距不再是视点的函数，同幅度必然同值。
        val amplitude = 0.06f
        val first = SpatialSourceLock.coverMargin(amplitude)
        val second = SpatialSourceLock.coverMargin(amplitude)

        assertEquals(first.x, second.x, 0f)
        assertEquals(first.y, second.y, 0f)
    }

    @Test
    fun `满偏移仍保留多像素光栅安全量`() {
        val amplitude = SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
        val geometricMinimum = amplitude * 0.5f +
            SpatialRenderDepthStabilizer.RIGID_PAN_AMPLITUDE
        val margin = SpatialSourceLock.coverMargin(amplitude)

        assertTrue(margin.x - geometricMinimum >= 0.003f)
    }
}
