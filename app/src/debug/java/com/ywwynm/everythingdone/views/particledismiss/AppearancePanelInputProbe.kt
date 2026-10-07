package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.ywwynm.everythingdone.activities.ThingsActivity
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.ThingListEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 外观面板回归入口：读取 UiAutomator 的系统界面树，输入由系统派发，不改记事数据。 */
class AppearancePanelInputProbe : Instrumentation() {
    private var args = Bundle()
    private val events = JSONArray()
    private var started = 0L
    private lateinit var automation: UiAutomation
    private lateinit var activity: ThingsActivity
    private val managerField = ThingsActivity::class.java.getDeclaredField("mThingManager").apply { isAccessible = true }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val run = args.getString("run_id") ?: "input"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val dir = File(targetContext.getExternalFilesDir(null), "appearance-panel-frames/$run").apply { mkdirs() }
        var error: String? = null
        started = SystemClock.uptimeMillis()
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            activity = startActivitySync(Intent(targetContext, ThingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ThingsActivity
            val config = JSONObject().put("run_id", run).put("style", args.getString("style") ?: "drift")
                .put("duration_ms", 180000).put("hold_window_ms", args.getString("hold_window_ms")?.toLongOrNull() ?: 0)
            runOnMainSync { AppearancePanelFrameObserver.start(activity, config) }
            val rounds = (args.getString("rounds")?.toIntOrNull() ?: 4).coerceIn(1, 10)
            repeat(rounds) { cycle ->
                var root = waitFor { n -> photos(n).isNotEmpty() && find(n, "act_customize_card_appearance") == null }
                val a = photos(root).first(); val key = key(a)
                action(cycle, "A-long", a, true)
                root = waitFor { find(it, "act_customize_card_appearance", "调整记事外观") != null }
                action(cycle, "A-open", requireNotNull(find(root, "act_customize_card_appearance", "调整记事外观")))
                if (args.getString("capture_handoff") == "true") captureHandoff(dir, cycle, "A")
                root = waitFor { find(it, "bt_cancel_thing_card_appearance")?.isVisibleToUser == true }
                action(cycle, "A-close", requireNotNull(find(root, "bt_cancel_thing_card_appearance")))
                root = waitFor { find(it, "bt_cancel_thing_card_appearance") == null && photos(it).any { c -> key(c) == key } }
                action(cycle, "A-deselect", photos(root).first { key(it) == key })
                root = waitFor { find(it, "act_customize_card_appearance") == null }
                var b: AccessibilityNodeInfo? = null
                for (scroll in 0 until 8) {
                    val list = requireNotNull(find(root, "rv_things")); val rect = rect(list)
                    event(cycle, "scroll-$scroll")
                    command("input swipe ${rect.centerX()} ${rect.top + rect.height() * 7 / 10} ${rect.centerX()} ${rect.top + rect.height() * 4 / 10} 160")
                    root = waitFor { find(it, "rv_things") != null }
                    b = photos(root).firstOrNull { key(it) != key && key(it).isNotEmpty() &&
                        (args.getString("video_second") != "true" || nodes(it).any { c -> id(c, "tv_thing_image_attachment_count") && c.text?.contains("视频") == true }) }
                    if (b != null) break
                }
                check(b != null) { "没有找到第二条普通图片记事" }
                action(cycle, "B-select", b)
                root = waitFor { find(it, "act_customize_card_appearance", "调整记事外观") != null }
                action(cycle, "B-open", requireNotNull(find(root, "act_customize_card_appearance", "调整记事外观")))
                if (args.getString("capture_handoff") == "true") captureHandoff(dir, cycle, "B")
                root = waitFor { find(it, "bt_cancel_thing_card_appearance")?.isVisibleToUser == true }
                // 保留交接后的观察窗口，不干扰被调查的几帧。
                SystemClock.sleep(1200)
                root = waitFor { find(it, "bt_cancel_thing_card_appearance") != null }
                action(cycle, "B-close", requireNotNull(find(root, "bt_cancel_thing_card_appearance")))
                waitFor { find(it, "bt_cancel_thing_card_appearance") == null }
                command("input keyevent 4")
                root = waitFor { find(it, "act_customize_card_appearance") == null }
                // 继续使用当前列表位置，下一轮换成另一组图片记事。
                event(cycle, "complete")
            }
        } catch (t: Throwable) { error = t.stackTraceToString() }
        runOnMainSync { AppearancePanelFrameObserver.flush() }
        val saved = File(dir, "frames.json")
        val until = SystemClock.uptimeMillis() + 3000
        while (!saved.exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        File(dir, "input.json").writeText(JSONObject().put("events", events).put("error", error ?: JSONObject.NULL).toString())
        finish(if (error == null) 0 else 1, Bundle().apply { putString("result", error ?: "完成") })
    }
    private fun command(cmd: String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() } }
    private fun captureHandoff(dir: File, cycle: Int, label: String) {
        val deadline = SystemClock.uptimeMillis() + 3000
        var since = 0L
        do {
            runOnMainSync { since = AppearancePanelFrameObserver.blockedSince() }
            if (since > 0 && SystemClock.uptimeMillis() - since in 0..320) break
            SystemClock.sleep(10)
        } while (SystemClock.uptimeMillis() < deadline)
        check(since > 0 && SystemClock.uptimeMillis() - since < 320) { "未进入受控交接延迟" }
        if (SystemClock.uptimeMillis() - since < 180) SystemClock.sleep(180 - (SystemClock.uptimeMillis() - since))
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(dir, "handoff-$cycle-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        var error = 0.0
        runOnMainSync { error = AppearancePanelFrameObserver.screenshotError(bitmap) }
        bitmap.recycle()
        events.put(JSONObject().put("cycle", cycle).put("action", "$label-handoff").put("rgbError", error))
        check(error < 5.0) { "面板交接存在空档：屏幕与面板快照 RGB 平均差 $error" }
        SystemClock.sleep(200)
    }
    private fun waitFor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 7000
        do {
            val root = automation.rootInActiveWindow
            if (root != null && predicate(root)) return root
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < until)
        error("系统界面树未到达预期状态")
    }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) } }
        visit(root); return out
    }
    private fun id(n: AccessibilityNodeInfo, id: String) = n.viewIdResourceName?.endsWith("/$id") == true
    private fun find(root: AccessibilityNodeInfo, id: String, desc: String? = null) = nodes(root).firstOrNull { id(it, id) && (desc == null || it.contentDescription?.toString() == desc) }
    private fun rect(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun topCards(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val cards = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) {
            if (id(n, "cv_thing")) { cards += n; return }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) }
        }
        visit(root); return cards
    }
    private fun photos(root: AccessibilityNodeInfo) = topCards(root).filter { n ->
        if (!id(n, "cv_thing") || !n.isVisibleToUser) false else {
            val children = nodes(n)
            val labels = children.filter { id(it, "tv_thing_image_attachment_count") }
            children.count { id(it, "cv_thing") } == 1 &&
                labels.size == 1 && (labels[0].text?.contains("图片") == true || labels[0].text?.contains("视频") == true) &&
                entry(n) is ThingListEntry.ThingEntry &&
                children.any { id(it, "iv_thing_image") && kotlin.math.abs(rect(it).width() - rect(n).width()) < 4 }
        }
    }
    private fun entry(n: AccessibilityNodeInfo): ThingListEntry? {
        val r = rect(n)
        var entry: ThingListEntry? = null
        runOnMainSync {
            val rv = activity.findViewById<RecyclerView>(R.id.rv_things)
            val at = IntArray(2); rv.getLocationOnScreen(at)
            val card = rv.findChildViewUnder((r.centerX() - at[0]).toFloat(), (r.centerY() - at[1]).toFloat())
            if (card != null) entry = (managerField.get(activity) as ThingManager).getThingListEntry(rv.getChildAdapterPosition(card))
        }
        return entry
    }
    private fun key(n: AccessibilityNodeInfo) = entry(n)?.stableId?.toString() ?: ""
    private fun event(cycle: Int, action: String) { events.put(JSONObject().put("ms", SystemClock.uptimeMillis() - started).put("cycle", cycle).put("action", action)) }
    private fun action(cycle: Int, label: String, node: AccessibilityNodeInfo, long: Boolean = false) {
        val r = rect(node); automation.rootInActiveWindow?.let { r.intersect(rect(it)) }; check(!r.isEmpty)
        if (id(node, "cv_thing")) check(entry(node) is ThingListEntry.ThingEntry) { "拒绝选择文件夹或其缩略图" }
        val x = r.centerX()
        val y = r.centerY()
        event(cycle, label)
        command(if (long) "input swipe $x $y $x $y 550" else "input tap $x $y")
    }
}
