package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolGlRenderer
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolGl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 同一个真实详情页内反复打开，不录音、不改变记事。图像捕获与性能统计分开运行。 */
class ParticleRepeatProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val output = File(getExternalFilesDir(null), "particle-repeat/$run").apply { mkdirs() }
        val thing = App.getRunningDetailActivities().lastOrNull()?.let {
            ThingDAO.getInstance(this)?.getThingById(it)
        }
        if (thing == null || thing.type != Thing.NOTE || ThingPrivacyResolver.isEffectivelyPrivate(this, thing)) {
            File(output, "result.json").writeText("{\"error\":\"先打开一条普通记事\"}")
            finish(); return
        }
        application.registerActivityLifecycleCallbacks(RepeatProbe(application, output,
            intent.getStringExtra("kind") ?: "attachment", intent.getIntExtra("repeat", 8).coerceIn(1, 30),
            intent.getBooleanExtra("capture", false), intent.getBooleanExtra("menu", true)))
        startActivity(DetailActivity.getOpenIntentForUpdate(this, "ParticleRepeatProbe", thing.id, -1))
        finish()
    }
}

private fun field(owner: Any, name: String): Any? = try {
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
} catch (_: NoSuchFieldException) {
    (owner.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(owner) as Lazy<*>).value
}

private class RepeatProbe(val app: Application, val output: File, val kind: String, val rounds: Int,
                          val capture: Boolean, val menu: Boolean) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var host: DetailActivity? = null
    private var source: AddAttachmentDialogFragment? = null
    private var cycle = 0
    private var stage = 0
    private var stageAt = 0L
    private var started = 0L
    private var done = false
    private val events = JSONArray()
    private val samples = JSONArray()
    private val rows = mutableListOf<JSONObject>()
    private val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ParticleDismissOverlay, Boolean>())
    private val waveRows = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    private val windowRows = JSONArray()
    private val windowListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) > 20_000_000L) {
            windowRows.put(JSONObject().put("ns", System.nanoTime()).put("ms", time())
                .put("total", metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6)
                .put("draw", metrics.getMetric(android.view.FrameMetrics.DRAW_DURATION) / 1e6)
                .put("sync", metrics.getMetric(android.view.FrameMetrics.SYNC_DURATION) / 1e6)
                .put("command", metrics.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) / 1e6)
                .put("swap", metrics.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION) / 1e6))
        }
    }
    private fun time() = SystemClock.uptimeMillis() - started
    private fun event(name: String) { events.put(JSONObject().put("cycle", cycle).put("event", name).put("ms", time())) }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
            if (f is AddAttachmentDialogFragment) source = f
            if (capture) visit(v) { if (it is WaveVisualizerFableSolGl) watchWave(it, cycle) }
        }
    }

    override fun onActivityResumed(a: Activity) {
        if (a !is DetailActivity || host != null) return
        host = a
        a.window.decorView.post {
            started = SystemClock.uptimeMillis()
            a.window.addOnFrameMetricsAvailableListener(windowListener, handler)
            a.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, false)
            open()
            Choreographer.getInstance().postFrameCallback { tick() }
        }
    }
    private fun open() {
        val a = checkNotNull(host)
        if (menu && (a.findViewById<View>(R.id.act_add_attachment)?.width ?: 0) == 0) {
            if (time() - stageAt > 4000) finish("工具栏附件菜单未就绪")
            else handler.postDelayed({ open() }, 16)
            return
        }
        stage = 0; stageAt = time(); event("open")
        source = null
        if (menu) {
            val item = checkNotNull(a.findViewById<View>(R.id.act_add_attachment))
            val where = IntArray(2); item.getLocationInWindow(where)
            val x = where[0] + item.width / 2f; val y = where[1] + item.height / 2f
            val down = SystemClock.uptimeMillis()
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also { a.dispatchTouchEvent(it); it.recycle() }
            handler.postDelayed({
                event("menu-up")
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                    .also { a.dispatchTouchEvent(it); it.recycle() }
            }, 90)
        } else source = AddAttachmentDialogFragment.newInstance().also {
            it.show(a.supportFragmentManager, AddAttachmentDialogFragment.TAG)
        }
    }
    private fun visit(v: View, action: (View) -> Unit) {
        action(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), action)
    }
    private fun tick() {
        if (done) return
        try {
            val a = checkNotNull(host)
            val root = a.window.decorView as ViewGroup
            val active = (0 until root.childCount).mapNotNull { root.getChildAt(it) as? ParticleDismissOverlay }
            for (overlay in active) {
                if (!seen.add(overlay)) continue
                val spec = field(overlay, "spec") as ParticleDismissSpec
                val row = JSONObject().put("cycle", cycle).put("createdMs", time()).put("reverse", spec.reverse)
                    .put("width", spec.snapshot.width).put("height", spec.snapshot.height)
                rows += row
                overlay.afterRelease {
                    row.put("removedMs", time())
                    val renderer = field(overlay, "renderer") as? ParticleDismissRenderer
                    renderer?.let { r ->
                        row.put("submitted", synchronized(r.submittedTimings) { JSONArray(r.submittedTimings.map { JSONArray(it.toList()) }) })
                        row.put("committed", synchronized(r.presentedTimings) { JSONArray(r.presentedTimings.map { JSONArray(it.toList()) }) })
                        row.put("draws", JSONArray(r.drawTimings.map { JSONArray(it.toList()) }))
                    }
                }
            }
            samples.put(JSONObject().put("ms", time()).put("ns", System.nanoTime()).put("cycle", cycle).put("stage", stage)
                .put("heap", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                .put("nativeHeap", android.os.Debug.getNativeHeapAllocatedSize()))
            val elapsed = time() - stageAt
            if (stage == 0 && elapsed > 1300 && active.isEmpty() && source?.dialog?.window?.attributes?.alpha == 1f) {
                event("close-or-record")
                if (kind == "record") {
                    val dialog = source!!.dialog!!
                    val button = dialog.findViewById<View>(R.id.tv_record_audio_as_bt)
                    val where = IntArray(2); button.getLocationInWindow(where)
                    val x = where[0] + button.width / 2f; val y = where[1] + button.height / 2f
                    val down = SystemClock.uptimeMillis()
                    MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also { dialog.dispatchTouchEvent(it); it.recycle() }
                    handler.postDelayed({
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                            .also { dialog.dispatchTouchEvent(it); it.recycle() }
                    }, 80)
                } else (source!!.dialog as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
                stage = 1; stageAt = time()
            } else if (stage == 1 && elapsed > (if (kind == "record") 2600 else 1300) && active.isEmpty()) {
                if (kind == "record") {
                    event("close-record")
                    a.supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>().forEach { it.dismiss() }
                }
                stage = 2; stageAt = time()
            } else if (stage == 2 && elapsed > (if (kind == "record") 1200 else 200) && active.isEmpty()) {
                event("cycle-done")
                cycle++
                if (cycle >= rounds) { finish(null); return }
                // 每次仅保留数字，不能用探针对旧叠加层的强引用制造内存累积。
                seen.clear()
                open()
            }
            check(elapsed < 12000) { "窗口未完成：cycle=$cycle stage=$stage" }
            Choreographer.getInstance().postFrameCallback { tick() }
        } catch (e: Throwable) { finish(e.stackTraceToString()) }
    }

    private fun watchWave(view: WaveVisualizerFableSolGl, round: Int) {
        val renderer = field(field(view, "renderThread")!!, "renderer") as FableSolGlRenderer
        var frozenCount = 0; var liveCount = 0
        renderer.presentationObserver = { r ->
            val frozen = field(r, "frozen") as Boolean
            val count = if (frozen) frozenCount++ else liveCount++
            if ((frozen && count < 2) || (!frozen && count < 4)) {
                val width = field(r, "width") as Int; val height = field(r, "height") as Int
                val buffer = java.nio.ByteBuffer.allocateDirect(width * height * 16).order(java.nio.ByteOrder.nativeOrder())
                val floats = buffer.asFloatBuffer()
                val beforeError = android.opengl.GLES30.glGetError()
                android.opengl.GLES30.glReadPixels(0, 0, width, height, android.opengl.GLES30.GL_RGBA,
                    android.opengl.GLES30.GL_FLOAT, floats)
                val bytes = ByteArray(buffer.capacity()); buffer.get(bytes)
                val filename = "$round-${if (frozen) "frozen" else "live"}-$count"
                val row = JSONObject().put("file", "$filename.f32.gz").put("width", width).put("height", height)
                    .put("ms", time()).put("frozen", frozen)
                    .put("beforeError", beforeError)
                    .put("linear", field(r, "sceneLinear"))
                    .put("columns", field(r, "columns")).put("vertexFloatCount", field(r, "vertexFloatCount"))
                    .put("error", android.opengl.GLES30.glGetError())
                val vertices = field(r, "vertexData") as FloatArray
                val countVertices = (field(r, "vertexFloatCount") as Int) / 9
                row.put("zeroPositions", (0 until countVertices).count { vertices[it*9] == 0f && vertices[it*9+1] == 0f })
                android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, field(r, "sceneFramebufferId") as Int)
                floats.clear()
                android.opengl.GLES30.glReadPixels(0, 0, width, height, android.opengl.GLES30.GL_RGBA,
                    android.opengl.GLES30.GL_FLOAT, floats)
                buffer.position(0)
                val sceneBytes = ByteArray(buffer.capacity()); buffer.get(sceneBytes)
                android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, 0)
                row.put("sceneError", android.opengl.GLES30.glGetError())
                waveRows += row
                writer.execute {
                    try {
                        java.util.zip.GZIPOutputStream(File(output, "$filename.f32.gz").outputStream()).use { it.write(bytes) }
                        java.util.zip.GZIPOutputStream(File(output, "$filename-scene.f32.gz").outputStream()).use { it.write(sceneBytes) }
                        File(output, "wave-frames.jsonl").appendText(row.toString() + "\n")
                    } catch (e: Exception) {
                        handler.post { finish("帧捕获失败：${e.message}") }
                    }
                }
            }
        }
    }
    private fun finish(error: String?) {
        if (done) return
        done = true; handler.removeCallbacksAndMessages(null)
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host?.window?.removeOnFrameMetricsAvailableListener(windowListener)
        val result = JSONObject().put("kind", kind).put("menu", menu).put("rounds", rounds).put("events", events)
            .put("samples", samples).put("overlays", JSONArray(rows))
            .put("windowFrames", windowRows)
            .put("waves", synchronized(waveRows) { JSONArray(waveRows) })
        if (error != null) result.put("error", error)
        writer.execute { File(output, "result.json").writeText(result.toString(2)) }
        writer.shutdown()
        host?.finish()
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
