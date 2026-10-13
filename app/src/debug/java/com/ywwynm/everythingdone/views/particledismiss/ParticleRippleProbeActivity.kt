package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 固定触摸反馈与真实 Dialog 管线；不访问记事，状态采样不重绘 Drawable。 */
class ParticleRippleProbeActivity : AppCompatActivity() {
    private val previousStyle = ParticleStyle.current
    private val previousPolicy = ParticlePresentationPolicy.originalForDebug
    private lateinit var output: File
    private lateinit var report: ParticlePresentationReports.Report
    private var samples = JSONArray()
    private var started = 0L
    private var copying = false
    private var sampling = false
    private var frame = 0
    private var forcedInvalidation = false
    private var sourceWindow: Window? = null
    private var source: View? = null
    private var page: ParticleDoingPage? = null
    private val snapshots = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<ParticleDismissOverlay, Boolean>())
    private val sampler = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!sampling || isDestroyed) return
            val elapsed = SystemClock.uptimeMillis() - started
            val button = source ?: return
            if (!forcedInvalidation && intent.hasExtra("invalidate_after_ms") &&
                elapsed >= intent.getIntExtra("invalidate_after_ms", 1000)) {
                forcedInvalidation = true
                button.invalidate()
                report.event("ripple_probe_forced_invalidation")
            }
            val feedback = button.foreground as? GradientRippleDrawable
            fun field(name: String): Any? = feedback?.let {
                GradientRippleDrawable::class.java.getDeclaredField(name).apply { isAccessible = true }.get(it)
            }
            val row = JSONObject().put("ms", elapsed).put("frame", frame++)
                .put("pressed", button.isPressed).put("shown", button.isShown)
                .put("focus", button.hasWindowFocus()).put("alpha", field("alphaFraction"))
                .put("radius", field("radiusFraction")).put("feedback", feedback?.feedbackAnimating)
            samples.put(row)
            if (intent.getBooleanExtra("images", true)) ParticleDismissOverlay.activeIn(this@ParticleRippleProbeActivity).forEach { overlay ->
                if (snapshots.add(overlay)) {
                    val spec = ParticleDismissOverlay::class.java.getDeclaredField("spec").apply { isAccessible = true }
                        .get(overlay) as ParticleDismissSpec
                    val pixels = spec.snapshot.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
                    if (pixels != null) save("material-${snapshots.size}-${spec.reverse}.png", pixels)
                }
            }
            if (!copying && button.isAttachedToWindow && sourceWindow != null && intent.getBooleanExtra("images", true)) {
                val at = IntArray(2).also(button::getLocationInWindow)
                val rect = Rect(at[0], at[1], at[0] + button.width, at[1] + button.height)
                val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
                copying = true
                val copied: (Int) -> Unit = { status ->
                    row.put("copyStatus", status).put("copiedMs", SystemClock.uptimeMillis() - started)
                    if (status == PixelCopy.SUCCESS) save("button-${row.getInt("frame")}.png", bitmap) else bitmap.recycle()
                    copying = false
                }
                if (page == null || ParticleDoingPage.of(button)?.copyFrame(rect, bitmap, copied) != true)
                    PixelCopy.request(checkNotNull(sourceWindow), rect, bitmap, copied, android.os.Handler(mainLooper))
            }
            if (elapsed < 3000) Choreographer.getInstance().postFrameCallback(this)
            else finishSampling("completed")
        }
    }

    private fun finishSampling(reason: String) {
        if (!sampling) return
        sampling = false
        report.event("ripple_probe_finished", mapOf("frames" to frame, "reason" to reason))
        report.save()
        val capturedSamples = JSONArray(samples.toString())
        val json = JSONObject().put("samples", capturedSamples).put("report", report.json())
        ParticlePresentationReports.worker.execute {
            File(output, "result.json").writeText(json.toString(2))
            File(report.folder, "ripple-samples.json").writeText(capturedSamples.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("run_id") ?: "manual-${System.currentTimeMillis()}"
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(getExternalFilesDir(null), "particle-ripple/$id").apply { mkdirs() }
        ParticleStyle.current = intent.getStringExtra("style")?.let(ParticleStyle::of) ?: previousStyle
        ParticlePresentationPolicy.originalForDebug = intent.getStringExtra("mode") == "original"
        report = ParticlePresentationReports.find(intent.getStringExtra(ParticlePresentationReports.EXTRA_RUN))
            ?: ParticlePresentationReports.start(this, "触摸波纹", "波纹持续播放与材料捕获",
            ParticlePresentationPolicy.debugOptions(ParticlePresentationPolicy.originalForDebug), "固定虚构内容",
            frameCapacity = 8192, checkpoints = false)
        ParticlePresentationDiagnostics.bind(this, report)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(80), dp(24), dp(24))
            setBackgroundColor(0xffeef4f8.toInt())
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "触摸波纹：${intent.getStringExtra("ripple")}\n观察按钮波纹是否自然扩散、淡出。" })
        val button = button()
        root.addView(button, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(24) })
        button.setOnClickListener {
            if (intent.getStringExtra("scene") in listOf("transition", "dismiss"))
                ParticleRippleProbeDialog().show(supportFragmentManager, "ripple-source")
            else begin(button, window) { showNext() }
        }
        root.addView(TextView(this).apply {
            text = "返回显示兼容性检查"; setPadding(0, dp(32), 0, dp(32))
            setOnClickListener { finish() }
        })
        if (intent.getStringExtra("scene")?.startsWith("doing") == true && !ParticlePresentationPolicy.originalForDebug)
            page = ParticleDoingPage(this)
        if (intent.getStringExtra("scene") in listOf("transition", "dismiss")) {
            root.post { ParticleRippleProbeDialog().show(supportFragmentManager, "ripple-source") }
        }
        root.post { File(output, "ready.json").writeText(JSONObject().put("report", report.id).toString()) }
    }

    fun button(): TextView = TextView(this).apply {
        text = "点击打开弹窗"; textSize = 18f; gravity = Gravity.CENTER
        isClickable = true; isFocusable = true; setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
        val color = 0xff2477dd.toInt()
        foreground = if (intent.getStringExtra("ripple") == "custom")
            GradientRippleDrawable(ThingBackground.pure(color), false)
        else RippleDrawable(ColorStateList.valueOf(0x662477dd), null, ColorDrawable(Color.WHITE))
    }

    fun begin(button: View, from: Window, action: () -> Unit) {
        if (sampling) {
            report.event("ripple_probe_repeated_click")
            if (intent.getStringExtra("scene")?.endsWith("control") != true) action()
            return
        }
        samples = JSONArray(); snapshots.clear(); frame = 0; forcedInvalidation = false
        source = button; sourceWindow = from; started = SystemClock.uptimeMillis(); sampling = true
        report.event("ripple_probe_click", mapOf("ripple" to button.foreground.javaClass.simpleName))
        Choreographer.getInstance().postFrameCallback(sampler)
        if (intent.getStringExtra("scene")?.endsWith("control") != true) action()
    }
    fun showNext() { ParticlePresentationTestDialog().show(supportFragmentManager, "ripple-target") }
    private fun save(name: String, bitmap: Bitmap) = ParticlePresentationReports.worker.execute {
        try { File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        if (name.startsWith("material-")) File(output, name).copyTo(File(report.folder, name), overwrite = true)
    }
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        finishSampling("activity_destroyed")
        Choreographer.getInstance().removeFrameCallback(sampler)
        ParticlePresentationDiagnostics.unbind(this)
        ParticleStyle.current = previousStyle; ParticlePresentationPolicy.originalForDebug = previousPolicy
        page?.close()
        super.onDestroy()
    }
    override fun finish() { page?.beginExit(); super.finish() }
}

class ParticleRippleProbeDialog : BaseDialogFragment() {
    override fun getLayoutResource() = 0
    override fun createDialogContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val host = requireActivity() as ParticleRippleProbeActivity
        return LinearLayout(inflater.context).apply {
            orientation = LinearLayout.VERTICAL; minimumWidth = host.dp(280); setPadding(24, 24, 24, 24)
            val transition = host.intent.getStringExtra("scene") == "transition"
            addView(TextView(context).apply { text = if (transition) "点击后与下一弹窗重叠转场" else "点击按钮关闭，观察消散中的按钮波纹" })
            addView(host.button().apply {
                text = if (transition) "点击打开弹窗" else "确认关闭"
                setOnClickListener { host.begin(this, checkNotNull(dialog?.window)) {
                    if (transition) host.showNext()
                    dismiss()
                } }
            }, LinearLayout.LayoutParams(-1, host.dp(64)))
        }
    }
}
