package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticleGestureProgressTest {
    @Test fun `按下预备不推进材料只有识别左滑后才接管进度`() {
        val gesture = ParticleGestureProgress(initiallyActive = false)
        gesture.presented = true
        gesture.update(-250f, 1000)
        assertEquals(0f, gesture.framePosition(60), 0f)
        assertEquals(0L, gesture.activatedAtNanos)
        gesture.activate()
        assertEquals(15f, gesture.framePosition(60), 0f)
        val activatedAt = gesture.activatedAtNanos
        gesture.activate()
        assertEquals(activatedAt, gesture.activatedAtNanos)
    }
    @Test fun `慢拖未跨过缓存档位时仍保留每次位移对应的画面进度`() {
        val gesture = ParticleGestureProgress().apply { presented = true }
        val positions = (0..120).map { pixel ->
            gesture.update(-pixel.toFloat(), 1000)
            gesture.framePosition(60).also { assertEquals(pixel / 1000f * 60, it, .00001f) }
        }
        assertEquals(121, positions.distinct().size)
    }

    @Test fun `立即左滑的首个粒子帧直接使用当前手指进度`() {
        val gesture = ParticleGestureProgress()
        gesture.update(-720f, 1080)
        assertEquals(40f, gesture.framePosition(60), 0f)
        gesture.presented = true
        assertEquals(40f, gesture.framePosition(60), 0f)
    }

    @Test fun `左移回拖和松手恢复访问同一相位且零位移恢复完整卡片`() {
        val gesture = ParticleGestureProgress().apply { presented = true }
        for (distance in listOf(-181f, -547f, -1080f, -547f, -181f, 0f, -547f)) {
            gesture.update(distance, 1080)
            assertEquals(-distance / 1080f, gesture.framePosition(60) / 60, .0001f)
        }
        // 手指停住时读取多少次都不能推进；墙钟和系统动画倍速不参与距离映射。
        repeat(120) { assertEquals(547 / 1080f * 60, gesture.framePosition(60), 0f) }
    }

    @Test fun `越界右移无效尺寸与非有限输入不会产生无效缓存索引`() {
        val gesture = ParticleGestureProgress().apply { presented = true }
        gesture.update(-2000f, 1080)
        assertEquals(60f, gesture.framePosition(60), 0f)
        for ((distance, width) in listOf(50f to 1080, -50f to 0,
            Float.NaN to 1080, Float.NEGATIVE_INFINITY to 1080)) {
            gesture.update(distance, width)
            assertEquals(0f, gesture.framePosition(60), 0f)
        }
    }

    @Test fun `松手收尾以实际呈现帧为起点而不是最新提交或手指进度`() {
        val gesture = ParticleGestureProgress()
        gesture.update(-1080f, 1080)
        gesture.submitted(10L, 0f)
        gesture.submitted(20L, .5f)
        gesture.submitted(30L, 1f)
        assertEquals(0f, gesture.progressAt(10L), 0f)
        assertEquals(.5f, gesture.progressAt(20L), 0f)
        gesture.seek(gesture.presentedProgress)
        assertEquals(.5f, gesture.progress, 0f)
        assertEquals(1f, gesture.progressAt(30L), 0f)
        gesture.submitted(40L, .25f)
        assertEquals(.25f, gesture.progressAt(40L), 0f)
    }
}
