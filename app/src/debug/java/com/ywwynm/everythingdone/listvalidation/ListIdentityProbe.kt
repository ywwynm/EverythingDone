package com.ywwynm.everythingdone.listvalidation

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.thingfile.ThingFileImporter
import com.ywwynm.everythingdone.thingfile.ThingFileReader
import com.ywwynm.everythingdone.thingfile.ThingFileWriter
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissOverlay
import com.ywwynm.everythingdone.views.particledismiss.ParticleStyle
import com.ywwynm.everythingdone.views.particledismiss.ParticleTuning
import com.ywwynm.everythingdone.views.particledismiss.ThingListRemovalAnimator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 只在独立包中重放用户报告的连续操作；所有记事与文件均为虚构夹具。 */
class ListIdentityProbe : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var activity: ThingsActivity
    private lateinit var list: RecyclerView
    private lateinit var manager: ThingManager
    private lateinit var mode: ModeManager
    private lateinit var automation: UiAutomation
    private lateinit var output: File
    private val steps = JSONArray()
    private val mismatches = JSONArray()

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun waitFor(label: String, timeout: Long = 20000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            val root = automation.rootInActiveWindow
            if (root?.findAccessibilityNodeInfosByText("Android 应用兼容性")?.isNotEmpty() == true)
                root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            val permissionPackage = root?.packageName?.toString().orEmpty()
            if (args.getString("scenario") in listOf("video", "complex") && permissionPackage.endsWith("permissioncontroller")) {
                for (id in listOf("permission_allow_all_button", "permission_allow_button", "permission_allow_foreground_only_button"))
                    root?.findAccessibilityNodeInfosByViewId("$permissionPackage:id/$id")?.firstOrNull()
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(32)
        }
        error("等待超时：$label")
    }
    private fun idle() {
        waitFor("动画及布局结束") {
            !list.isComputingLayout && !list.isLayoutRequested && list.itemAnimator?.isRunning != true &&
                (field(activity, "mListRemovalAnimator") as? ThingListRemovalAnimator)?.isRunning != true &&
                ParticleDismissOverlay.activeIn(activity).isEmpty() &&
                activity.supportFragmentManager.fragments.none { it is AlertDialogFragment && it.dialog?.isShowing == true }
        }
        waitForIdleSync()
    }
    private fun press(rect: Rect, duration: Long = 64) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true)); event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(duration)
        }
    }
    private fun screenshot(label: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(output, "$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun graphicsMemory(label: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "dumpsys gfxinfo com.ywwynm.everythingdone.batchvalidation")).use { input ->
            File(output, "gfx-$label.txt").outputStream().use(input::copyTo)
        }
    }
    private fun snapshot(label: String) {
        main {
            val rows = JSONArray()
            for (i in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(i))
                val view = holder.itemView
                val bound = view.getTag(R.id.tag_thing_card_bound_stable_id) as? Long
                val position = holder.bindingAdapterPosition
                val entry = manager.getThingListEntry(position)
                val row = JSONObject().put("bound", bound).put("position", position).put("model", entry?.stableId)
                    .put("title", view.findViewById<TextView>(R.id.tv_thing_title)?.text)
                    .put("view", System.identityHashCode(view)).put("alpha", view.alpha).put("visible", view.visibility)
                rows.put(row)
                if (position >= 0 && bound != entry?.stableId) mismatches.put(JSONObject(row.toString()).put("step", label))
            }
            steps.put(JSONObject().put("step", label).put("mode", mode.getCurrentMode()).put("rows", rows)
                .put("selected", JSONArray(manager.getSelectedThings().orEmpty().mapNotNull { it?.id })))
        }
        File(output, "steps.json").writeText(steps.toString(2))
    }
    private fun select(id: Long, label: String) {
        var position = -1
        main { position = manager.getListPositionForThingId(id); check(position > 0) }
        if (args.getString("actual_scroll") == "true") {
            for (attempt in 0..48) {
                var visible = false
                val bounds = Rect()
                var direction = -1
                main {
                    val card = list.findViewHolderForAdapterPosition(position)?.itemView
                    visible = card?.isShown == true && card.getGlobalVisibleRect(bounds) &&
                        bounds.height() > 180 && bounds.bottom > list.height / 3
                    val positions = (0 until list.childCount).map { list.getChildAdapterPosition(list.getChildAt(it)) }.filter { it >= 0 }
                    direction = if (position < (positions.minOrNull() ?: 0)) 1 else -1
                    list.getGlobalVisibleRect(bounds)
                }
                if (visible) break
                check(attempt < 48) { "实际滚动未找到目标" }
                val down = SystemClock.uptimeMillis()
                val start = bounds.top + bounds.height() * if (direction < 0) .78f else .32f
                val distance = bounds.height() * .42f * direction
                for (step in 0..25) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        if (step == 0) MotionEvent.ACTION_DOWN else if (step == 25) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                        bounds.left + bounds.width() * .3f, start + distance * minOf(step, 24) / 24f, 0)
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    check(automation.injectInputEvent(event, true)); event.recycle()
                    SystemClock.sleep(16)
                }
                waitFor("手指滚动停止") { list.scrollState == RecyclerView.SCROLL_STATE_IDLE }
            }
        } else main { (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, list.height / 3) }
        idle()
        val rect = Rect()
        waitFor("目标卡片 $id") {
            list.findViewHolderForAdapterPosition(position)?.itemView?.let {
                it.isShown && it.alpha > .99f && it.getGlobalVisibleRect(rect) && rect.height() > 20 &&
                    (it.animation == null || it.animation.hasEnded())
            } == true
        }
        snapshot("$label-before")
        press(rect, 560)
        waitFor("长按选中") { mode.getCurrentMode() == ModeManager.SELECTING }
        idle()
        snapshot("$label-selected")
        screenshot("$label-selected")
    }
    private fun action(menuId: Int, selection: Boolean) {
        main {
            val toolbar = if (selection) activity.findViewById<Toolbar>(R.id.contextual_toolbar)
                else field(activity, "mActionbar") as Toolbar
            check(toolbar.menu.findItem(menuId)?.isVisible == true) { "菜单不可用 $menuId" }
            toolbar.menu.performIdentifierAction(menuId, 0)
        }
        val settled = BaseDialogFragment::class.java.getDeclaredField("appearanceSettled").apply { isAccessible = true }
        val rect = Rect()
        waitFor("确认弹窗") {
            activity.supportFragmentManager.fragments.filterIsInstance<AlertDialogFragment>().any {
                val view = it.dialog?.findViewById<View>(R.id.tv_confirm_as_bt_alert)
                val ready = view?.isShown == true && settled.getBoolean(it) &&
                    it.dialog?.window?.decorView?.hasWindowFocus() == true
                if (ready) {
                    val at = IntArray(2); view!!.getLocationOnScreen(at)
                    rect.set(at[0], at[1], at[0] + view.width, at[1] + view.height)
                }
                ready
            } && ParticleDismissOverlay.activeIn(activity).isEmpty()
        }
        press(rect)
        waitFor("退出选择") { mode.getCurrentMode() == ModeManager.NORMAL }
        idle()
    }
    private fun status(status: Int) {
        main {
            val drawer = field(activity, "mDrawer")!!
            @Suppress("UNCHECKED_CAST")
            val listener = field(drawer, "statusFilterChangeListener") as (Int) -> Unit
            listener(status)
        }
        idle()
        snapshot("status-$status")
    }
    override fun onStart() {
        require(targetContext.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val run = args.getString("run_id") ?: "identity"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(targetContext.getExternalFilesDir(null), "list-identity/$run").apply { mkdirs() }
        automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val result = JSONObject().put("run", run).put("steps", steps).put("mismatches", mismatches)
        try {
            if (args.getString("scenario") == "gif-lifecycle") {
                result.put("gifLifecycle", GifLifecycleAudit.run(this, output, ::main)).put("ok", true)
                return
            }
            if (args.getString("scenario") == "crop-reuse") {
                result.put("cropReuse", MediaMemoryAudit.cropReuse(targetContext, output)).put("ok", true)
                return
            }
            var a = 0L
            val videoScenario = args.getString("scenario") == "video"
            val complexScenario = args.getString("scenario") == "complex"
            val complexFixture = if (complexScenario) ComplexMediaDragAudit.prepare(targetContext, args, output) else null
            var videoFile: File? = null
            main {
                val prefs = targetContext.getSharedPreferences(Def.Meta.META_DATA_NAME, 0).edit()
                listOf(Def.Meta.KEY_1_0_4_TO_1_0_5, Def.Meta.KEY_1_2_7_TO_1_3_0, Def.Meta.KEY_1_3_0_TO_1_3_1,
                    Def.Meta.KEY_1_3_3_TO_1_3_4).forEach { prefs.putBoolean(it, true) }; prefs.commit()
                File(targetContext.filesDir, Def.Meta.FEEDBACK_ERROR_FILE_NAME).delete()
                DBHelper(targetContext).use { it.writableDatabase.execSQL("DELETE FROM things WHERE type <> ${Thing.HEADER}")
                    it.writableDatabase.execSQL("DELETE FROM thing_folders") }
                App.getApp()!!.setStatus(Def.ThingStatus.UNDERWAY, true)
                manager = ThingManager.getInstance(targetContext)!!
                manager.loadThings()
                ThingAnimationPreferences.preferences(targetContext).edit()
                    .putInt(ThingAnimationPreferences.SWIPE_KEY, if (args.getString("particles") == "false") 0 else 1).commit()
                ParticleTuning.setStyle(targetContext, ParticleStyle.of(args.getString("style") ?: "drift"))
                if (complexFixture != null) {
                    complexFixture.things.forEach { it.location = manager.getHeaderId(); manager.create(it, false, true) }
                }
                for (i in 1..if (complexScenario) 0 else 32) {
                    val thing = Thing(manager.getHeaderId(), Thing.NOTE, 0xff3388aa.toInt(), manager.getHeaderId()).apply {
                        title = if (i == 8) "身份测试 A" else "虚构记事 $i"
                        content = (1..(i % 5 + 1)).joinToString("\n") { "仅用于复现，条目 $i" }
                    }
                    if (videoScenario && i == 8) videoFile = TallVideoScrollAudit.decorate(targetContext, thing, args.getString("background") == "true")
                    manager.create(thing, false, true)
                    if (i == 8) a = thing.id
                }
            }
            if (videoScenario) check(com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
                .generateBlocking(targetContext, videoFile!!.absolutePath, null) != null)
            // 用与 A 相同的源 ID 生成发送文件，经正式读写和导入入口验证本地重新编号。
            var b = 0L
            if (!complexScenario) {
                val source = Thing(a, Thing.NOTE, 0xffaa6633.toInt(), a).apply { title = "传入记事 B"; content = "来自传输文件的虚构内容" }
                val file = File(output, "fixture.edthing")
                ThingFileWriter.write(targetContext, listOf(source), file)
                val parsed = file.inputStream().use { ThingFileReader.read(targetContext, it) } as ThingFileReader.Result.Ok
                check(parsed.things.single().thing.id == 0L)
                val imported = ThingFileImporter.import(targetContext, parsed.things)
                b = parsed.things.single().thing.id
                check(imported.imported == 1 && b != a)
                result.put("a", a).put("b", b).put("sourceId", source.id)
            }
            activity = startActivitySync(Intent(targetContext, ThingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(Def.Communication.KEY_STATUS, Def.ThingStatus.UNDERWAY)) as ThingsActivity
            waitFor("首页") {
                list = field(activity, "mRecyclerView") as RecyclerView
                mode = field(activity, "mModeManager") as ModeManager
                list.childCount > 1 && activity.hasWindowFocus()
            }
            idle()
            main { (list.adapter as com.ywwynm.everythingdone.adapters.ThingsAdapter).setShouldThingsAnimWhenAppearing(false) }
            if (complexFixture != null) {
                args.getString("pool_multiplier")?.toFloat()?.let { multiplier ->
                    check(multiplier in 1f..2f)
                    main { com.bumptech.glide.Glide.get(targetContext).bitmapPool.setSizeMultiplier(multiplier) }
                }
                if (args.getString("gesture") == "performance") {
                    result.put("performance", ScrollPerformanceAudit.run(activity, list, mode, complexFixture, args, output, automation, ::main))
                }
                if (args.getString("gesture") in listOf("scroll", "both")) {
                    result.put("regular", RegularMediaScrollAudit.run(activity, list, complexFixture, output, automation, ::main))
                    if (args.getString("gesture") == "both") {
                        main { list.scrollToPosition(0) }
                        idle()
                    }
                }
                if (args.getString("gesture") !in listOf("scroll", "performance")) {
                    result.put("complex", ComplexMediaDragAudit.run(activity, list, mode, complexFixture, args, output, automation, ::main))
                }
                if (args.getString("memory_audit") == "true") {
                    graphicsMemory("after-drag")
                    main {
                        MediaMemoryAudit.snapshot(targetContext, output, "after-drag")
                        MediaMemoryAudit.recyclerOwners(list, output)
                        activity.finish()
                    }
                    SystemClock.sleep(3000)
                    var running = 0
                    main { running = MediaMemoryAudit.snapshot(targetContext, output, "activity-finished") }
                    if (args.getString("memory_gc") != "false") {
                        Runtime.getRuntime().gc()
                        SystemClock.sleep(2000)
                        main { MediaMemoryAudit.snapshot(targetContext, output, "after-gc") }
                        main { com.bumptech.glide.Glide.get(targetContext).clearMemory() }
                        SystemClock.sleep(1000)
                        main { running = MediaMemoryAudit.snapshot(targetContext, output, "after-clear-memory") }
                        graphicsMemory("after-clear-memory")
                    }
                    if (args.getString("lifecycle_assert") == "true") check(running == 0) { "Activity 销毁后仍有 $running 个 GIF loader 解码" }
                }
                result.put("ok", true)
                return
            }
            if (videoScenario) {
                result.put("video", TallVideoScrollAudit.run(activity, list, mode, a, args, output, automation, ::main))
                result.put("ok", true)
                return
            }
            select(a, "finish-a")
            action(R.id.act_finish_selected, true)
            status(Def.ThingStatus.FINISHED)
            select(a, "restore-a")
            action(R.id.act_restore_selected, true)
            status(Def.ThingStatus.UNDERWAY)
            main { result.put("restoredAIndex", manager.getListPositionForThingId(a))
                .put("bIndex", manager.getListPositionForThingId(b)) }
            snapshot("restored-top")
            select(a, "delete-a")
            action(R.id.act_delete_selected, true)
            status(Def.ThingStatus.DELETED)
            snapshot("trash-before-permanent")
            action(R.id.act_delete_all_forever, false)
            status(Def.ThingStatus.UNDERWAY)
            snapshot("final")
            screenshot("final")
            main {
                val dao = ThingDAO.getInstance(targetContext)!!
                val actualA = dao.getThingById(a)
                val actualB = dao.getThingById(b)
                result.put("aExists", actualA != null).put("aState", actualA?.state)
                    .put("bExists", actualB != null).put("bState", actualB?.state)
                check(actualA == null && actualB?.state == Thing.UNDERWAY) { "删除目标错误：A=${actualA?.state} B=${actualB?.state}" }
                check(mismatches.length() == 0) { "卡片绑定身份与位置不一致" }
            }
            result.put("ok", true)
        } catch (failure: Throwable) {
            result.put("ok", false).put("error", failure.stackTraceToString())
            screenshot("failure")
        } finally {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            automation.injectInputEvent(cancel, true)
            cancel.recycle()
            File(output, "result.json").writeText(result.toString(2))
            finish(if (result.optBoolean("ok")) 0 else 1, Bundle().apply { putString("result", result.toString()) })
        }
    }
}
