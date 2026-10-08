package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticleDriftVisualSizeTest {
    @Test fun `同一大小倍率在不同显示密度下保持逻辑粒径`() {
        // 稳定普通粒子：源码平均半径 3.5，外框半宽系数 0.7，
        // 新片元轮廓的半峰值直径为外框的 0.5，工作面放大两倍。
        for (density in listOf(1f, 1.5f, 2f, 2.75f, 3f, 3.5f, 3.75f, 4f, 4.5f)) {
            for (multiplier in ParticleTuning.DRIFT_SIZE_LEVELS) {
                val scale = ParticleDriftModel.sizeScaleForDisplay(multiplier, density)
                val diameterDp = 3.5f * .7f * 4f * .5f * scale / density
                assertEquals("density=$density size=$multiplier", 1.6f * multiplier, diameterDp, 2e-6f)
            }
        }
    }

    @Test fun `显示密度只改变绘制大小而不改变数量与运动输入`() {
        fun model(density: Float) = ParticleDriftModel.random(1200, 700, -1f, -1f, 1f,
            ParticleDriftModel.seededRandom(129), size = ParticleDriftModel.sizeScaleForDisplay(1f, density))
        val first = model(2f)
        for (density in listOf(3f, 3.75f, 4.5f)) {
            val next = model(density)
            assertEquals(first.count, next.count)
            assertEquals(first.windX, next.windX, 0f)
            assertEquals(first.windY, next.windY, 0f)
            assertEquals(first.totalSeconds, next.totalSeconds, 0f)
            org.junit.Assert.assertArrayEquals(first.lattice, next.lattice, 0f)
            assertEquals(density / 2f, next.sizeScale / first.sizeScale, 5e-7f)
        }
    }
}
