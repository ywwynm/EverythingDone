package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.Choreographer
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅由回归 Instrumentation 显式启动；正常应用启动不会注册观察器。 */
internal object AppearancePanelFrameObserver {
    private var collector: PanelFrames? = null
    fun start(activity: ThingsActivity, config: JSONObject) {
        collector?.finish()
        collector = PanelFrames(activity, config).also { it.start() }
    }
    fun flush() { collector?.finish() }
    fun blockedSince(): Long = collector?.blockedSince ?: 0L
    fun screenshotError(bitmap: Bitmap): Double = checkNotNull(collector).screenshotError(bitmap)
}

private class PanelFrames(val activity: ThingsActivity, val config: JSONObject) {
    private val main = Handler(Looper.getMainLooper())
    private val root get() = activity.window.decorView
    private val panel get() = activity.findViewById<View>(R.id.thing_card_appearance_panel)
    private val started = SystemClock.uptimeMillis()
    private val samples = JSONArray()
    private val trees = JSONArray()
    private val pixels = JSONArray()
    private var reference: IntArray? = null
    private var referenceId = 0
    private var readUntil = 0L
    private var reading = false
    var blockedSince = 0L
        private set
    private var blockedId = 0
    private val windowBitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
    private val particleBitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
    private val seen = mutableSetOf<Int>()
    private val originalStyle = ParticleStyle.current
    private var previousTree = ""
    private var done = false
    private val progress = ParticleDismissOverlay::class.java.getDeclaredField("presentedProgress").apply { isAccessible = true }
    private val prep = ParticleDismissController::class.java.getDeclaredField("appearancePreparations").apply { isAccessible = true }
    private val spec = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
    private val output: File
    init {
        val id = config.getString("run_id")
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(activity.getExternalFilesDir(null), "appearance-panel-frames/$id").apply { mkdirs() }
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        sample("pre")
        val holdMs = config.optLong("hold_window_ms", 0)
        val p = panel
        if (holdMs > 0 && p?.visibility == View.VISIBLE && p.alpha == 1f && referenceId != 0 && referenceId != blockedId) {
            blockedId = referenceId
            blockedSince = SystemClock.uptimeMillis()
        }
        blockedSince == 0L || SystemClock.uptimeMillis() - blockedSince >= holdMs
    }
    private val draw = ViewTreeObserver.OnDrawListener { sample("draw") }
    fun start() {
        config.optString("style").takeIf { it.isNotEmpty() }?.let { ParticleStyle.current = ParticleStyle.of(it) }
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        root.viewTreeObserver.addOnDrawListener(draw)
        Choreographer.getInstance().postFrameCallback(pixelFrame)
        main.postDelayed({ finish() }, config.optLong("duration_ms", 180000).coerceIn(10000, 300000))
    }
    private fun sample(phase: String) {
        if (done) return
        val view = panel ?: return
        val overlays = ParticleDismissOverlay.activeIn(activity)
        val at = IntArray(2); view.getLocationOnScreen(at)
        val overlayRows = JSONArray()
        overlays.forEach { overlay ->
            val id = System.identityHashCode(overlay)
            val s = spec.get(overlay) as ParticleDismissSpec
            overlayRows.put(JSONArray(listOf(id, overlay.isDisappearance, progress.getFloat(overlay),
                overlay.visibility, overlay.alpha)))
            if (seen.add(id)) {
                trees.put(JSONObject().put("ms", time()).put("overlay", id)
                    .put("reverse", s.reverse).put("x", s.originXPx).put("y", s.originYPx)
                    .put("w", s.snapshot.width).put("h", s.snapshot.height).put("tree", tree(view)))
                if (s.reverse && view.visibility == View.VISIBLE && s.snapshot.height >= view.height) {
                    val cropLeft = (at[0] - s.originXPx).toInt().coerceAtLeast(0)
                    val cropTop = (at[1] - s.originYPx).toInt().coerceAtLeast(0)
                    val body = Bitmap.createBitmap(s.snapshot, cropLeft, cropTop, view.width, view.height)
                    val scaled = Bitmap.createScaledBitmap(body, 48, 64, true)
                    reference = IntArray(48 * 64).also { scaled.getPixels(it, 0, 48, 0, 0, 48, 64) }
                    if (scaled !== s.snapshot) scaled.recycle()
                    if (body !== s.snapshot && body !== scaled) body.recycle()
                    referenceId = id
                    readUntil = time() + 1800
                }
            }
        }
        val preparing = (prep.get(ParticleDismissController) as Map<*, *>).containsKey(view)
        if (view.visibility != View.VISIBLE && overlays.isEmpty() && phase == "draw") return
        if (phase == "pre" && view.visibility == View.VISIBLE) {
            val tree = tree(view).toString()
            if (tree != previousTree) { previousTree = tree; trees.put(JSONObject().put("ms", time()).put("tree", JSONArray(tree))) }
        }
        samples.put(JSONArray(listOf(time(), phase, view.visibility, view.alpha, at[0], at[1],
            view.width, view.height, view.translationX, view.translationY, view.scaleX, view.scaleY,
            view.elevation, view.isLayoutRequested, preparing, overlayRows)))
    }
    private fun tree(view: View): JSONArray {
        val rows = JSONArray()
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            rows.put(JSONArray(listOf(if (v.id == View.NO_ID) "none" else v.resources.getResourceEntryName(v.id),
                v.left, v.top, v.right, v.bottom, v.alpha, v.translationX, v.translationY,
                v.scaleX, v.scaleY, v.scrollX, v.scrollY,
                (v as? ViewGroup)?.layoutTransition?.isRunning == true)))
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(view)
        return rows
    }
    private fun time() = SystemClock.uptimeMillis() - started
    fun screenshotError(bitmap: Bitmap): Double {
        val p = checkNotNull(panel)
        val at = IntArray(2); p.getLocationOnScreen(at)
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
        val body = Bitmap.createBitmap(software, at[0], at[1], p.width, p.height)
        val scaled = Bitmap.createScaledBitmap(body, 48, 64, true)
        val data = IntArray(48 * 64).also { scaled.getPixels(it, 0, 48, 0, 0, 48, 64) }
        val expected = checkNotNull(reference)
        var error = 0L
        for (i in data.indices) for (shift in intArrayOf(0, 8, 16)) error += kotlin.math.abs((data[i] shr shift and 255) - (expected[i] shr shift and 255))
        if (scaled !== body) scaled.recycle()
        if (body !== software) body.recycle()
        if (software !== bitmap) software.recycle()
        return error.toDouble() / (data.size * 3)
    }
    private val pixelFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (done) return
            if (!reading && reference != null && time() < readUntil) readPixels()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun readPixels() {
        val view = panel ?: return
        if (view.width <= 0 || view.height <= 0) return
        val origin = IntArray(2); view.getLocationInWindow(origin)
        val rect = Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
        val row = JSONObject().put("ms", time()).put("id", referenceId).put("alpha", view.alpha).put("visibility", view.visibility)
        val expected = reference ?: return
        fun stats(bitmap: Bitmap): JSONArray {
            val data = IntArray(48 * 64); bitmap.getPixels(data, 0, 48, 0, 0, 48, 64)
            var error = 0L; var alpha = 0L; var same = 0
            for (i in data.indices) {
                val a = data[i]; val b = expected[i]
                val d = kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255)) +
                    kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255)) + kotlin.math.abs((a and 255) - (b and 255))
                error += d; alpha += (a ushr 24); if (d <= 9) same++
            }
            return JSONArray(listOf(error.toDouble() / (data.size * 3), alpha.toDouble() / (data.size * 255), same.toDouble() / data.size))
        }
        fun surface(v: View): ParticleOverlaySurfaceView? {
            if (v is ParticleOverlaySurfaceView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) surface(v.getChildAt(i))?.let { return it }
            return null
        }
        reading = true
        var remaining = 2
        fun complete() { remaining--; if (remaining == 0) { pixels.put(row); reading = false } }
        PixelCopy.request(activity.window, rect, windowBitmap, callback@{ result ->
            if (done) return@callback
            row.put("windowStatus", result).put("windowMs", time())
            if (result == PixelCopy.SUCCESS) row.put("window", stats(windowBitmap))
            complete()
        }, main)
        val particle = surface(root)
        if (particle == null || !particle.holder.surface.isValid || done) complete()
        else {
            val at = IntArray(2); particle.getLocationInWindow(at)
            val local = Rect(rect).apply { offset(-at[0], -at[1]) }
            PixelCopy.request(particle, local, particleBitmap, callback@{ status ->
                if (done) return@callback
                row.put("particleStatus", status).put("particleMs", time())
                if (status == PixelCopy.SUCCESS) row.put("particle", stats(particleBitmap))
                complete()
            }, main)
        }
    }
    fun finish() {
        if (done) return
        done = true
        root.viewTreeObserver.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnDrawListener(draw) }
        ParticleStyle.current = originalStyle
        val data = JSONObject().put("epoch", System.currentTimeMillis() - time()).put("frames", samples).put("trees", trees).put("pixels", pixels)
        Thread { File(output, "frames.json").writeText(data.toString()) }.start()
    }
}
