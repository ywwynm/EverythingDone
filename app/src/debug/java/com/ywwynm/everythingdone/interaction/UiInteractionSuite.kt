package com.ywwynm.everythingdone.interaction

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.core.app.ActivityCompat
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ParticleTuningActivity
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.permission.PermissionCallback
import org.json.JSONArray
import org.json.JSONObject

/** 合并真实界面回归与无数据夹具回归；只临时修改动画档位，结束后原样恢复。 */
internal class UiInteractionSuite(
    private val runner: Instrumentation, private val created: List<Activity>, private val result: JSONObject,
    private val fromCase: String? = null
) {
    private var casesStarted = fromCase == null
    private val events = JSONArray().also { result.put("cases", it) }
    private val automation = runner.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    fun run() {
        cancelInput()
        val prefs = runner.targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
        val present = prefs.contains(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION)
        val original = prefs.getInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, 3)
        try {
            for (mode in listOf(0, 3)) {
                check(prefs.edit().putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, mode).commit())
                case("settings-queued-$mode") { settingsQueued() }
                case("settings-reverse-$mode") { settingsReverse() }
                case("settings-multitouch-$mode") { settingsMulti() }
                case("detail-attachment-$mode") { detail() }
                case("child-replacement-popup-$mode") { normalFlows() }
            }
            case("ordinary-multitouch-and-pinch") { gestures() }
            case("launch-failure-and-recreation") { recovery() }
            case("activity-result-continuation") { activityResults() }
            for (mode in listOf(0, 3)) {
                check(prefs.edit().putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, mode).commit())
                case("permission-after-confirm-granted-$mode") { permissionAfterConfirm(true) }
                case("permission-after-confirm-denied-$mode") { permissionAfterConfirm(false) }
            }
        } finally {
            cancelInput()
            val edit = prefs.edit()
            if (present) edit.putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, original)
            else edit.remove(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION)
            check(edit.commit())
        }
    }

    private fun case(name: String, action: () -> Unit) {
        if (!casesStarted) {
            if (name != fromCase) return
            casesStarted = true
        }
        val entry = JSONObject().put("name", name)
        events.put(entry)
        try { action(); entry.put("passed", true) }
        catch (failure: Throwable) {
            entry.put("passed", false)
            val dir = java.io.File(runner.targetContext.getExternalFilesDir(null), "interaction-probe").apply { mkdirs() }
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(dir, "failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input")).use {
                java.io.File(dir, "failure-input.txt").writeBytes(it.readBytes())
            }
            throw AssertionError(name, failure)
        }
    }

    private fun settings(): SettingsActivity = start(SettingsActivity::class.java)
    private fun digit(activity: SettingsActivity) = activity.findViewById<View>(R.id.rl_doing_digit_style_as_bt)
    private fun particle(activity: SettingsActivity) = activity.findViewById<View>(R.id.rl_particle_tuning_as_bt)

    private fun settingsQueued() {
        val activity = settings()
        val before = main { created.count { it is ParticleTuningActivity } }
        main { digit(activity).performClick(); particle(activity).performClick() }
        readyDialog(activity)
        main {
            check(dialogs(activity).size == 1)
            check(created.count { it is ParticleTuningActivity } == before)
            noSplit(activity.window.decorView)
            noSplit(dialogs(activity).single().requireDialog().window!!.decorView)
        }
        closeDialogs(activity)
        main { particle(activity).performClick() }
        until("页面启动") { created.count { it is ParticleTuningActivity } == before + 1 }
        main { created.last { it is ParticleTuningActivity }.finish() }
        rootReady(activity)
        main { digit(activity).performClick() }
        readyDialog(activity)
        close(activity)
    }

    private fun settingsReverse() {
        val activity = settings()
        val before = main { created.count { it is ParticleTuningActivity } }
        main { particle(activity).performClick(); digit(activity).performClick(); particle(activity).performClick() }
        until("反向页面启动") { created.count { it is ParticleTuningActivity } == before + 1 }
        main { check(dialogs(activity).isEmpty()); created.last { it is ParticleTuningActivity }.finish() }
        rootReady(activity)
        close(activity)
    }

    private fun settingsMulti() {
        val activity = settings()
        val second = particle(activity)
        main { second.requestRectangleOnScreen(Rect(0, 0, second.width, second.height), true) }
        runner.waitForIdleSync()
        val before = main { created.count { it is ParticleTuningActivity } }
        twoFingerTap(bounds(digit(activity)), bounds(second))
        readyDialog(activity)
        main { check(dialogs(activity).size == 1); check(created.count { it is ParticleTuningActivity } == before) }
        close(activity)
    }

    private fun detail() {
        val activity = runner.startActivitySync(DetailActivity.getOpenIntentForCreate(
            runner.targetContext, null, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        rootReady(activity)
        until("详情工具栏") { activity.findViewById<View>(R.id.act_add_attachment) != null }
        val attachment = activity.findViewById<View>(R.id.act_add_attachment)
        val color = activity.findViewById<View>(R.id.act_change_color)
        val attachmentBounds = bounds(attachment)
        twoFingerTap(attachmentBounds, bounds(color))
        // 凝聚期间继续点击原位置；alpha=0 不能让这些点击再次创建弹窗。
        repeat(3) { tap(attachmentBounds) }
        readyDialog(activity)
        main {
            check(dialogs(activity).size == 1)
            check(dialogs(activity).single().javaClass.simpleName == "AddAttachmentDialogFragment")
        }
        closeDialogs(activity)
        tap(bounds(color))
        readyDialog(activity)
        main { check(dialogs(activity).single().javaClass.simpleName == "ThingBackgroundEditorBottomSheet") }
        closeDialogs(activity)
        // 同一主线程的两个独立菜单回调，验证不依赖多指策略。
        main { attachment.performClick(); color.performClick(); attachment.performClick() }
        readyDialog(activity)
        main { check(dialogs(activity).size == 1) }
        close(activity)
    }

    private fun normalFlows() {
        val activity = start(InteractionProbeActivity::class.java)
        main { InteractionParentDialog().show(activity.supportFragmentManager, "parent") }
        readyDialog(activity)
        tap(bounds(label(dialogsMain(activity).single().requireView(), "child")))
        until("子弹窗") { dialogs(activity).size == 2 && isReady(dialogs(activity).last().requireDialog().window!!.decorView) }
        main { dialogs(activity).last().dismiss() }
        until("回到父弹窗") { dialogs(activity).size == 1 && isReady(dialogs(activity).single().requireDialog().window!!.decorView) }
        tap(bounds(label(dialogsMain(activity).single().requireView(), "replace")))
        until("替换弹窗") { dialogs(activity).size == 1 && dialogs(activity).single().tag == "replacement" &&
            isReady(dialogs(activity).single().requireDialog().window!!.decorView) }
        closeDialogs(activity)
        main {
            activity.popup.show()
            InteractionProbeActivity.alert("competing").show(activity.supportFragmentManager, "competing")
        }
        until("菜单出现") { activity.popup.isShowing() }
        main { check(dialogs(activity).isEmpty()) }
        // 生产 PopupWindow 的“先关闭，再打开后继弹窗”。
        val rootField = com.ywwynm.everythingdone.views.pickers.PopupPicker::class.java
            .getDeclaredField("mContentView").apply { isAccessible = true }
        val popupView = main { rootField.get(activity.popup) as View }
        until("菜单可交互") { isReady(popupView) }
        // 某些系统给无裁剪 PopupWindow 报告虚拟窗口坐标；此项同时覆盖无障碍点击路径。
        main { check(popupView.findViewById<View>(R.id.bt_pick_color_from_camera)
            .performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)) }
        readyDialog(activity)
        main { check(dialogs(activity).single().tag == "popup-next") }
        closeDialogs(activity)
        main { InteractionParentDialog().show(activity.supportFragmentManager, "parent-again") }
        readyDialog(activity)
        val before = main { created.count { it is SettingsActivity } }
        tap(bounds(label(dialogsMain(activity).single().requireView(), "page")))
        until("弹窗内页面启动") { created.count { it is SettingsActivity } == before + 1 }
        main { created.last { it is SettingsActivity }.finish() }
        readyDialog(activity)
        close(activity)
    }

    private fun gestures() {
        val activity = start(InteractionProbeActivity::class.java)
        main { noSplit(activity.window.decorView) }
        twoFingerTap(bounds(label(activity.content, "first")), bounds(label(activity.content, "second")))
        until("普通按钮点击") { activity.ordinaryFirst == 1 }
        main { check(activity.ordinarySecond == 0) }
        val area = bounds(activity.photo)
        val cx = area.exactCenterX(); val cy = area.exactCenterY()
        val start = area.width() * .10f; val end = area.width() * .30f
        gesture(floatArrayOf(cx - start, cy, cx + start, cy)) { send ->
            repeat(10) { index ->
                val gap = start + (end - start) * (index + 1) / 10f
                send(MotionEvent.ACTION_MOVE, floatArrayOf(cx - gap, cy, cx + gap, cy))
                SystemClock.sleep(16)
            }
        }
        main { check(activity.photo.scale > 1.1f) { "PhotoView 缩放未收到双指输入：${activity.photo.scale}" } }
        close(activity)
    }

    private fun recovery() {
        val activity = start(InteractionProbeActivity::class.java)
        main {
            try { activity.startActivity(Intent("com.ywwynm.everythingdone.MISSING_TEST_TARGET")); error("应抛出缺失目标") }
            catch (_: ActivityNotFoundException) {}
            InteractionParentDialog().show(activity.supportFragmentManager, "restore")
        }
        readyDialog(activity)
        val before = main { created.count { it is InteractionProbeActivity } }
        main { activity.recreate() }
        until("配置重建") { created.count { it is InteractionProbeActivity } == before + 1 }
        val restored = main { created.last { it is InteractionProbeActivity } as InteractionProbeActivity }
        readyDialog(restored)
        main { check(dialogs(restored).size == 1) }
        closeDialogs(restored)
        tap(bounds(label(restored.content, "first")))
        until("重建后输入恢复") { restored.ordinaryFirst == 1 }
        close(restored)
    }

    private fun activityResults() {
        val activity = start(InteractionProbeActivity::class.java)
        main { activity.startActivityForResult(Intent(activity, InteractionResultActivity::class.java), 73) }
        readyDialog(activity)
        main { check(dialogs(activity).single().tag == "legacy-result") }
        closeDialogs(activity)
        main { activity.resultLauncher.launch(Intent(activity, InteractionResultActivity::class.java)) }
        readyDialog(activity)
        main { check(dialogs(activity).single().tag == "registry-result") }
        close(activity)
    }

    /** 真实确认框及权限封装；只接管系统授权结果，不改变设备权限或启动下载。 */
    private fun permissionAfterConfirm(allow: Boolean) {
        val activity = settings()
        val permission = "com.ywwynm.everythingdone.permission.INTERACTION_TEST"
        val requestCode = 6381
        val previousDelegate = ActivityCompat.getPermissionCompatDelegate()
        var requests = 0
        var granted = 0
        var denied = 0
        val callback = object : PermissionCallback {
            override fun onGranted() { granted++; showResult() }
            override fun onDenied() { denied++; showResult() }
            private fun showResult() {
                InteractionProbeActivity.alert("permission-result")
                    .show(activity.supportFragmentManager, "permission-result")
            }
        }
        result.put("permission_result_source", "ActivityCompat.PermissionCompatDelegate")
        try {
            main {
                ActivityCompat.setPermissionCompatDelegate(object : ActivityCompat.PermissionCompatDelegate {
                    override fun requestPermissions(host: Activity, permissions: Array<out String>, code: Int): Boolean {
                        check(host === activity && permissions.contentEquals(arrayOf(permission)) && code == requestCode)
                        requests++
                        return true
                    }
                    override fun onActivityResult(host: Activity, code: Int, result: Int, data: Intent?): Boolean = false
                })
                InteractionProbeActivity.alert("permission-confirm").apply {
                    setConfirmListener(object : AlertDialogFragment.ConfirmListener {
                        override fun onConfirm() {
                            activity.doWithPermissionChecked(callback, requestCode, permission)
                        }
                    })
                }.show(activity.supportFragmentManager, "permission-confirm")
            }
            readyDialog(activity)
            tap(bounds(main { dialogs(activity).single().requireView().findViewById(R.id.tv_confirm_as_bt_alert) }))
            until("确认框关闭") { dialogs(activity).isEmpty() }
            main {
                check(requests == 1)
                // 原确认框已关闭，等待结果期间仍应拒绝新的请求。
                activity.doWithPermissionChecked(callback, requestCode, permission)
                check(requests == 1) { "权限等待期间受理了重复请求" }
                check(!InteractionProbeActivity.alert("permission-competing")
                    .tryShow(activity.supportFragmentManager, "permission-competing"))
                activity.onRequestPermissionsResult(requestCode, arrayOf(permission), intArrayOf(
                    if (allow) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                ))
                check(granted == (if (allow) 1 else 0) && denied == (if (allow) 0 else 1)) {
                    "确认框关闭后权限回调丢失：allow=$allow granted=$granted denied=$denied"
                }
            }
            readyDialog(activity)
            main { check(dialogs(activity).single().tag == "permission-result") }
            closeDialogs(activity)
        } finally {
            main { ActivityCompat.setPermissionCompatDelegate(previousDelegate) }
            close(activity)
        }
    }

    private fun <T : FragmentActivity> start(type: Class<T>): T {
        val activity = type.cast(runner.startActivitySync(Intent(runner.targetContext, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))!!
        rootReady(activity)
        return activity
    }
    private fun dialogs(activity: FragmentActivity) = activity.supportFragmentManager.fragments
        .filterIsInstance<BaseDialogFragment>().filter { !it.isRemoving && it.dialog?.isShowing == true }
    private fun dialogsMain(activity: FragmentActivity) = main { dialogs(activity) }
    private fun isReady(view: View) = view.isAttachedToWindow &&
        view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    private fun rootReady(activity: Activity) {
        until("页面可交互 ${activity.javaClass.simpleName}") {
            activity.hasWindowFocus() && isReady(activity.window.decorView)
        }
        automation.waitForIdle(100, 3000)
    }
    private fun readyDialog(activity: FragmentActivity) = until("弹窗可交互") {
        dialogs(activity).lastOrNull()?.dialog?.window?.let { isReady(it.decorView) && it.attributes.alpha > 0f } == true
    }
    private fun closeDialogs(activity: FragmentActivity) {
        main { dialogs(activity).asReversed().forEach { it.dismiss() } }
        until("弹窗完全关闭") { dialogs(activity).isEmpty() && isReady(activity.window.decorView) }
    }
    private fun close(activity: FragmentActivity) {
        closeDialogs(activity)
        main { activity.finish() }
        until("页面销毁") { activity.isDestroyed }
    }
    private fun bounds(view: View): Rect = main {
        Rect().also {
            check(view.getLocalVisibleRect(it) && it.width() > 0 && it.height() > 0)
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            it.offset(origin[0], origin[1])
            val screen = Rect()
            view.getWindowVisibleDisplayFrame(screen)
            check(it.intersect(screen)) { "控件不在屏幕内：rect=$it screen=$screen" }
        }
    }
    private fun label(root: View, text: String): View = main {
        allViews(root).first { it is TextView && it.text.toString() == text }
    }
    private fun allViews(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else emptyList()
    private fun noSplit(root: View) {
        val offenders = allViews(root).filterIsInstance<ViewGroup>().filter { it.isMotionEventSplittingEnabled }
        check(offenders.isEmpty()) { "仍在拆分多指：${offenders.map { it.javaClass.simpleName }}" }
    }
    private fun until(name: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(20)
        }
        val state = main {
            val field = UiInteractions::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            val sessions = field.get(UiInteractions) as Map<*, *>
            sessions.entries.joinToString { (host, value) ->
                val session = value as UiInteractions.Session
                val layers = UiTransitionState::class.java.getDeclaredField("layers").apply { isAccessible = true }.get(session.state)
                "${host?.javaClass?.simpleName}: layers=$layers roots=${session.roots.map { (_, pair) ->
                    "original=${pair.second},current=${pair.first.importantForAccessibility},attached=${pair.first.isAttachedToWindow}"
                }}"
            }
        }
        error("等待超时：$name；$state")
    }
    private fun <T> main(action: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        runner.runOnMainSync { try { value = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun tap(rect: Rect) {
        val points = floatArrayOf(rect.exactCenterX(), rect.exactCenterY())
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, points)
        inject(down, MotionEvent.ACTION_UP, points)
    }
    private fun twoFingerTap(first: Rect, second: Rect) = gesture(floatArrayOf(
        first.exactCenterX(), first.exactCenterY(), second.exactCenterX(), second.exactCenterY())) {}
    private fun gesture(initial: FloatArray, body: ((Int, FloatArray) -> Unit) -> Unit) {
        val down = SystemClock.uptimeMillis()
        var points = initial
        inject(down, MotionEvent.ACTION_DOWN, points.copyOf(2))
        inject(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
        body { action, coords -> points = coords; inject(down, action, coords) }
        inject(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
        inject(down, MotionEvent.ACTION_UP, points.copyOf(2))
    }
    private fun inject(down: Long, action: Int, points: FloatArray) {
        val count = points.size / 2
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply {
            x = points[index * 2]; y = points[index * 2 + 1]; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count,
            properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            check(automation.injectInputEvent(event, true)) { "输入注入失败：action=$action points=${points.toList()}" }
        } finally { event.recycle() }
    }

    private fun cancelInput() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { automation.injectInputEvent(event, true) } finally { event.recycle() }
    }
}
