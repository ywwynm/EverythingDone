package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer
import com.ywwynm.everythingdone.taptransfer.protocol.TapStream
import java.io.IOException
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** 仅由接收建组路径使用。未经 NFC 身份验证的连接不会进入文件读取流程。 */
internal class TapReceiverServer(bindAddress: Inet4Address,
                                 private val record: ((String, String) -> Unit)? = null) : AutoCloseable {
    private val server = ServerSocket(0, 50, bindAddress)
    @Volatile private var candidate: Socket? = null
    @Volatile private var closed = false
    @Volatile private var accepting = false
    @Volatile private var acceptCalls = 0
    @Volatile private var rawConnections = 0
    val port: Int get() = server.localPort

    fun diagnosticState(): String = "port=$port, bind=${server.inetAddress?.hostAddress}, " +
        "wildcard=${server.inetAddress?.isAnyLocalAddress}, bound=${server.isBound}, " +
        "closed=${server.isClosed}, closeRequested=$closed, accepting=$accepting, " +
        "acceptCalls=$acceptCalls, rawConnections=$rawConnections, candidate=${candidate != null}"

    fun accept(offer: TapReceiverGroupOffer, active: () -> Boolean, rejected: (IOException) -> Unit): Socket? {
        server.soTimeout = 250
        while (!closed && active()) {
            val socket = try {
                accepting = true
                if (record != null) acceptCalls++
                server.accept()
            } catch (e: SocketTimeoutException) { continue }
            finally { accepting = false }
            candidate = socket
            if (record != null) rawConnections++
            var accepted = false
            try {
                record?.invoke("接收监听收到原始 TCP", "port=$port, local=${socket.localAddress?.hostAddress}, " +
                    "peer=${socket.inetAddress?.hostAddress}, rawConnections=$rawConnections")
                if (closed || !active()) return null
                socket.soTimeout = 1000
                // 不加缓冲，避免预读后续文件头。新的身份前导只有固定的 37 字节。
                if (!offer.matches(TapStream.readSenderHello(socket.getInputStream())))
                    throw IOException("发送身份与本次碰触不匹配")
                if (closed || !active()) return null
                accepted = true
                return socket
            } catch (e: IOException) {
                if (!closed && active()) rejected(e)
            } finally {
                candidate = null
                if (!accepted) runCatching { socket.close() }
            }
        }
        return null
    }

    override fun close() = close("资源释放")

    @Synchronized
    fun close(reason: String) {
        if (closed) return
        closed = true
        record?.invoke("关闭接收监听", "reason=$reason, ${diagnosticState()}")
        runCatching { server.close() }
        runCatching { candidate?.close() }
        record?.invoke("接收监听已关闭", diagnosticState())
    }
}
