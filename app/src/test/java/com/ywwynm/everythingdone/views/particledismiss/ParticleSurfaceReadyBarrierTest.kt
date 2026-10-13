package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticleSurfaceReadyBarrierTest {
    @Test fun `窗口已提交但独立表面尚无恢复帧时保留覆盖`() {
        var removed = 0
        val gate = ParticleSurfaceReadyBarrier(1) { removed++ }
        gate.windowReady()
        assertEquals(0, removed)
        gate.surfaceReady(0)
        assertEquals(1, removed)
    }

    @Test fun `表面先恢复不能早于窗口撤盖`() {
        var removed = 0
        val gate = ParticleSurfaceReadyBarrier(1) { removed++ }
        gate.surfaceReady(0)
        assertEquals(0, removed)
        gate.windowReady()
        assertEquals(1, removed)
    }

    @Test fun `多个表面和重复回调不会让尚未就绪的区域露出`() {
        var removed = 0
        val gate = ParticleSurfaceReadyBarrier(3) { removed++ }
        gate.windowReady()
        repeat(4) { gate.surfaceReady(1); gate.surfaceReady(2) }
        assertEquals(0, removed)
        gate.surfaceReady(0)
        repeat(4) { gate.surfaceReady(0); gate.windowReady() }
        assertEquals(1, removed)
    }

    @Test fun `提前准备的表面在窗口恢复时重建则等待替换后的缓冲`() {
        var removed = 0
        val gate = ParticleSurfaceReadyBarrier(2) { removed++ }
        gate.surfaceReady(0)
        gate.surfaceReady(1)
        gate.surfaceReplaced(1)
        gate.windowReady()
        assertEquals(0, removed)
        gate.surfaceReady(1)
        assertEquals(1, removed)
    }

    @Test fun `窗口退出后迟到的恢复帧不能执行正常撤盖流程`() {
        var removed = 0
        val gate = ParticleSurfaceReadyBarrier(1) { removed++ }
        gate.windowReady()
        gate.close()
        gate.surfaceReady(0)
        gate.windowReady()
        assertEquals(0, removed)
    }

    @Test fun `无独立表面仍须等普通窗口`() {
        var removed = 0
        val gate = ParticleSurfaceReadyBarrier(0) { removed++ }
        assertEquals(0, removed)
        gate.windowReady()
        assertEquals(1, removed)
    }
}
