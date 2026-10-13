package com.ywwynm.everythingdone.taptransfer.diagnostics

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException

/** 调试包的本地、有界诊断。不依赖 logcat，写盘在独立线程；不主动上传。 */
object TapDiagnostics {
    /** 临时排查总开关。重新采集时改为 true；正式包始终关闭，不影响传输兼容策略。 */
    private const val ENABLE_DIAGNOSTICS = false
    val enabled: Boolean = BuildConfig.DEBUG && ENABLE_DIAGNOSTICS

    private val sessions = ArrayDeque<Session>()
    private val writer by lazy { Executors.newSingleThreadScheduledExecutor() }
    private val pendingSave = AtomicBoolean()
    private var preferences: SharedPreferences? = null
    private val previous = ArrayDeque<String>()
    private var sequence = 0

    class Session internal constructor(private val buffer: TapDiagnosticBuffer?) {
        private val started = if (buffer != null) SystemClock.elapsedRealtime() else 0L

        internal val enabled: Boolean get() = TapDiagnostics.enabled && buffer != null

        fun hide(vararg secrets: String) { if (enabled) buffer?.hide(*secrets) }

        fun event(stage: String, detail: String = "", error: Throwable? = null) {
            if (!enabled) return
            buffer?.add(SystemClock.elapsedRealtime() - started, stage, detail, error) ?: return
            saveSoon()
        }

        fun checkpoint(stage: String, detail: String = "", error: Throwable? = null) {
            if (!enabled) return
            buffer?.add(SystemClock.elapsedRealtime() - started, stage, detail, error, checkpoint = true) ?: return
            saveSoon()
        }

        fun environment(stage: String, detail: String) {
            if (!enabled) return
            buffer?.add(SystemClock.elapsedRealtime() - started, stage, detail, environmentInfo = true) ?: return
            saveSoon()
        }

        fun failure(stage: String, detail: String = "", error: Throwable? = null) {
            if (!enabled) return
            buffer?.add(SystemClock.elapsedRealtime() - started, stage, detail, error, true) ?: return
            writer.execute { persist() }
        }

        internal fun snapshot(): String = buffer?.snapshot().orEmpty()
    }

    val NONE = Session(null)

    @Synchronized
    private fun initialize(context: Context) {
        if (preferences != null || !enabled) return
        preferences = context.applicationContext.getSharedPreferences("tap_diagnostics", Context.MODE_PRIVATE)
        val saved = preferences?.getString("sessions", null) ?: return
        try {
            val reports = JSONArray(saved)
            repeat(minOf(reports.length(), 3)) { previous.addLast(reports.getString(it).take(40_000)) }
        } catch (e: JSONException) {
            previous.addLast("历史诊断无法读取：${e.javaClass.simpleName}\n")
        }
    }

    fun begin(context: Context, role: String): Session {
        if (!enabled) return NONE
        initialize(context)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())
        val session = synchronized(this) {
            Session(TapDiagnosticBuffer("会话 ${++sequence} · $role · $date")).also {
                sessions.addLast(it)
                while (sessions.size + previous.size > 3) {
                    if (previous.isNotEmpty()) previous.removeLast() else sessions.removeFirst()
                }
            }
        }
        session.environment("版本和设备", "app=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}, " +
            "debugUpdate=${context.getString(R.string.debug_update_code)}, " +
            "manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, " +
            "Android=${Build.VERSION.RELEASE}, API=${Build.VERSION.SDK_INT}, build=${Build.DISPLAY}")
        environment(context, session)
        return session
    }

    /** 仅读取平台公开状态；某个查询不支持时记录异常，不阻止其它诊断和传输。 */
    fun environment(context: Context, session: Session) {
        if (!session.enabled) return
        fun probe(name: String, value: () -> String) {
            try { session.environment(name, value()) }
            catch (e: RuntimeException) { session.event("环境查询异常：$name", error = e) }
        }
        probe("硬件声明") {
            val pm = context.packageManager
            "NFC=${pm.hasSystemFeature(PackageManager.FEATURE_NFC)}, " +
                "HCE=${pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)}, " +
                "Wi-Fi Direct=${pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)}, " +
                "P2P service=${context.getSystemService(WifiP2pManager::class.java) != null}, " +
                "模拟=${TapTransfer.isSimulationEnabled(context)}"
        }
        probe("开关和权限") {
            val nfc = NfcAdapter.getDefaultAdapter(context)
            val permissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE).joinToString { permission ->
                "${permission.substringAfterLast('.')}=${context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED}"
            }
            "NFC adapter=${nfc != null}, enabled=${nfc?.isEnabled}, " +
                "Wi-Fi=${context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled}, " +
                "location=${context.getSystemService(LocationManager::class.java)?.isLocationEnabled}, $permissions"
        }
        probe("默认网络") {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            "available=${caps != null}, wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, " +
                "cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}, " +
                "vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}"
        }
        probe("应用权限声明") {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val permissions = info.requestedPermissions.orEmpty()
            val nearbyIndex = permissions.indexOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            val nearbyFlags = info.requestedPermissionsFlags?.getOrNull(nearbyIndex) ?: 0
            val neverForLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                nearbyFlags and android.content.pm.PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION != 0
            } else null
            "targetSdk=${context.applicationInfo.targetSdkVersion}, nearbyDeclared=${nearbyIndex >= 0}, " +
                "nearbyNeverForLocation=$neverForLocation, " +
                "fineLocationDeclared=${Manifest.permission.ACCESS_FINE_LOCATION in permissions}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val appOpsPermissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appOpsPermissions.add(0, Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            appOpsPermissions.forEach { permission ->
                probe("权限执行状态：${permission.substringAfterLast('.')}") {
                    val manager = context.getSystemService(AppOpsManager::class.java)
                    val op = AppOpsManager.permissionToOp(permission)
                    if (manager == null || op == null) "AppOps 无可查询映射"
                    else {
                        // 只读取，不用 noteOp/startOp 产生一次新的权限使用记录。
                        @Suppress("DEPRECATION")
                        val mode = manager.unsafeCheckOpNoThrow(op, Process.myUid(), context.opPackageName)
                        "mode=$mode (${appOpMode(mode)})；系统报告值，不能单独代表 P2P 服务授权结果"
                    }
                }
            }
        }
    }

    @Synchronized
    fun report(context: Context): String {
        if (!enabled) return ""
        initialize(context)
        return snapshot()
    }

    @Synchronized
    private fun snapshot(): String = buildString {
        append("[TapDiagnostics v7] 碰一碰传输诊断\n")
        append("仅记录阶段、计数和连接环境；请同时提供另一台设备的诊断。\n")
        if (sessions.isEmpty() && previous.isEmpty()) append("暂无记录。进入接收状态或打开分享面板后开始记录。\n")
        sessions.toList().asReversed().forEach { append('\n').append(it.snapshot()) }
        if (previous.isNotEmpty()) {
            append("\n此前保存的会话：\n")
            previous.forEach { append('\n').append(it) }
        }
    }

    private fun saveSoon() {
        if (!enabled) return
        if (pendingSave.compareAndSet(false, true)) writer.schedule({
            pendingSave.set(false)
            persist()
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun persist() {
        if (!enabled) return
        val reports = synchronized(this) {
            sessions.toList().asReversed().map { it.snapshot() } + previous.toList()
        }
        preferences?.edit()?.putString("sessions", JSONArray(reports).toString())?.apply()
    }

    fun p2pReason(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "$reason (ERROR)"
        WifiP2pManager.P2P_UNSUPPORTED -> "$reason (P2P_UNSUPPORTED)"
        WifiP2pManager.BUSY -> "$reason (BUSY)"
        else -> "$reason (未知系统错误码)"
    }

    private fun appOpMode(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "ALLOWED"
        AppOpsManager.MODE_IGNORED -> "IGNORED"
        AppOpsManager.MODE_ERRORED -> "ERRORED"
        AppOpsManager.MODE_DEFAULT -> "DEFAULT"
        AppOpsManager.MODE_FOREGROUND -> "FOREGROUND"
        else -> "未知"
    }
}
