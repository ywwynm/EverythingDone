package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** 身份、网络方向和一次连接资格一同验证，主动／被动 TCP 不能抢同一个接收会话。 */
internal class TapSendPeer(
    val id: ByteArray, val idHex: String, val name: String,
    val receiverGroup: TapReceiverGroupOffer?, val deadlineMs: Long
) {
    val state = AtomicInteger(WAITING)
    var connecting = false

    fun claim(receiverId: ByteArray, initiator: TapSendPeer?): Boolean {
        if (!MessageDigest.isEqual(id, receiverId)) return false
        if (receiverGroup != null && initiator !== this) return false
        if (initiator != null && (initiator !== this || receiverGroup == null)) return false
        return state.compareAndSet(WAITING, CONNECTED)
    }

    companion object {
        const val WAITING = 0
        const val CONNECTED = 1
        const val FINISHED = 2
    }
}
