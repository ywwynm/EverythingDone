package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * 仅 debug：files/thing-animation-performance-enabled 显式开启。
 * 观察系统输入、真实窗口和生产动画；没有业务调用、偏好写入或事件注入。
 */
class ThingAnimationPerformanceObserver : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        // 粒子 GPU 计时钩子的目录；同样由独立诊断文件显式开启，缺文件即全程关闭。
        ParticlePerfProbe.attach(app.filesDir, app.getExternalFilesDir(null))
        // 第一批各项改动的逐项开关；缺文件时全部取默认（全开）。
        ParticlePerfTuning.attach(app.filesDir)
        if (File(app.filesDir, "thing-animation-performance-enabled").exists()) ObserverManager(app).start()
        return true
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

private class ObserverManager(val app: Application) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val activities = WeakHashMap<Activity, Boolean>()
    private var collector: PerformanceCollector? = null
    private var run: String? = null
    private val poll = object : Runnable {
        override fun run() {
            val enabled = File(app.filesDir, "thing-animation-performance-enabled").exists()
            val config = runCatching { JSONObject(File(app.filesDir, "thing-animation-performance-control.json").readText()) }.getOrNull()
            val next = if (enabled) config?.optString("run_id")?.takeIf { it.isNotEmpty() } else null
            if (next != run) {
                collector?.finish(); collector = null; run = next
                if (next != null && next.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) {
                    collector = PerformanceCollector(app, config!!).also { c ->
                        activities.keys.toList().forEach { c.watch(it) }
                        c.start()
                    }
                }
            }
            // 每个轮询周期重新登记一次；漏掉宿主窗口会让整轮没有帧数据。
            collector?.let { c -> activities.keys.toList().forEach { c.watch(it) } }
            if (enabled) handler.postDelayed(this, 250) else {
                collector?.finish(); app.unregisterActivityLifecycleCallbacks(this@ObserverManager)
            }
        }
    }
    fun start() { app.registerActivityLifecycleCallbacks(this); handler.post(poll) }
    override fun onActivityCreated(a: Activity, b: Bundle?) { activities[a] = true; collector?.watch(a) }
    override fun onActivityResumed(a: Activity) { activities[a] = true; collector?.watch(a); collector?.event(a.javaClass.simpleName + "-resumed") }
    override fun onActivityDestroyed(a: Activity) { activities.remove(a); collector?.unwatch(a) }
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
}

private class PerformanceCollector(val app: Application, val config: JSONObject) : Choreographer.FrameCallback {
    private val handler = Handler(Looper.getMainLooper())
    private val metricsThread = HandlerThread("ThingAnimationMetrics").apply { start() }
    private val metricsHandler = Handler(metricsThread.looper)
    private val frames = Collections.synchronizedList(mutableListOf<LongArray>())
    private data class Watch(val activity: Activity?, val original: Window.Callback,
                             val wrapper: Window.Callback, val metrics: Window.OnFrameMetricsAvailableListener)
    private val windows = mutableMapOf<Window, Watch>()
    private val windowLabels = JSONArray()
    private var nextWindowType = 2L
    private val stalls = JSONArray()
    @Volatile private var lastMainFrameAt = System.nanoTime()
    private var stallThread: Thread? = null
    private val seenOverlays = WeakHashMap<ParticleDismissOverlay, Boolean>()

    /** 一轮里只跑一次离屏分段基准：它要独占 GPU 二十几秒，两个动画层各跑一次没有意义。 */
    private var seenBench = false
    private var seenOptions = false
    private val overlayRows = JSONArray()
    private val events = JSONArray()
    private val inputs = JSONArray()
    private val states = JSONArray()
    private val mainFrames = mutableListOf<Long>()
    private val output = File(app.getExternalFilesDir(null), "thing-animation-performance/" + config.getString("run_id")).apply { mkdirs() }
    private val startedAt = System.nanoTime()
    private val cpuStart = Process.getElapsedCpuTime()
    private val memoryStart = memory()
    private val recyclerField = ThingsActivity::class.java.getDeclaredField("mRecyclerView").apply { isAccessible = true }
    private val revealField = ThingsActivity::class.java.getDeclaredField("mIsRevealAnimPlaying").apply { isAccessible = true }
    private val gatingField = ThingsActivity::class.java.getDeclaredField("mNewItemRevealGating").apply { isAccessible = true }
    private val transitionField = DetailActivity::class.java.getDeclaredField("creationTransition").apply { isAccessible = true }
    private val closedField = ThingCreationTransition::class.java.getDeclaredField("closed").apply { isAccessible = true }
    private val specField = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
    private val rendererField = ParticleDismissOverlay::class.java.getDeclaredField("session").apply { isAccessible = true }
    private var lastInputAt = 0L
    private var lastLifecycleAt = 0L
    private var lastAction = -1
    private var lastStatus = ""
    private var editorReady = false
    private var idleAt = 0L
    private var stopped = false

    fun start() {
        progress(false, true); Choreographer.getInstance().postFrameCallback(this)
        if (config.optBoolean("stallSampling")) startStallSampler()
        // 第三批的节拍级取证：默认开着，一轮约 1500 行，收尾时整份落进 result.json。
        if (config.optBoolean("beatTrace", true)) ParticleBeatTrace.start()
    }

    /** 每 2 ms 检查主线程上一次帧回调距今是否超过一帧，超了就抓栈顶；只读取，不干预。 */
    private fun startStallSampler() {
        val main = Looper.getMainLooper().thread
        stallThread = Thread({
            var lastCapture = 0L
            while (!stopped) {
                val now = System.nanoTime()
                val gap = now - lastMainFrameAt
                if (gap > 16_000_000L && now - lastCapture > 8_000_000L && stalls.length() < 4000) {
                    lastCapture = now
                    val trace = runCatching { main.stackTrace }.getOrNull().orEmpty()
                    stalls.put(JSONObject().put("at", now).put("gapMs", gap / 1e6)
                        .put("stack", JSONArray(trace.take(8).map { it.toString() })))
                }
                try { Thread.sleep(2) } catch (e: InterruptedException) { return@Thread }
            }
        }, "ThingAnimationStalls").apply { isDaemon = true; start() }
    }
    fun event(label: String) {
        if (label.endsWith("-resumed")) { lastLifecycleAt = System.nanoTime(); idleAt = 0 }
        events.put(JSONObject().put("event", label).put("at", System.nanoTime()).put("cpuMs", Process.getElapsedCpuTime()))
    }

    fun watch(a: Activity) {
        if (stopped || a !is ThingsActivity && a !is DetailActivity || windows.containsKey(a.window)) return
        attach(a, a.window, if (a is ThingsActivity) 0L else 1L, a.javaClass.simpleName)
    }

    /** 弹窗各有独立 Window，涟漪与消散都发生在这些窗口上，必须单独取 FrameMetrics。 */
    private fun watchDialogs(a: Activity) {
        if (stopped || a !is androidx.fragment.app.FragmentActivity) return
        for (f in a.supportFragmentManager.fragments) {
            val w = (f as? androidx.fragment.app.DialogFragment)?.dialog?.window ?: continue
            if (windows.containsKey(w)) continue
            attach(null, w, nextWindowType++, f.javaClass.simpleName)
        }
    }

    private fun attach(a: Activity?, w: Window, type: Long, label: String) {
        windowLabels.put(JSONObject().put("type", type).put("label", label).put("at", System.nanoTime()))
        val original = w.callback
        val wrapper = object : Window.Callback by original {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                lastInputAt = System.nanoTime(); lastAction = e.actionMasked
                inputs.put(JSONArray(listOf(lastInputAt, 0, e.actionMasked,
                    (e.x * 1000).toLong(), (e.y * 1000).toLong(), type)))
                return original.dispatchTouchEvent(e)
            }
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                lastInputAt = System.nanoTime(); lastAction = e.action
                inputs.put(JSONArray(listOf(lastInputAt, 1, e.action, e.keyCode, 0, type)))
                return original.dispatchKeyEvent(e)
            }
        }
        val metrics = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
            frames += longArrayOf(m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                m.getMetric(FrameMetrics.TOTAL_DURATION), m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), m.getMetric(FrameMetrics.DRAW_DURATION),
                m.getMetric(FrameMetrics.SYNC_DURATION), m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION), m.getMetric(FrameMetrics.GPU_DURATION),
                m.getMetric(FrameMetrics.DEADLINE), m.getMetric(FrameMetrics.FIRST_DRAW_FRAME), dropped.toLong(), type)
        }
        w.callback = wrapper
        windows[w] = Watch(a, original, wrapper, metrics)
        w.addOnFrameMetricsAvailableListener(metrics, metricsHandler)
    }

    fun unwatch(a: Activity) {
        val w = a.window
        windows.remove(w)?.let {
            w.removeOnFrameMetricsAvailableListener(it.metrics)
            if (w.callback === it.wrapper) w.callback = it.original
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (stopped) return
        mainFrames += frameTimeNanos
        lastMainFrameAt = System.nanoTime()
        var busy = false
        var overlays = 0
        try {
            for ((w, watched) in windows.entries.toList()) {
                val a = watched.activity ?: continue
                watchDialogs(a)
                // 动画层可能挂在 DecorView 的 ViewGroupOverlay 里（遍历子节点看不到），改查登记表。
                for (overlay in ParticleDismissOverlay.activeIn(a)) {
                    overlays++
                    observe(overlay, if (a is ThingsActivity) "save-or-swipe" else "open")
                }
                if (!a.hasWindowFocus()) continue
                if (a is DetailActivity) {
                    val transition = transitionField.get(a)
                    val closed = transition == null || closedField.getBoolean(transition)
                    busy = busy || !closed
                    if (closed && !editorReady) { editorReady = true; event("editor-ready") }
                }
                if (a is ThingsActivity) {
                    val rv = recyclerField.get(a) as? RecyclerView ?: continue
                    val target = config.optString("title")
                    val card = (0 until rv.childCount).map { rv.getChildAt(it) }.firstOrNull {
                        it.findViewById<TextView>(R.id.tv_thing_title)?.text?.toString() == target
                    }
                    val revealing = revealField.getBoolean(a) || gatingField.getBoolean(a)
                    val animating = rv.itemAnimator?.isRunning == true
                    busy = busy || revealing || animating || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE ||
                        (card != null && (card.alpha != 1f || card.visibility != View.VISIBLE || card.translationX != 0f))
                    states.put(JSONArray(listOf(System.nanoTime(), if (revealing) 1 else 0,
                        if (animating) 1 else 0, rv.scrollState, card?.alpha ?: -1f,
                        card?.visibility ?: -1, card?.translationX ?: 0f)))
                }
            }
            busy = busy || overlays > 0
            val now = System.nanoTime()
            val signalAt = maxOf(lastInputAt, lastLifecycleAt)
            if (busy || signalAt == 0L || lastInputAt > lastLifecycleAt && lastAction != 1) idleAt = 0L
            else if (idleAt == 0L) idleAt = now
            val settled = idleAt != 0L && now - maxOf(idleAt, signalAt) > 900_000_000
            progress(settled, busy)
        } catch (e: Throwable) {
            event("observer-error: " + e.javaClass.simpleName + ": " + e.message)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun progress(settled: Boolean, busy: Boolean) {
        val status = "$settled:$busy:$editorReady:${inputs.length()}"
        if (status == lastStatus) return
        lastStatus = status
        // 手指移动时只更新内存计数，避免每个 MOVE 都写文件。
        if (lastAction == MotionEvent.ACTION_MOVE) return
        File(output, "progress.json").writeText(JSONObject().put("ready", true)
            .put("run_id", config.getString("run_id")).put("settled", settled).put("busy", busy)
            .put("editorReady", editorReady).put("inputCount", inputs.length()).put("at", System.nanoTime()).toString())
    }

    /** 缺值或非有限值写成 JSON null：NaN 会让 result.json 整份写不出来。 */
    private fun json(value: Float?): Any =
        if (value != null && value.isFinite()) value else JSONObject.NULL

    private fun observe(overlay: ParticleDismissOverlay, label: String) {
        if (seenOverlays.put(overlay, true) != null) return
        val spec = specField.get(overlay) as ParticleDismissSpec
        if (config.optBoolean("gpuCheck")) {
            val bitmap = spec.snapshot.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val copied = ParticleDismissSpec(bitmap, spec.originXPx, spec.originYPx,
                spec.virtualTouchXPx, spec.virtualTouchYPx, spec.durationScale,
                if (config.optBoolean("diagnosticOnly")) config.optInt("gpuCheckSeed", spec.hashSeed) else spec.hashSeed,
                touchGap = spec.touchGap, touchStrength = spec.touchStrength)
            val context = overlay.context.applicationContext
            val frames = config.optJSONArray("gpuCheckFrames")
            val inspect = (0 until minOf(frames?.length() ?: 0, 2)).map { frames!!.getInt(it) }.toSet()
            // 第五批：单像素差异的定性轮。每条路径自己跑多遍，并可选地在另一个上下文里制造
            // 与提前预算同形态的并发 GPU 负载——「并发」这一项因此成了可独立开关的自变量。
            val diagnostics = config.optBoolean("gpuCheckDiagnostics")
            val load = config.optBoolean("gpuCheckLoad")
            val repeats = config.optInt("gpuCheckRepeats", 3)
            val pinFrame = config.optInt("gpuCheckPinFrame", -1)
            // 第二阶段：逐项的逐位 / 逐通道判据。`gpuCheckPhase2Only` 时不跑第一批那几格，
            // 华为 2880×1920 上一格就是几十条 61 帧序列的逐像素比对（第四批 42 分钟没收尾）。
            val names = config.optJSONArray("gpuCheckPhase2")
            val phase2 = (0 until (names?.length() ?: 0)).map { names!!.getString(it) }
            val phase2Only = config.optBoolean("gpuCheckPhase2Only")
            overlay.afterRelease {
                ParticleTrajectoryCheck.run(context, copied, overlay.width, overlay.height, output,
                    inspect, diagnostics, load, repeats, pinFrame, phase2, phase2Only)
            }
        }
        // 逐帧成本专项：拿这一次动画真实的材料，在离屏上下文里做消融归因。与 gpuCheck 互斥使用，
        // 免得两条离屏路径抢同一块 GPU。
        if (config.optBoolean("segmentBench") && !seenBench) {
            seenBench = true
            val bitmap = spec.snapshot.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val copied = ParticleDismissSpec(bitmap, spec.originXPx, spec.originYPx,
                spec.virtualTouchXPx, spec.virtualTouchYPx, spec.durationScale, spec.hashSeed,
                touchGap = spec.touchGap, touchStrength = spec.touchStrength)
            val context = overlay.context.applicationContext
            val repeats = config.optInt("segmentBenchRepeats", 2)
            val width = overlay.width
            val height = overlay.height
            val perFrame = config.optBoolean("perFrameBench")
            // 第二阶段：逐项墙钟 A/B。条件可以只给一部分（`phase2Conditions`），真机要省着跑。
            val phase2 = config.optBoolean("phase2Bench")
            val names = config.optJSONArray("phase2Conditions")
            val wanted = (0 until (names?.length() ?: 0)).map { names!!.getString(it) }
            overlay.afterRelease {
                if (phase2) ParticleSegmentBench.runPhase2(context, copied, width, height, output, repeats,
                    names = wanted)
                else if (perFrame) ParticleSegmentBench.runPerFrame(context, copied, width, height, output, repeats)
                else ParticleSegmentBench.run(context, copied, width, height, output, repeats)
            }
        }
        val row = JSONObject().put("stage", label).put("requestAt", spec.requestedAtNanos)
            .put("createdAt", System.nanoTime()).put("reverse", spec.reverse)
            .put("width", spec.snapshot.width).put("height", spec.snapshot.height)
            // 2b：本次用的方向与触点输入；appearanceTouch 无论开关如何都有，便于事后对照。
            // 注意：JSON 不接受 NaN，写进去会让整份 result.json 在 toString 时抛异常、一个字都留不下
            // （2026-09-15 实际发生：左滑那一轮没有真实触点，整轮数据丢失）。缺值一律写 null。
            .put("virtualTouch", JSONArray(listOf(json(spec.virtualTouchXPx), json(spec.virtualTouchYPx))))
            .put("appearanceTouch", JSONArray(listOf(json(spec.appearanceTouchXPx), json(spec.appearanceTouchYPx))))
            .put("origin", JSONArray(listOf(json(spec.originXPx), json(spec.originYPx))))
            .put("touchGap", json(spec.touchGap))
            // T3：框架给这次动画的时长缩放。不要相信 `settings get global`——OPD2515 上
            // 那一项根本不存在，而框架实际施加的是 5.0（见 .claude/rules/adb.md）。
            .put("durationScale", json(spec.durationScale))
            .put("touchStrength", json(spec.touchStrength))
            .put("skipEmptyTail", spec.skipEmptyTail)
            .put("creationPage", spec.creationPage)
            .put("touchMode", ParticlePerfTuning.creationAppearanceTouch)
            .put("skipTailEnabled", ParticlePerfTuning.creationAppearanceSkipEmptyTail)
            .put("dialogSkipTailEnabled", ParticlePerfTuning.dialogAppearanceSkipEmptyTail)
            .put("aliveRatio", ParticlePerfTuning.appearanceAliveRatio)
            .put("materialLanes", ParticleMaterialNative.lanes())
        // 第四批 ②：命中与否只对创建页有意义——诊断是进程级的一份，挂到弹窗行上会被误读成
        // 「这个弹窗也命中了」。
        if (spec.creationPage) row.put("prewarm", ParticleCreationPrewarm.diagnostics())
        overlayRows.put(row)
        // 第三批任务二：E12 / C7 的受控对照。挂在**消散**那一侧（正向路径同时有现场积分
        // 与压力更新，两个选项的代价都体现得出来），一轮只做一次。
        if (!spec.reverse && config.optBoolean("optionFrames") && !seenOptions) {
            seenOptions = true
            val bitmap = spec.snapshot.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val context = overlay.context.applicationContext
            overlay.afterRelease {
                ParticleOptionFrames.run(context, spec, bitmap, overlay.width, overlay.height, output)
            }
        }
        if (spec.reverse && spec.creationPage && config.optBoolean("appearanceFrames")) {
            val bitmap = spec.snapshot.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val context = overlay.context.applicationContext
            overlay.afterRelease {
                ParticleAppearanceFrames.run(context, spec, bitmap, overlay.width, overlay.height, output)
            }
        }
        overlay.afterRelease {
            row.put("removedAt", System.nanoTime())
            val renderer = rendererField.get(overlay) as? ParticleSession
            row.put("sessionPresent", renderer != null)
            row.put("textures", synchronized(overlay.textureTimings) { JSONArray(overlay.textureTimings.map { JSONArray(it.toList()) }) })
            fun capture(r: ParticleSession) {
                row.put("submitted", synchronized(r.submittedTimings) { JSONArray(r.submittedTimings.map { JSONArray(it.toList()) }) })
                row.put("committed", synchronized(r.presentedTimings) { JSONArray(r.presentedTimings.map { JSONArray(it.toList()) }) })
                row.put("draw", synchronized(r.drawTimings) { JSONArray(r.drawTimings.map { JSONArray(it.toList()) }) })
                // 第二批 F15：手势的滞后曲线（手指要的相位、实际画出的相位、可画/已提交/目标档位）。
                row.put("gesture", synchronized(r.gestureTimings) {
                    JSONArray(r.gestureTimings.map { JSONArray(it.toList()) })
                })
                row.put("startup", JSONArray(r.startupTimings.toList()))
                row.put("gestureActivatedAt", r.gestureActivatedAtNanos)
                // 2a / 2c：预计算结束时刻、倒放起点与每个采样的存活片数——
                // 「首个可见粒子帧」由分析脚本按这三项与逐帧进度算出来。
                row.put("precomputeDoneAt", r.precomputeDoneAt)
                row.put("appearanceStart", r.appearanceStartFrame)
                row.put("appearanceSamples", r.appearanceSamples)
                row.put("appearancePlaybackMs", r.appearancePlaybackMs)
                row.put("particleCount", r.particleCount)
                r.aliveCounts?.let { row.put("aliveCounts", JSONArray(it.toList())) }
            }
            // 先同步抓一份，再在 180 ms 后刷新（末尾几帧的呈现回调可能还在路上）；
            // 只靠延迟那一次时，动画层释放得晚就会赶不上 result.json 的写出。
            renderer?.let { capture(it) }
            handler.postDelayed({ renderer?.let { capture(it) } }, 180)
        }
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("at", System.nanoTime()).put("javaBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeBytes", Debug.getNativeHeapAllocatedSize())
            // 第二批起没有每次动画一条的 GL 线程；改记进程级常驻线程的条数（正常各 1）。
            .put("particleThreads", Thread.getAllStackTraces().keys.count {
                it.name == "ParticleDismissGl" || it.name == "ParticleGl" || it.name == "ParticleClock" })
    }

    fun finish() {
        if (stopped) return
        stopped = true
        Choreographer.getInstance().removeFrameCallback(this)
        ParticleBeatTrace.stop()
        val cpuEnd = Process.getElapsedCpuTime()
        val memoryEnd = memory()
        stallThread?.interrupt()
        windows.entries.toList().forEach { (w, watch) ->
            windows.remove(w)
            runCatching {
                w.removeOnFrameMetricsAvailableListener(watch.metrics)
                if (w.callback === watch.wrapper) w.callback = watch.original
            }
        }
        handler.postDelayed({
            val result = JSONObject().put("schema", 2).put("driver", "system-input-observed")
                .put("config", config).put("startedAt", startedAt).put("cpuStartMs", cpuStart).put("cpuEndMs", cpuEnd)
                .put("memoryStart", memoryStart).put("memoryEnd", memoryEnd).put("events", events)
                .put("inputs", inputs).put("states", states).put("overlays", overlayRows)
                .put("mainFrames", JSONArray(mainFrames))
                .put("windowLabels", windowLabels).put("stalls", stalls)
                // 2a：FAB 抬手 → 首页快照 → startActivity → onCreate → 布局 → 请求 → 捕获
                .put("launchTrace", JSONArray(ParticleLaunchTrace.snapshot().map {
                    JSONObject().put("label", it.label).put("at", it.at) }))
                .put("windowFrames", synchronized(frames) { JSONArray(frames.map { JSONArray(it.toList()) }) })
                // F14：两个上下文**实际读回**的优先级档位与同线程切换的累计开销。
                // 规范说请求值只是 hint，拿不到 HIGH 时这一整项的结论不成立，必须逐轮记下来。
                .put("glContext", JSONObject().put("note", ParticleGl.contextNote)
                    .put("background", ParticleGl.hasBackgroundContext)
                    .put("priorities", ParticleGl.contextPriorities?.let { JSONArray(it.toList()) }
                        ?: JSONObject.NULL)
                    .put("switchCount", ParticleGl.switchCount)
                    .put("switchMs", ParticleGl.switchNanos / 1e6))
                // 第三批：节拍级取证（类别码见 ParticleBeatTrace），外加本轮量到的 vsync 周期。
                .put("beats", JSONArray(ParticleBeatTrace.snapshot().map { JSONArray(it.toList()) }))
                .put("clock", JSONObject()
                    .put("vsyncNanos", ParticleClock.measuredVsyncNanos)
                    .put("beatNanos", ParticleClock.outputPeriodNanos)
                    .put("phaseLock", ParticlePerfTuning.beatPhaseLock))
            // 序列化失败（例如某处混进 NaN）不能静默：否则驱动脚本只会报「观察器没有收尾」，
            // 查不到真正的原因。失败时留下一份带异常的最小文件。
            runCatching { File(output, "result.json").writeText(result.toString()) }.onFailure {
                File(output, "result-error.txt").writeText(it.stackTraceToString())
            }
            metricsThread.quitSafely()
        }, 300)
    }
}
