package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 只旁观真实首页的预绘制状态，不创建记事、不改动画参数、不替代 ItemAnimator。
 * 外部脚本通过正常 UI 创建指定的测试标题；通过反射读取实际已上屏的粒子进度。
 */
class NewItemAppearanceProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val run = requireNotNull(intent.getStringExtra("run_id"))
        val title = requireNotNull(intent.getStringExtra("title"))
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(title.matches(Regex("CodexAnimation[a-zA-Z0-9_-]{1,80}")))
        active?.finish("被下一次探针替代")
        val output = File(getExternalFilesDir(null), "new-item-appearance/$run").apply { mkdirs() }
        active = NewItemAppearanceProbe(application, output, title).also { it.start() }
        startActivity(Intent(this, ThingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    companion object {
        private var active: NewItemAppearanceProbe? = null
    }
}

private class NewItemAppearanceProbe(
    val app: Application, val output: File, val title: String
) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val samples = JSONArray()
    private var host: ThingsActivity? = null
    private var completed = false
    private var startAt = 0L
    private var stableFrames = 0
    private var sawOverlay = false
    private var sawPresentedEnd = false
    private var earlyVisibleFrames = 0
    private var overlappingItemAnimationFrames = 0
    private var firstVisibleMs: Long? = null
    private var firstOverlayMs: Long? = null
    private val progressField = ParticleDismissOverlay::class.java.getDeclaredField("presentedProgress")
        .apply { isAccessible = true }
    private val recyclerField = ThingsActivity::class.java.getDeclaredField("mRecyclerView")
        .apply { isAccessible = true }
    private val timeout = Runnable { finish("没有在 120 秒内观察到完整保存动画") }
    private val observer = ViewTreeObserver.OnPreDrawListener {
        try { sample() } catch (e: Exception) { finish(e.stackTraceToString()) }
        true
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        handler.postDelayed(timeout, 120_000L)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is ThingsActivity || activity === host || completed) return
        detach()
        host = activity
        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(observer)
    }

    private fun sample() {
        if (completed) return
        val activity = host ?: return
        val rv = recyclerField.get(activity) as? RecyclerView ?: return
        val card = (0 until rv.childCount).map { rv.getChildAt(it) }.firstOrNull {
            it.findViewById<TextView>(R.id.tv_thing_title)?.text?.toString() == title
        } ?: return
        val now = SystemClock.uptimeMillis()
        if (startAt == 0L) startAt = now
        val ms = now - startAt
        val overlay = findOverlay(activity.window.decorView)
        val progress = overlay?.let { progressField.getFloat(it) }
        val running = rv.itemAnimator?.isRunning == true
        val visible = card.visibility == View.VISIBLE && card.alpha > .001f
        if (overlay != null) {
            sawOverlay = true
            if (firstOverlayMs == null) firstOverlayMs = ms
            if (running) overlappingItemAnimationFrames++
        }
        if (progress != null && progress >= 1f) sawPresentedEnd = true
        if (visible && !sawPresentedEnd) earlyVisibleFrames++
        if (visible && firstVisibleMs == null) firstVisibleMs = ms
        val maxTranslation = (0 until rv.childCount).maxOfOrNull {
            val child = rv.getChildAt(it)
            abs(child.translationX) + abs(child.translationY)
        } ?: 0f
        samples.put(JSONObject().put("ms", ms).put("alpha", card.alpha)
            .put("visibility", card.visibility).put("itemAnimatorRunning", running)
            .put("maxTranslation", maxTranslation).put("scrollState", rv.scrollState)
            .put("overlay", overlay != null).put("presentedProgress", progress ?: JSONObject.NULL))
        stableFrames = if (sawOverlay && overlay == null && visible && !running) stableFrames + 1 else 0
        if (stableFrames >= 4) {
            finish()
        } else {
            // 动画结束后的静态画面也取样，避免只有一帧而永远等不到收尾。
            rv.postInvalidateOnAnimation()
        }
    }

    private fun findOverlay(view: View): ParticleDismissOverlay? {
        if (view is ParticleDismissOverlay && !view.isDisappearance) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findOverlay(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    fun finish(error: String? = null) {
        if (completed) return
        completed = true
        handler.removeCallbacks(timeout)
        detach()
        app.unregisterActivityLifecycleCallbacks(this)
        val result = JSONObject().put("title", title).put("samples", samples)
            .put("sawOverlay", sawOverlay).put("sawPresentedEnd", sawPresentedEnd)
            .put("earlyVisibleFrames", earlyVisibleFrames)
            .put("overlappingItemAnimationFrames", overlappingItemAnimationFrames)
            .put("firstVisibleMs", firstVisibleMs ?: JSONObject.NULL)
            .put("firstOverlayMs", firstOverlayMs ?: JSONObject.NULL)
            .put("passed", error == null && sawOverlay && sawPresentedEnd &&
                earlyVisibleFrames == 0 && overlappingItemAnimationFrames == 0)
        if (error != null) result.put("error", error)
        File(output, "result.json").writeText(result.toString(2))
    }

    private fun detach() {
        host?.window?.decorView?.viewTreeObserver?.let {
            if (it.isAlive) it.removeOnPreDrawListener(observer)
        }
        host = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) finish("观察中的首页销毁")
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
}
