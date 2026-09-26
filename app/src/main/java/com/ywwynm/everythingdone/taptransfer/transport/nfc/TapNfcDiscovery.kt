package com.ywwynm.everythingdone.taptransfer.transport.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Build
import android.util.Log

/**
 * 分享界面开着时，发送方只做卡、不读卡（决策 2026-09-26「发送方只做卡」）。
 *
 * 两台手机都亮屏时，各自的 NFC 控制器轮流读卡、做卡，谁先读到对方，谁就成了读卡的一方，这一次贴合
 * 期间另一方一直在做卡，读不了对方。接收方开着读取模式：AOSP 上读取模式关掉了做卡，发送方的轮询找不到它；
 * OPPO / 一加的读取模式仍然做卡，发送方的系统先读到接收方，做 NDEF 检查、探测自家的碰一碰应用，
 * 接收方的读取模式整次贴合都读不到发送方的卡模拟服务（2026-09-26 Find X9 Ultra 与一加 15T 的 NFC 事件日志）。
 *
 * Android 15 起可以让发送方在这段时间停掉轮询、只留做卡。更早的系统做不到，照旧靠轮询的先后。
 *
 * 平台的限制：与读取模式互斥（同一个 Activity 开着读取模式时设不了，反之亦然），只在 Activity 在前台时
 * 生效；离开前台时平台自动恢复，回到前台再自动套用，[reset] 之后才彻底撤销。
 */
object TapNfcDiscovery {

    private const val TAG = "TapNfcDiscovery"

    /** 停掉 [activity] 在前台期间的轮询，只做卡。返回是否设上了。 */
    fun listenOnly(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return false
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        return try {
            adapter.setDiscoveryTechnology(activity, NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP)
            true
        } catch (e: RuntimeException) {
            // 不支持（UnsupportedOperationException）、同一个页面开着读取模式（IllegalStateException）等
            Log.w(TAG, "停不掉轮询：$e")
            false
        }
    }

    /** 撤销 [listenOnly]。没设过也可以调。 */
    fun reset(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        try {
            adapter.resetDiscoveryTechnology(activity)
        } catch (e: RuntimeException) {
            Log.w(TAG, "恢复轮询失败：$e")
        }
    }
}
