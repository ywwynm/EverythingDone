package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class TapReceiverServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val offer = TapReceiverGroupOffer(ByteArray(16) { 5 }, ByteArray(16) { 9 }, 12345,
        "DIRECT-aa-test", "12345678", "接收方")

    @Test fun listensOnTheRequestedIpv4InsteadOfWildcard() {
        TapReceiverServer(loopback).use { server ->
            assertTrue(server.diagnosticState().contains("bind=127.0.0.1,"))
            assertTrue(server.diagnosticState().contains("wildcard=false"))
            assertTrue(server.diagnosticState().contains("bound=true, closed=false"))
        }
    }

    @Test fun realTcpSendsFileIntoReceiverOwnedServerAndAcknowledgesBeforeClosing() {
        TapReceiverServer(loopback).use { server ->
            val payload = ByteArray(200_000) { (it % 251).toByte() }
            val receiver = CompletableFuture.supplyAsync {
                server.accept(offer, { true }, { throw AssertionError(it) })!!.use { s ->
                    s.soTimeout = 3000
                    TapStream.writeClientHello(s.getOutputStream(), offer.token, offer.receiverId)
                    val length = TapStream.readHeader(s.getInputStream())
                    val file = ByteArrayOutputStream()
                    TapStream.copyExactly(s.getInputStream(), file, length) {}
                    TapStream.writeAck(s.getOutputStream())
                    TapStream.awaitSenderClose(s.getInputStream())
                    file.toByteArray()
                }
            }
            Socket("127.0.0.1", server.port).use { sender ->
                sender.soTimeout = 3000
                TapStream.writeSenderHello(sender.getOutputStream(), offer.token, offer.receiverId)
                assertTrue(offer.matches(TapStream.readClientHello(sender.getInputStream())))
                TapStream.writeHeader(sender.getOutputStream(), TapStream.STATUS_OK, payload.size.toLong())
                sender.getOutputStream().write(payload)
                TapStream.readAck(sender.getInputStream())
            }
            assertArrayEquals(payload, receiver.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun badTokenBadIdAndWrongDirectionAreRejectedBeforeLegitimateConnection() {
        TapReceiverServer(loopback).use { server ->
            val rejected = AtomicInteger()
            val receiver = CompletableFuture.supplyAsync {
                server.accept(offer, { true }, { rejected.incrementAndGet() })!!.use { true }
            }
            repeat(3) { attempt ->
                Socket("127.0.0.1", server.port).use { s ->
                    s.soTimeout = 3000
                    when (attempt) {
                        0 -> TapStream.writeSenderHello(s.getOutputStream(), ByteArray(16), offer.receiverId)
                        1 -> TapStream.writeSenderHello(s.getOutputStream(), offer.token, ByteArray(16))
                        2 -> TapStream.writeClientHello(s.getOutputStream(), offer.token, offer.receiverId)
                    }
                    assertEquals(-1, s.getInputStream().read())
                }
            }
            Socket("127.0.0.1", server.port).use { s ->
                TapStream.writeSenderHello(s.getOutputStream(), offer.token, offer.receiverId)
                assertTrue(receiver.get(5, TimeUnit.SECONDS))
            }
            assertEquals(3, rejected.get())
        }
    }

    @Test fun cancellationClosesListenerAndUnverifiedConnection() {
        val events = ConcurrentLinkedQueue<String>()
        val rawConnection = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val server = TapReceiverServer(loopback) { stage, detail ->
            events.add("$stage $detail")
            if (stage == "接收监听收到原始 TCP") rawConnection.countDown()
        }
        assertTrue(server.diagnosticState().contains("closed=false"))
        assertTrue(server.diagnosticState().contains("rawConnections=0"))
        val receiver = CompletableFuture.supplyAsync {
            try { server.accept(offer, { active.get() }, {}) } catch (e: IOException) { null }
        }
        Socket("127.0.0.1", server.port).use {
            // 尚未写入身份时就必须记录 accept，才能区分端口不可达和身份读取失败。
            assertTrue(rawConnection.await(3, TimeUnit.SECONDS))
            assertTrue(server.diagnosticState().contains("rawConnections=1"))
            active.set(false)
            server.close("连接期限结束")
            assertNull(receiver.get(3, TimeUnit.SECONDS))
        }
        server.close()
        assertTrue(server.diagnosticState().contains("closed=true"))
        assertEquals(1, events.count { it.startsWith("关闭接收监听") })
        assertTrue(events.any { it.contains("reason=连接期限结束") })
    }

    @Test fun expiredAttemptDoesNotAcceptAQueuedConnection() {
        TapReceiverServer(loopback).use { server ->
            Socket("127.0.0.1", server.port).use { s ->
                TapStream.writeSenderHello(s.getOutputStream(), offer.token, offer.receiverId)
                assertNull(server.accept(offer, { false }, {}))
            }
        }
    }
}
