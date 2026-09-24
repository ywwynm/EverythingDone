package com.ywwynm.everythingdone.taptransfer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 在本机回环地址上按真实的收发顺序跑一遍：接收方先报令牌，发送方核对后发长度与内容，接收方
 * 收齐后回确认。Wi-Fi Direct 只是把回环换成组内地址，帧格式与这里完全相同。
 */
class TapStreamTest {

    private val token = ByteArray(TapHandshake.TOKEN_BYTES) { (it + 1).toByte() }
    private val receiverId = ByteArray(TapApdu.RECEIVER_ID_BYTES) { (0x40 + it).toByte() }

    private fun payload(size: Int): ByteArray = ByteArray(size).also { Random(7).nextBytes(it) }

    /** 发送方：只服务一条连接，只放行带着 [expectedToken] 与 [expectedReceiver] 的那一台。返回发送方看到的结果（null 为成功）。 */
    private fun serveOnce(
        server: ServerSocket,
        data: ByteArray,
        expectedToken: ByteArray,
        expectedReceiver: ByteArray = receiverId
    ): AtomicReference<Throwable?> {
        val error = AtomicReference<Throwable?>(null)
        thread {
            try {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    val hello = TapStream.readClientHello(input)
                    if (!hello.token.contentEquals(expectedToken)) {
                        TapStream.writeHeader(output, TapStream.STATUS_BAD_TOKEN, 0L)
                        return@thread
                    }
                    if (!hello.receiverId.contentEquals(expectedReceiver)) {
                        TapStream.writeHeader(output, TapStream.STATUS_NOT_EXPECTED, 0L)
                        return@thread
                    }
                    TapStream.writeHeader(output, TapStream.STATUS_OK, data.size.toLong())
                    TapStream.copyExactly(ByteArrayInputStream(data), output, data.size.toLong()) {}
                    TapStream.readAck(input)
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        return error
    }

    @Test
    fun fullTransferOverLoopback() {
        val data = payload(3 * 1024 * 1024 + 17)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val senderError = serveOnce(server, data, token)
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                TapStream.writeClientHello(s.getOutputStream(), token, receiverId)
                val total = TapStream.readHeader(s.getInputStream())
                assertEquals(data.size.toLong(), total)
                val received = ByteArrayOutputStream()
                val progress = ArrayList<Long>()
                TapStream.copyExactly(s.getInputStream(), received, total) { progress.add(it) }
                TapStream.writeAck(s.getOutputStream())
                assertArrayEquals(data, received.toByteArray())
                assertEquals(total, progress.last())
                assertTrue("进度只增不减", progress.zipWithNext().all { (a, b) -> b > a })
            }
            Thread.sleep(100)
            assertEquals(null, senderError.get())
        }
    }

    @Test
    fun wrongTokenIsRejected() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            serveOnce(server, payload(10), token)
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                TapStream.writeClientHello(s.getOutputStream(), ByteArray(TapHandshake.TOKEN_BYTES), receiverId)
                try {
                    TapStream.readHeader(s.getInputStream())
                    fail("令牌不对时应当被拒绝")
                } catch (e: TapProtocolException) {
                    // 预期
                }
            }
        }
    }

    @Test
    fun receiverThatDidNotSayHelloIsRejected() {
        // 令牌对（读过握手），但发送方此刻在等的是另一台
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            serveOnce(server, payload(10), token)
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                TapStream.writeClientHello(s.getOutputStream(), token, ByteArray(TapApdu.RECEIVER_ID_BYTES))
                try {
                    TapStream.readHeader(s.getInputStream())
                    fail("不是发送方在等的那一台时应当被拒绝")
                } catch (e: TapProtocolException) {
                    // 预期
                }
            }
        }
    }

    @Test
    fun clientHelloRoundTrip() {
        val bytes = ByteArrayOutputStream()
        TapStream.writeClientHello(bytes, token, receiverId)
        val hello = TapStream.readClientHello(ByteArrayInputStream(bytes.toByteArray()))
        assertArrayEquals(token, hello.token)
        assertArrayEquals(receiverId, hello.receiverId)
    }

    @Test
    fun truncatedPayloadFails() {
        // 发送方声称 100 字节，只发了 40 字节就断开
        val header = ByteArrayOutputStream()
        TapStream.writeHeader(header, TapStream.STATUS_OK, 100L)
        val stream = ByteArrayInputStream(header.toByteArray() + payload(40))
        val total = TapStream.readHeader(stream)
        try {
            TapStream.copyExactly(stream, ByteArrayOutputStream(), total) {}
            fail("少了字节应当报错")
        } catch (e: EOFException) {
            // 预期：传了一半的内容一律作废
        }
    }

    @Test
    fun missingAckFails() {
        try {
            TapStream.readAck(ByteArrayInputStream(ByteArray(0)))
            fail("没有确认字节应当报错")
        } catch (e: IOException) {
            // 预期
        }
    }

    @Test(expected = TapProtocolException::class)
    fun garbageHelloIsRejected() {
        TapStream.readClientHello(ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".toByteArray()))
    }
}
