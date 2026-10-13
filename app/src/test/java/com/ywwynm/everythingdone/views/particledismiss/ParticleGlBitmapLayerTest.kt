package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.*
import org.junit.Test

class ParticleGlBitmapLayerTest {
    @Test fun `粒子首帧替代静态卡片后会话消失不能闪回卡片`() {
        val card = ParticleGlBitmapLayer("card", 0, false).apply { texture = 1; visible = true }
        assertTrue(card.drawThisFrame(replacementDrawn = false))
        assertFalse(card.drawThisFrame(replacementDrawn = true))
        repeat(3) { assertFalse(card.drawThisFrame(replacementDrawn = false)) }
        // 只有显式的取消／回聚交接才再次展示静态末帧。
        card.visible = true
        assertTrue(card.drawThisFrame(replacementDrawn = false))
    }

    @Test fun `关闭后迟到的显示请求不能恢复前景`() {
        val front = ParticleGlBitmapLayer("front", 1, true).apply { texture = 2; visible = true }
        assertTrue(front.drawThisFrame(false))
        front.closed = true
        front.visible = true
        assertFalse(front.drawThisFrame(false))
    }

    @Test fun `上传前不能用空纹理替代真实内容`() {
        val front = ParticleGlBitmapLayer("front", 1, true).apply { visible = true }
        assertFalse(front.drawThisFrame(false))
        front.texture = 3
        assertTrue(front.drawThisFrame(false))
    }
}
