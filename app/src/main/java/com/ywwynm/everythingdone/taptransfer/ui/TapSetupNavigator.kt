package com.ywwynm.everythingdone.taptransfer.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.taptransfer.TapPrerequisite
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import java.util.UUID

/**
 * 把缺的那一项交给系统去开：权限走系统授权框，NFC 与 Wi-Fi 弹系统的快捷开关面板，位置信息
 * 打开系统设置。应用自己打不开这些开关（官方文档只提供 `Settings.ACTION_NFC_SETTINGS` 这类
 * 入口，决策 2026-09-24）。
 *
 * 权限被永久拒绝之后系统不再弹授权框，这时改为打开应用详情页；用户从那里回来时，调用方用
 * [consumePermissionSettingsReturn] 得知要重新检查。
 *
 * 权限申请用 [ComponentActivity.activityResultRegistry] 临时注册，不要求在 onCreate 里注册；
 * 用完调用 [release]。
 */
class TapSetupNavigator(
    private val activity: ComponentActivity,
    private val onPermissionResult: (granted: Boolean) -> Unit
) {

    /** 发起申请那一刻系统是否还会解释理由（即用户明确拒绝过一次、还能再问）。 */
    private var rationaleBefore = false
    private var launchedAt = 0L

    private val permissionLauncher: ActivityResultLauncher<String> =
        activity.activityResultRegistry.register(
            "tap_transfer_permission_" + UUID.randomUUID(),
            ActivityResultContracts.RequestPermission()
        ) { granted -> onPermissionAnswered(granted) }

    private var awaitingPermissionSettings = false

    fun open(prerequisite: TapPrerequisite) {
        when (prerequisite) {
            TapPrerequisite.PERMISSION -> requestPermission()
            TapPrerequisite.NFC -> startPanel(Settings.Panel.ACTION_NFC, Settings.ACTION_NFC_SETTINGS)
            TapPrerequisite.WIFI -> startPanel(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            TapPrerequisite.LOCATION -> startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), null)
        }
    }

    /** 上一次申请权限是不是把用户送去了应用详情页；是的话清掉这个标记并返回 true。 */
    fun consumePermissionSettingsReturn(): Boolean {
        val was = awaitingPermissionSettings
        awaitingPermissionSettings = false
        return was
    }

    fun release() {
        permissionLauncher.unregister()
    }

    private fun requestPermission() {
        val permission = TapTransfer.requiredPermission()
        val rationale = activity.shouldShowRequestPermissionRationale(permission)
        if (!rationale && hasDeniedBefore(activity)) {
            // 拒绝过、系统又不再解释理由：已被永久拒绝，授权框不会再弹出来
            if (!openAppDetails()) {
                onPermissionResult(false)
            }
            return
        }
        rationaleBefore = rationale
        launchedAt = SystemClock.uptimeMillis()
        permissionLauncher.launch(permission)
    }

    private fun openAppDetails(): Boolean {
        awaitingPermissionSettings = startSafely(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", activity.packageName, null)),
            null
        )
        return awaitingPermissionSettings
    }

    /**
     * 用户拒绝有两种：明确点了「不允许」，或者点了授权框外面把它关掉。后者不算拒绝，下次照常弹框。
     * 系统不直接告诉我们是哪一种，只能看拒绝前后「是否还会解释理由」：拒绝过一次之后为 true，
     * 第二次拒绝（从此不再弹框）之后变回 false，点框外不改变它。
     */
    private fun onPermissionAnswered(granted: Boolean) {
        if (granted) {
            setDeniedBefore(activity, false)
            onPermissionResult(true)
            return
        }
        val rationaleAfter = activity.shouldShowRequestPermissionRationale(TapTransfer.requiredPermission())
        // 授权框根本没弹出来、系统直接拒绝了（快到人来不及操作）：已被永久拒绝，只是本机没记下来
        val autoDenied = !rationaleAfter && SystemClock.uptimeMillis() - launchedAt < AUTO_DENIED_MS
        if (rationaleAfter || rationaleBefore || autoDenied) {
            setDeniedBefore(activity, true)
        }
        if (autoDenied && openAppDetails()) {
            return
        }
        onPermissionResult(false)
    }

    private fun startPanel(panelAction: String, fallbackAction: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startSafely(Intent(panelAction), Intent(fallbackAction))
        } else {
            startSafely(Intent(fallbackAction), null)
        }
    }

    /** 返回是否打开了什么。 */
    private fun startSafely(intent: Intent, fallback: Intent?): Boolean {
        try {
            activity.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // 部分定制系统没有快捷开关面板
            if (fallback != null) {
                try {
                    activity.startActivity(fallback)
                    return true
                } catch (ignored: ActivityNotFoundException) {
                }
            }
        }
        return false
    }

    companion object {
        /** 从发起申请到拿到拒绝不到这么久，说明授权框没有弹出来：它的出场动画都不止这么长。 */
        private const val AUTO_DENIED_MS = 400L

        private const val STATE_PREFS = "tap_transfer_state"

        /** 用户明确拒绝过碰一碰所需的权限，用来区分「永久拒绝」与「从没问过」（两者都不解释理由）。 */
        private const val KEY_PERMISSION_DENIED = "permission_denied_before"

        private fun hasDeniedBefore(context: Context): Boolean =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PERMISSION_DENIED, false)

        private fun setDeniedBefore(context: Context, denied: Boolean) {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PERMISSION_DENIED, denied).apply()
        }

        /** 缺这一项时显示的说明文字。 */
        @JvmStatic
        fun titleOf(activity: Activity, prerequisite: TapPrerequisite): String {
            val res = when (prerequisite) {
                TapPrerequisite.PERMISSION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    R.string.tap_setup_permission_nearby
                } else {
                    R.string.tap_setup_permission_location
                }
                TapPrerequisite.NFC -> R.string.tap_setup_nfc
                TapPrerequisite.WIFI -> R.string.tap_setup_wifi
                TapPrerequisite.LOCATION -> R.string.tap_setup_location
            }
            return activity.getString(res)
        }

        /** 标题下面那行说明：权限是「两台手机都需要允许」，开关是「两台手机都需要打开」。 */
        @JvmStatic
        fun hintOf(activity: Activity, prerequisite: TapPrerequisite): String {
            return activity.getString(
                if (prerequisite == TapPrerequisite.PERMISSION) R.string.tap_setup_hint_permission else R.string.tap_setup_hint
            )
        }

        /** 按钮上的字：权限是「允许」，开关是「去打开」。 */
        @JvmStatic
        fun actionOf(activity: Activity, prerequisite: TapPrerequisite): String {
            return activity.getString(
                if (prerequisite == TapPrerequisite.PERMISSION) R.string.tap_setup_allow else R.string.tap_setup_turn_on
            )
        }
    }
}
