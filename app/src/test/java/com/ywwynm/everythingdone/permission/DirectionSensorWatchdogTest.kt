package com.ywwynm.everythingdone.permission

import com.ywwynm.everythingdone.permission.DirectionSensorWatchdog.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionSensorWatchdogTest {

    @Test
    fun `registration without a first sample reaches the stalled verdict`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        assertEquals(State.WAITING, watchdog.currentState())
        assertFalse(watchdog.isStalled())

        assertTrue(watchdog.onWaitExpired())
        assertTrue(watchdog.isStalled())
    }

    @Test
    fun `a sample inside the wait window cancels the verdict`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        assertTrue(watchdog.onSample())
        assertEquals(State.RECEIVING, watchdog.currentState())

        assertFalse(watchdog.onWaitExpired())
        assertFalse(watchdog.isStalled())
    }

    @Test
    fun `a sample after the verdict clears it again`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        watchdog.onWaitExpired()
        assertTrue(watchdog.isStalled())

        // 受限窗口结束后的第一个样本必须让提示消失，这正是「过了开屏时间就隐藏」的要求。
        assertTrue(watchdog.onSample())
        assertFalse(watchdog.isStalled())
    }

    @Test
    fun `further samples report no change once receiving`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        assertTrue(watchdog.onSample())
        assertFalse(watchdog.onSample())
        assertFalse(watchdog.onSample())
    }

    @Test
    fun `an expired wait only reports the verdict once`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        assertTrue(watchdog.onWaitExpired())
        assertFalse(watchdog.onWaitExpired())
    }

    @Test
    fun `without registration nothing is monitored`() {
        val watchdog = DirectionSensorWatchdog()

        assertEquals(State.IDLE, watchdog.currentState())
        assertFalse(watchdog.onWaitExpired())
        assertFalse(watchdog.isStalled())
    }

    @Test
    fun `an in-flight sample after unregistering does not claim availability`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        watchdog.onUnregistered()

        assertFalse(watchdog.onSample())
        assertEquals(State.IDLE, watchdog.currentState())
    }

    @Test
    fun `unregistering hides an already shown verdict`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        watchdog.onWaitExpired()
        watchdog.onUnregistered()

        assertFalse(watchdog.isStalled())
    }

    @Test
    fun `re-registration restarts the wait instead of inheriting the verdict`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        watchdog.onWaitExpired()
        assertTrue(watchdog.isStalled())

        watchdog.onRegistered()
        assertEquals(State.WAITING, watchdog.currentState())
        assertFalse(watchdog.isStalled())
    }

    @Test
    fun `re-registration after a healthy session monitors again`() {
        val watchdog = DirectionSensorWatchdog()

        watchdog.onRegistered()
        watchdog.onSample()
        watchdog.onUnregistered()

        watchdog.onRegistered()
        assertTrue(watchdog.onWaitExpired())
        assertTrue(watchdog.isStalled())
    }
}
