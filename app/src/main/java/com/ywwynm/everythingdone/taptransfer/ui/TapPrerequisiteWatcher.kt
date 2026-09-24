package com.ywwynm.everythingdone.taptransfer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat

/**
 * NFC、Wi-Fi、位置信息三个开关的状态变化。
 *
 * 用户在系统面板里打开开关之后，开关要一两秒才真正打开，回到前台那一刻去查可能还是关着的；
 * 用户也可能不离开应用、直接从下拉的快捷设置里打开。两种情况都以广播为准：状态一变就回调，
 * 由调用方重新检查（回调里不带状态，调用方自己查，广播只是提醒）。
 */
class TapPrerequisiteWatcher(context: Context, private val onChanged: () -> Unit) {

    private val context = context.applicationContext
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = onChanged()
    }

    fun start() {
        if (registered) {
            return
        }
        val filter = IntentFilter()
        filter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        filter.addAction(LocationManager.MODE_CHANGED_ACTION)
        // 必须是 EXPORTED：NFC 的状态广播由 NFC 服务以它自己的 uid 发出，不是系统进程，
        // 不导出的接收器收不到。别的应用伪造这几个广播也只会让调用方多查一次真实状态。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }
}
