package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer

/** 每次实体碰触单独持有，避免 P2P 与普通 Wi-Fi 的迟到回调重复启动下载。 */
internal class TapJoinAttempt(
    private val startedAtMs: Long = 0L,
    private val wifiSenderWaitMs: Int = TapHandshake.DEFAULT_CONNECTION_WAIT_MS,
    receiverGroup: Boolean = false
) {
    enum class Route { P2P, WIFI, RECEIVER_GROUP }

    private var route = if (receiverGroup) Route.RECEIVER_GROUP else Route.P2P
    private var transferring = false
    @Volatile var active = true
        private set
    var deadlineMs = startedAtMs + if (receiverGroup) TapReceiverGroupOffer.CONNECTION_WAIT_MS - SENDER_MARGIN_MS else NORMAL_RECEIVER_WAIT_MS
        private set
    val windowMs: Long get() = deadlineMs - startedAtMs

    fun remainingMs(nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)

    fun waitingFor(expected: Route): Boolean = active && !transferring && route == expected

    fun switchToWifi(): Boolean {
        if (!waitingFor(Route.P2P)) return false
        route = Route.WIFI
        // 始终从本次碰触开始计时，不从回调时刻重新加一轮，也不超过发送方声明的等待。
        deadlineMs = startedAtMs + wifiSenderWaitMs - SENDER_MARGIN_MS
        return true
    }

    fun beginTransfer(expected: Route): Boolean {
        if (!waitingFor(expected)) return false
        transferring = true
        return true
    }

    fun finish(): Boolean {
        if (!active) return false
        active = false
        return true
    }

    companion object {
        const val NORMAL_RECEIVER_WAIT_MS = 22_000L
        private const val SENDER_MARGIN_MS = 3000L
    }
}
