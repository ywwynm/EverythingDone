package com.ywwynm.everythingdone.helpers

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

import androidx.core.app.NotificationManagerCompat

import java.util.ArrayList
import java.util.Arrays
import java.util.Locale

/**
 * Created by ywwynm.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 *
 * Helpers for guiding the user toward making notifications fire reliably.
 *
 * Two layers matter on modern Android:
 * - **Battery optimization** — controlled by the AOSP
 *   [android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
 *   dialog. Requires the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission
 *   in the manifest.
 * - **Vendor autostart / background management** — non-AOSP screens on
 *   Xiaomi/MIUI, Huawei/EMUI, Oppo/ColorOS, Vivo/OriginOS, Meizu/Flyme,
 *   OnePlus, Honor, Letv, ZTE, Samsung. The package/activity names below are
 *   stable across many releases but can drift, so every launch is wrapped in
 *   try/catch and falls back to the app's system settings page.
 */
object NotificationReliabilityHelper {

    private const val TAG: String = "NotificationReliability"

    /** Channel IDs whose silent disabling would break the core reminder UX. */
    @JvmField
    val CRITICAL_CHANNEL_IDS: List<String?> =
            Arrays.asList("reminder", "habit", "goal")

    @JvmStatic
    fun areNotificationsEnabled(context: Context?): Boolean {
        return NotificationManagerCompat.from(context!!).areNotificationsEnabled()
    }

    /**
     * Returns the channel IDs from [CRITICAL_CHANNEL_IDS] that are
     * currently disabled by the user (importance == NONE). Empty list means all
     * critical channels are enabled. Always returns empty on API &lt; 26.
     */
    @JvmStatic
    fun getDisabledCriticalChannels(context: Context?): List<String?>? {
        val disabled: MutableList<String?> = ArrayList()
        val nm: NotificationManager =
            context!!.getSystemService(NotificationManager::class.java) ?: return disabled
        for (id in CRITICAL_CHANNEL_IDS) {
            val ch: NotificationChannel? = nm.getNotificationChannel(id)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                disabled.add(id)
            }
        }
        return disabled
    }

    @JvmStatic
    fun canUseFullScreenIntent(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Pre-API-34: granted automatically when USE_FULL_SCREEN_INTENT is in manifest.
            return true
        }
        val nm: NotificationManager? = context!!.getSystemService(NotificationManager::class.java)
        return nm != null && nm.canUseFullScreenIntent()
    }

    @JvmStatic
    fun openAppNotificationSettings(context: Context?): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context!!.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(context, intent)) return true
        return openAppDetailsSettings(context)
    }

    @JvmStatic
    fun openChannelSettings(context: Context?, channelId: String?): Boolean {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context!!.packageName)
        intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(context, intent)) return true
        return openAppNotificationSettings(context)
    }

    /**
     * Open the system page for granting USE_FULL_SCREEN_INTENT to this app.
     * On API 34+ this is the dedicated MANAGE_APP_USE_FULL_SCREEN_INTENT screen;
     * on older platforms it falls back to the app's notification settings.
     */
    @JvmStatic
    fun openFullScreenIntentSettings(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            intent.setData(Uri.parse("package:" + context!!.packageName))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(context, intent)) return true
        }
        return openAppNotificationSettings(context)
    }

    @JvmStatic
    fun isBatteryOptimizationIgnored(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val pm: PowerManager? = context!!.getSystemService(Context.POWER_SERVICE) as PowerManager?
        return pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the system "Ignore battery optimization" dialog for this app. Returns
     * false if the OS or device does not provide the dialog (very rare).
     */
    @SuppressLint("BatteryLife")
    @JvmStatic
    fun requestIgnoreBatteryOptimization(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.setData(Uri.parse("package:" + context!!.packageName))
        return startSafely(context, intent)
    }

    /**
     * Open the user's vendor-specific autostart / background-management page.
     * If the device is not from a known vendor or none of the candidates resolve,
     * falls back to the app's system settings page.
     *
     * @return true if any screen was successfully opened.
     */
    @JvmStatic
    fun openVendorAutostartSettings(context: Context?): Boolean {
        for (candidate in autostartCandidates()) {
            val intent = Intent()
            intent.setComponent(candidate)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startSafely(context, intent)) {
                return true
            }
        }
        return openAppDetailsSettings(context)
    }

    @JvmStatic
    fun openAppDetailsSettings(context: Context?): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.setData(Uri.parse("package:" + context!!.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, intent)
    }

    /**
     * Whether this device is likely to need a vendor-specific autostart hint.
     * Pure AOSP / Pixel / most western OEMs return false; Chinese OEM ROMs that
     * are known to aggressively kill background apps return true.
     */
    @JvmStatic
    fun needsVendorAutostartHint(): Boolean {
        val manufacturer: String = if (Build.MANUFACTURER == null) "" else Build.MANUFACTURER.lowercase(Locale.getDefault())
        val brand: String = if (Build.BRAND == null) "" else Build.BRAND.lowercase(Locale.getDefault())
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") ||
                manufacturer.contains("huawei") || brand.contains("huawei") || brand.contains("honor") ||
                manufacturer.contains("honor") ||
                manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("realme") ||
                manufacturer.contains("realme") ||
                manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") ||
                manufacturer.contains("iqoo") ||
                manufacturer.contains("oneplus") || brand.contains("oneplus") ||
                manufacturer.contains("meizu") || brand.contains("meizu") ||
                manufacturer.contains("letv") || brand.contains("letv") ||
                manufacturer.contains("smartisan") || brand.contains("smartisan") ||
                manufacturer.contains("zte") || brand.contains("zte") ||
                manufacturer.contains("samsung") || brand.contains("samsung")
    }

    private fun autostartCandidates(): Array<ComponentName?> {
        return arrayOf<ComponentName?>(
                // Xiaomi / Redmi / Poco — MIUI
                ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                // Huawei / Honor — EMUI / MagicOS
                ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                // Oppo / Realme / OnePlus — ColorOS / OxygenOS / RealmeUI
                ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                // Vivo / iQOO — OriginOS / FuntouchOS
                ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.safeguard.PurviewTabActivity"),
                ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                // Meizu — Flyme
                ComponentName("com.meizu.safe",
                        "com.meizu.safe.permission.PermissionMainActivity"),
                // Letv
                ComponentName("com.letv.android.letvsafe",
                        "com.letv.android.letvsafe.AutobootManageActivity"),
                // Samsung — battery / device care entry point
                ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"),
                // ZTE
                ComponentName("com.zte.heartyservice",
                        "com.zte.heartyservice.autorun.AppAutoRunManager")
        )
    }

    private fun startSafely(context: Context?, intent: Intent): Boolean {
        val pm: PackageManager = context!!.packageManager
        if (intent.resolveActivity(pm) == null) {
            return false
        }
        try {
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start $intent", e)
            return false
        }
    }
}
