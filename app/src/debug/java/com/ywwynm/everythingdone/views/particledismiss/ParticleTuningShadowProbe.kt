package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ParticleTuningActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 实际设置页的只读预览回归；不触碰用户记事，也不改粒子参数。 */
class ParticleTuningShadowProbe : Instrumentation() {
    private var args = Bundle()
    private fun <T> mainValue(block: () -> T): T = AtomicReference<T>().also { value ->
        runOnMainSync { value.set(block()) }
    }.get()
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < until)
            val window = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
            if (window?.findAccessibilityNodeInfosByText("Android 应用兼容性")?.isNotEmpty() == true) {
                window.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(16)
        }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val run = args.getString("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val output = File(targetContext.getExternalFilesDir(null), "tuning-shadow/$run").apply { mkdirs() }
        var activity: ParticleTuningActivity? = null
        var report: ParticlePresentationReports.Report? = null
        var callback: Choreographer.FrameCallback? = null
        val samples = JSONArray()
        val captures = mutableListOf<Pair<String, Bitmap>>()
        val previousStyle = ParticleStyle.current
        var error: String? = null
        try {
            activity = startActivitySync(Intent(targetContext, ParticleTuningActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ParticleTuningActivity
            val host = activity
            await { mainValue { host.hasWindowFocus() && host.findViewById<View>(R.id.cv_particle_tuning_card)?.isShown == true } }
            val card = mainValue { host.findViewById<View>(R.id.cv_particle_tuning_card) }
            val bounds = mainValue { Rect().also { check(card.getGlobalVisibleRect(it)) } }
            mainValue {
                ParticleStyle.current = ParticleStyle.of(args.getString("style"))
                report = ParticlePresentationReports.start(host, "设置页模拟卡片", "首尾阴影",
                    ParticlePresentationPolicy.debugOptions(false, true), "实际设置页；不改记事",
                    frameCapacity = 8192, checkpoints = false)
                ParticlePresentationDiagnostics.bind(host, checkNotNull(report))
            }
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            fun screenshot(name: String) { automation.takeScreenshot()?.let { captures += name to it } }
            screenshot("before.png")
            File(output, "ready.json").writeText(JSONObject().put("report", report?.id)
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))).toString())
            await { File(output, "start").exists() }
            val started = SystemClock.uptimeMillis()
            val seen = mutableSetOf<ParticleDismissOverlay>()
            mainValue {
                callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val view = host.findViewById<View>(R.id.cv_particle_tuning_card)
                        val row = JSONObject().put("ms", SystemClock.uptimeMillis() - started)
                            .put("elevation", view?.elevation).put("translationZ", view?.translationZ)
                            .put("alpha", view?.alpha).put("translationX", view?.translationX)
                            .put("itemHelperElevation", view?.getTag(androidx.recyclerview.R.id.item_touch_helper_previous_elevation))
                        samples.put(row)
                        ParticleDismissOverlay.activeIn(host).forEach { overlay ->
                            if (seen.add(overlay)) {
                                val spec = ParticleDismissOverlay::class.java.getDeclaredField("spec")
                                    .apply { isAccessible = true }.get(overlay) as ParticleDismissSpec
                                captures += "material-${seen.size}-${spec.reverse}.png" to spec.snapshot.copy(Bitmap.Config.ARGB_8888, false)
                                row.put("material", seen.size).put("originX", spec.originXPx).put("originY", spec.originYPx)
                            }
                        }
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                Choreographer.getInstance().postFrameCallback(checkNotNull(callback))
            }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            val x = bounds.left + bounds.width() * .85f
            val distance = bounds.width() * if (args.getString("gesture") == "cancel") .18f else .75f
            touch(MotionEvent.ACTION_DOWN, x)
            for (step in 1..36) {
                val until = down + step * 16
                val delay = until - SystemClock.uptimeMillis(); if (delay > 0) SystemClock.sleep(delay)
                touch(MotionEvent.ACTION_MOVE, x - distance * step / 36f)
            }
            SystemClock.sleep(96)
            touch(MotionEvent.ACTION_UP, x - distance)
            val returning = ParticleTuningActivity::class.java.getDeclaredField("returning").apply { isAccessible = true }
            val condensing = ParticleTuningActivity::class.java.getDeclaredField("condensing").apply { isAccessible = true }
            await { mainValue {
                val view = host.findViewById<View>(R.id.cv_particle_tuning_card)
                seen.size >= (if (args.getString("gesture") == "cancel") 1 else 2) &&
                    !returning.getBoolean(host) && !condensing.getBoolean(host) && view.alpha == 1f &&
                    view.translationX == 0f && ParticleDismissOverlay.activeIn(host).isEmpty()
            } }
            SystemClock.sleep(100)
            screenshot("after.png")
        } catch (failure: Throwable) { error = failure.stackTraceToString() }
        finally {
            mainValue {
                callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
                report?.let { File(output, "report.json").writeText(it.json().toString()) }
                activity?.let { ParticlePresentationDiagnostics.unbind(it); it.finish() }
                ParticleStyle.current = previousStyle
            }
            captures.forEach { (name, pixels) ->
                File(output, name).outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
                pixels.recycle()
            }
            File(output, "samples.json").writeText(samples.toString())
            File(output, "result.json").writeText(JSONObject().put("error", error ?: JSONObject.NULL).toString())
            finish(if (error == null) 0 else 1, Bundle().apply { putString("result", error ?: "完成") })
        }
    }
}
