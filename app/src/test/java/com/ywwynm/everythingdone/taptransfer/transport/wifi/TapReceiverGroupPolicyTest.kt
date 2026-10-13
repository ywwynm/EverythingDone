package com.ywwynm.everythingdone.taptransfer.transport.wifi

import org.junit.Assert.*
import org.junit.Test

class TapReceiverGroupPolicyTest {
    @Test fun onlyKnownCompatibilityEnvironmentDefaultsToReceivingAsOwner() {
        assertTrue(TapGroupRolePolicy.preferReceiverGroup(true, "HUAWEI", "System 104.3.5.001(00HP)", null))
        assertFalse(TapGroupRolePolicy.preferReceiverGroup(true, "HUAWEI", "VOL-AL00 12.0.0", null))
        assertFalse(TapGroupRolePolicy.preferReceiverGroup(true, "OPPO", "System anything", null))
        assertFalse(TapGroupRolePolicy.preferReceiverGroup(false, "HUAWEI", "System 104.3.5", null))
    }

    @Test fun debugOverrideAllowsComparisonButCannotEnableReleaseExperiment() {
        assertFalse(TapGroupRolePolicy.preferReceiverGroup(true, "HUAWEI", "System 104.3.5", false))
        assertTrue(TapGroupRolePolicy.preferReceiverGroup(true, "OPPO", "PMA110", true))
        assertFalse(TapGroupRolePolicy.preferReceiverGroup(false, "OPPO", "PMA110", true))
    }

    @Test fun receiverGroupRouteCannotBeClaimedByLateP2pOrWifiCallbacks() {
        val attempt = TapJoinAttempt(1000, receiverGroup = true)
        assertEquals(33_000L, attempt.deadlineMs)
        assertFalse(attempt.beginTransfer(TapJoinAttempt.Route.P2P))
        assertFalse(attempt.switchToWifi())
        assertFalse(attempt.beginTransfer(TapJoinAttempt.Route.WIFI))
        assertTrue(attempt.beginTransfer(TapJoinAttempt.Route.RECEIVER_GROUP))
        assertFalse(attempt.beginTransfer(TapJoinAttempt.Route.RECEIVER_GROUP))
        assertTrue(attempt.finish())
        assertFalse(attempt.finish())
    }

    @Test fun cancellationRejectsLateReceiverGroupConnection() {
        val attempt = TapJoinAttempt(receiverGroup = true)
        attempt.finish()
        assertFalse(attempt.beginTransfer(TapJoinAttempt.Route.RECEIVER_GROUP))
    }
}
