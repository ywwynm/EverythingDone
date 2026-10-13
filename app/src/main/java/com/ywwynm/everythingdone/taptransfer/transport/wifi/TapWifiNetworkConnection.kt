package com.ywwynm.everythingdone.taptransfer.transport.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics

/** 一次明确 SSID 的本地网络申请；请求及回调都在主线程，关闭后不再交付网络。 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class TapWifiNetworkConnection(context: Context, private val diagnostics: TapDiagnostics.Session) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var closed = false
    private var requestedAt = 0L
    private var available = false

    @SuppressLint("MissingPermission") // 权限由既有前提检查提供，调试清单另声明 CHANGE_NETWORK_STATE。
    fun request(ssid: String, password: String, timeoutMs: Int,
                onReady: (Network) -> Unit, onFailure: () -> Unit) {
        check(callback == null && !closed)
        requestedAt = SystemClock.elapsedRealtime()
        if (manager == null) {
            diagnostics.failure("普通 Wi-Fi 兼容连接不可用", "ConnectivityManager=false")
            onFailure()
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var delivered: Network? = null

            override fun onAvailable(network: Network) {
                if (closed || callback !== this || delivered != null) return
                delivered = network
                available = true
                diagnostics.event("普通 Wi-Fi 网络已就绪", "elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}；传输 Socket 将绑定该网络")
                onReady(network)
            }

            override fun onUnavailable() {
                if (closed || callback !== this || delivered != null) return
                diagnostics.failure("普通 Wi-Fi 请求未能建立连接", "可能为用户拒绝、系统不支持或请求超时；此回调不提供细分原因")
                onFailure()
            }

            override fun onLost(network: Network) {
                if (closed || callback !== this || delivered != network) return
                // 已开始的数据流交给 Socket 的读写结果裁决，避免 ACK 后的断网反写为发送失败。
                diagnostics.event("普通 Wi-Fi 网络断开", "已开始传输；等待 TCP 结果")
            }
        }
        callback = cb
        try {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            diagnostics.event("请求普通 Wi-Fi 兼容连接", "timeoutMs=$timeoutMs；等待系统确认或网络回调")
            manager.requestNetwork(request, cb, Handler(Looper.getMainLooper()), timeoutMs)
            diagnostics.event("普通 Wi-Fi 请求调用已返回", "等待系统选择设备、允许连接；返回不表示网络已连接")
        } catch (e: RuntimeException) {
            diagnostics.failure("普通 Wi-Fi 请求调用失败", error = e)
            close()
            onFailure()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val cb = callback ?: return
        callback = null
        try {
            manager?.unregisterNetworkCallback(cb)
            diagnostics.event("普通 Wi-Fi 连接请求已释放", "available=$available, elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}")
        } catch (e: RuntimeException) {
            diagnostics.event("普通 Wi-Fi 请求释放异常", error = e)
        }
    }
}
