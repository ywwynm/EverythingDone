package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONObject
import java.io.File

/** 由外部脚本按系统 UI 树派发手势；默认虚构记事，指定既有记事时只读展示。 */
class ParticlePresentationRegressionProbe : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val run = args.getString("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder = File(targetContext.getExternalFilesDir(null), "presentation-regression/$run").apply { mkdirs() }
        val previousStyle = ParticleStyle.current
        val previousPolicy = ParticlePresentationPolicy.originalForDebug
        val previousSeed = ParticleCardSurfaceHandoff.seedForTests
        val beatTrace = args.getString("beat_trace") == "true"
        val methodTrace = args.getString("method_trace") == "true"
        var methodTraceStarted = false
        var activity: Activity? = null
        var parent: ParticlePresentationDiagnosticsActivity? = null
        var parentBounds: android.graphics.Rect? = null
        var report: ParticlePresentationReports.Report? = null
        var error: String? = null
        try {
            if (beatTrace) ParticleBeatTrace.start()
            val scene = args.getString("scene") ?: "卡片"
            val mode = args.getString("mode") ?: "opaque"
            val pageOverride = args.getString("doing_gl_scene")?.toBooleanStrictOrNull()
            val expectPage = scene == "Doing" && android.os.Build.VERSION.SDK_INT >= 34 &&
                (pageOverride ?: (mode == "unified"))
            val options = ParticlePresentationDiagnostics.Options(
                dialogMode = if (mode == "native") ParticlePresentationDiagnostics.DialogMode.ORIGINAL
                    else ParticlePresentationDiagnostics.DialogMode.CONTENT,
                images = mode != "native" && args.getString("images") != "false", glForeground = mode != "native",
                opaqueGlHandoff = mode in listOf("opaque", "fast", "unified"), surfaceRecovery = mode in listOf("opaque", "fast", "unified"),
                fastOpaqueHandoff = mode in listOf("fast", "unified"), unified = mode == "unified")
            runOnMainSync {
                ParticlePresentationPolicy.originalForDebug = mode == "native"
                ParticleStyle.current = ParticleStyle.of(args.getString("style"))
                ParticleCardSurfaceHandoff.seedForTests = args.getString("particle_seed")?.toIntOrNull()
                report = ParticlePresentationReports.start(
                    targetContext, scene, "自动回归 $mode", options, "原生 Android 虚构场景",
                    frameCapacity = 16384, checkpoints = false)
            }
            if (args.getString("exit_to_parent") == "true") {
                parent = startActivitySync(Intent(targetContext, ParticlePresentationDiagnosticsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ParticlePresentationDiagnosticsActivity
                runOnMainSync { parent?.bindExitReportForTests(checkNotNull(report)) }
                waitForIdleSync()
                val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                val deadline = SystemClock.uptimeMillis() + 5000
                while (parentBounds == null && SystemClock.uptimeMillis() < deadline) {
                    val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    automation.rootInActiveWindow?.let(pending::add)
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        if (node.className == "android.widget.ScrollView")
                            parentBounds = android.graphics.Rect().also(node::getBoundsInScreen)
                        for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                        node.recycle()
                    }
                    if (parentBounds == null) SystemClock.sleep(30)
                }
                check(parentBounds != null) { "调试主页 UI 树未提供滚动区" }
            }
            val target = if (scene == "Doing") ParticleDoingProbeActivity::class.java
                else if (scene == "卡片") ParticleSwipeShadowProbeActivity::class.java
                else ParticlePresentationCaseActivity::class.java
            activity = startActivitySync(Intent(targetContext, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("scene", scene).putExtra("with_chrome", true).putExtra("normal_chrome", true)
                .putExtra("with_fab", true).putExtra("performance_only", true).putExtra("run_id", run)
                .putExtra("dynamic_background", args.getString("dynamic_background") == "true")
                .putExtra("doing_long_card", args.getString("doing_long_card") == "true")
                .putExtra("existing_thing_id", args.getString("existing_thing_id")?.toLongOrNull() ?: -1L)
                .putExtra("doing_fixture", args.getString("doing_fixture", "text"))
                .putExtra("card_height_dp", args.getString("card_height_dp")?.toIntOrNull() ?: 180)
                .putExtra("card_count", args.getString("card_count")?.toIntOrNull() ?: 1)
                .also { intent ->
                    pageOverride?.let { intent.putExtra("doing_gl_scene", it) }
                    report?.let { intent.putExtra(ParticlePresentationReports.EXTRA_RUN, it.id) }
                })
            waitForIdleSync()
            if (methodTrace) {
                android.os.Debug.startMethodTracingSampling(File(folder, "methods.trace").absolutePath,
                    32 * 1024 * 1024, 500)
                methodTraceStarted = true
            }
            File(folder, "ready.json").writeText(JSONObject().put("run", run).put("scene", scene)
                .put("mode", mode).put("style", ParticleStyle.current.name).put("report", report?.id)
                .put("pageSelection", pageOverride?.toString() ?: "business_default").put("expectPage", expectPage)
                .put("parentBounds", parentBounds?.let { org.json.JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
                .put("reportFolder", report?.folder?.absolutePath).toString())
            val deadline = SystemClock.uptimeMillis() + if (args.containsKey("existing_thing_id") ||
                args.getString("doing_fixture") == "video") 90000 else 60000
            var injected = false
            if (args.getString("scheduler_check") == "true") {
                DoingFrameCallbackChecks.verify(this, checkNotNull(activity))
                report?.event("doing_frame_callbacks_verified")
            }
            var lastUiRequest = ""
            var lastCommand = ""
            while (!File(folder, "finish").exists() && SystemClock.uptimeMillis() < deadline) {
                val command = File(folder, "command.json")
                if (command.exists() && command.readText() != lastCommand) {
                    lastCommand = command.readText()
                    val request = JSONObject(lastCommand)
                    runOnMainSync {
                        (activity as? ParticleDoingProbeActivity)?.let { page ->
                            page.command(request.getString("action"))
                            if (request.getString("action") == "media")
                                File(folder, "media.json").writeText(page.inspectMedia().toString())
                        }
                    }
                    File(folder, "command-done.json").writeText(lastCommand)
                }
                val uiRequest = File(folder, "ui-request")
                if (uiRequest.exists() && uiRequest.readText() != lastUiRequest) {
                    lastUiRequest = uiRequest.readText()
                    val nodes = org.json.JSONArray()
                    val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    automation.rootInActiveWindow?.let(queue::add)
                    while (queue.isNotEmpty()) {
                        val node = queue.removeFirst()
                        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                        nodes.put(JSONObject().put("resource-id", node.viewIdResourceName)
                            .put("class", node.className).put("text", node.text)
                            .put("content-desc", node.contentDescription)
                            .put("bounds", org.json.JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
                        for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                        node.recycle()
                    }
                    var settled = false
                    runOnMainSync {
                        val host = activity
                        val state = com.ywwynm.everythingdone.fragments.BaseDialogFragment::class.java
                            .getDeclaredField("appearanceSettled").apply { isAccessible = true }
                        settled = host == null || (ParticleDismissOverlay.activeIn(host).none { it.parent != null } &&
                            (host as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.fragments
                                .orEmpty().filterIsInstance<com.ywwynm.everythingdone.fragments.BaseDialogFragment>()
                                .none { it.dialog?.isShowing == true && !state.getBoolean(it) })
                    }
                    File(folder, "ui.json").writeText(JSONObject().put("request", lastUiRequest)
                        .put("nodes", nodes).put("settled", settled).toString())
                }
                val request = File(folder, "gesture.json")
                if (!injected && request.exists()) {
                    val gesture = JSONObject(request.readText())
                    val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    val start = SystemClock.uptimeMillis()
                    val duration = gesture.getLong("duration")
                    val hold = gesture.optLong("hold", 0)
                    fun send(action: Int, at: Long, x: Float) {
                        val event = MotionEvent.obtain(start, at, action, x, gesture.getDouble("y").toFloat(), 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        try { check(automation.injectInputEvent(event, false)) { "系统输入注入失败" } }
                        finally { event.recycle() }
                    }
                    val from = gesture.getDouble("from").toFloat(); val to = gesture.getDouble("to").toFloat()
                    send(MotionEvent.ACTION_DOWN, start, from)
                    // 与真实触屏相同，事件时刻不等待应用处理 DOWN；避免 shell input 的同步等待吞掉全部 MOVE。
                    for (step in 1..10) {
                        val at = start + duration * step / 10
                        val wait = at - SystemClock.uptimeMillis()
                        if (wait > 0) SystemClock.sleep(wait)
                        send(MotionEvent.ACTION_MOVE, at, from + (to - from) * step / 10)
                    }
                    val up = start + duration + hold
                    val wait = up - SystemClock.uptimeMillis()
                    if (wait > 0) SystemClock.sleep(wait)
                    send(MotionEvent.ACTION_UP, up, to)
                    File(folder, "gesture-injected.json").writeText(gesture.put("moves", 10).toString())
                    injected = true
                }
                SystemClock.sleep(30)
            }
            check(File(folder, "finish").exists()) { "外部回归脚本未完成" }
            if (scene == "Doing") {
                val events = checkNotNull(report).json().getJSONArray("events")
                val names = (0 until events.length()).map { events.getJSONObject(it).getString("event") }
                check("doing_scene_failed" !in names) { "整页绘制失败，不能把恢复 View 或提前退出记为通过" }
                check(("doing_scene_created" in names) == expectPage) { "整页入口与本次对照选项不一致" }
            }
            if (parent != null) {
                val events = checkNotNull(report).json().getJSONArray("events")
                val rows = (0 until events.length()).map(events::getJSONObject)
                fun first(name: String) = rows.first { it.getString("event") == name }.getDouble("elapsedMs")
                check(rows.none { it.getString("event") == "doing_scene_failed" }) { "退出发生整页捕获失败" }
                check(first("diagnostic_parent_first_scroll") - first("doing_probe_finish_requested") < 2000) {
                    "退出后主页未及时收到测试滑动"
                }
                check(activity?.isDestroyed == true) { "Doing 页面未销毁" }
                if (expectPage) {
                    first("doing_scene_capture_resources_released")
                    first("doing_scene_window_detached")
                }
            }
            if (args.getString("export") == "true") {
                val latch = java.util.concurrent.CountDownLatch(1)
                var exported: Result<File>? = null
                ParticlePresentationReports.export(targetContext) { result -> exported = result; latch.countDown() }
                check(latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "诊断导出超时" }
                checkNotNull(exported).getOrThrow().copyTo(File(folder, "diagnostics.zip"))
            }
        } catch (failure: Throwable) {
            error = failure.stackTraceToString()
        } finally {
            if (methodTraceStarted) android.os.Debug.stopMethodTracing()
            if (beatTrace) {
                ParticleBeatTrace.stop()
                File(folder, "beat-trace.json").writeText(org.json.JSONArray(
                    ParticleBeatTrace.snapshot().map { org.json.JSONArray(it.toList()) }).toString())
            }
            runOnMainSync {
                if (activity?.isDestroyed == false) (activity as? ParticleDoingProbeActivity)?.captureFinalState()
                activity?.finish()
                parent?.finish()
                ParticleStyle.current = previousStyle
                ParticlePresentationPolicy.originalForDebug = previousPolicy
                ParticleCardSurfaceHandoff.seedForTests = previousSeed
            }
            waitForIdleSync()
            report?.let { File(folder, "report.json").writeText(it.json().toString(2)) }
            File(folder, "result.json").writeText(JSONObject().put("error", error ?: JSONObject.NULL).toString())
        }
        finish(if (error == null) 0 else 1, Bundle().apply { putString("result", error ?: "完成") })
    }
}
