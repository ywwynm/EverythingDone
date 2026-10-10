package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.DBHelper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 同一探针可在迁移前后编译；限定指定真实记事，只操作滚动和折叠，不保存。 */
class DetailPerformanceProbe : Instrumentation() {
    private lateinit var args: Bundle
    private var current: DetailActivity? = null
    private var home: Activity? = null
    private var openedAt = 0L
    private var firstDrawAt = 0L
    private var firstDraw = CountDownLatch(1)

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        super.callActivityOnCreate(activity, state)
        if (activity !is DetailActivity) return
        DetailActivity::class.java.getDeclaredField("dontSaveAfterOnPause").apply { isAccessible = true }.set(activity, true)
        val content = activity.findViewById<View>(R.id.sv_detail)
        if (content is RecyclerView) {
            if (args.getString("diagnostic_no_prefetch") == "true") content.layoutManager?.isItemPrefetchEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= 33 && args.getString("diagnostic_no_handwriting") == "true") {
                // 仅用于区分系统位置追踪成本；不属于可交付功能配置。
                fun disable(view: View) {
                    view.isAutoHandwritingEnabled = false
                    if (view is ViewGroup) repeat(view.childCount) { disable(view.getChildAt(it)) }
                }
                content.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = disable(view)
                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
                disable(content)
            }
            args.getString("diagnostic_cache")?.toInt()?.takeIf { it >= 0 }?.let(content::setItemViewCacheSize)
            val poolSize = args.getString("diagnostic_pool")?.toInt() ?: 0
            if (poolSize > 0) {
                val checklist = field(activity, "mCheckListAdapter") as CheckListAdapter
                val combined = content.adapter as ConcatAdapter
                val position = combined.adapters.takeWhile { it !== checklist }.sumOf { it.itemCount }
                val type = combined.getItemViewType(position)
                content.recycledViewPool.setMaxRecycledViews(type, poolSize)
                repeat(poolSize) { content.recycledViewPool.putRecycledView(combined.createViewHolder(content, type)) }
            }
        }
        val root = activity.window.decorView
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                firstDrawAt = SystemClock.elapsedRealtimeNanos()
                firstDraw.countDown()
                return true
            }
        })
    }

    override fun onStart() {
        val run = args.getString("run_id") ?: "performance"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(targetContext.getExternalFilesDir(null), "detail-layout-regression/$run").apply { mkdirs() }
        val report = JSONObject()
        val samples = JSONArray()
        val id = args.getString("thing_id")?.toLong() ?: error("需要明确提供测试记事 id")
        require(id == 780L) { "此探针仅用于已授权的指定真实记事" }
        val before = fingerprint(id)
        try {
            if (args.getString("home_first") == "true") {
                home = startActivitySync(Intent(targetContext, ThingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                idle()
            }
            repeat((args.getString("iterations")?.toInt() ?: 5).coerceIn(1, 5)) { index ->
                // 可选的等候用于排除进程初始字体/媒体等后台准备；原始无等候结果仍单独保留。
                if (index == 1) SystemClock.sleep((args.getString("settle_ms")?.toLong() ?: 0L).coerceIn(0, 15000))
                firstDraw = CountDownLatch(1)
                openedAt = SystemClock.elapsedRealtimeNanos()
                val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "detail-performance", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                current = activity
                check(firstDraw.await(10, TimeUnit.SECONDS))
                if (args.getString("diagnostic_no_playback") == "true") {
                    onMain {
                        // 单变量诊断：隔离媒体播放调度的首滑成本，不作为功能合格候选。
                        val controller = field(activity, "mAttachmentPlaybackController")!!
                        controller.javaClass.getMethod("dispose").invoke(controller)
                    }
                }
                val openedMs = (firstDrawAt - openedAt) / 1e6
                idle()
                val adapter = field(activity, "mCheckListAdapter") as CheckListAdapter
                check(adapter.getItems()!!.first()!!.contains("右滑记事的动画优化"))
                val root = activity.findViewById<View>(R.id.sv_detail)
                val list = if (root is RecyclerView) root else activity.findViewById(R.id.rv_check_list)
                val sample = JSONObject().put("iteration", index).put("open_to_predraw_ms", openedMs)
                    .put("initial_expanded", onMain { adapter.isExpanded() })
                    .put("initial_mounted_checklist", mounted(list, adapter)).put("initial_views", countViews(root))
                val checklistPosition = if (root is RecyclerView) (root.adapter as ConcatAdapter).adapters
                    .takeWhile { it !== adapter }.sumOf { it.itemCount } else 0
                sample.put("initial_pooled_checklist", onMain { list.recycledViewPool.getRecycledViewCount(
                    list.adapter!!.getItemViewType(checklistPosition)) })
                check(!onMain { adapter.isExpanded() }) { "本次基线没有以相同折叠状态打开" }
                if (index in 1..3) {
                    val tracing = index == 1 && args.getString("trace_first_scroll") == "true"
                    if (tracing) android.os.Debug.startMethodTracingSampling(
                        File(directory, "first-scroll-methods.trace").absolutePath, 16 * 1024 * 1024, 1000)
                    try { scrollFrames(root, directory, "folded-$index") }
                    finally { if (tracing) android.os.Debug.stopMethodTracing() }
                }
                val controlPosition = adapter.getItems()!!.indexOf("4")
                check(controlPosition >= 0)
                onMain {
                    if (root is RecyclerView) {
                        val sections = (root.adapter as ConcatAdapter).adapters
                        val offset = sections.takeWhile { it !== adapter }.sumOf { it.itemCount }
                        root.scrollToPosition(offset + controlPosition)
                    } else (root as NestedScrollView).scrollTo(0, root.getChildAt(0).height)
                }
                idle()
                val holder = onMain {
                    (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }
                        .first { it.bindingAdapter === adapter && it.bindingAdapterPosition == controlPosition } as CheckListAdapter.EditTextHolder
                }
                val nextFrame = CountDownLatch(1)
                var changedAt = 0L
                var readyAt = 0L
                onMain {
                    root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            root.viewTreeObserver.removeOnPreDrawListener(this)
                            readyAt = SystemClock.elapsedRealtimeNanos(); nextFrame.countDown(); return true
                        }
                    })
                    changedAt = SystemClock.elapsedRealtimeNanos()
                    holder.ivExpandShrink!!.performClick()
                }
                check(nextFrame.await(10, TimeUnit.SECONDS))
                idle()
                check(onMain { adapter.isExpanded() })
                sample.put("expand_to_predraw_ms", (readyAt - changedAt) / 1e6)
                    .put("expanded_mounted_checklist", mounted(list, adapter)).put("expanded_views", countViews(root))
                top(root)
                if (index in 1..3) scrollFrames(root, directory, "expanded-$index")
                samples.put(sample)
                onMain { activity.finish() }; idle(); current = null
            }
            report.put("completed", true)
        } catch (failure: Throwable) { report.put("error", failure.stackTraceToString()) }
        finally {
            onMain { current?.finish(); home?.finish() }; idle()
            val after = fingerprint(id)
            report.put("samples", samples).put("data_unchanged", before == after)
                .put("before_sha256", before).put("after_sha256", after)
            File(directory, "result.json").writeText(report.toString(2))
            finish(if (report.has("error") || before != after) 1 else 0, Bundle().apply { putString("result", report.toString()) })
        }
    }

    private fun top(view: View) {
        onMain { if (view is RecyclerView) view.scrollToPosition(0) else view.scrollTo(0, 0) }; idle()
    }
    private fun scrollFrames(view: View, output: File, name: String) {
        top(view)
        val rect = onMain { Rect().also { check(view.getGlobalVisibleRect(it)) } }
        val x = rect.left + rect.width() * 4 / 5
        val low = rect.top + rect.height() * 4 / 5
        val high = rect.top + rect.height() * 3 / 10
        repeat(6) { index ->
            // framestats 是有限环形缓冲；每个手势单独读取，避免快版本覆盖前面的帧。
            shell("dumpsys gfxinfo ${targetContext.packageName} reset")
            val start = if (index % 2 == 0) low else high
            val end = if (index % 2 == 0) high else low
            shell("input swipe $x $start $x $end 400")
            File(output, "$name-$index-gfx.txt").writeText(shell("dumpsys gfxinfo ${targetContext.packageName} framestats"))
        }
        idle()
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun fingerprint(id: Long): String {
        val row = DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(id.toString())).use {
            check(it.moveToFirst()); JSONArray((0 until it.columnCount).map { column -> if (it.isNull(column)) null else it.getString(column) }).toString()
        }
        return MessageDigest.getInstance("SHA-256").digest(row.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun mounted(list: RecyclerView, adapter: CheckListAdapter) = onMain {
        (0 until list.childCount).count { list.getChildViewHolder(list.getChildAt(it)).bindingAdapter === adapter }
    }
    private fun countViews(view: View): Int = onMain {
        fun count(node: View): Int = 1 + if (node is ViewGroup) (0 until node.childCount).sumOf { count(node.getChildAt(it)) } else 0
        count(view)
    }
    private fun field(activity: DetailActivity, name: String) = DetailActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun idle() { waitForIdleSync(); SystemClock.sleep(150); waitForIdleSync() }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
