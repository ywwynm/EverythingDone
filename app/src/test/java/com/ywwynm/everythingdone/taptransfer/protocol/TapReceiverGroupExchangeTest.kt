package com.ywwynm.everythingdone.taptransfer.protocol

import org.junit.Assert.*
import org.junit.Test

class TapReceiverGroupExchangeTest {
    private val handshake = TapHandshake(ByteArray(16) { 7 }, 32001, "DIRECT-aa-sender", "12345678", "发送方", 1, intArrayOf())
    private val group = TapReceiverGroupOffer(handshake.token, ByteArray(16) { 2 }, 32002,
        "DIRECT-bb-receiver", "87654321", "接收方")

    private class Offer(override val handshake: ByteArray, override val supportsReceiverGroup: Boolean) : TapCardResponder.Offer {
        var normal = false
        var received: TapReceiverGroupOffer? = null
        override fun onHello(receiverId: ByteArray, receiverName: String): Int {
            normal = true
            return TapApdu.SW_OK
        }
        override fun onReceiverGroup(offer: TapReceiverGroupOffer): Int {
            received = offer
            return TapApdu.SW_OK
        }
    }

    @Test fun newReceiverExchangesItsGroupDuringTheSameTouch() {
        val offer = Offer(handshake.encode(), true)
        val card = TapCardResponder { offer }
        val commands = mutableListOf<Int>()
        val reader = TapReaderExchange { commands.add(it[1].toInt() and 255); card.process(it) }
        val outcome = reader.readHandshake() as TapReaderExchange.Outcome.Offered
        assertTrue(outcome.supportsReceiverGroup)
        assertEquals(TapApdu.SW_OK, reader.receiverGroup(group))
        assertEquals(listOf(TapApdu.INS_SELECT, TapApdu.INS_GET, TapApdu.INS_RECEIVER_GROUP), commands)
        assertArrayEquals(group.encode(), offer.received!!.encode())
        assertFalse(offer.normal)
    }

    @Test fun oldSenderStillUsesTheOriginalHello() {
        val offer = Offer(handshake.encode(), false)
        val reader = TapReaderExchange(TapCardResponder { offer }::process)
        assertFalse((reader.readHandshake() as TapReaderExchange.Outcome.Offered).supportsReceiverGroup)
        assertEquals(TapApdu.SW_OK, reader.hello(group.receiverId, group.receiverName))
        assertTrue(offer.normal)
        assertEquals(TapApdu.SW_UNKNOWN_INS, reader.receiverGroup(group))
        assertNull(offer.received)
    }

    @Test fun oldReaderCanIgnoreTheNewSelectByteAndUseHello() {
        val offer = Offer(handshake.encode(), true)
        val card = TapCardResponder { offer }
        val info = TapApdu.data(card.process(TapApdu.select()))
        assertEquals(4, info.size)
        val originalThreeBytes = info.copyOfRange(0, 3)
        val size = ((originalThreeBytes[1].toInt() and 255) shl 8) or (originalThreeBytes[2].toInt() and 255)
        assertEquals(handshake.encode().size, size)
        assertEquals(TapApdu.SW_OK, TapApdu.statusWord(card.process(TapApdu.hello(group.receiverId, "旧接收方"))))
        assertTrue(offer.normal)
    }

    @Test fun largestOfferFitsAShortApduAndTruncatesOnlyDisplayName() {
        val maximum = TapReceiverGroupOffer(group.token, group.receiverId, 65535,
            "DIRECT-xy-" + "s".repeat(22), "p".repeat(63), "名".repeat(100))
        val encoded = maximum.encode()
        assertEquals(229, encoded.size)
        val decoded = TapReceiverGroupOffer.decode(encoded)
        assertEquals(maximum.ssid, decoded.ssid)
        assertEquals(maximum.passphrase, decoded.passphrase)
        assertEquals("名".repeat(32), decoded.receiverName)
        assertTrue(TapApdu.parse(TapApdu.receiverGroup(maximum)) is TapApdu.Command.ReceiverGroup)
    }

    @Test fun truncatedOrMalformedOfferNeverInvokesGroupCallback() {
        val offer = Offer(handshake.encode(), true)
        val card = TapCardResponder { offer }
        val apdu = TapApdu.receiverGroup(group)
        for (n in 4 until apdu.size) {
            assertEquals(TapApdu.SW_WRONG_LENGTH, TapApdu.statusWord(card.process(apdu.copyOf(n))))
        }
        val corrupt = listOf(
            apdu + byteArrayOf(0),
            apdu.clone().also { it[5] = 2 }, // 未支持的版本
            apdu.clone().also { it[5 + 33] = 0; it[5 + 34] = 0 }, // 端口 0
            apdu.clone().also { it[5 + 36] = 0xff.toByte() } // 非 UTF-8 的组名
        )
        corrupt.forEach { assertEquals(TapApdu.SW_WRONG_LENGTH, TapApdu.statusWord(card.process(it))) }
        assertNull(offer.received)
    }

    @Test fun closedShareDoesNotAcceptReceiverGroup() {
        val reader = TapReaderExchange(TapCardResponder { null }::process)
        assertEquals(TapApdu.SW_NOT_READY, reader.receiverGroup(group))
    }
}
