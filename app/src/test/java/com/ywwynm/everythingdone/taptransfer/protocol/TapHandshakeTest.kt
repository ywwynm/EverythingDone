package com.ywwynm.everythingdone.taptransfer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

class TapHandshakeTest {

    private fun sample(name: String = "Galaxy S23", colors: IntArray = intArrayOf(0xFF00B1C6.toInt(), 0xFFD28656.toInt())) =
        TapHandshake(
            token = ByteArray(16) { it.toByte() },
            port = 43210,
            ssid = "DIRECT-ab-Galaxy S23",
            passphrase = "k3yP4ss!",
            senderName = name,
            thingCount = 3,
            colors = colors
        )

    @Test
    fun roundTrip() {
        val original = sample()
        val decoded = TapHandshake.decode(original.encode())
        assertArrayEquals(original.token, decoded.token)
        assertEquals(original.port, decoded.port)
        assertEquals(original.ssid, decoded.ssid)
        assertEquals(original.passphrase, decoded.passphrase)
        assertEquals(original.senderName, decoded.senderName)
        assertEquals(original.thingCount, decoded.thingCount)
        assertArrayEquals(original.colors, decoded.colors)
    }

    @Test
    fun cardShapesSurviveTheRoundTrip() {
        val original = TapHandshake(ByteArray(16) { it.toByte() }, 43210, "DIRECT-ab-Galaxy S23", "k3yP4ss!",
            "Galaxy S23", 3, intArrayOf(0xFF00B1C6.toInt(), 0xFFD28656.toInt()),
            aspects = floatArrayOf(2.106f, 1.357f), fullSpan = booleanArrayOf(false, true))
        val decoded = TapHandshake.decode(original.encode())
        assertArrayEquals(original.colors, decoded.colors)
        assertEquals(2, decoded.aspects.size)
        // 高宽比按千分之一存
        assertEquals(2.106f, decoded.aspects[0], 5e-4f)
        assertEquals(1.357f, decoded.aspects[1], 5e-4f)
        assertEquals(listOf(false, true), decoded.fullSpan.toList())
    }

    @Test
    fun handshakeWithoutCardShapesStillDecodes() {
        // 旧版发送方没有卡片形状这一段：读到末尾即当作没有
        val decoded = TapHandshake.decode(sample().encode())
        assertEquals(0, decoded.aspects.size)
        assertEquals(0, decoded.fullSpan.size)
    }

    @Test(expected = TapProtocolException::class)
    fun truncatedCardShapesAreRejected() {
        val bytes = TapHandshake(ByteArray(16), 43210, "DIRECT-ab-x", "k3yP4ss!", "n", 1, intArrayOf(0),
            aspects = floatArrayOf(1.5f), fullSpan = booleanArrayOf(false)).encode()
        TapHandshake.decode(bytes.copyOf(bytes.size - 1))
    }

    @Test
    fun longSenderNameIsTruncatedWithoutSplittingCodePoints() {
        // 每个 emoji 4 字节，96 字节上限正好放 24 个；第 25 个不能被切成半个
        val name = "😀".repeat(40)
        val decoded = TapHandshake.decode(sample(name = name).encode())
        assertEquals(24, decoded.senderName.codePointCount(0, decoded.senderName.length))
        assertTrue(decoded.senderName.toByteArray(StandardCharsets.UTF_8).size <= TapHandshake.MAX_NAME_BYTES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyColorsIsRejected() {
        sample(colors = IntArray(TapHandshake.MAX_COLORS + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlongSsidIsRejectedInsteadOfTruncated() {
        TapHandshake(ByteArray(16), 1, "x".repeat(33), "password", "n", 1, IntArray(0))
    }

    @Test(expected = TapProtocolException::class)
    fun wrongMagicIsRejected() {
        val bytes = sample().encode()
        bytes[0] = 'X'.code.toByte()
        TapHandshake.decode(bytes)
    }

    @Test(expected = TapProtocolException::class)
    fun newerVersionIsRejected() {
        val bytes = sample().encode()
        bytes[4] = (TapHandshake.VERSION + 1).toByte()
        TapHandshake.decode(bytes)
    }

    @Test(expected = TapProtocolException::class)
    fun truncatedHandshakeIsRejected() {
        val bytes = sample().encode()
        TapHandshake.decode(bytes.copyOf(bytes.size - 3))
    }

    @Test
    fun truncateUtf8KeepsShortStrings() {
        assertEquals("完事儿", TapHandshake.truncateUtf8("完事儿", 9))
        assertEquals("完事", TapHandshake.truncateUtf8("完事儿", 8))
        assertEquals("", TapHandshake.truncateUtf8("完", 2))
    }

    // ---------------------------------------------------------------- 来自任意设备的握手

    /** 按握手的字节布局直接拼，绕过构造时的校验，模拟一台不是完事儿、或者写错了的设备。 */
    private fun rawHandshake(ssid: ByteArray, passphrase: ByteArray, name: ByteArray = "n".toByteArray()): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("EDTP".toByteArray())
            out.writeByte(TapHandshake.VERSION)
            out.write(ByteArray(TapHandshake.TOKEN_BYTES))
            out.writeShort(43210)
            for (field in arrayOf(ssid, passphrase, name)) {
                out.writeShort(field.size)
                out.write(field)
            }
            out.writeShort(1)
            out.writeByte(0)
        }
        return bytes.toByteArray()
    }

    @Test
    fun rawHandshakeMatchesTheRealLayout() {
        val decoded = TapHandshake.decode(rawHandshake("DIRECT-ab-x".toByteArray(), "password".toByteArray()))
        assertEquals("DIRECT-ab-x", decoded.ssid)
        assertEquals("password", decoded.passphrase)
    }

    @Test(expected = TapProtocolException::class)
    fun ssidWithoutDirectPrefixIsRejected() {
        // WifiP2pConfig.Builder 对这样的组名抛 IllegalArgumentException，接收方会在主线程崩溃
        TapHandshake.decode(rawHandshake("X".toByteArray(), "password".toByteArray()))
    }

    @Test(expected = TapProtocolException::class)
    fun shortPassphraseIsRejected() {
        TapHandshake.decode(rawHandshake("DIRECT-ab-x".toByteArray(), ByteArray(0)))
    }

    @Test(expected = TapProtocolException::class)
    fun malformedUtf8IsRejected() {
        // 32 个非法字节：宽松解码会变成 32 个三字节的替换符，越过 SSID 的长度上限
        TapHandshake.decode(rawHandshake("DIRECT-ab-".toByteArray() + ByteArray(22) { 0xFF.toByte() }, "password".toByteArray()))
    }

    @Test(expected = TapProtocolException::class)
    fun malformedUtf8InNameIsRejected() {
        TapHandshake.decode(
            rawHandshake("DIRECT-ab-x".toByteArray(), "password".toByteArray(), byteArrayOf(0xC3.toByte()))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun senderWithInvalidGroupCredentialsFailsEarly() {
        TapHandshake(ByteArray(16), 1, "DIRECT-ab-x", "short", "n", 1, IntArray(0))
    }
}
