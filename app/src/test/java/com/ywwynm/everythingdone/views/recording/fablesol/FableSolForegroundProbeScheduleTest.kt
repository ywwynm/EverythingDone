package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolForegroundProbeScheduleTest {
    @Test fun `粒子期间交接不采样 恢复后无需呈现回调仍会采样`() {
        val schedule = FableSolForegroundProbeSchedule()
        assertFalse(schedule.shouldSample(1, false, 0))
        assertFalse(schedule.shouldSample(1, false, 350))
        assertFalse(schedule.shouldSample(1, true, 1000))
        assertFalse(schedule.shouldSample(1, true, 1250))
        assertFalse(schedule.shouldSample(1, true, 1500))
        assertTrue(schedule.shouldSample(1, true, 1750))
        assertFalse(schedule.shouldSample(1, true, 2000))
    }

    @Test fun `关闭粒子也在首帧持续可见后采样`() {
        val schedule = FableSolForegroundProbeSchedule()
        assertFalse(schedule.shouldSample(1, true, 50))
        assertFalse(schedule.shouldSample(1, true, 649))
        assertTrue(schedule.shouldSample(1, true, 650))
    }

    @Test fun `HDR切换重新等待目标图层稳定 不继承旧计时`() {
        val schedule = FableSolForegroundProbeSchedule()
        assertFalse(schedule.shouldSample(1, true, 0))
        assertFalse(schedule.shouldSample(2, true, 500))
        assertFalse(schedule.shouldSample(2, true, 600))
        assertTrue(schedule.shouldSample(2, true, 1100))
        assertFalse(schedule.shouldSample(3, true, 1101))
        assertTrue(schedule.shouldSample(3, true, 1701))
    }

    @Test fun `隐藏或关闭取消采样 后台恢复重新等待`() {
        val schedule = FableSolForegroundProbeSchedule()
        assertFalse(schedule.shouldSample(1, true, 0))
        assertFalse(schedule.shouldSample(1, false, 599))
        assertFalse(schedule.shouldSample(1, true, 600))
        assertTrue(schedule.shouldSample(1, true, 1200))
        assertFalse(schedule.shouldSample(null, false, 1300))
        assertFalse(schedule.shouldSample(1, true, 1400))
        assertTrue(schedule.shouldSample(1, true, 2000))
    }

    @Test fun `新生命周期即使复用目标编号也重新采样`() {
        val schedule = FableSolForegroundProbeSchedule()
        assertFalse(schedule.shouldSample(1, true, 0))
        assertTrue(schedule.shouldSample(1, true, 600))
        schedule.reset()
        assertFalse(schedule.shouldSample(1, true, 1000))
        assertTrue(schedule.shouldSample(1, true, 1600))
    }
}
