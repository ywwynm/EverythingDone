package com.ywwynm.everythingdone.taptransfer.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 接收方预建的组，经一次短 APDU 交给发送方；网络角色与文件方向分别协商。 */
class TapReceiverGroupOffer(
    val token: ByteArray,
    val receiverId: ByteArray,
    val port: Int,
    val ssid: String,
    val passphrase: String,
    val receiverName: String
) {
    init {
        require(token.size == TapHandshake.TOKEN_BYTES)
        require(receiverId.size == TapApdu.RECEIVER_ID_BYTES)
        require(port in 1..65535)
        TapHandshake.credentialProblem(ssid, passphrase)?.let { throw IllegalArgumentException(it) }
    }

    fun matches(hello: TapStream.ClientHello): Boolean =
        MessageDigest.isEqual(token, hello.token) && MessageDigest.isEqual(receiverId, hello.receiverId)

    /** version u8 | token 16 | receiverId 16 | port u16 | 三个 u8 长度的 UTF-8 字符串。最多 229 字节。 */
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeByte(1)
            out.write(token)
            out.write(receiverId)
            out.writeShort(port)
            for (text in listOf(ssid, passphrase, TapHandshake.truncateUtf8(receiverName, TapHandshake.MAX_NAME_BYTES))) {
                val value = text.toByteArray(StandardCharsets.UTF_8)
                out.writeByte(value.size)
                out.write(value)
            }
        }
    }.toByteArray()

    companion object {
        /** 两端共同遵守；包含发送方退出原组、加入接收组和建立 TCP 的时间。 */
        const val CONNECTION_WAIT_MS = 35_000

        fun decode(bytes: ByteArray): TapReceiverGroupOffer {
            try {
                val input = ByteBuffer.wrap(bytes)
                require(input.get().toInt() == 1)
                val token = ByteArray(16).also { input.get(it) }
                val id = ByteArray(16).also { input.get(it) }
                val port = input.short.toInt() and 0xffff
                fun string(max: Int): String {
                    val n = input.get().toInt() and 0xff
                    require(n <= max && n <= input.remaining())
                    val raw = ByteArray(n).also { input.get(it) }
                    return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString()
                }
                val ssid = string(TapHandshake.MAX_SSID_BYTES)
                val passphrase = string(TapHandshake.MAX_PASSPHRASE_BYTES)
                val name = string(TapHandshake.MAX_NAME_BYTES)
                require(!input.hasRemaining())
                return TapReceiverGroupOffer(token, id, port, ssid, passphrase, name)
            } catch (e: Exception) {
                throw TapProtocolException("接收组信息不完整或不合规")
            }
        }
    }
}
