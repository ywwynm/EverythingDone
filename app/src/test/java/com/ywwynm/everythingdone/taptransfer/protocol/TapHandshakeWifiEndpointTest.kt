package com.ywwynm.everythingdone.taptransfer.protocol

import org.junit.Assert.*
import org.junit.Test

class TapHandshakeWifiEndpointTest {
    private fun withWait(waitMs: Int) = TapHandshake(ByteArray(16), 34461, "DIRECT-ab-test", "password",
        "测试", 1, intArrayOf(0), groupOwnerIpv4 = "192.168.49.1", wifiConnectionTimeoutMs = waitMs)

    @Test fun waitExtensionPreservesV3BytesAndDefaultsForOldOffers() {
        val old = handshake("192.168.49.1").encode()
        val extended = withWait(60_000).encode()
        assertArrayEquals(old, extended.copyOf(old.size))
        assertEquals(25_000, TapHandshake.decode(old).wifiConnectionTimeoutMs)
        assertEquals(25_000, TapHandshake.decode(handshake().encode()).wifiConnectionTimeoutMs)
        assertEquals(60_000, TapHandshake.decode(extended).wifiConnectionTimeoutMs)
    }

    @Test fun malformedWaitExtensionIsRejected() {
        val valid = withWait(60_000).encode()
        for (size in valid.size - 3 until valid.size) {
            assertThrows(TapProtocolException::class.java) { TapHandshake.decode(valid.copyOf(size)) }
        }
        for (wait in listOf(-1, 0, 24_999, 90_001, Int.MAX_VALUE)) {
            val malformed = valid.copyOf()
            java.nio.ByteBuffer.wrap(malformed).putInt(malformed.size - 4, wait)
            assertThrows(TapProtocolException::class.java) { TapHandshake.decode(malformed) }
        }
    }

    private fun handshake(host: String? = null, cards: Boolean = false) = TapHandshake(
        ByteArray(16), 34461, "DIRECT-ab-test", "password", "测试", 1, intArrayOf(0),
        if (cards) floatArrayOf(1.5f) else floatArrayOf(),
        if (cards) booleanArrayOf(true) else booleanArrayOf(), groupOwnerIpv4 = host)

    @Test fun endpointRoundTripsWithAndWithoutCardShapes() {
        for (cards in listOf(false, true)) {
            val decoded = TapHandshake.decode(handshake("192.168.90.70", cards).encode())
            assertEquals("192.168.90.70", decoded.groupOwnerIpv4)
            assertEquals(if (cards) 1 else 0, decoded.aspects.size)
        }
    }

    @Test fun oldSendersRemainReadableAndOldBodyIsUnchanged() {
        for (cards in listOf(false, true)) {
            val oldBytes = handshake(cards = cards).encode()
            assertNull(TapHandshake.decode(oldBytes).groupOwnerIpv4)
            val newBytes = handshake("192.168.49.1", cards).encode()
            assertArrayEquals(oldBytes, newBytes.copyOf(oldBytes.size))
            // v1 旧读取器在卡片段后结束；无卡片时必须先补 cardCount=0 再放扩展。
            if (!cards) assertEquals(0, newBytes[oldBytes.size].toInt())
        }
    }

    @Test(expected = TapProtocolException::class)
    fun recognizedButTruncatedEndpointIsRejected() {
        val bytes = handshake("192.168.49.1").encode()
        TapHandshake.decode(bytes.copyOf(bytes.size - 1))
    }

    @Test fun endpointMustBeAnIpv4LocalUnicastLiteral() {
        listOf("192.168.90.70", "10.12.0.1", "172.16.1.2", "169.254.2.3").forEach {
            assertTrue(it, TapHandshake.isLocalIpv4(it))
        }
        listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "224.0.0.1", "192.168.1.256",
            "192.168.1.-1", "localhost", "192.168.1.1/path", "172.32.0.1", "192.168.1.1\n", "010.0.0.1").forEach {
            assertFalse(it, TapHandshake.isLocalIpv4(it))
        }
    }

    @Test(expected = TapProtocolException::class)
    fun untrustedEndpointCannotRedirectToLoopback() {
        val bytes = handshake("192.168.49.1").encode()
        val addressStart = bytes.size - "192.168.49.1".length
        "127".toByteArray().copyInto(bytes, addressStart)
        TapHandshake.decode(bytes)
    }
}
