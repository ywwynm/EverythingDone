package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TapSendPeerTest {
    private val id = ByteArray(16) { 9 }
    private fun peer(reverse: Boolean) = TapSendPeer(id.copyOf(), "id", "接收方",
        if (reverse) TapReceiverGroupOffer(ByteArray(16), id, 32001, "DIRECT-aa-test", "12345678", "接收方") else null, 35000)

    @Test fun reversePeerCannotBeClaimedByTheOldListeningPortOrADifferentAttempt() {
        val current = peer(true)
        assertFalse(current.claim(id, null))
        assertFalse(current.claim(id, peer(true)))
        assertFalse(current.claim(ByteArray(16), current))
        assertTrue(current.claim(id, current))
        assertFalse(current.claim(id, current))
    }

    @Test fun normalPeerKeepsOriginalIncomingConnectionAndRejectsWrongDirection() {
        val current = peer(false)
        assertFalse(current.claim(id, current))
        assertTrue(current.claim(id, null))
        assertFalse(current.claim(id, null))
    }

    @Test fun aCancelledOrExpiredAttemptCannotBeClaimed() {
        val current = peer(true)
        current.state.set(TapSendPeer.FINISHED)
        assertFalse(current.claim(id, current))
    }

    @Test fun simultaneousTimeoutAndConnectionHaveExactlyOneWinner() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(30) {
                val current = peer(true)
                val start = CountDownLatch(1)
                val claim = workers.submit<Boolean> { start.await(); current.claim(id, current) }
                val timeout = workers.submit<Boolean> {
                    start.await(); current.state.compareAndSet(TapSendPeer.WAITING, TapSendPeer.FINISHED)
                }
                start.countDown()
                assertNotEquals(claim.get(3, TimeUnit.SECONDS), timeout.get(3, TimeUnit.SECONDS))
            }
        } finally { workers.shutdownNow() }
    }
}
