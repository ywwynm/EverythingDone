package com.ywwynm.everythingdone.taptransfer.transport

import java.io.File

/** 一次碰一碰失败在哪一步。界面上只区分「再碰一次」，这里的细分用于日志。 */
enum class TapFailure {
    /** 建 Wi-Fi Direct 组失败，发送方没法进入可被碰的状态。 */
    GROUP,

    /** 接收方没能入组。 */
    JOIN,

    /** 入组了但连不上发送方。 */
    CONNECT,

    /** 传到一半断开，或对方没有确认。 */
    TRANSFER,

    /** 记事文件写不出来（发送方）或读不出来（接收方）。 */
    FILE
}

/** 碰上的那一刻接收方就知道的内容：谁发来的、几条、各是什么颜色。 */
class TapIncomingOffer(
    val senderName: String,
    val thingCount: Int,
    val colors: IntArray,
    /** 前几条记事的卡片高宽比（高 / 宽）与是否通栏（与 [colors] 同序）；旧版发送方没有，为空。 */
    val aspects: FloatArray = FloatArray(0),
    val fullSpan: BooleanArray = BooleanArray(0)
)

/**
 * 发送方要传的记事文件。分享界面一打开就开始在后台写，碰上时通常已经写好；
 * 附件很大时，传输线程在这里等它写完。
 */
fun interface TapPayload {
    /** 在后台线程调用，最多等 [timeoutMs]；写不出来时返回 null。 */
    fun await(timeoutMs: Long): File?
}

/** 发送方。所有回调都在主线程。 */
interface TapSenderTransport {

    class Offer(
        val senderName: String,
        val thingCount: Int,
        val colors: IntArray,
        val payload: TapPayload,
        /** 前几条记事的卡片高宽比（按本机列表里的样子量）与是否通栏，交给接收方预计落点。 */
        val aspects: FloatArray = FloatArray(0),
        val fullSpan: BooleanArray = BooleanArray(0)
    )

    interface Listener {
        /** 可以被碰了。 */
        fun onReady()

        /** 准备失败，碰一碰这次用不了（下方三项照常可用）。 */
        fun onUnavailable(reason: TapFailure)

        fun onPeerTouched(peerName: String)
        fun onPeerConnected()
        fun onProgress(done: Long, total: Long)
        fun onSent(peerName: String)
        fun onFailed(reason: TapFailure)

        /**
         * 可以被碰之后，Wi-Fi Direct 组被系统或别的应用拆掉了（例如系统自己的碰一碰占了 Wi-Fi Direct）。
         * 只在没有正在进行的一次时报；正在传的那一次先按失败走完，等界面回到可以被碰时再报。
         * 调用方停掉这个传输、重新准备。
         */
        fun onGroupLost()
    }

    fun start(offer: Offer, listener: Listener)

    /**
     * 界面是否在等下一次碰触。流出、「已发送」、流回的动画期间为 false，这时别的手机碰上来
     * 会被告知「正忙」，不会打断正在播的动画。
     */
    fun setAccepting(accepting: Boolean)

    fun stop()
}

/** 接收方。所有回调都在主线程。 */
interface TapReceiverTransport {

    interface Listener {
        fun onTouched(offer: TapIncomingOffer)
        fun onConnected(totalBytes: Long)
        fun onProgress(done: Long, total: Long)

        /**
         * 收齐了。[file] 归调用方处理，用完删掉。
         *
         * 可能在 [stop] 之后才到：确认字节已经发出（发送方显示已发送）时，这份文件不能丢，
         * 调用方即使已经退出接收状态也要把它导入。
         */
        fun onReceived(file: File)

        fun onFailed(reason: TapFailure)
    }

    fun start(receiverName: String, listener: Listener)

    /**
     * 一次接收（无论成败）的动画播完、可以接下一台时调用。在这之前再有手机碰上来一律忽略，
     * 两台手机分开又贴上也不会被当成第二次碰触。
     */
    fun resume()

    fun stop()
}
