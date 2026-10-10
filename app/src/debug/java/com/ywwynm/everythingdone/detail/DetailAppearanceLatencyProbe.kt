package com.ywwynm.everythingdone.detail

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.views.ThingCardCropEditorView
import com.ywwynm.everythingdone.interaction.UiInteractions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅打开用户指定记事的附件外观并取消；实际触摸，禁止保存，核对完整行指纹。 */
class DetailAppearanceLatencyProbe : Instrumentation() {
    private var args = Bundle()
    private var current: DetailActivity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        super.callActivityOnCreate(activity, state)
        if (activity is DetailActivity) DetailActivity::class.java.getDeclaredField("dontSaveAfterOnPause")
            .apply { isAccessible = true }.set(activity, true)
    }

    override fun onStart() {
        val run = args.getString("run_id") ?: "appearance-latency"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val directory = File(targetContext.getExternalFilesDir(null), "detail-layout-regression/$run").apply { mkdirs() }
        val result = JSONObject()
        val samples = JSONArray()
        val before = fingerprint()
        try {
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                "detail-latency", 779L, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
            current = activity
            waitForIdleSync()
            val list = activity.findViewById<RecyclerView>(R.id.sv_detail)
            val adapter = field(activity, "mImageAttachmentAdapter") as ImageAttachmentAdapter
            val position = (args.getString("position")?.toInt() ?: 1).coerceIn(0, adapter.itemCount - 1)
            val offset = (list.adapter as ConcatAdapter).adapters.takeWhile { it !== adapter }.sumOf { it.itemCount }
            onMain { list.scrollToPosition(offset + position) }
            waitForIdleSync()
            SystemClock.sleep(1000)
            val rounds = (args.getString("rounds")?.toInt() ?: 1).coerceIn(1, 3)
            repeat(rounds) { round ->
                val interactUntil = SystemClock.uptimeMillis() + 10000
                while (!onMain {
                    val sessions = UiInteractions::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                        .get(UiInteractions) as Map<*, *>
                    val session = sessions[activity] as UiInteractions.Session
                    session.state.canInteract(session.state.root)
                }) {
                    check(SystemClock.uptimeMillis() < interactUntil) { "上一个窗口仍占用输入" }
                    SystemClock.sleep(50)
                }
                val button = onMain {
                    val holder = list.findViewHolderForAdapterPosition(offset + position)!!
                    check(holder.bindingAdapter === adapter && holder.bindingAdapterPosition == position)
                    holder.itemView.findViewById<View>(R.id.iv_edit_image_attachment_appearance)
                }
                if (args.getString("diagnostic_sync_preview") == "true") onMain {
                    // 同一二进制下隔离解码线程；直接使用仍用于重建的同步生产恢复路径。
                    button.setOnClickListener {
                        MediaCropAppearanceDialogFragment.newInstance(
                            MediaCropAppearanceDialogFragment.REQUEST_DETAIL_ATTACHMENT, position
                        ).show(activity.supportFragmentManager, MediaCropAppearanceDialogFragment.TAG)
                    }
                }
                val expected = onMain { Rect().also { check(button.getGlobalVisibleRect(it)) } }
                var bounds: Rect? = null
                val deadline = SystemClock.uptimeMillis() + 6000
                val observed = JSONArray()
                while (bounds == null && SystemClock.uptimeMillis() < deadline) {
                    val nodes = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(
                        "${targetContext.packageName}:id/iv_edit_image_attachment_appearance").orEmpty()
                    val candidates = nodes.map { Rect().also(it::getBoundsInScreen) }
                    observed.put(JSONArray(candidates.map { it.toShortString() }))
                    bounds = candidates.firstOrNull { it.contains(expected.centerX(), expected.centerY()) }
                    if (bounds == null) SystemClock.sleep(100)
                }
                check(bounds != null) { "目标坐标 $expected，实际辅助功能节点 $observed" }
                val sample = JSONObject().put("round", round).put("position", position)
                    .put("bounds_from_accessibility", bounds.toShortString())
                samples.put(sample)
                val frames = mutableListOf<Long>()
                val ready = CountDownLatch(1)
                val mediaReady = CountDownLatch(1)
                var monitoring = true
                var firstPredraw = 0L
                var firstTouch = 0L
                var upTouch = 0L
                val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
                        if (f is MediaCropAppearanceDialogFragment) sample.put("fragment_start_ns", SystemClock.elapsedRealtimeNanos())
                    }
                    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
                        if (f !is MediaCropAppearanceDialogFragment) return
                        sample.put("content_created_ns", SystemClock.elapsedRealtimeNanos())
                        v.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                if (firstPredraw == 0L) {
                                    firstPredraw = SystemClock.elapsedRealtimeNanos()
                                    sample.put("dialog_size_before", JSONArray(listOf(v.width, v.height)))
                                    ready.countDown()
                                }
                                fun editor(view: View): ThingCardCropEditorView? = if (view is ThingCardCropEditorView) view
                                    else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { editor(view.getChildAt(it)) } else null
                                val bitmap = editor(v)?.let { ThingCardCropEditorView::class.java.getDeclaredField("bitmap")
                                    .apply { isAccessible = true }.get(it) }
                                if (bitmap == null) sample.put("opened_before_bitmap", true)
                                else {
                                    v.viewTreeObserver.removeOnPreDrawListener(this)
                                    sample.put("media_ready_ns", SystemClock.elapsedRealtimeNanos())
                                    sample.put("dialog_size_after", JSONArray(listOf(v.width, v.height)))
                                    check(sample.getJSONArray("dialog_size_before").toString() == sample.getJSONArray("dialog_size_after").toString())
                                    mediaReady.countDown()
                                }
                                return true
                            }
                        })
                    }
                }
                val heartbeat = object : Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        frames += SystemClock.elapsedRealtimeNanos()
                        if (monitoring) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                onMain {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, false)
                    button.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) { firstTouch = SystemClock.elapsedRealtimeNanos(); sample.put("down_ns", firstTouch) }
                        if (event.actionMasked == MotionEvent.ACTION_UP) { upTouch = SystemClock.elapsedRealtimeNanos(); sample.put("up_ns", upTouch) }
                        false
                    }
                    Choreographer.getInstance().postFrameCallback(heartbeat)
                }
                val tracing = round == rounds - 1 && args.getString("sample") == "true"
                if (tracing) Debug.startMethodTracingSampling(File(directory, "appearance-methods.trace").absolutePath, 16 * 1024 * 1024, 1000)
                try {
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                        try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                    check(ready.await(15, TimeUnit.SECONDS)) { "编辑器未绘制" }
                    check(mediaReady.await(15, TimeUnit.SECONDS)) { "预览未填入" }
                    SystemClock.sleep(350)
                } finally { if (tracing) Debug.stopMethodTracing() }
                onMain {
                    monitoring = false
                    Choreographer.getInstance().removeFrameCallback(heartbeat)
                    button.setOnTouchListener(null)
                    activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
                    sample.put("sampled", tracing).put("down_to_predraw_ms", (firstPredraw - firstTouch) / 1e6)
                        .put("up_to_predraw_ms", (firstPredraw - upTouch) / 1e6)
                        .put("creation_ms", (sample.getLong("content_created_ns") - sample.getLong("fragment_start_ns")) / 1e6)
                        .put("down_to_media_ready_ms", (sample.getLong("media_ready_ns") - firstTouch) / 1e6)
                        .put("max_main_frame_gap_ms", frames.zipWithNext { a, b -> (b - a) / 1e6 }.maxOrNull())
                        .put("first_main_frame_after_down_ms", frames.firstOrNull { it >= firstTouch }?.let { (it - firstTouch) / 1e6 })
                        .put("max_gap_while_preparing_ms", frames.filter { it < sample.getLong("fragment_start_ns") }
                            .zipWithNext { a, b -> (b - a) / 1e6 }.maxOrNull())
                }
                val settledUntil = SystemClock.uptimeMillis() + 10000
                val settledField = BaseDialogFragment::class.java.getDeclaredField("appearanceSettled").apply { isAccessible = true }
                while (!onMain {
                    val fragment = activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG)
                    fragment != null && settledField.getBoolean(fragment)
                }) {
                    check(SystemClock.uptimeMillis() < settledUntil) { "出现动画未完成" }
                    SystemClock.sleep(50)
                }
                onMain {
                    val fragment = activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG)
                        as MediaCropAppearanceDialogFragment
                    fun find(view: View): ThingCardCropEditorView? = if (view is ThingCardCropEditorView) view
                        else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
                    find(fragment.requireView())?.let { editor ->
                        val bitmap = ThingCardCropEditorView::class.java.getDeclaredField("bitmap").apply { isAccessible = true }
                            .get(editor) as android.graphics.Bitmap
                        val pixels = java.nio.ByteBuffer.allocate(bitmap.byteCount)
                        bitmap.copyPixelsToBuffer(pixels)
                        sample.put("preview_width", bitmap.width).put("preview_height", bitmap.height)
                            .put("preview_sha256", MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) })
                    }
                    fragment.dismiss()
                }
                val dismissedUntil = SystemClock.uptimeMillis() + 10000
                while (onMain { activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) != null }) {
                    check(SystemClock.uptimeMillis() < dismissedUntil) { "关闭动画未完成" }
                    SystemClock.sleep(50)
                }
                waitForIdleSync()
            }
            if (args.getString("cancel_check") == "true") {
                // 新 Activity 保证没有缓存；仍用同一原记事且禁止保存。
                onMain { activity.finish() }; waitForIdleSync()
                val next = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "detail-latency-cancel", 779L, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                current = next
                waitForIdleSync()
                val edit = DetailActivity::class.java.getDeclaredMethod("showDetailAttachmentMediaAppearanceEditor", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                onMain {
                    edit.invoke(next, 1)
                    val owner = field(next, "mDetailCropPreviewModal")
                    check(owner != null)
                    edit.invoke(next, 0)
                    check(field(next, "mDetailCropPreviewModal") === owner) { "等待中重复点击替换了请求" }
                    next.onBackPressedDispatcher.onBackPressed()
                    check(!next.isFinishing && field(next, "mDetailCropPreviewModal") == null)
                }
                SystemClock.sleep(2500)
                onMain { check(next.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) == null) }
                result.put("pending_back_and_duplicate_pass", true)
                onMain {
                    edit.invoke(next, 1)
                    DetailActivity::class.java.getDeclaredMethod("dismissDetailDialogFragmentsForAppearance")
                        .apply { isAccessible = true }.invoke(next)
                    check(field(next, "mDetailCropPreviewModal") == null)
                }
                SystemClock.sleep(2500)
                onMain { check(next.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) == null) }
                result.put("pending_theme_change_pass", true)
                onMain { edit.invoke(next, 1); next.finish() }
                SystemClock.sleep(2500)
                result.put("pending_destroy_pass", onMain { next.isDestroyed && field(next, "mDetailCropPreviewModal") == null })
                check(result.getBoolean("pending_destroy_pass"))
            }
        } catch (failure: Throwable) { result.put("error", failure.stackTraceToString()) }
        finally {
            onMain { current?.finish() }; waitForIdleSync()
            result.put("samples", samples).put("data_unchanged", before == fingerprint())
            File(directory, "result.json").writeText(result.toString(2))
            finish(if (result.has("error") || !result.getBoolean("data_unchanged")) 1 else 0,
                Bundle().apply { putString("result", result.toString()) })
        }
    }

    private fun fingerprint(): String = DBHelper(targetContext).readableDatabase.rawQuery(
        "SELECT * FROM things WHERE id=779", null).use {
        check(it.moveToFirst())
        check(it.getString(it.getColumnIndexOrThrow("content")).contains("你好啊，我的老朋友，好久不见"))
        val row = JSONArray((0 until it.columnCount).map { c -> if (it.isNull(c)) null else it.getString(c) }).toString()
        MessageDigest.getInstance("SHA-256").digest(row.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }
    private fun field(activity: DetailActivity, name: String) = DetailActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
