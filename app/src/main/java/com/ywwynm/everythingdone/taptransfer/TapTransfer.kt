package com.ywwynm.everythingdone.taptransfer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import com.ywwynm.everythingdone.taptransfer.transport.TapSenderTransport
import com.ywwynm.everythingdone.taptransfer.transport.sim.SimulatedReceiverTransport
import com.ywwynm.everythingdone.taptransfer.transport.sim.SimulatedSenderTransport
import com.ywwynm.everythingdone.taptransfer.transport.wifi.WifiDirectReceiverTransport
import com.ywwynm.everythingdone.taptransfer.transport.wifi.WifiDirectSenderTransport
import java.io.File

/** 碰一碰开始之前必须就绪、但应用自己不能替用户打开的几项（决策 2026-09-24「逐项引导」）。 */
enum class TapPrerequisite {
    /** Android 13 起「附近的 Wi-Fi 设备」，10–12 为精确位置。 */
    PERMISSION,
    NFC,
    WIFI,

    /** Android 10–12 上 Wi-Fi Direct 还要求系统的位置信息开关开着。 */
    LOCATION
}

/**
 * 应用内碰一碰（Tap Transfer）的入口判定与环境信息。
 *
 * 碰一碰由 NFC 握手 + Wi-Fi Direct 传输组成：发送方在分享界面打开时建一个 Wi-Fi Direct 组，
 * 用卡模拟把组的 SSID、密码与一次性令牌交给碰上来的接收方；接收方开读取模式读到之后直接入组，
 * 经 TCP 收下记事文件并导入。决策见 `docs/features/thing-transfer/decisions.md` 的二期各条。
 */
object TapTransfer {

    private const val DEBUG_PREFS = "tap_transfer_debug"
    private const val KEY_SIMULATE = "simulate"
    private const val KEY_SIMULATE_FAILURE = "simulate_failure"
    private const val KEY_SIMULATE_SAMPLES = "simulate_samples"

    private const val WORK_DIR = "/tap_transfer"

    /**
     * 设备能不能碰一碰：有 NFC、支持主机卡模拟、有 Wi-Fi Direct，且在 Android 10 以上
     * （按 SSID 与密码直接入组的 `WifiP2pConfig.Builder` 从 API 29 起才有）。不满足时两个入口
     * 直接隐藏（决策 2026-09-24）。
     */
    @JvmStatic
    fun isHardwareSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC)
            && pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
            && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            && NfcAdapter.getDefaultAdapter(context) != null
    }

    /**
     * 入口是否显示：真能碰，或者调试包打开了模拟开关（模拟不需要 NFC 与 Wi-Fi Direct）。
     * 模拟也要求 Android 10 以上：粒子动画用的 `drawVertices` 从 API 29 起才走硬件加速。
     */
    @JvmStatic
    fun isEntryAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        return isSimulationEnabled(context) || isHardwareSupported(context)
    }

    /** 还缺哪几项，按引导顺序排列。模拟时什么都不缺。 */
    @JvmStatic
    fun missingPrerequisites(context: Context): List<TapPrerequisite> {
        if (isSimulationEnabled(context)) {
            return emptyList()
        }
        val missing = ArrayList<TapPrerequisite>(4)
        if (context.checkSelfPermission(requiredPermission()) != PackageManager.PERMISSION_GRANTED) {
            missing.add(TapPrerequisite.PERMISSION)
        }
        if (NfcAdapter.getDefaultAdapter(context)?.isEnabled != true) {
            missing.add(TapPrerequisite.NFC)
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled != true) {
            missing.add(TapPrerequisite.WIFI)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val location = context.getSystemService(LocationManager::class.java)
            if (location?.isLocationEnabled != true) {
                missing.add(TapPrerequisite.LOCATION)
            }
        }
        return missing
    }

    @JvmStatic
    fun requiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    /** 本机在对方屏幕上显示的名字：系统设置里的设备名，取不到时用型号。 */
    @JvmStatic
    fun deviceName(context: Context): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: SecurityException) {
            null
        }
        return if (name.isNullOrBlank()) Build.MODEL else name
    }

    /**
     * NFC 天线在屏幕上的对应位置（窗口坐标），写进 [out]，取得到时返回 true。
     *
     * Android 14 起 `NfcAdapter.getNfcAntennaInfo()` 给出天线相对机身左上角的毫米坐标（竖屏、
     * 面对屏幕），按机身尺寸折算成比例，再按当前的屏幕方向转过来。取不到时由调用方退回屏幕顶部
     * 中间（决策 2026-09-24「发送方的粒子流向 NFC 天线在屏幕上的对应位置」）。
     */
    @JvmStatic
    fun antennaPosition(activity: Activity, width: Int, height: Int, out: PointF): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || width <= 0 || height <= 0) {
            return false
        }
        val info = try {
            NfcAdapter.getDefaultAdapter(activity)?.nfcAntennaInfo
        } catch (e: RuntimeException) {
            null
        } ?: return false
        val antenna = info.availableNfcAntennas.firstOrNull() ?: return false
        if (info.deviceWidth <= 0 || info.deviceHeight <= 0 || info.isDeviceFoldable) {
            // 折叠屏展开与折叠时机身尺寸不同，比例没法直接对到屏幕上
            return false
        }
        val fx = (antenna.locationX.toFloat() / info.deviceWidth).coerceIn(0f, 1f)
        val fy = (antenna.locationY.toFloat() / info.deviceHeight).coerceIn(0f, 1f)
        val rotation = activity.display?.rotation ?: Surface.ROTATION_0
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> fy to 1f - fx
            Surface.ROTATION_180 -> 1f - fx to 1f - fy
            Surface.ROTATION_270 -> 1f - fy to fx
            else -> fx to fy
        }
        out.set(sx * width, sy * height)
        return true
    }

    @JvmStatic
    fun isSimulationEnabled(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SIMULATE, false)
    }

    /**
     * 调试：模拟传输传到一半时失败，用来核对失败时两边的动画（发送方卡片流回、接收方轮廓散回
     * 边框）。界面上没有入口，只能用 run-as 改 `tap_transfer_debug.xml` 的 `simulate_failure`。
     */
    @JvmStatic
    fun isSimulatedFailure(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SIMULATE_FAILURE, false)
    }

    /**
     * 调试：模拟接收时发来哪几条样例，逗号分隔，每一项是一种卡片样式（见 `SimulatedReceiverTransport`
     * 的 `Variant`）。没有设置时是两条纯文字的记事。界面上没有入口，用 run-as 改
     * `tap_transfer_debug.xml` 的 `simulate_samples`，例如 `top,bottom,background`。
     */
    @JvmStatic
    fun simulatedSamples(context: Context): String? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SIMULATE_SAMPLES, null)
    }

    @JvmStatic
    fun setSimulationEnabled(context: Context, enabled: Boolean) {
        if (!BuildConfig.DEBUG) {
            return
        }
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SIMULATE, enabled).apply()
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createSenderTransport(context: Context): TapSenderTransport {
        return if (isSimulationEnabled(context)) {
            SimulatedSenderTransport(context.applicationContext)
        } else {
            WifiDirectSenderTransport(context.applicationContext)
        }
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createReceiverTransport(activity: Activity): TapReceiverTransport {
        return if (isSimulationEnabled(activity)) {
            SimulatedReceiverTransport(activity.applicationContext)
        } else {
            WifiDirectReceiverTransport(activity)
        }
    }

    /**
     * 碰上的那一刻给一下触感。读取模式关掉了系统的提示音（碰到公交卡、门禁卡也会响，容易误会），
     * 所以由这里给出「碰上了」的反馈。
     */
    @JvmStatic
    fun performTouchedFeedback(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    /** 碰一碰的临时文件目录：发送方写好的记事文件、接收方收下来的记事文件。 */
    @JvmStatic
    fun workDir(context: Context, child: String): File {
        val dir = File(Def.getAppFileDir(context) + WORK_DIR, child)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
