package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.*
import org.junit.Test

class ParticleGlHandoffStateTest {
    @Test fun `取消早于首帧回调时不再次隐藏控件`() {
        val state = ParticleGlHandoffState()
        var nativeVisible = true
        assertTrue(state.beginStart())
        val pendingFrame = { if (state.acceptStartFrame()) nativeVisible = false }
        assertTrue(state.beginReturn())
        pendingFrame()
        assertTrue(nativeVisible)
        assertTrue(state.close())
        pendingFrame()
        assertTrue(nativeVisible)
    }

    @Test fun `多次启动和重复首帧只隐藏一次`() {
        val state = ParticleGlHandoffState()
        var hidden = 0
        repeat(4) { state.beginStart() }
        repeat(4) { if (state.acceptStartFrame()) hidden++ }
        assertEquals(1, hidden)
        assertFalse(state.beginStart())
    }

    @Test fun `预热后直接取消仍可完成一次恢复流程`() {
        val state = ParticleGlHandoffState()
        assertTrue(state.beginReturn())
        assertFalse(state.beginStart())
        assertFalse(state.acceptStartFrame())
        assertFalse(state.beginReturn())
        assertTrue(state.close())
        assertFalse(state.close())
    }

    @Test fun `页面退出优先于所有迟到回调`() {
        for (started in listOf(false, true)) {
            val state = ParticleGlHandoffState()
            if (started) { state.beginStart(); state.acceptStartFrame() }
            assertTrue(state.close())
            assertFalse(state.beginStart())
            assertFalse(state.acceptStartFrame())
            assertFalse(state.beginReturn())
        }
    }
}
