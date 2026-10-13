package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapP2pDiagnostics
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.protocol.TapReceiverGroupOffer
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/** 进入接收时提前建组，不占用 NFC 交换时间。生命周期操作均在主线程。 */
internal class TapReceiverGroupHost(private val context: Context, private val diagnostics: TapDiagnostics.Session) {
    class Ready(val ssid: String, val passphrase: String, val server: TapReceiverServer) {
        fun offer(token: ByteArray, id: ByteArray, name: String) =
            TapReceiverGroupOffer(token.copyOf(), id.copyOf(), server.port, ssid, passphrase, name)
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var closed = false
    @Volatile private var listeningServer: TapReceiverServer? = null
    @Volatile var ready: Ready? = null
        private set
    private var creating = false
    private var created = false
    private var ssid: String? = null
    private var cleaning = false
    private var cleaned = false
    private val afterClose = ArrayList<() -> Unit>()
    private var prepared = false
    private var openingServer = false

    fun prepare(result: (Boolean) -> Unit) {
        fun failed() {
            if (closed || prepared) return
            prepared = true
            diagnostics.failure("接收方预建组未就绪", "本次保留原接收方式")
            close("预建组未就绪") { result(false) }
        }
        val m = manager ?: run { failed(); return }
        val c = m.initialize(context, Looper.getMainLooper()) {
            diagnostics.event("接收建组通道断开", "closed=$closed")
            if (!prepared) failed()
        }
        channel = c
        TapP2pDiagnostics.capture(diagnostics, m, c, "接收建组通道初始化后")
        main.postDelayed({ if (!prepared && !closed) failed() }, 8000)
        try {
            m.requestGroupInfo(c) { group ->
                if (closed) return@requestGroupInfo
                val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SSID, null)
                if (group != null && group.isGroupOwner && group.networkName == saved) {
                    diagnostics.event("清理上次接收留下的组")
                    ssid = saved
                    m.removeGroup(c, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { if (!closed) awaitVacant(m, c, result, ::failed) }
                        override fun onFailure(reason: Int) { failed() }
                    })
                } else if (group == null) create(m, c, result, ::failed)
                else {
                    diagnostics.event("接收预建组跳过", "已有其它 P2P 组")
                    failed()
                }
            }
        } catch (e: RuntimeException) {
            diagnostics.failure("接收预建组初始化异常", error = e)
            failed()
        }
    }

    private fun awaitVacant(m: WifiP2pManager, c: WifiP2pManager.Channel,
                            result: (Boolean) -> Unit, failed: () -> Unit) {
        if (closed || prepared) return
        try {
            m.requestGroupInfo(c) { group ->
                if (closed || prepared) return@requestGroupInfo
                when {
                    group == null -> { ssid = null; create(m, c, result, failed) }
                    group.isGroupOwner && group.networkName == ssid ->
                        main.postDelayed({ awaitVacant(m, c, result, failed) }, 200)
                    else -> failed()
                }
            }
        } catch (e: RuntimeException) { failed() }
    }

    private fun create(m: WifiP2pManager, c: WifiP2pManager.Channel,
                       result: (Boolean) -> Unit, failed: () -> Unit) {
        if (closed) return
        diagnostics.event("接收方请求 P2P createGroup")
        creating = true
        try {
            m.createGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    creating = false
                    created = true
                    if (closed) { cleanup(); return }
                    diagnostics.event("接收方 createGroup 已受理")
                    poll(m, c, result, failed)
                }
                override fun onFailure(reason: Int) {
                    creating = false
                    diagnostics.failure("接收方 createGroup 被拒绝", "reason=${TapDiagnostics.p2pReason(reason)}")
                    if (closed) cleanup() else failed()
                }
            })
        } catch (e: RuntimeException) {
            creating = false
            diagnostics.failure("接收方 createGroup 异常", error = e)
            failed()
        }
    }

    private fun poll(m: WifiP2pManager, c: WifiP2pManager.Channel,
                     result: (Boolean) -> Unit, failed: () -> Unit) {
        if (closed || prepared || openingServer) return
        try {
            m.requestGroupInfo(c) { group ->
                if (closed || prepared || openingServer) return@requestGroupInfo
                val name = group?.networkName
                val password = group?.passphrase
                if (group?.isGroupOwner != true || name.isNullOrEmpty() || password.isNullOrEmpty()) {
                    main.postDelayed({ poll(m, c, result, failed) }, 200)
                    return@requestGroupInfo
                }
                ssid = name
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SSID, name).apply()
                diagnostics.hide(name, password)
                if (TapHandshake.credentialProblem(name, password) != null) { failed(); return@requestGroupInfo }
                try {
                    diagnostics.event("查询接收组监听地址", "interface=${group.getInterface()}")
                    m.requestConnectionInfo(c) { info ->
                        if (closed || prepared || openingServer) return@requestConnectionInfo
                        try {
                            val iface = group.getInterface()
                            val addresses = if (iface.isNullOrEmpty()) emptyList() else
                                NetworkInterface.getByName(iface)?.inetAddresses?.toList().orEmpty()
                            val address = TapReceiverBindAddress.select(info?.groupFormed == true,
                                info?.isGroupOwner == true, info?.groupOwnerAddress, addresses)
                            if (address == null) {
                                diagnostics.event("接收组 IPv4 尚未就绪", "formed=${info?.groupFormed}, " +
                                    "owner=${info?.isGroupOwner}, host=${info?.groupOwnerAddress?.hostAddress}, interface=$iface")
                                main.postDelayed({ poll(m, c, result, failed) }, 200)
                                return@requestConnectionInfo
                            }
                            diagnostics.checkpoint("接收监听地址已核对", "address=${address.hostAddress}, interface=$iface, mode=P2P_IPV4")
                            openingServer = true
                            openServer(name, password, address, result, failed)
                        } catch (e: IOException) {
                            diagnostics.failure("读取接收组接口失败", error = e)
                            failed()
                        } catch (e: RuntimeException) {
                            diagnostics.failure("读取接收组地址失败", error = e)
                            failed()
                        }
                    }
                } catch (e: RuntimeException) {
                    diagnostics.failure("查询接收组地址失败", error = e)
                    failed()
                }
            }
        } catch (e: RuntimeException) { failed() }
    }

    private fun openServer(name: String, password: String, address: Inet4Address,
                           result: (Boolean) -> Unit, failed: () -> Unit) {
        Thread({
            val server = try {
                val record: ((String, String) -> Unit)? = if (diagnostics.enabled) {
                    { stage, detail -> diagnostics.event(stage, detail) }
                } else null
                TapReceiverServer(address, record)
            } catch (e: IOException) {
                diagnostics.failure("接收建组 TCP 监听失败", "bind=${address.hostAddress}", e)
                main.post { failed() }
                return@Thread
            } catch (e: RuntimeException) {
                diagnostics.failure("接收建组 TCP 监听异常", "bind=${address.hostAddress}", e)
                main.post { failed() }
                return@Thread
            }
            listeningServer = server
            // close() 可能已经移除了尚未执行的主线程回调，监听资源必须先独立登记。
            if (closed) { server.close("创建监听时接收已退出"); return@Thread }
            main.post {
                if (closed || prepared) server.close("监听就绪回调已过期")
                else {
                    ready = Ready(name, password, server)
                    prepared = true
                    main.removeCallbacksAndMessages(null)
                    if (diagnostics.enabled)
                        diagnostics.checkpoint("接收方组和 TCP 已就绪", "ssidLength=${name.length}, ${server.diagnosticState()}")
                    capture("接收监听就绪")
                    result(true)
                }
            }
        }, "tap-receive-listen").start()
    }

    fun capture(scene: String) {
        if (!diagnostics.enabled) return
        diagnostics.checkpoint("接收监听状态", "scene=$scene, hostClosed=$closed, " +
            (listeningServer?.diagnosticState() ?: "server=null"))
        TapP2pDiagnostics.capture(diagnostics, manager, channel, scene, ssid)
    }

    fun close(reason: String, then: () -> Unit = {}) {
        if (cleaned) { then(); return }
        afterClose.add(then)
        if (closed) return
        closed = true
        listeningServer?.close(reason)
        ready = null
        main.removeCallbacksAndMessages(null)
        // createGroup 已发出时先等受理结果，避免刚查到空组就关闭通道、随后遗留新组。
        if (creating) main.postDelayed({ cleanup() }, 8000) else cleanup()
    }

    private fun cleanup() {
        if (cleaning || cleaned) return
        cleaning = true
        val m = manager
        val c = channel
        fun finished() {
            cleaned = true
            main.removeCallbacksAndMessages(null)
            val callbacks = afterClose.toList()
            afterClose.clear()
            callbacks.forEach { it() }
        }
        if (m == null || c == null) { finished(); return }
        closeTapP2pChannel(m, c, main, diagnostics, setOfNotNull(ssid), created && ssid == null) { ok ->
            if (ok) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SSID).apply()
            finished()
        }
    }

    companion object {
        const val PREFS = "tap_receiver_group"
        const val KEY_SSID = "owned_ssid"
    }
}
