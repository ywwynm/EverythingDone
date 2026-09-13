package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment
import com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅调试：真实详情页与真实按钮触摸；只打开录音准备界面，不启动录音、不写记事。 */
class ParticleTransitionProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val run = intent.getStringExtra("run_id") ?: "latest"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val output = File(getExternalFilesDir(null), "particle-transition/$run").apply { mkdirs() }
        val id = App.getRunningDetailActivities().lastOrNull()
        val thing = id?.let { ThingDAO.getInstance(this)?.getThingById(it) }
        if (thing == null || thing.type != Thing.NOTE || ThingPrivacyResolver.isEffectivelyPrivate(this, thing)) {
            File(output, "result.json").writeText("{\"error\":\"先打开一条普通记事\"}")
            finish(); return
        }
        val kind = intent.getStringExtra("kind") ?: "record"
        val probe = TransitionProbe(application, output, kind, intent.getBooleanExtra("capture", false),
            intent.getBooleanExtra("profile", false))
        application.registerActivityLifecycleCallbacks(probe)
        startActivity(DetailActivity.getOpenIntentForUpdate(this, "ParticleTransitionProbe", thing.id, -1))
        finish()
    }
}

private class TransitionProbe(
    val app: Application, val output: File, val kind: String, val capture: Boolean, val profile: Boolean
) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var host: DetailActivity? = null
    private var source: BaseDialogFragment? = null
    private var button: View? = null
    private var ripple: GradientRippleDrawable? = null
    private var started = 0L
    private var clickAt = 0L
    private var closeAt = 0L
    private var done = false
    private val samples = JSONArray()
    private val textures = linkedMapOf<TextureView, JSONObject>()
    private val overlays = linkedMapOf<ParticleDismissOverlay, JSONObject>()
    private val events = JSONArray()
    private var cleanChecked = false
    private var cleanMatches: Boolean? = null
    @Volatile private var profiling = false
    private val stacks = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val windowFrames = JSONArray()
    private val watchedWindows = mutableSetOf<android.view.Window>()
    private val frameListener = android.view.Window.OnFrameMetricsAvailableListener { w, metrics, dropped ->
        windowFrames.put(JSONObject().put("ms", time()).put("source", w === source?.dialog?.window)
            .put("totalMs", metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6)
            .put("drawMs", metrics.getMetric(android.view.FrameMetrics.DRAW_DURATION) / 1e6)
            .put("syncMs", metrics.getMetric(android.view.FrameMetrics.SYNC_DURATION) / 1e6)
            .put("droppedReports", dropped))
    }

    private fun time() = SystemClock.uptimeMillis() - started
    private fun event(name: String) { events.put(JSONObject().put("event", name).put("ms", time())) }
    private val fragmentTimings = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
            event("${f.javaClass.simpleName}-attach")
        }
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
            event("${f.javaClass.simpleName}-view")
        }
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            event("${f.javaClass.simpleName}-start")
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity !is DetailActivity || host != null) return
        host = activity
        activity.window.decorView.post {
            started = SystemClock.uptimeMillis()
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentTimings, false)
            source = (if (kind in setOf("confirm", "cancel")) chooser(activity)
                else AddAttachmentDialogFragment.newInstance()).also {
                it.show(activity.supportFragmentManager, "transition-source")
            }
            event("source-request")
            Choreographer.getInstance().postFrameCallback { sample() }
        }
    }

    private fun chooser(activity: DetailActivity) = ChooserDialogFragment().apply {
        setTitle("普通下一弹窗")
        setItems(mutableListOf("第一项", "第二项", "第三项"))
        setAccentBackground(activity.getAccentBackground())
    }

    private fun sample() {
        if (done) return
        try {
            val activity = checkNotNull(host)
            val root = activity.window.decorView as ViewGroup
            (listOf(activity.window) + activity.supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>()
                .mapNotNull { it.dialog?.window }).forEach { w ->
                if (watchedWindows.add(w)) w.addOnFrameMetricsAvailableListener(frameListener, handler)
            }
            for (i in 0 until root.childCount) {
                val overlay = root.getChildAt(i) as? ParticleDismissOverlay ?: continue
                if (overlays.containsKey(overlay)) continue
                val spec = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
                    .get(overlay) as ParticleDismissSpec
                val row = JSONObject().put("createdMs", time()).put("reverse", spec.reverse)
                    .put("width", spec.snapshot.width).put("height", spec.snapshot.height)
                overlays[overlay] = row
                overlay.afterRelease {
                    row.put("removedMs", time())
                    val renderer = ParticleDismissOverlay::class.java.getDeclaredField("renderer").apply { isAccessible = true }
                        .get(overlay) as? ParticleDismissRenderer
                    renderer?.submittedTimings?.let { rows ->
                        row.put("submitted", synchronized(rows) { JSONArray(rows.map { JSONArray(it.toList()) }) })
                    }
                    renderer?.presentedTimings?.let { rows ->
                        row.put("committed", synchronized(rows) { JSONArray(rows.map { JSONArray(it.toList()) }) })
                    }
                }
                val texture = (0 until overlay.childCount).mapNotNull { overlay.getChildAt(it) as? TextureView }.first()
                val delegate = texture.surfaceTextureListener!!
                val frames = JSONArray(); row.put("presentedMs", frames); textures[texture] = row
                val frameDetails = JSONArray(); row.put("presentationDetails", frameDetails)
                texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener by delegate {
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        delegate.onSurfaceTextureUpdated(surface)
                        if (!spec.reverse && surface.timestamp == 0L) {
                            row.put("blankBufferKeptSnapshot", (0 until overlay.childCount)
                                .map { overlay.getChildAt(it) }.filterIsInstance<android.widget.ImageView>()
                                .any { it.visibility == View.VISIBLE })
                        }
                        frames.put(time())
                        frameDetails.put(JSONObject().put("at", System.nanoTime()).put("timestamp", surface.timestamp))
                    }
                }
            }
            val dialog = source?.dialog
            val buttonId = when (kind) {
                "confirm" -> R.id.tv_confirm_as_bt_fragment_chooser
                "cancel" -> R.id.tv_cancel_as_bt_fragment_chooser
                else -> R.id.tv_record_audio_as_bt
            }
            val item = button ?: dialog?.findViewById<View>(buttonId)?.also {
                button = it
                ripple = (it.background as? GradientRippleDrawable) ?: (it.foreground as? GradientRippleDrawable)
                if (kind in setOf("chooser", "close")) it.setOnClickListener {
                    if (kind == "chooser") chooser(activity).show(activity.supportFragmentManager, "next-chooser")
                    source?.dismiss()
                }
            }
            fun field(name: String): Float = ripple?.let {
                GradientRippleDrawable::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(it)
            } ?: 0f
            val dims = JSONArray()
            activity.supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>().forEach { f ->
                val d = f.dialog ?: return@forEach
                val layer = runCatching { d.javaClass.getDeclaredField("dimLayer").apply { isAccessible = true }.get(d) }.getOrNull()
                val dim = layer?.let { DialogDimLayer::class.java.getDeclaredField("view").apply { isAccessible = true }.get(it) as View }
                dims.put(JSONObject().put("source", f === source).put("alpha", dim?.alpha ?: 0f))
            }
            samples.put(JSONObject().put("ms", time()).put("dims", dims)
                .put("rippleAlpha", field("alphaFraction")).put("rippleRadius", field("radiusFraction"))
                .put("sourceAttached", item?.isAttachedToWindow == true)
                .put("sourceShowing", dialog?.isShowing == true))
            if (capture && !cleanChecked && clickAt > 0 &&
                SystemClock.uptimeMillis() - clickAt > 190 && field("alphaFraction") == 0f &&
                dialog?.isShowing == true && item?.isAttachedToWindow == true) {
                val original = overlays.keys.firstOrNull { it.isDisappearance }
                if (original != null) {
                    cleanChecked = true
                    val expected = ParticleDismissController::class.java.getDeclaredMethod("captureSnapshot",
                        View::class.java, Context::class.java).apply { isAccessible = true }
                        .invoke(ParticleDismissController, dialog.window!!.decorView, activity) as android.graphics.Bitmap
                    ParticleDismissController::class.java.getDeclaredMethod("applyRoundedCornerMask",
                        android.graphics.Bitmap::class.java, Context::class.java).apply { isAccessible = true }
                        .invoke(ParticleDismissController, expected, activity)
                    val spec = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
                        .get(original) as ParticleDismissSpec
                    cleanMatches = expected.sameAs(spec.snapshot)
                    File(output, "clean-input.png").outputStream().use { spec.snapshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    File(output, "feedback-ended.png").outputStream().use { expected.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    expected.recycle()
                }
            }
            if (clickAt == 0L && time() > 1600 && item != null && dialog?.isShowing == true &&
                dialog.window?.attributes?.alpha == 1f && overlays.keys.none { it.parent != null }) {
                clickAt = SystemClock.uptimeMillis()
                val where = IntArray(2); item.getLocationInWindow(where)
                val x = where[0] + item.width / 2f; val y = where[1] + item.height / 2f
                if (kind == "back") {
                    event("up")
                    (dialog as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
                    Choreographer.getInstance().postFrameCallback { sample() }
                    return
                }
                val outside = -2f * android.view.ViewConfiguration.get(activity).scaledWindowTouchSlop - 1f
                val tapX = if (kind == "outside") outside else x
                val tapY = if (kind == "outside") outside else y
                event("down")
                MotionEvent.obtain(clickAt, clickAt, MotionEvent.ACTION_DOWN, tapX, tapY, 0).also {
                    dialog.dispatchTouchEvent(it); it.recycle()
                }
                handler.postDelayed({
                    event("up")
                    if (profile) {
                        profiling = true
                        Thread({
                            val begin = SystemClock.uptimeMillis()
                            while (profiling && SystemClock.uptimeMillis() - begin < 1000) {
                                stacks += "${SystemClock.uptimeMillis()-begin}: " + Looper.getMainLooper().thread.stackTrace
                                    .take(26).joinToString(" | ")
                                Thread.sleep(3)
                            }
                        }, "ParticleTransitionSampler").start()
                    }
                    MotionEvent.obtain(clickAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, tapX, tapY, 0).also {
                        dialog.dispatchTouchEvent(it); it.recycle()
                    }
                }, 80)
            }
            val sinceClick = if (clickAt == 0L) 0 else SystemClock.uptimeMillis() - clickAt
            if (sinceClick > 3000 && closeAt == 0L) {
                closeAt = SystemClock.uptimeMillis(); event("close-next")
                activity.supportFragmentManager.fragments.filterIsInstance<BaseDialogFragment>()
                    .filter { it !== source }.forEach { it.dismiss() }
            }
            if (sinceClick > 4800 && overlays.keys.none { it.parent != null }) {
                val recording = activity.supportFragmentManager.fragments.any { it is AudioRecordDialogFragment }
                check(!recording) { "录音窗口未清理" }
                finish(null); return
            }
            check(time() < 15000) { "交接未完成" }
            Choreographer.getInstance().postFrameCallback { sample() }
        } catch (error: Throwable) { finish(error.stackTraceToString()) }
    }

    private fun finish(error: String?) {
        if (done) return
        done = true; handler.removeCallbacksAndMessages(null)
        profiling = false
        if (profile) File(output, "main-stacks.txt").writeText(synchronized(stacks) { stacks.joinToString("\n") })
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentTimings)
        watchedWindows.forEach { it.removeOnFrameMetricsAvailableListener(frameListener) }
        val result = JSONObject().put("kind", kind).put("events", events).put("samples", samples)
            .put("windowFrames", windowFrames)
            .put("overlays", JSONArray(overlays.values.toList()))
            .put("ripplePresent", ripple != null)
        if (capture) result.put("cleanSnapshotMatches", cleanMatches ?: false)
        if (error != null) result.put("error", error)
        File(output, "result.json").writeText(result.toString(2))
        host?.finish()
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
