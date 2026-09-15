package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滑动完成记事的方向。正在做事界面左右滑都是完成记事，与首页左滑共用同一套粒子消散，
 * 差别只在这三处变号的量；符号写反在真机上表现为「粒子朝反方向飞」或「进度永远是 0」，
 * 所以在这里逐项定死。
 */
class ParticleSwipeDirectionTest {

    @Test fun `方向按位移取，恰好为零时保持未定向`() {
        assertEquals(ParticleSwipeDirection.LEFT, ParticleSwipeDirection.of(-1f))
        assertEquals(ParticleSwipeDirection.LEFT, ParticleSwipeDirection.of(-0.01f))
        assertEquals(ParticleSwipeDirection.RIGHT, ParticleSwipeDirection.of(1f))
        assertEquals(ParticleSwipeDirection.RIGHT, ParticleSwipeDirection.of(0.01f))
        assertEquals(ParticleSwipeDirection.UNKNOWN, ParticleSwipeDirection.of(0f))
    }

    @Test fun `两个方向滑过同样的距离得到同样的进度`() {
        val width = 1200
        val left = ParticleGestureProgress()
        left.update(ParticleSwipeDirection.progressDisplacement(
            ParticleSwipeDirection.LEFT, -600f), width)
        val right = ParticleGestureProgress()
        right.update(ParticleSwipeDirection.progressDisplacement(
            ParticleSwipeDirection.RIGHT, 600f), width)
        assertEquals(0.5f, left.progress, 0f)
        assertEquals(0.5f, right.progress, 0f)
    }

    @Test fun `往回滑过原点不会给出负进度`() {
        val width = 1200
        val gesture = ParticleGestureProgress()
        // 方向已定为左，手指却滑到了原点右侧：进度收在 0，不是负数也不是镜像后的正数。
        gesture.update(ParticleSwipeDirection.progressDisplacement(
            ParticleSwipeDirection.LEFT, 300f), width)
        assertEquals(0f, gesture.progress, 0f)
    }

    @Test fun `虚拟触点落在手指滑走的那一侧`() {
        // 快照左上角 x=100、宽 600，中心在 400；触点放在中心外 900 处。
        assertEquals(-500f,
            ParticleSwipeDirection.virtualTouchX(ParticleSwipeDirection.LEFT, 100f, 600, 900f), 0f)
        assertEquals(1300f,
            ParticleSwipeDirection.virtualTouchX(ParticleSwipeDirection.RIGHT, 100f, 600, 900f), 0f)
        // 未定向时按左滑处理，与首页原有取值完全一致。
        assertEquals(-500f,
            ParticleSwipeDirection.virtualTouchX(ParticleSwipeDirection.UNKNOWN, 100f, 600, 900f), 0f)
    }

    @Test fun `完成之后卡片停在滑走的那一侧屏外`() {
        assertEquals(-1080f, ParticleSwipeDirection.parkTranslationX(ParticleSwipeDirection.LEFT, 1080), 0f)
        assertEquals(1080f, ParticleSwipeDirection.parkTranslationX(ParticleSwipeDirection.RIGHT, 1080), 0f)
        assertEquals(-1080f, ParticleSwipeDirection.parkTranslationX(ParticleSwipeDirection.UNKNOWN, 1080), 0f)
    }
}
