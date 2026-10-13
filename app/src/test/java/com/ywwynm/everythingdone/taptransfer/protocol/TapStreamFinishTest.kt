package com.ywwynm.everythingdone.taptransfer.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TapStreamFinishTest {
    @Test fun receiverRetainsConnectionUntilSenderReadsAckAndCloses() {
        val workers = Executors.newFixedThreadPool(2)
        val ackWritten = CountDownLatch(1)
        val allowSenderRead = CountDownLatch(1)
        val receiverFinished = CountDownLatch(1)
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            ServerSocket(0, 1, loopback).use { server ->
                val sending = workers.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        check(allowSenderRead.await(3, TimeUnit.SECONDS))
                        TapStream.readAck(socket.getInputStream())
                    }
                }
                val receiving = workers.submit {
                    Socket(loopback, server.localPort).use { socket ->
                        socket.soTimeout = 3000
                        TapStream.writeAck(socket.getOutputStream())
                        ackWritten.countDown()
                        TapStream.awaitSenderClose(socket.getInputStream())
                        receiverFinished.countDown()
                    }
                }
                assertTrue(ackWritten.await(3, TimeUnit.SECONDS))
                assertFalse("仅写出 ACK 时还不能释放网络", receiverFinished.await(100, TimeUnit.MILLISECONDS))
                allowSenderRead.countDown()
                sending.get(3, TimeUnit.SECONDS)
                receiving.get(3, TimeUnit.SECONDS)
                assertEquals(0L, receiverFinished.count)
            }
        } finally {
            allowSenderRead.countDown()
            workers.shutdownNow()
        }
    }

    @Test(expected = TapProtocolException::class)
    fun extraDataAfterPayloadIsNotReportedAsNormalClose() {
        TapStream.awaitSenderClose(ByteArrayInputStream(byteArrayOf(1)))
    }
}
