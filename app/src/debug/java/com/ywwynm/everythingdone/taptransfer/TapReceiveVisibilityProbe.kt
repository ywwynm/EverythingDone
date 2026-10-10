package com.ywwynm.everythingdone.taptransfer

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter.BaseThingViewHolder
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingListEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 接收后滚动的真机回归：生产适配器 + 瀑布流 + RecyclerView 的真实缓存。
 * 所有记事和文件夹只在内存中创建；不调用导入器、DAO 写入或用户记事操作。
 */
class TapReceiveVisibilityProbe : Instrumentation() {
    private var args = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val results = JSONArray()
        var activity: TapReceiveVisibilityActivity? = null
        val report = JSONObject()
        try {
            activity = startActivitySync(Intent(targetContext, TapReceiveVisibilityActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TapReceiveVisibilityActivity
            for (variant in listOf("plain", "video-checklist", "folder", "snapshot-alpha")) {
                results.put(cachedCard(activity, variant))
            }
            runOnMainSync {
                results.put(reboundCard(activity))
                results.put(multipleHolders(activity))
            }
            report.put("passed", (0 until results.length()).all { results.getJSONObject(it).getBoolean("passed") })
        } catch (failure: Throwable) {
            report.put("passed", false).put("error", failure.stackTraceToString())
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
        report.put("cases", results)
        val run = args.getString("run_id", "latest")!!
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val output = File(targetContext.getExternalFilesDir(null), "tap-visibility/$run.json")
        output.parentFile!!.mkdirs()
        output.writeText(report.toString(2))
        finish(if (report.optBoolean("passed")) 0 else 1, Bundle().apply { putString("result", report.toString()) })
    }

    private fun cachedCard(activity: TapReceiveVisibilityActivity, variant: String): JSONObject {
        lateinit var adapter: VisibilityFixtureAdapter
        lateinit var rv: RecyclerView
        runOnMainSync {
            adapter = activity.newList(variant)
            rv = activity.list
            adapter.hideFirst(true)
        }
        await { rv.findViewHolderForAdapterPosition(0)?.itemView?.height?.let { it > 0 } == true }
        lateinit var original: View
        var initialBinds = 0
        runOnMainSync {
            original = rv.findViewHolderForAdapterPosition(0)!!.itemView
            check(original.visibility == View.INVISIBLE)
            if (variant == "snapshot-alpha") {
                original.visibility = View.VISIBLE
                original.alpha = 0f
            }
            initialBinds = adapter.firstBinds
            // 第一张通栏长卡片移到屏幕外。缓存容量仍使用 RecyclerView 的默认值。
            val margin = (original.layoutParams as StaggeredGridLayoutManager.LayoutParams).bottomMargin
            rv.scrollBy(0, original.bottom + margin + 1)
        }
        await { rv.findViewHolderForAdapterPosition(0) == null }
        var wasCached = false
        var stayedHidden = false
        runOnMainSync {
            val recycler = RecyclerView::class.java.getDeclaredField("mRecycler").apply { isAccessible = true }.get(rv)
            val cached = recycler.javaClass.getDeclaredField("mCachedViews").apply { isAccessible = true }
                .get(recycler) as List<*>
            wasCached = cached.any { (it as RecyclerView.ViewHolder).itemView === original }
            check(wasCached) { "夹具未进入 RecyclerView 缓存，不能用重新绑定代替本回归" }
            stayedHidden = original.visibility == View.INVISIBLE || original.alpha == 0f
            // 与 ThingsActivity.revealTapReceivedThings 相同的接收收尾入口。
            adapter.hideFirst(false)
            (rv.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(0, 0)
        }
        await { rv.findViewHolderForAdapterPosition(0) != null }
        val result = JSONObject().put("case", variant).put("cached", wasCached).put("hidden_before_reveal", stayedHidden)
        runOnMainSync {
            val returned = rv.findViewHolderForAdapterPosition(0)!!.itemView
            val sameView = returned === original
            val noRebind = initialBinds == adapter.firstBinds
            val visible = returned.visibility == View.VISIBLE && returned.alpha == 1f
            result.put("same_view", sameView).put("no_rebind", noRebind)
                .put("visibility", returned.visibility).put("alpha", returned.alpha)
                .put("width", returned.width).put("height", returned.height)
                .put("passed", wasCached && stayedHidden && sameView && noRebind && visible)
        }
        return result
    }

    /** 被回收并改绑后，旧记事的揭示不能让另一条还在接收中的记事提前出现。 */
    private fun reboundCard(activity: TapReceiveVisibilityActivity): JSONObject {
        val adapter = activity.newList("plain")
        val holder = adapter.createViewHolder(activity.list, 0)
        holder.itemView.layoutParams = StaggeredGridLayoutManager.LayoutParams(600, 900)
        adapter.setThingHidden(VisibilityFixtureAdapter.FIRST_ID, true)
        adapter.bindViewHolder(holder, 0)
        adapter.onViewRecycled(holder)
        adapter.setThingHidden(VisibilityFixtureAdapter.FIRST_ID + 1, true)
        adapter.bindViewHolder(holder, 1)
        adapter.setThingHidden(VisibilityFixtureAdapter.FIRST_ID, false)
        val stayedHidden = holder.itemView.visibility == View.INVISIBLE
        // 未参与接收的卡片仍可由其它动画隐藏，揭示无关 id 不得更改它。
        adapter.bindViewHolder(holder, 2)
        holder.itemView.visibility = View.INVISIBLE
        adapter.setThingHidden(VisibilityFixtureAdapter.FIRST_ID + 1, false)
        return JSONObject().put("case", "rebound-owner")
            .put("passed", stayedHidden && holder.itemView.visibility == View.INVISIBLE)
    }

    /** 同一 id 可因列表变更短暂对应多个 holder；全部恢复，已揭示的视图不再归接收动画管理。 */
    private fun multipleHolders(activity: TapReceiveVisibilityActivity): JSONObject {
        val adapter = activity.newList("plain")
        adapter.hideFirst(true)
        val holders = (0..1).map {
            adapter.createViewHolder(activity.list, 0).also { holder ->
                holder.itemView.layoutParams = StaggeredGridLayoutManager.LayoutParams(600, 900)
                adapter.bindViewHolder(holder, 0)
            }
        }
        val hidden = holders.all { it.itemView.visibility == View.INVISIBLE }
        holders.last().itemView.apply { visibility = View.VISIBLE; alpha = 0f }
        adapter.hideFirst(false)
        val revealed = holders.all { it.itemView.visibility == View.VISIBLE && it.itemView.alpha == 1f }
        holders.first().itemView.visibility = View.INVISIBLE
        adapter.hideFirst(false)
        return JSONObject().put("case", "multiple-holders-and-release")
            .put("passed", hidden && revealed && holders.first().itemView.visibility == View.INVISIBLE)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        do {
            waitForIdleSync()
            var ready = false
            runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(32)
        } while (SystemClock.uptimeMillis() < deadline)
        error("等待列表状态超时")
    }
}

class TapReceiveVisibilityActivity : Activity() {
    lateinit var list: RecyclerView
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        setContentView(root)
    }

    internal fun newList(variant: String): VisibilityFixtureAdapter {
        if (::list.isInitialized) list.adapter = null
        root.removeAllViews()
        list = RecyclerView(this).apply {
            layoutManager = StaggeredGridLayoutManager(2, RecyclerView.VERTICAL).apply {
                // 不让后台预取替代本测试要验证的“缓存原 holder 再挂回”。
                isItemPrefetchEnabled = false
            }
            itemAnimator = null
        }
        val adapter = VisibilityFixtureAdapter(application as App, variant)
        list.adapter = adapter
        root.addView(list, FrameLayout.LayoutParams(-1, (480 * resources.displayMetrics.density).toInt()))
        return adapter
    }
}

internal class VisibilityFixtureAdapter(app: App, private val variant: String) : ThingsAdapter(app, null) {
    private val entries = (0 until 16).map { index ->
        if (variant == "folder" && index == 0) {
            ThingListEntry.FolderEntry(ThingFolder(FIRST_ID, "仅内存：接收文件夹", Color.CYAN, FIRST_ID), 0)
        } else {
            ThingListEntry.ThingEntry(Thing(FIRST_ID + index, Thing.NOTE, Color.CYAN, FIRST_ID + index).apply {
                title = "仅内存：接收卡片 $index"
                content = "滚动后仍需显示"
                if (variant == "video-checklist" && index == 0) {
                    content = (1..36).joinToString("") { CheckListHelper.SIGNAL + "01清单项 $it" }
                    val video = File(app.cacheDir, "tap-visibility-fixture.mp4")
                    app.assets.open("detail-regression/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
                    attachment = AttachmentHelper.SIGNAL + AttachmentHelper.VIDEO + video.absolutePath
                }
            })
        }
    }
    var firstBinds = 0

    init {
        setShouldThingsAnimWhenAppearing(false)
        setAnimatedPlaybackEnabled(false)
    }

    override fun getEntries(): List<ThingListEntry> = entries
    override fun getThings(): List<Thing?> = entries.map { (it as? ThingListEntry.ThingEntry)?.thing }
    override fun isThingEffectivelyPrivate(thing: Thing): Boolean = false
    override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        if (position == 0) firstBinds++
        // 固定几何使两台设备都只把第一张卡片移进默认的两项缓存；内容仍由生产代码绑定。
        (holder.itemView.layoutParams as StaggeredGridLayoutManager.LayoutParams).apply {
            isFullSpan = position == 0
            height = ((if (position == 0) 360 else 240) * holder.itemView.resources.displayMetrics.density).toInt()
        }
    }

    fun hideFirst(hidden: Boolean) {
        if (variant == "folder") setFolderHidden(FIRST_ID, hidden) else setThingHidden(FIRST_ID, hidden)
    }

    companion object { const val FIRST_ID = 9_000_000_000L }
}
