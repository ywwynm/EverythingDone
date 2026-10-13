package com.ywwynm.everythingdone.taptransfer.protocol

import java.io.IOException

/**
 * 卡模拟端（发送方）的应答逻辑。与 `HostApduService` 分开写，是为了能在 JVM 上单测：
 * 服务本身只负责把命令交给这里、把应答交回系统。
 *
 * @param offerProvider 取当前可被碰的内容；分享界面没打开或还没准备好时返回 null，
 *        此时一律应答 [TapApdu.SW_NOT_READY]——任何人拿读卡设备碰一下都不应读走握手。
 */
class TapCardResponder(private val offerProvider: () -> Offer?) {

    interface Offer {
        /** 已编码好的 [TapHandshake]。 */
        val handshake: ByteArray
        val supportsReceiverGroup: Boolean get() = false
        fun onReceiverGroup(offer: TapReceiverGroupOffer): Int = TapApdu.SW_UNKNOWN_INS

        /**
         * 接收方读完握手、确认要收之后发来 HELLO。返回状态字：[TapApdu.SW_OK] 表示发送方认定
         * 碰上了这一台，[TapApdu.SW_BUSY] / [TapApdu.SW_ALREADY_RECEIVED] 表示拒绝。
         */
        fun onHello(receiverId: ByteArray, receiverName: String): Int
    }

    fun process(apdu: ByteArray?): ByteArray {
        return when (val command = TapApdu.parse(apdu)) {
            is TapApdu.Command.Select -> {
                if (!command.ours) {
                    return TapApdu.response(null, TapApdu.SW_WRONG_AID)
                }
                val offer = offerProvider() ?: return TapApdu.response(null, TapApdu.SW_NOT_READY)
                val size = offer.handshake.size
                TapApdu.response(
                    byteArrayOf(TapHandshake.VERSION.toByte(), (size shr 8).toByte(), size.toByte()) +
                        if (offer.supportsReceiverGroup) byteArrayOf(1) else byteArrayOf(),
                    TapApdu.SW_OK
                )
            }
            is TapApdu.Command.Get -> {
                val offer = offerProvider() ?: return TapApdu.response(null, TapApdu.SW_NOT_READY)
                val handshake = offer.handshake
                if (command.offset >= handshake.size) {
                    return TapApdu.response(null, TapApdu.SW_WRONG_OFFSET)
                }
                val end = minOf(handshake.size, command.offset + command.length)
                TapApdu.response(handshake.copyOfRange(command.offset, end), TapApdu.SW_OK)
            }
            is TapApdu.Command.Hello -> {
                val offer = offerProvider() ?: return TapApdu.response(null, TapApdu.SW_NOT_READY)
                TapApdu.response(null, offer.onHello(command.receiverId, command.receiverName))
            }
            is TapApdu.Command.ReceiverGroup -> {
                val offer = offerProvider() ?: return TapApdu.response(null, TapApdu.SW_NOT_READY)
                TapApdu.response(null, if (offer.supportsReceiverGroup) offer.onReceiverGroup(command.offer)
                    else TapApdu.SW_UNKNOWN_INS)
            }
            TapApdu.Command.Invalid -> TapApdu.response(null, TapApdu.SW_WRONG_LENGTH)
            TapApdu.Command.Unknown -> TapApdu.response(null, TapApdu.SW_UNKNOWN_INS)
        }
    }
}

/**
 * 读取端（接收方）一次碰触里的命令序列。[transceive] 在 Android 上是 `IsoDep::transceive`，
 * 单测里直接接到 [TapCardResponder.process]。
 */
class TapReaderExchange(private val transceive: (ByteArray) -> ByteArray) {

    sealed class Outcome {
        /** 对方没打开分享界面，或者碰到的根本不是完事儿。 */
        object NotOffering : Outcome()

        class Offered(val handshake: TapHandshake, val supportsReceiverGroup: Boolean = false) : Outcome()
    }

    /** 选中我们的 AID 并按段读完握手。IO 失败照常抛出，由调用方当作这次碰触没碰好。 */
    @Throws(IOException::class)
    fun readHandshake(): Outcome {
        val selected = transceive(TapApdu.select())
        if (TapApdu.statusWord(selected) != TapApdu.SW_OK) {
            return Outcome.NotOffering
        }
        val info = TapApdu.data(selected)
        if (info.size < 3) {
            throw TapProtocolException("SELECT 应答不完整")
        }
        val version = info[0].toInt() and 0xFF
        if (version != TapHandshake.VERSION) {
            throw TapProtocolException("握手版本不支持：$version")
        }
        val size = ((info[1].toInt() and 0xFF) shl 8) or (info[2].toInt() and 0xFF)
        if (size == 0) {
            throw TapProtocolException("握手为空")
        }
        val handshake = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val length = minOf(TapApdu.MAX_CHUNK, size - offset)
            val response = transceive(TapApdu.get(offset, length))
            val sw = TapApdu.statusWord(response)
            if (sw == TapApdu.SW_NOT_READY) {
                // 读到一半发送方关了分享界面
                return Outcome.NotOffering
            }
            if (sw != TapApdu.SW_OK) {
                throw TapProtocolException("GET 失败：${Integer.toHexString(sw)}")
            }
            val chunk = TapApdu.data(response)
            if (chunk.isEmpty() || chunk.size > length) {
                throw TapProtocolException("GET 返回的长度不对：${chunk.size}")
            }
            System.arraycopy(chunk, 0, handshake, offset, chunk.size)
            offset += chunk.size
        }
        return Outcome.Offered(TapHandshake.decode(handshake), (info.getOrNull(3)?.toInt() ?: 0) and 1 != 0)
    }

    /** 返回发送方给的状态字。 */
    @Throws(IOException::class)
    fun hello(receiverId: ByteArray, receiverName: String): Int {
        return TapApdu.statusWord(transceive(TapApdu.hello(receiverId, receiverName)))
    }

    fun receiverGroup(offer: TapReceiverGroupOffer): Int =
        TapApdu.statusWord(transceive(TapApdu.receiverGroup(offer)))
}
