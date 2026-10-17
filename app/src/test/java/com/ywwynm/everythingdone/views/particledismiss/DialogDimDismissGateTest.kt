package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.*
import org.junit.Test

class DialogDimDismissGateTest {
    @Test fun `粒子首帧与覆盖层的任意到达顺序均先交接再淡出最后移除`() {
        for (overlayFirst in listOf(true, false)) {
            val events = mutableListOf<String>()
            val dimGate = DialogDimDismissGate { events += "remove" }
            val particleGate = ParticleDismissStartGate {
                events += "hide"
                assertTrue(dimGate.begin())
                events += "fade"
            }
            if (overlayFirst) particleGate.markOverlayShown() else particleGate.markAnimationStarted()
            assertTrue(events.isEmpty())
            dimGate.frameCommitted()
            if (overlayFirst) particleGate.markAnimationStarted() else particleGate.markOverlayShown()
            assertEquals(listOf("hide", "fade"), events)
            dimGate.dimCleared()
            particleGate.markOverlayShown()
            particleGate.markAnimationStarted()
            assertEquals(listOf("hide", "fade"), events)
            dimGate.frameCommitted()
            assertEquals(listOf("hide", "fade", "remove"), events)
        }
    }

    @Test fun `旧弹窗暗层退出不会阻止新弹窗独立进入退出流程`() {
        val removed = mutableListOf<String>()
        val first = DialogDimDismissGate { removed += "first" }
        val second = DialogDimDismissGate { removed += "second" }
        first.begin()
        second.begin()
        second.dimCleared()
        second.frameCommitted()
        assertEquals(listOf("second"), removed)
        first.dimCleared()
        first.frameCommitted()
        assertEquals(listOf("second", "first"), removed)
    }

    @Test fun `暗层归零之前的窗口帧不能触发移除`() {
        var dismissals = 0
        val gate = DialogDimDismissGate { dismissals++ }
        assertTrue(gate.begin())
        gate.frameCommitted()
        assertEquals(0, dismissals)
        assertTrue(gate.dimCleared())
        assertEquals(0, dismissals)
        gate.frameCommitted()
        assertEquals(1, dismissals)
    }

    @Test fun `按钮返回和销毁的重复关闭只移除一次`() {
        var dismissals = 0
        val gate = DialogDimDismissGate { dismissals++ }
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.dimCleared())
        assertFalse(gate.dimCleared())
        repeat(3) { gate.frameCommitted(); gate.finishImmediately() }
        assertEquals(1, dismissals)
    }

    @Test fun `宿主摘除后迟到的动画和帧回调失效`() {
        var dismissals = 0
        val gate = DialogDimDismissGate { dismissals++ }
        gate.begin()
        gate.detached()
        assertFalse(gate.dimCleared())
        gate.frameCommitted()
        gate.finishImmediately()
        assertEquals(0, dismissals)
    }

    @Test fun `动画被关闭时直接完成且后续帧不重复完成`() {
        var dismissals = 0
        val gate = DialogDimDismissGate { dismissals++ }
        gate.begin()
        gate.finishImmediately()
        gate.dimCleared()
        gate.frameCommitted()
        assertEquals(1, dismissals)
    }
}
