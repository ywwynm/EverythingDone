package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics

/** 查询和移除在同一通道完成；迟到回调不能在调用者开始下一次连接后继续拆组。 */
internal fun closeTapP2pChannel(
    manager: WifiP2pManager, channel: WifiP2pManager.Channel, main: Handler,
    diagnostics: TapDiagnostics.Session, matchingSsids: Set<String>,
    unnamedCreatedGroup: Boolean = false, then: (Boolean) -> Unit = {}
) {
    // 调用者 stop() 会清空自己的 Handler；清理必须仍有独立的超时保障。
    val cleanupMain = Handler(main.looper)
    var finished = false
    var timeout: Runnable? = null
    fun finish(ok: Boolean) {
        if (finished) return
        finished = true
        timeout?.let { cleanupMain.removeCallbacks(it) }
        runCatching { channel.close() }
        then(ok)
    }
    timeout = Runnable {
        diagnostics.failure("P2P 清理回调超时", "已关闭本次通道")
        finish(false)
    }
    cleanupMain.postDelayed(timeout!!, 4000)
    fun waitUntilGone(removedSsid: String) {
        if (finished) return
        try {
            manager.requestGroupInfo(channel) { remaining ->
                if (finished) return@requestGroupInfo
                when {
                    remaining == null -> finish(true)
                    remaining.networkName != removedSsid -> finish(false)
                    else -> cleanupMain.postDelayed({ waitUntilGone(removedSsid) }, 200)
                }
            }
        } catch (e: RuntimeException) { finish(false) }
    }
    try {
        manager.requestGroupInfo(channel) { group ->
            if (finished) return@requestGroupInfo
            val matches = group != null && (group.networkName in matchingSsids ||
                (unnamedCreatedGroup && group.isGroupOwner))
            diagnostics.event("清理本次 P2P 通道", "exists=${group != null}, matches=$matches")
            if (!matches) { finish(group == null); return@requestGroupInfo }
            try {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!finished) waitUntilGone(group!!.networkName)
                    }
                    override fun onFailure(reason: Int) {
                        if (finished) return
                        diagnostics.failure("移除本次 P2P 组失败", "reason=${TapDiagnostics.p2pReason(reason)}")
                        finish(false)
                    }
                })
            } catch (e: RuntimeException) { finish(false) }
        }
    } catch (e: RuntimeException) { finish(false) }
}
