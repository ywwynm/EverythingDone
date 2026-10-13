package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import org.junit.Assert.*
import org.junit.Test

class TapJoinAttemptTest {
    @Test fun systemConfirmationGetsMoreTimeOnlyAfterFallbackAndOnlyWithNewSender() {
        val offer = TapHandshake(ByteArray(16), 38033, "DIRECT-ab-test", "password", "测试", 1,
            intArrayOf(), groupOwnerIpv4 = "192.168.49.1", wifiConnectionTimeoutMs = 60_000)
        val received = TapHandshake.decode(offer.encode())
        val attempt = TapJoinAttempt(1000L, received.wifiConnectionTimeoutMs)
        assertEquals(23_000L, attempt.deadlineMs) // 普通 P2P 不因支持兼容连接而延长。
        assertTrue(attempt.switchToWifi())
        assertEquals(58_000L, attempt.deadlineMs)
        assertEquals(26_000L, attempt.remainingMs(32_000L)) // 人工确认超过旧 22 秒仍能继续。
        assertFalse(attempt.switchToWifi())
        assertEquals(58_000L, attempt.deadlineMs) // 重复回调不能不断续期。
        assertEquals(0L, attempt.remainingMs(60_000L))
    }

    @Test fun oldSenderKeepsOriginalDeadlineAndCancellationCannotExtendIt() {
        val offer = TapHandshake(ByteArray(16), 38033, "DIRECT-ab-test", "password", "测试", 1,
            intArrayOf(), groupOwnerIpv4 = "192.168.49.1")
        val attempt = TapJoinAttempt(1000L, TapHandshake.decode(offer.encode()).wifiConnectionTimeoutMs)
        assertTrue(attempt.switchToWifi())
        assertEquals(23_000L, attempt.deadlineMs)
        val cancelled = TapJoinAttempt(1000L, 60_000)
        cancelled.finish()
        assertFalse(cancelled.switchToWifi())
        assertEquals(23_000L, cancelled.deadlineMs)
    }

    @Test fun rejectedP2pCanSwitchOnceAndLateP2pSuccessCannotStartAnotherDownload() {
        val attempt = TapJoinAttempt()
        assertTrue(attempt.switchToWifi())
        assertFalse(attempt.switchToWifi())
        assertFalse(attempt.beginTransfer(TapJoinAttempt.Route.P2P))
        assertTrue(attempt.beginTransfer(TapJoinAttempt.Route.WIFI))
        assertFalse(attempt.beginTransfer(TapJoinAttempt.Route.WIFI))
    }

    @Test fun cancelledAttemptCannotBeRevivedByNetworkCallbacksOrASecondFailure() {
        val old = TapJoinAttempt()
        old.switchToWifi()
        assertTrue(old.finish())
        val current = TapJoinAttempt()
        assertFalse(old.beginTransfer(TapJoinAttempt.Route.WIFI))
        assertFalse(old.finish())
        assertTrue(current.beginTransfer(TapJoinAttempt.Route.P2P))
    }

    @Test fun normalP2pSuccessNeverSwitchesToWifi() {
        val attempt = TapJoinAttempt()
        assertTrue(attempt.beginTransfer(TapJoinAttempt.Route.P2P))
        assertFalse(attempt.switchToWifi())
        assertTrue(attempt.finish())
        assertFalse(attempt.active)
    }
}
