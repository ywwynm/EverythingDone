package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 只启动指定已有记事并记录；输入由外部 UI 树驱动，不自动完成、保存或删除记事。 */
class ParticleBusinessRegressionProbe : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val run = args.getString("run_id") ?: "business"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder = File(targetContext.getExternalFilesDir(null), "presentation-business/$run").apply { mkdirs() }
        val previousStyle = ParticleStyle.current
        val previousSeed = ParticleCardSurfaceHandoff.seedForTests
        val original = args.getString("mode") == "native"
        var mediaProbe: ParticleMediaLoadProbe? = null
        try {
            runOnMainSync {
                ParticleStyle.current = ParticleStyle.of(args.getString("style"))
                ParticleCardSurfaceHandoff.seedForTests = 42017
                ParticlePresentationBusinessTrace.enable(targetContext.applicationContext as android.app.Application, original)
            }
            val id = args.getString("thing_id")?.toLongOrNull()
            val target = when (args.getString("entry")) {
                "tuning" -> com.ywwynm.everythingdone.activities.ParticleTuningActivity::class.java
                "removal" -> ThingListRemovalProbeActivity::class.java
                else -> if (id == null) ThingsActivity::class.java else DetailActivity::class.java
            }
            val intent = Intent(targetContext, target).putExtra("run_id", run)
                .putExtra("case", "mixed").putExtra("exit_chrome", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (id != null) intent.putExtra(Def.Communication.KEY_ID, id)
            val activity = startActivitySync(intent)
            waitForIdleSync()
            if (args.getString("entry") == "removal") runOnMainSync {
                val report = ParticlePresentationReports.start(activity, "批量移除虚构列表", "统一实现",
                    ParticlePresentationPolicy.debugOptions(original), "不访问或修改已有记事")
                ParticlePresentationDiagnostics.bind(activity, report)
            }
            val media = JSONObject()
            if (args.getString("media_probe") == "true") {
                runOnMainSync {
                    mediaProbe = ParticleMediaLoadProbe(activity.window.decorView)
                    media.put("initial", mediaProbe!!.inspect())
                }
                val settle = args.getString("settle_ms")?.toLongOrNull()?.coerceIn(0, 15000) ?: 0L
                if (settle > 0) SystemClock.sleep(settle)
                media.put("settleMs", settle)
            }
            if (args.getString("beat_trace") == "true") ParticleBeatTrace.start()
            ParticlePresentationDiagnostics.of(activity)?.event("benchmark_fixed_seed", mapOf("seed" to 42017,
                "scope" to "instrumentation_only_not_application_diagnostics"))
            File(folder, "ready.json").writeText(JSONObject().put("mode", if (original) "native" else "unified")
                .put("thingId", id).put("noMutationOperations", true).toString())
            val until = SystemClock.uptimeMillis() + 240000
            var injected = false
            while (!File(folder, "finish").exists() && SystemClock.uptimeMillis() < until) {
                val request = File(folder, "gesture.json")
                if (!injected && request.exists()) {
                    val gesture = JSONObject(request.readText())
                    val from = gesture.getDouble("from").toFloat()
                    val to = gesture.getDouble("to").toFloat()
                    val y = gesture.getDouble("y").toFloat()
                    if (mediaProbe != null) runOnMainSync {
                        media.put("before", mediaProbe!!.inspect(args.getString("pause_media") == "true"))
                    }
                    val cpuBefore = if (mediaProbe != null) ParticleMediaLoadProbe.cpu() else emptyMap()
                    require(kotlin.math.abs(to - from) <= targetContext.resources.displayMetrics.widthPixels * .16f)
                    val automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    val start = SystemClock.uptimeMillis()
                    fun send(action: Int, at: Long, x: Float) {
                        val wait = at - SystemClock.uptimeMillis()
                        if (wait > 0) SystemClock.sleep(wait)
                        val event = android.view.MotionEvent.obtain(start, at, action, x, y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        ParticlePresentationDiagnostics.of(activity)?.event("input", mapOf("action" to action,
                            "eventUptime" to at, "source" to "readonly_business_gesture"))
                        try { check(automation.injectInputEvent(event, false)) }
                        finally { event.recycle() }
                    }
                    send(android.view.MotionEvent.ACTION_DOWN, start, from)
                    for (step in 1..120) send(android.view.MotionEvent.ACTION_MOVE, start + step * 8,
                        from + (to - from) * step / 120)
                    for (step in 1..120) send(android.view.MotionEvent.ACTION_MOVE, start + 1200 + step * 8,
                        to + (from - to) * step / 120)
                    send(android.view.MotionEvent.ACTION_UP, start + 2260, from)
                    if (mediaProbe != null) {
                        media.put("cpuTicks", ParticleMediaLoadProbe.cpuDelta(cpuBefore))
                        media.put("ticksPerSecond", android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK))
                        runOnMainSync { media.put("after", mediaProbe!!.inspect()) }
                        File(folder, "media.json").writeText(media.toString(2))
                    }
                    File(folder, "gesture-injected.json").writeText(gesture.put("returnedToOrigin", true).toString())
                    injected = true
                }
                SystemClock.sleep(30)
            }
            val latch = CountDownLatch(1)
            if (args.getString("beat_trace") == "true") {
                ParticleBeatTrace.stop()
                File(folder, "beat.json").writeText(ParticleBeatTrace.snapshot().joinToString(
                    prefix = "[", postfix = "]") { it.joinToString(prefix = "[", postfix = "]") })
            }
            var exportFailure: Throwable? = null
            runOnMainSync {
                ParticlePresentationReports.export(targetContext) { result ->
                    try { result.getOrThrow().copyTo(File(folder, "diagnostics.zip"), overwrite = true) }
                    catch (failure: Throwable) { exportFailure = failure }
                    latch.countDown()
                }
                ParticleStyle.current = previousStyle
            }
            check(latch.await(20, TimeUnit.SECONDS))
            exportFailure?.let { throw it }
            finish(0, Bundle().apply { putString("result", "完成") })
        } catch (failure: Exception) {
            finish(1, Bundle().apply { putString("result", failure.toString()) })
        } finally {
            if (args.getString("beat_trace") == "true") ParticleBeatTrace.stop()
            runOnMainSync {
                ParticleStyle.current = previousStyle
                ParticleCardSurfaceHandoff.seedForTests = previousSeed
                ParticlePresentationPolicy.originalForDebug = false
                mediaProbe?.restore()
            }
        }
    }
}
