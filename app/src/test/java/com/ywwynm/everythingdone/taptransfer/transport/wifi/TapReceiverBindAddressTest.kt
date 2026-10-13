package com.ywwynm.everythingdone.taptransfer.transport.wifi

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class TapReceiverBindAddressTest {
    private fun ip(value: String) = InetAddress.getByName(value)

    @Test fun choosesActualGroupAddressAcrossSubnets() {
        listOf("192.168.49.1", "192.168.82.1", "10.42.0.1", "172.20.1.1").forEach { value ->
            val groupAddress = ip(value)
            assertEquals(groupAddress, TapReceiverBindAddress.select(true, true, groupAddress,
                listOf(ip("fe80::1"), groupAddress)))
        }
    }

    @Test fun doesNotOpenListenerOnStaleOrForeignInterfaceAddress() {
        val reported = ip("192.168.49.1")
        assertNull(TapReceiverBindAddress.select(false, true, reported, listOf(reported)))
        assertNull(TapReceiverBindAddress.select(true, false, reported, listOf(reported)))
        assertNull(TapReceiverBindAddress.select(true, true, reported, emptyList()))
        assertNull(TapReceiverBindAddress.select(true, true, reported, listOf(ip("192.168.49.4"))))
        assertNull(TapReceiverBindAddress.select(true, true, reported, listOf(ip("192.168.100.166"))))
    }

    @Test fun rejectsMissingWildcardIpv6LoopbackAndPublicAddresses() {
        assertNull(TapReceiverBindAddress.select(true, true, null, emptyList()))
        listOf("0.0.0.0", "::", "fe80::1", "127.0.0.1", "8.8.8.8", "224.0.0.1").forEach { value ->
            val address = ip(value)
            assertNull(TapReceiverBindAddress.select(true, true, address, listOf(address)))
        }
    }
}
