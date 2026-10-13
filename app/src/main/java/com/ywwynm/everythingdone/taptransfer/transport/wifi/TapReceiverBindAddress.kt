package com.ywwynm.everythingdone.taptransfer.transport.wifi

import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import java.net.Inet4Address
import java.net.InetAddress

/** 仅使用系统报告且实际属于本次组接口的地址，避免绑定普通 Wi-Fi 或上一轮组的地址。 */
internal object TapReceiverBindAddress {
    fun select(groupFormed: Boolean, isGroupOwner: Boolean, reported: InetAddress?,
               interfaceAddresses: List<InetAddress>): Inet4Address? {
        if (!groupFormed || !isGroupOwner || reported !is Inet4Address) return null
        if (!TapHandshake.isLocalIpv4(reported.hostAddress.orEmpty())) return null
        return reported.takeIf { it in interfaceAddresses }
    }
}
