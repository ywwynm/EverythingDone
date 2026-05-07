package com.ywwynm.everythingdone.helpers;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helpers for guiding the user toward making notifications fire reliably.
 *
 * <p>Two layers matter on modern Android:
 * <ul>
 *     <li><b>Battery optimization</b> — controlled by the AOSP
 *     {@link android.provider.Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}
 *     dialog. Requires the {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} permission
 *     in the manifest.</li>
 *     <li><b>Vendor autostart / background management</b> — non-AOSP screens on
 *     Xiaomi/MIUI, Huawei/EMUI, Oppo/ColorOS, Vivo/OriginOS, Meizu/Flyme,
 *     OnePlus, Honor, Letv, ZTE, Samsung. The package/activity names below are
 *     stable across many releases but can drift, so every launch is wrapped in
 *     try/catch and falls back to the app's system settings page.</li>
 * </ul>
 */
public final class NotificationReliabilityHelper {

    private static final String TAG = "NotificationReliability";

    private NotificationReliabilityHelper() {}

    /** Channel IDs whose silent disabling would break the core reminder UX. */
    public static final List<String> CRITICAL_CHANNEL_IDS =
            Arrays.asList("reminder", "habit", "goal");

    public static boolean areNotificationsEnabled(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * Returns the channel IDs from {@link #CRITICAL_CHANNEL_IDS} that are
     * currently disabled by the user (importance == NONE). Empty list means all
     * critical channels are enabled. Always returns empty on API &lt; 26.
     */
    public static List<String> getDisabledCriticalChannels(Context context) {
        List<String> disabled = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return disabled;
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return disabled;
        for (String id : CRITICAL_CHANNEL_IDS) {
            NotificationChannel ch = nm.getNotificationChannel(id);
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                disabled.add(id);
            }
        }
        return disabled;
    }

    public static boolean canUseFullScreenIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Pre-API-34: granted automatically when USE_FULL_SCREEN_INTENT is in manifest.
            return true;
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    public static boolean openAppNotificationSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startSafely(context, intent)) return true;
        }
        return openAppDetailsSettings(context);
    }

    public static boolean openChannelSettings(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startSafely(context, intent)) return true;
        }
        return openAppNotificationSettings(context);
    }

    /**
     * Open the system page for granting USE_FULL_SCREEN_INTENT to this app.
     * On API 34+ this is the dedicated MANAGE_APP_USE_FULL_SCREEN_INTENT screen;
     * on older platforms it falls back to the app's notification settings.
     */
    public static boolean openFullScreenIntentSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startSafely(context, intent)) return true;
        }
        return openAppNotificationSettings(context);
    }

    public static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Open the system "Ignore battery optimization" dialog for this app. Returns
     * false if the OS or device does not provide the dialog (very rare).
     */
    @SuppressLint("BatteryLife")
    public static boolean requestIgnoreBatteryOptimization(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return startSafely(context, intent);
    }

    /**
     * Open the user's vendor-specific autostart / background-management page.
     * If the device is not from a known vendor or none of the candidates resolve,
     * falls back to the app's system settings page.
     *
     * @return true if any screen was successfully opened.
     */
    public static boolean openVendorAutostartSettings(Context context) {
        for (ComponentName candidate : autostartCandidates()) {
            Intent intent = new Intent();
            intent.setComponent(candidate);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startSafely(context, intent)) {
                return true;
            }
        }
        return openAppDetailsSettings(context);
    }

    public static boolean openAppDetailsSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startSafely(context, intent);
    }

    /**
     * Whether this device is likely to need a vendor-specific autostart hint.
     * Pure AOSP / Pixel / most western OEMs return false; Chinese OEM ROMs that
     * are known to aggressively kill background apps return true.
     */
    public static boolean needsVendorAutostartHint() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi")
                || manufacturer.contains("huawei") || brand.contains("huawei") || brand.contains("honor")
                || manufacturer.contains("honor")
                || manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("realme")
                || manufacturer.contains("realme")
                || manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")
                || manufacturer.contains("iqoo")
                || manufacturer.contains("oneplus") || brand.contains("oneplus")
                || manufacturer.contains("meizu") || brand.contains("meizu")
                || manufacturer.contains("letv") || brand.contains("letv")
                || manufacturer.contains("smartisan") || brand.contains("smartisan")
                || manufacturer.contains("zte") || brand.contains("zte")
                || manufacturer.contains("samsung") || brand.contains("samsung");
    }

    private static ComponentName[] autostartCandidates() {
        return new ComponentName[] {
                // Xiaomi / Redmi / Poco — MIUI
                new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                // Huawei / Honor — EMUI / MagicOS
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                // Oppo / Realme / OnePlus — ColorOS / OxygenOS / RealmeUI
                new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                new ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"),
                new ComponentName("com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                // Vivo / iQOO — OriginOS / FuntouchOS
                new ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                new ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.safeguard.PurviewTabActivity"),
                new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                // Meizu — Flyme
                new ComponentName("com.meizu.safe",
                        "com.meizu.safe.permission.PermissionMainActivity"),
                // Letv
                new ComponentName("com.letv.android.letvsafe",
                        "com.letv.android.letvsafe.AutobootManageActivity"),
                // Samsung — battery / device care entry point
                new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"),
                new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"),
                // ZTE
                new ComponentName("com.zte.heartyservice",
                        "com.zte.heartyservice.autorun.AppAutoRunManager"),
        };
    }

    private static boolean startSafely(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        if (intent.resolveActivity(pm) == null) {
            return false;
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to start " + intent, e);
            return false;
        }
    }
}
