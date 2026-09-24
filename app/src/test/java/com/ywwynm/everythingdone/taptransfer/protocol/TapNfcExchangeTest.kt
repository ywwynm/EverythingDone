package com.ywwynm.everythingdone.taptransfer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读取端与卡模拟端直接对接，走完一次碰触的命令序列。卡模拟端就是 `TapHceService` 里用的那个
 * [TapCardResponder]，这里用内存里的转发代替 NFC。
 */
class TapNfcExchangeTest {

    private class FakeOffer(override val handshake: ByteArray, val sw: Int = TapApdu.SW_OK) : TapCardResponder.Offer {
        var helloFrom: String? = null
        var helloId: ByteArray? = null
        override fun onHello(receiverId: ByteArray, receiverName: String): Int {
            helloId = receiverId
            helloFrom = receiverName
            return sw
        }
    }

    /** 名字与颜色都取到上限，握手超过单段 240 字节，读取端必须分两段读。 */
    private fun bigHandshake(): TapHandshake = TapHandshake(
        ByteArray(16) { (it * 7).toByte() }, 51234,
        "DIRECT-xy-" + "a".repeat(22), "p".repeat(63),
        "名".repeat(32), 12, IntArray(TapHandshake.MAX_COLORS) { 0xFF000000.toInt() or it }
    )

    @Test
    fun readsChunkedHandshakeAndSaysHello() {
        val handshake = bigHandshake()
        val encoded = handshake.encode()
        assertTrue("握手应超过单段长度", encoded.size > TapApdu.MAX_CHUNK)
        val offer = FakeOffer(encoded)
        val card = TapCardResponder { offer }
        var commands = 0
        val reader = TapReaderExchange { apdu ->
            commands++
            card.process(apdu)
        }

        val outcome = reader.readHandshake()
        assertTrue(outcome is TapReaderExchange.Outcome.Offered)
        val read = (outcome as TapReaderExchange.Outcome.Offered).handshake
        assertEquals(handshake.ssid, read.ssid)
        assertEquals(handshake.passphrase, read.passphrase)
        assertEquals(handshake.senderName, read.senderName)
        assertArrayEquals(handshake.colors, read.colors)
        // SELECT + 两段 GET
        assertEquals(3, commands)

        val id = ByteArray(TapApdu.RECEIVER_ID_BYTES) { 9 }
        assertEquals(TapApdu.SW_OK, reader.hello(id, "魅族 20"))
        assertEquals("魅族 20", offer.helloFrom)
        assertArrayEquals(id, offer.helloId)
    }

    @Test
    fun notReadyWhenSharingIsClosed() {
        val card = TapCardResponder { null }
        val reader = TapReaderExchange { card.process(it) }
        assertTrue(reader.readHandshake() is TapReaderExchange.Outcome.NotOffering)
        assertEquals(TapApdu.SW_NOT_READY, reader.hello(ByteArray(16), "x"))
    }

    @Test
    fun senderRejectionIsPassedThrough() {
        val offer = FakeOffer(bigHandshake().encode(), TapApdu.SW_ALREADY_RECEIVED)
        val card = TapCardResponder { offer }
        val reader = TapReaderExchange { card.process(it) }
        assertTrue(reader.readHandshake() is TapReaderExchange.Outcome.Offered)
        assertEquals(TapApdu.SW_ALREADY_RECEIVED, reader.hello(ByteArray(16), "x"))
    }

    @Test
    fun selectingAnotherAidIsRefused() {
        val card = TapCardResponder { FakeOffer(bigHandshake().encode()) }
        val otherAid = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xF0.toByte(), 0x45, 0x44, 0x54, 0x52, 0x4E, 0x53, 0x00)
        assertEquals(TapApdu.SW_WRONG_AID, TapApdu.statusWord(card.process(otherAid)))
    }

    @Test
    fun getBeyondTheEndIsRefused() {
        val encoded = bigHandshake().encode()
        val card = TapCardResponder { FakeOffer(encoded) }
        assertEquals(TapApdu.SW_WRONG_OFFSET, TapApdu.statusWord(card.process(TapApdu.get(encoded.size, 10))))
    }

    @Test
    fun unknownCommandIsRefused() {
        val card = TapCardResponder { FakeOffer(bigHandshake().encode()) }
        assertEquals(TapApdu.SW_UNKNOWN_INS, TapApdu.statusWord(card.process(byteArrayOf(0x80.toByte(), 0x7F, 0, 0))))
        assertEquals(TapApdu.SW_UNKNOWN_INS, TapApdu.statusWord(card.process(null)))
    }
}
