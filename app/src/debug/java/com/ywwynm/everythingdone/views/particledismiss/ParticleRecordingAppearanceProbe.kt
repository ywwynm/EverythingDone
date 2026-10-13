package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment
import com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.model.Thing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 真实附件→录音准备页，只读记事，不按下录音键；记录截图和 Surface 恢复状态。 */
class ParticleRecordingAppearanceProbe : Instrumentation() {
    private var args = Bundle()
    private fun <T> mainValue(block: () -> T): T = AtomicReference<T>().also { value ->
        runOnMainSync { value.set(block()) }
    }.get()
    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (!condition()) { check(SystemClock.uptimeMillis() < until) { "等待条件超时" }; SystemClock.sleep(20) }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val run = args.getString("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val output = File(targetContext.getExternalFilesDir(null), "recording-appearance/$run").apply { mkdirs() }
        val previousStyle = ParticleStyle.current
        val previousPolicy = ParticlePresentationPolicy.originalForDebug
        var activity: DetailActivity? = null
        var report: ParticlePresentationReports.Report? = null
        var callback: Choreographer.FrameCallback? = null
        val samples = JSONArray()
        val snapshots = mutableListOf<Pair<String, Bitmap>>()
        val images = args.getString("images") != "false"
        val appearanceState = BaseDialogFragment::class.java.getDeclaredField("appearanceSettled")
            .apply { isAccessible = true }
        var error: String? = null
        try {
            val id = checkNotNull(args.getString("thing_id")?.toLongOrNull())
            val thing = checkNotNull(ThingDAO.getInstance(targetContext)?.getThingById(id))
            check(thing.type == Thing.NOTE && !ThingPrivacyResolver.isEffectivelyPrivate(targetContext, thing))
            mainValue {
                ParticleStyle.current = ParticleStyle.of(args.getString("style"))
                ParticlePresentationPolicy.originalForDebug = args.getString("mode") == "native"
            }
            activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                "ParticleRecordingAppearanceProbe", id, -1).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
            val host = activity
            val warmupMs = args.getString("warmup_ms")?.toLongOrNull()?.coerceIn(0, 10000) ?: 0L
            if (warmupMs > 0) {
                val began = SystemClock.uptimeMillis()
                val cpu = ParticleMediaLoadProbe.cpu()
                val media = ParticleMediaLoadProbe(host.window.decorView)
                val load = JSONArray()
                while (SystemClock.uptimeMillis() - began < warmupMs) {
                    load.put(mainValue { media.inspect().put("ms", SystemClock.uptimeMillis() - began) })
                    SystemClock.sleep(100)
                }
                File(output, "media-load.json").writeText(JSONObject().put("samples", load)
                    .put("cpu", ParticleMediaLoadProbe.cpuDelta(cpu)).toString())
            }
            mainValue {
                report = ParticlePresentationReports.start(host, "添加附件→录音", "计时与水体交接",
                    ParticlePresentationPolicy.debugOptions(ParticlePresentationPolicy.originalForDebug, images),
                    "只读记事与录音准备页", frameCapacity = 8192, checkpoints = false)
                ParticlePresentationDiagnostics.bind(host, checkNotNull(report))
                AddAttachmentDialogFragment.newInstance().show(host.supportFragmentManager, "recording-probe-source")
            }
            await { mainValue {
                host.supportFragmentManager.fragments.any { it is AddAttachmentDialogFragment && it.dialog?.isShowing == true &&
                    appearanceState.getBoolean(it) && it.dialog?.window?.decorView?.hasWindowFocus() == true &&
                    it.dialog?.window?.decorView?.alpha == 1f && it.dialog?.window?.attributes?.alpha == 1f } &&
                    ParticleDismissOverlay.activeIn(host).none { it.parent != null }
            } }
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            var bounds: Rect? = null
            await {
                val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                automation.rootInActiveWindow?.let(pending::add)
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    if (node.viewIdResourceName?.endsWith("/tv_record_audio_as_bt") == true)
                        bounds = Rect().also(node::getBoundsInScreen)
                    for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                    node.recycle()
                }
                bounds != null
            }
            File(output, "ready.json").writeText(JSONObject().put("report", report?.id)
                .put("recordButton", bounds.toString()).toString())
            await(20000) { File(output, "start").exists() }
            val start = SystemClock.uptimeMillis()
            val seen = mutableSetOf<ParticleDismissOverlay>()
            fun readField(owner: Any, name: String): Any? = runCatching {
                owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            }.getOrNull()
            mainValue {
                callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val recording = host.supportFragmentManager.fragments.filterIsInstance<AudioRecordDialogFragment>().lastOrNull()
                        val root = recording?.dialog?.window?.decorView
                        val clock = root?.findViewById<TimelyClockView>(R.id.clock_record_audio)
                        val water = root?.findViewById<View>(R.id.voice_visualizer)
                        if (clock != null) {
                            val at = IntArray(2).also(clock::getLocationOnScreen)
                            val row = JSONObject().put("ms", SystemClock.uptimeMillis() - start)
                                .put("clockAlpha", clock.alpha).put("clockTransitionAlpha", clock.transitionAlpha)
                                .put("clockBounds", JSONArray(listOf(at[0], at[1], at[0] + clock.width, at[1] + clock.height)))
                                .put("particleHolds", water?.let { readField(it, "particleHolds") })
                                .put("hostFrozen", water?.let { readField(it, "hostFrozen") })
                            for (name in listOf("fillMode", "inkGradient", "inkStartColor", "inkEndColor", "glowBaseColor"))
                                row.put(name, readField(clock, name))
                            samples.put(row)
                        }
                        for (overlay in ParticleDismissOverlay.activeIn(host)) {
                            if (!images) break
                            if (recording == null || overlay.isDisappearance || !seen.add(overlay)) continue
                            val spec = readField(overlay, "spec") as? ParticleDismissSpec ?: continue
                            snapshots += "appearance-${seen.size}.png" to spec.snapshot.copy(Bitmap.Config.ARGB_8888, false)
                            report?.event("recording_probe_snapshot", mapOf("name" to snapshots.last().first,
                                "originX" to spec.originXPx, "originY" to spec.originYPx,
                                "width" to spec.snapshot.width, "height" to spec.snapshot.height))
                        }
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                Choreographer.getInstance().postFrameCallback(checkNotNull(callback))
            }
            val rect = checkNotNull(bounds)
            for ((action, at) in listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_UP to start + 80)) {
                val delay = at - SystemClock.uptimeMillis(); if (delay > 0) SystemClock.sleep(delay)
                val event = MotionEvent.obtain(start, at, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            await { mainValue { host.supportFragmentManager.fragments.any { it is AudioRecordDialogFragment } } }
            for (at in if (images) listOf(650L, 1000L, 1400L, 2000L, 3000L, 4500L) else listOf(4500L)) {
                val delay = start + at - SystemClock.uptimeMillis(); if (delay > 0) SystemClock.sleep(delay)
                automation.takeScreenshot()?.let { bitmap ->
                    File(output, "screen-$at.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            // 冷启动时按钮处理可能较晚；不能按注入后的固定时长关掉仍在凝聚的窗口。
            await { mainValue {
                val recording = host.supportFragmentManager.fragments.filterIsInstance<AudioRecordDialogFragment>().lastOrNull()
                recording != null && appearanceState.getBoolean(recording) &&
                    ParticleDismissOverlay.activeIn(host).none { it.parent != null }
            } }
            mainValue {
                val events = checkNotNull(report).json().getJSONArray("events")
                check((0 until events.length()).count { index ->
                    events.getJSONObject(index).let { event -> event.optString("event") == "particle_first_frame_callback" &&
                        event.optJSONObject("values")?.optBoolean("reverse") == true }
                } == 2) { "附件或录音的出现动画未真正开播" }
            }
            if (images) {
                val settled = AtomicReference<Bitmap>()
                val captureFinished = java.util.concurrent.atomic.AtomicBoolean()
                mainValue {
                    val recording = host.supportFragmentManager.fragments.filterIsInstance<AudioRecordDialogFragment>().last()
                    val window = checkNotNull(recording.dialog?.window)
                    val clock = window.decorView.findViewById<View>(R.id.clock_record_audio)
                    val location = IntArray(2).also(clock::getLocationInWindow)
                    val pixels = Bitmap.createBitmap(clock.width, clock.height, Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(window, Rect(location[0], location[1],
                        location[0] + clock.width, location[1] + clock.height), pixels, { status ->
                        if (status == android.view.PixelCopy.SUCCESS) snapshots += "window-clock.png" to pixels
                        else pixels.recycle()
                        ParticleHardwareSnapshot.capture(window.decorView) {
                            settled.set(it); captureFinished.set(true)
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                await { captureFinished.get() }
                settled.get()?.let { snapshots += "settled-capture.png" to it }
            }
            mainValue {
                checkNotNull(callback).let { Choreographer.getInstance().removeFrameCallback(it) }; callback = null
                host.supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>().forEach { it.dismiss() }
            }
            await { mainValue {
                host.supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>().none { it.dialog?.isShowing == true } &&
                    ParticleDismissOverlay.activeIn(host).none { it.parent != null }
            } }
        } catch (failure: Throwable) { error = failure.stackTraceToString() }
        finally {
            mainValue {
                callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
                activity?.finish()
                ParticleStyle.current = previousStyle
                ParticlePresentationPolicy.originalForDebug = previousPolicy
            }
            snapshots.forEach { (name, bitmap) ->
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            File(output, "samples.json").writeText(samples.toString())
            report?.let { File(output, "report.json").writeText(it.json().toString(2)) }
            File(output, "result.json").writeText(JSONObject().put("error", error ?: JSONObject.NULL).toString())
            finish(if (error == null) 0 else -1, Bundle().apply { putString("result", error ?: "完成") })
        }
    }
}
