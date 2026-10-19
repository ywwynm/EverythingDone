package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ParticleTuningActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.roundToInt

/** 真实关闭链、首页外观面板和设置预览；只允许独立测试包，不接触正式包记事。 */
class ParticleDefaultDirectionProbe : Instrumentation() {
    private var args = Bundle()
    private val rows = JSONArray()
    private lateinit var output: File
    private val pictured = mutableSetOf<String>()
    private val activities = mutableListOf<Activity>()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun await(label: String, timeout: Long = 18000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < end) { "等待超时：$label" }
            val root = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
            if (root?.findAccessibilityNodeInfosByText("Android 应用兼容性")?.isNotEmpty() == true)
                root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(24)
        }
    }
    private fun <T : Activity> launch(type: Class<T>): T {
        val activity = type.cast(startActivitySync(Intent(targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))!!
        activities += activity
        main { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        await("页面获得焦点") { activity.hasWindowFocus() }
        return activity
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        require(targetContext.packageName == "com.ywwynm.everythingdone.directionvalidation") {
            "拒绝在用户应用中创建或变更测试记事"
        }
        val run = args.getString("run_id") ?: "directions"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(targetContext.getExternalFilesDir(null), "default-directions/$run").apply { mkdirs() }
        val result = JSONObject().put("package", targetContext.packageName).put("style", args.getString("style"))
        try {
            main {
                ParticleTuning.setStyle(targetContext, ParticleStyle.of(args.getString("style")))
                ThingAnimationPreferences.preferences(targetContext).edit()
                    .putInt(ThingAnimationPreferences.SWIPE_KEY, 1)
                    .putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, 3).commit()
                targetContext.getSharedPreferences(Def.Meta.META_DATA_NAME, 0).edit().apply {
                    listOf(Def.Meta.KEY_1_0_4_TO_1_0_5, Def.Meta.KEY_1_2_7_TO_1_3_0,
                        Def.Meta.KEY_1_3_0_TO_1_3_1, Def.Meta.KEY_1_3_3_TO_1_3_4).forEach { putBoolean(it, true) }
                }.commit()
                File(targetContext.filesDir, Def.Meta.FEEDBACK_ERROR_FILE_NAME).delete()
            }
            val host = launch(ParticleDirectionFixtureActivity::class.java)
            for (route in listOf("back", "code", "async", "center")) randomRounds("dialog-$route") {
                val dialog = main { ParticleDirectionFixtureDialog().also { it.show(host.supportFragmentManager, "direction") } }
                await("弹窗出现") { dialog.dialog?.window?.decorView?.hasWindowFocus() == true &&
                    ParticleDismissOverlay.activeIn(host).isEmpty() && dialog.dialog?.window?.attributes?.alpha == 1f }
                observe(host, "dialog-$route", setOf(90, 135)) {
                    when (route) {
                        "back" -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        "center" -> tapDialog(dialog, .5f, .5f)
                        "async" -> main { host.window.decorView.postDelayed({ dialog.dismiss() }, 360) }
                        else -> main { dialog.dismiss() }
                    }
                }.also { await("弹窗关闭") { dialog.dialog?.isShowing != true } }
            }
            val touched = main { ParticleDirectionFixtureDialog().also { it.show(host.supportFragmentManager, "touch") } }
            await("触点弹窗出现") { touched.dialog?.window?.decorView?.hasWindowFocus() == true && ParticleDismissOverlay.activeIn(host).isEmpty() }
            observe(host, "dialog-real-touch", setOf(315)) { tapDialog(touched, .75f, .75f) }
            randomRounds("panel-back") {
                main { host.addPanel() }
                await("面板布局") { host.panel?.isLaidOut == true }
                observe(host, "panel-back", setOf(90, 135)) { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
            }
            main { host.finish() }
            homePanels()
            tuning()
            result.put("ok", true)
        } catch (error: Throwable) {
            result.put("ok", false).put("error", error.stackTraceToString())
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                File(output, "failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        finally { main { activities.filterNot { it.isFinishing }.forEach { it.finish() } } }
        File(output, "result.json").writeText(result.put("animations", rows).toString(2))
        finish(if (result.optBoolean("ok")) 0 else 1, Bundle().apply { putString("result", result.toString()) })
    }

    private fun randomRounds(label: String, play: () -> Int) {
        val seen = mutableSetOf<Int>()
        var count = 0
        while (count < 4 || seen.size < 2) {
            check(count++ < 16) { "$label 没有覆盖两个默认方向：$seen" }
            seen += play()
        }
    }

    private fun observe(activity: Activity, label: String, allowed: Set<Int>, trigger: () -> Unit): Int {
        var row: JSONObject? = null
        var released = false
        var angle = -1
        var seenAt = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (row == null) ParticleDismissOverlay.activeIn(activity).firstOrNull { it.isDisappearance }?.let { overlay ->
                    val spec = field(overlay, "spec") as ParticleDismissSpec
                    var centerX = spec.originXPx + spec.snapshot.width / 2f
                    var centerY = spec.originYPx + spec.snapshot.height / 2f
                    val snapshotAngle = (Math.toDegrees(atan2(spec.virtualTouchYPx - centerY,
                        spec.virtualTouchXPx - centerX).toDouble()) + 360) % 360
                    // 带外扩阴影的面板按本体中心定向，快照中心会因投影裁切产生亚角度偏移。
                    if (label.startsWith("home-") || label.startsWith("preview-")) overlay.nativeCardSource?.let { source ->
                        val sourceAt = IntArray(2).also { source.getLocationOnScreen(it) }
                        val hostAt = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
                        centerX = sourceAt[0] - hostAt[0] + source.width / 2f
                        centerY = sourceAt[1] - hostAt[1] + source.height / 2f
                    }
                    val dx = spec.virtualTouchXPx - centerX
                    val dy = spec.virtualTouchYPx - centerY
                    val degrees = (Math.toDegrees(atan2(-dy, dx).toDouble()) + 360) % 360
                    angle = degrees.roundToInt() % 360
                    seenAt = SystemClock.uptimeMillis()
                    row = JSONObject().put("case", label).put("angle", degrees)
                        .put("snapshotAngle", (360 - snapshotAngle) % 360).put("style", spec.style.key)
                        .put("width", spec.snapshot.width).put("height", spec.snapshot.height)
                    rows.put(row)
                    overlay.afterRelease { released = true; row?.put("released", true)
                        ?.put("lifetimeMs", SystemClock.uptimeMillis() - seenAt) }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        main { Choreographer.getInstance().postFrameCallback(callback) }
        try {
            trigger()
            val end = SystemClock.uptimeMillis() + 20000
            while (!main { row != null && released && ParticleDismissOverlay.activeIn(activity).isEmpty() &&
                (activity !is ParticleTuningActivity || if (field(activity, "previewingCard") == true) {
                    field(activity, "returning") == false && field(activity, "condensing") == false &&
                        activity.findViewById<View>(R.id.cv_particle_tuning_card)?.alpha == 1f
                } else field(activity, "dialogStepsLeft") == 0 && field(activity, "dialogBusy") == false && field(activity, "dialogShowing") == true) }) {
                check(SystemClock.uptimeMillis() < end) { "$label 未完整播放并清理" }
                if (main { row != null && SystemClock.uptimeMillis() - seenAt > 300 } && pictured.add(label)) {
                    getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                        File(output, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                SystemClock.sleep(16)
            }
            check(angle in allowed) { "$label 方向 $angle 不在 $allowed" }
            File(output, "progress.json").writeText(rows.toString())
            return angle
        } finally { main { Choreographer.getInstance().removeFrameCallback(callback) } }
    }

    private fun tapDialog(dialog: ParticleDirectionFixtureDialog, x: Float, y: Float) {
        val point = main {
            val view = checkNotNull(dialog.dialog?.window?.decorView)
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            val span = minOf(view.width, view.height)
            floatArrayOf(at[0] + view.width / 2f + span * (x - .5f),
                at[1] + view.height / 2f + span * (y - .5f))
        }
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event, true)) }
            finally { event.recycle() }
            SystemClock.sleep(48)
        }
    }

    private fun homePanels() {
        main {
            DBHelper(targetContext).use { db ->
                db.writableDatabase.execSQL("DELETE FROM things WHERE id BETWEEN 8000001 AND 8000032 OR id BETWEEN 8100001 AND 8100003")
                db.writableDatabase.execSQL("DELETE FROM thing_folders WHERE id IN (9000000,9000001)")
            }
            check(!ThingDAO.getInstance(targetContext)!!.create(Thing(8_000_001, Thing.NOTE, 0xff80bcb8.toInt(), 1).apply {
                title = "方向验证虚构记事"; content = "仅独立测试包使用，不涉及真实记事"
            }, false, false))
            check(ThingFolderDAO.getInstance(targetContext)!!.create(ThingFolder(9_000_001, "方向验证虚构文件夹", 0xffb7a8ce.toInt(), 0)) > 0)
            check(!ThingDAO.getInstance(targetContext)!!.create(Thing(8_000_002, Thing.NOTE, 0xffb7a8ce.toInt(), 2).apply {
                title = "文件夹内虚构记事"; folderId = 9_000_001
            }, false, false))
            App.getApp()!!.setStatus(Def.ThingStatus.UNDERWAY, true)
        }
        val home = launch(ThingsActivity::class.java)
        val list = main { home.findViewById<RecyclerView>(R.id.rv_things) }
        for ((name, id) in listOf("thing" to 8_000_001L, "folder" to -9_000_001L)) randomRounds("home-$name-back") {
            await("首页测试卡片") { (0 until list.childCount).any { list.getChildAt(it).getTag(R.id.tag_thing_card_bound_stable_id) == id } && list.itemAnimator?.isRunning != true }
            // 先建立确定的单选状态，再经过正式菜单打开面板；不依赖长按进入拖拽后的异步切换。
            main {
                val manager = field(home, "mThingManager") as ThingManager
                val mode = field(home, "mModeManager") as ModeManager
                val position = manager.getListPositionForStableId(id)
                mode.toSelectingMode(position)
                manager.setListEntrySelected(position, true)
                mode.updateSelectedCount(); mode.updateMenuItems()
                list.adapter!!.notifyDataSetChanged()
            }
            await("外观菜单") { home.findViewById<Toolbar>(R.id.contextual_toolbar).menu.findItem(R.id.act_customize_card_appearance)?.isVisible == true }
            main { home.findViewById<Toolbar>(R.id.contextual_toolbar).menu.performIdentifierAction(R.id.act_customize_card_appearance, 0) }
            await("外观面板") { home.findViewById<View>(R.id.bt_cancel_thing_card_appearance)?.isShown == true && ParticleDismissOverlay.activeIn(home).isEmpty() }
            val angle = observe(home, "home-$name-back", setOf(90, 135)) { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
            await("外观面板关闭") { home.findViewById<View>(R.id.bt_cancel_thing_card_appearance)?.isShown != true }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("退出选择") { !home.findViewById<View>(R.id.rl_contextual_toolbar).isShown }
            angle
        }
        main { home.finish() }
    }

    private fun tuning() {
        val page = launch(ParticleTuningActivity::class.java)
        observe(page, "preview-default", setOf(90, 135)) { main { page.findViewById<View>(R.id.tv_particle_tuning_mode_dialog).performClick() } }
        randomRounds("preview-default") {
            observe(page, "preview-default", setOf(90, 135)) { main { (field(page, "dialogConfirm") as View).performClick() } }
        }
        // 当前随机方向也可以被用户手动锁定，不能因为数值相同而忽略这次选择。
        val fixed = main { (field(page, "dialogDirection") as Float).roundToInt() }
        main { page.findViewById<View>(R.id.iv_particle_tuning_direction).performClick() }
        await("方向弹层") { field(page, "directionPopup") != null }
        main { val popup = field(page, "directionPopup") as android.widget.PopupWindow
            popup.contentView.findViewById<View>(if (fixed == 90) R.id.iv_particle_direction_t else R.id.iv_particle_direction_lt).performClick() }
        repeat(3) { observe(page, "preview-manual-same", setOf(fixed)) { main { (field(page, "dialogConfirm") as View).performClick() } } }
        main { page.findViewById<View>(R.id.iv_particle_tuning_direction).performClick() }
        observe(page, "preview-manual-right", setOf(0)) { main {
            (field(page, "directionPopup") as android.widget.PopupWindow).contentView.findViewById<View>(R.id.iv_particle_direction_r).performClick()
        } }
        main { page.finish() }
        val reopened = launch(ParticleTuningActivity::class.java)
        observe(reopened, "preview-reenter-default", setOf(90, 135)) { main { reopened.findViewById<View>(R.id.tv_particle_tuning_mode_dialog).performClick() } }
        main { reopened.findViewById<View>(R.id.tv_particle_tuning_mode_card).performClick() }
        for (right in listOf(false, true)) {
            await("记事预览静止") { reopened.findViewById<View>(R.id.cv_particle_tuning_card)?.isShown == true &&
                field(reopened, "returning") == false && field(reopened, "condensing") == false && ParticleDismissOverlay.activeIn(reopened).isEmpty() }
            observe(reopened, if (right) "swipe-right" else "swipe-left", setOf(if (right) 0 else 180)) {
                val bounds = main { android.graphics.Rect().also { check(reopened.findViewById<View>(R.id.cv_particle_tuning_card).getGlobalVisibleRect(it)) } }
                val from = bounds.left + bounds.width() * if (right) .16f else .84f
                val distance = bounds.width() * if (right) .70f else -.70f
                val start = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { check(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, from)
                for (step in 1..36) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, from + distance * step / 36f) }
                send(MotionEvent.ACTION_UP, from + distance)
            }
        }
        main { reopened.finish() }
    }
}

/** 固定测试素材，所有关闭都经过正式 BaseDialogFragment。 */
class ParticleDirectionFixtureDialog : BaseDialogFragment() {
    override fun getLayoutResource() = 0
    override fun getDialogWindowWidthPx() = (240 * resources.displayMetrics.density).toInt()
    override fun createDialogContent(inflater: LayoutInflater, container: ViewGroup?): View = TextView(inflater.context).apply {
        val side = (240 * resources.displayMetrics.density).toInt()
        layoutParams = ViewGroup.LayoutParams(side, side)
        text = "方向验证\n固定测试素材"; textSize = 25f; gravity = Gravity.CENTER
        setTextColor(Color.BLACK); setBackgroundColor(0xffd2e4d5.toInt())
        setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_UP) dismiss(); true }
    }
}

class ParticleDirectionFixtureActivity : AppCompatActivity() {
    var panel: View? = null
    private lateinit var root: FrameLayout
    private val animator by lazy { ParticlePanelAnimator(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(0xff354858.toInt()) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = panel ?: return
                animator.dismissFromBack()
                animator.dismiss(view) { root.removeView(view); panel = null }
            }
        })
    }
    fun addPanel() {
        val side = (240 * resources.displayMetrics.density).toInt()
        panel = TextView(this).apply {
            text = "面板方向验证"; textSize = 25f; gravity = Gravity.CENTER
            setTextColor(Color.BLACK); setBackgroundColor(0xffe8cba0.toInt())
        }
        root.addView(panel, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
    }
}
