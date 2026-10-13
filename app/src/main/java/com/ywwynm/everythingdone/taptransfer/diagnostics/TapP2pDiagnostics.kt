package com.ywwynm.everythingdone.taptransfer.diagnostics

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 仅查询公开状态，不触发发现、建组或重试，也不把查询结果接入传输状态机。 */
@RequiresApi(Build.VERSION_CODES.Q)
internal object TapP2pDiagnostics {
    private const val CALLBACK_WAIT_MS = 1500L
    private val sequence = AtomicInteger()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("MissingPermission") // 诊断必须能观察权限不足的返回，逐项捕获并记录查询异常。
    fun capture(session: TapDiagnostics.Session, manager: WifiP2pManager?,
                channel: WifiP2pManager.Channel?, scene: String, expectedSsid: String? = null) {
        if (!session.enabled) return
        if (manager == null || channel == null) {
            session.event("P2P 状态查询跳过", "scene=$scene, manager=${manager != null}, channel=${channel != null}")
            return
        }
        val id = sequence.incrementAndGet()
        session.event("P2P 状态查询开始", "probe=$id, scene=$scene；异步结果按各自回调时间记录")

        fun query(name: String, request: ((() -> String) -> Unit) -> Unit) {
            val started = SystemClock.elapsedRealtime()
            val complete = AtomicBoolean()
            val timeout = Runnable {
                if (!complete.get()) session.event("P2P 查询尚无回调",
                    "probe=$id, query=$name, waitMs=$CALLBACK_WAIT_MS；不等同于功能不支持")
            }
            main.postDelayed(timeout, CALLBACK_WAIT_MS)
            try {
                request { detail ->
                    main.removeCallbacks(timeout)
                    if (complete.compareAndSet(false, true)) {
                        try {
                            session.event("P2P 查询：$name", "probe=$id, elapsedMs=${SystemClock.elapsedRealtime() - started}, ${detail()}")
                        } catch (e: RuntimeException) {
                            session.event("P2P 查询结果读取异常", "probe=$id, query=$name", e)
                        }
                    }
                }
            } catch (e: RuntimeException) {
                main.removeCallbacks(timeout)
                complete.set(true)
                // 查询本身失败不是传输失败，不能挤掉报告中保留的 connect/ACK 错误。
                session.event("P2P 查询异常", "probe=$id, query=$name", e)
            }
        }

        query("服务状态") { done ->
            manager.requestP2pState(channel) { state ->
                val label = when (state) {
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "ENABLED"
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "DISABLED"
                    else -> "未知"
                }
                done { "state=$state ($label)" }
            }
        }
        query("发现状态") { done ->
            manager.requestDiscoveryState(channel) { state ->
                val label = when (state) {
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> "STARTED"
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> "STOPPED"
                    else -> "未知"
                }
                done { "state=$state ($label)" }
            }
        }
        query("网络状态") { done ->
            manager.requestNetworkInfo(channel) { info ->
                done {
                    // 兼容层可能违反非空注解；诊断保留空值分支，不据此改变传输流程。
                    @Suppress("DEPRECATION", "SENSELESS_COMPARISON")
                    val detail = "info=${info != null}, available=${info?.isAvailable}, " +
                        "connected=${info?.isConnected}, state=${info?.detailedState}"
                    detail
                }
            }
        }
        query("本机状态") { done ->
            manager.requestDeviceInfo(channel) { device ->
                done { "device=${device != null}, status=${device?.status?.let(::deviceStatus)}, " +
                    "owner=${device?.isGroupOwner}, address=${addressKind(device?.deviceAddress)}" }
            }
        }
        query("连接信息") { done ->
            manager.requestConnectionInfo(channel) { info ->
                done { "info=${info != null}, formed=${info?.groupFormed}, owner=${info?.isGroupOwner}, " +
                    "host=${info?.groupOwnerAddress?.hostAddress}" }
            }
        }
        query("当前组") { done ->
            manager.requestGroupInfo(channel) { group ->
                done { "exists=${group != null}, owner=${group?.isGroupOwner}, " +
                    "matches=${if (expectedSsid == null) "未指定" else group?.networkName == expectedSsid}, " +
                    "clients=${group?.clientList?.size}, frequency=${group?.frequency}, " +
                    interfaceState(group?.getInterface()) }
            }
        }
    }

    private fun interfaceState(name: String?): String {
        if (name.isNullOrEmpty()) return "interface=未提供"
        return try {
            val networkInterface = NetworkInterface.getByName(name)
            val addresses = networkInterface?.inetAddresses?.toList().orEmpty()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress }
                .map { it.hostAddress }.take(4)
            "interface=$name, present=${networkInterface != null}, up=${networkInterface?.isUp}, localIpv4=$addresses"
        } catch (e: SocketException) {
            "interface=$name, readError=${e.javaClass.simpleName}"
        } catch (e: SecurityException) {
            "interface=$name, readError=${e.javaClass.simpleName}"
        }
    }

    fun config(session: TapDiagnostics.Session, config: WifiP2pConfig) {
        if (!session.enabled) return
        try {
            session.event("P2P 入组配置摘要", "ssidUtf8Bytes=${config.networkName?.toByteArray(Charsets.UTF_8)?.size}, " +
                "directPrefix=${config.networkName?.startsWith("DIRECT-")}, passwordLength=${config.passphrase?.length}, " +
                "address=${addressKind(config.deviceAddress)}, bandOrFrequency=${config.groupOwnerBand}")
        } catch (e: RuntimeException) {
            session.event("P2P 配置摘要读取异常", error = e)
        }
    }

    private fun addressKind(address: String?): String = when {
        address.isNullOrEmpty() -> "空"
        address.equals("02:00:00:00:00:00", ignoreCase = true) -> "系统默认／匿名地址"
        address.equals("00:00:00:00:00:00", ignoreCase = true) -> "全零地址"
        else -> "已提供（不记录值）"
    }

    private fun deviceStatus(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "$status (CONNECTED)"
        WifiP2pDevice.INVITED -> "$status (INVITED)"
        WifiP2pDevice.FAILED -> "$status (FAILED)"
        WifiP2pDevice.AVAILABLE -> "$status (AVAILABLE)"
        WifiP2pDevice.UNAVAILABLE -> "$status (UNAVAILABLE)"
        else -> "$status (未知)"
    }
}
