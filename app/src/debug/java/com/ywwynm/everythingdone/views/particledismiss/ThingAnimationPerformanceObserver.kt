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
    private data class Watch(val activity: Activity, val original: Window.Callback,
                             val wrapper: Window.Callback, val metrics: Window.OnFrameMetricsAvailableListener)
    private val windows = mutableMapOf<Window, Watch>()
    private val seenOverlays = WeakHashMap<ParticleDismissOverlay, Boolean>()
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
    private val rendererField = ParticleDismissOverlay::class.java.getDeclaredField("renderer").apply { isAccessible = true }
    private var lastInputAt = 0L
    private var lastLifecycleAt = 0L
    private var lastAction = -1
    private var lastStatus = ""
    private var editorReady = false
    private var idleAt = 0L
    private var stopped = false

    fun start() { progress(false, true); Choreographer.getInstance().postFrameCallback(this) }
    fun event(label: String) {
        if (label.endsWith("-resumed")) { lastLifecycleAt = System.nanoTime(); idleAt = 0 }
        events.put(JSONObject().put("event", label).put("at", System.nanoTime()).put("cpuMs", Process.getElapsedCpuTime()))
    }

    fun watch(a: Activity) {
        if (stopped || a !is ThingsActivity && a !is DetailActivity || windows.containsKey(a.window)) return
        val w = a.window
        val type = if (a is ThingsActivity) 0L else 1L
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
        var busy = false
        var overlays = 0
        try {
            for ((w, watched) in windows) {
                val a = watched.activity
                val root = w.decorView as ViewGroup
                for (i in 0 until root.childCount) {
                    val overlay = root.getChildAt(i) as? ParticleDismissOverlay ?: continue
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
            overlay.afterRelease { ParticleTrajectoryCheck.run(context, copied, overlay.width, overlay.height, output, inspect) }
        }
        val row = JSONObject().put("stage", label).put("requestAt", spec.requestedAtNanos)
            .put("createdAt", System.nanoTime()).put("reverse", spec.reverse)
            .put("width", spec.snapshot.width).put("height", spec.snapshot.height)
        overlayRows.put(row)
        overlay.afterRelease {
            row.put("removedAt", System.nanoTime())
            val renderer = rendererField.get(overlay) as? ParticleDismissRenderer
            row.put("textures", synchronized(overlay.textureTimings) { JSONArray(overlay.textureTimings.map { JSONArray(it.toList()) }) })
            handler.postDelayed({
                renderer?.let { r ->
                    row.put("submitted", synchronized(r.submittedTimings) { JSONArray(r.submittedTimings.map { JSONArray(it.toList()) }) })
                    row.put("committed", synchronized(r.presentedTimings) { JSONArray(r.presentedTimings.map { JSONArray(it.toList()) }) })
                    row.put("draw", JSONArray(r.drawTimings.map { JSONArray(it.toList()) }))
                    row.put("startup", JSONArray(r.startupTimings.toList()))
                    row.put("gestureActivatedAt", r.gestureActivatedAtNanos)
                }
            }, 180)
        }
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("at", System.nanoTime()).put("javaBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeBytes", Debug.getNativeHeapAllocatedSize())
            .put("particleThreads", Thread.getAllStackTraces().keys.count { it.name == "ParticleDismissGl" })
    }

    fun finish() {
        if (stopped) return
        stopped = true
        Choreographer.getInstance().removeFrameCallback(this)
        val cpuEnd = Process.getElapsedCpuTime()
        val memoryEnd = memory()
        windows.values.map { it.activity }.toList().forEach { unwatch(it) }
        handler.postDelayed({
            val result = JSONObject().put("schema", 2).put("driver", "system-input-observed")
                .put("config", config).put("startedAt", startedAt).put("cpuStartMs", cpuStart).put("cpuEndMs", cpuEnd)
                .put("memoryStart", memoryStart).put("memoryEnd", memoryEnd).put("events", events)
                .put("inputs", inputs).put("states", states).put("overlays", overlayRows)
                .put("mainFrames", JSONArray(mainFrames))
                .put("windowFrames", synchronized(frames) { JSONArray(frames.map { JSONArray(it.toList()) }) })
            File(output, "result.json").writeText(result.toString())
            metricsThread.quitSafely()
        }, 300)
    }
}
