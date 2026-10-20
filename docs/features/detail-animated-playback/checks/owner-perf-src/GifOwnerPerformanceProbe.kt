package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.signature.ObjectKey
import com.ywwynm.everythingdone.*
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.*
import com.ywwynm.everythingdone.listvalidation.*
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.ywwynm.everythingdone.model.Thing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 仅独立包：同一 APK 切换 owner 策略，使用正式首页、详情和真实触摸事件。 */
class GifOwnerPerformanceProbe : Instrumentation() {
    companion object { @JvmField var isolationEnabled = true }
    private var args = Bundle()
    private lateinit var output: File
    private var home: ThingsActivity? = null
    private var detail: DetailActivity? = null
    private val events = JSONArray()
    private var stage = "initial"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun field(value: Any?, name: String) = MediaMemoryAudit.field(value, name)
    private fun progress(name: String) {
        stage = name
        File(output, "progress.json").writeText(JSONObject().put("phase", name).toString())
    }
    private fun await(label: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 45000
        while (!main(test)) {
            check(SystemClock.uptimeMillis() < end) { "等待超时：$stage / $label" }
            val root = uiAutomation.rootInActiveWindow
            if (root?.packageName == "android" && root.findAccessibilityNodeInfosByText("Android 应用兼容性").isNotEmpty()) {
                root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            } else if (root?.packageName?.contains("permissioncontroller") == true) {
                listOf("permission_allow_all_button", "permission_allow_button").firstNotNullOfOrNull {
                    root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$it").firstOrNull()
                }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(80)
        }
    }
    override fun onStart() {
        check(targetContext.packageName == "com.ywwynm.everythingdone.gifownervalidation")
        val run = args.getString("run_id")!!
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        isolationEnabled = args.getString("policy") == "isolated"
        output = File(targetContext.getExternalFilesDir(null), "gif-owner/$run").apply { mkdirs() }
        val result = JSONObject().put("policy", args.getString("policy"))
        try {
            progress("prepare")
            val prefs = targetContext.getSharedPreferences(Def.Meta.META_DATA_NAME, 0).edit()
            listOf(Def.Meta.KEY_1_0_4_TO_1_0_5, Def.Meta.KEY_1_1_4_TO_1_1_5, Def.Meta.KEY_1_2_7_TO_1_3_0,
                Def.Meta.KEY_1_3_0_TO_1_3_1, Def.Meta.KEY_1_3_3_TO_1_3_4).forEach { prefs.putBoolean(it, true) }
            prefs.commit()
            File(targetContext.filesDir, Def.Meta.FEEDBACK_ERROR_FILE_NAME).delete()
            args.putString("items", "24")
            val fixture = ComplexMediaDragAudit.prepare(targetContext, args, output)
            if (args.getString("large_home") == "true") {
                // 只替换隔离夹具的媒体文件，首页仍走正式 File 加载与裁切路径。
                for (index in listOf(3, 9, 15, 21)) {
                    targetContext.assets.open("gif-owner/large.gif").use { input ->
                        File(targetContext.filesDir, "complex-drag/animation-$index.gif").outputStream().use(input::copyTo)
                    }
                }
                result.put("largeHomeGifBytes", 35240637).put("largeHomeGifCount", 4)
            }
            main {
                DBHelper(targetContext).use {
                    it.writableDatabase.execSQL("DELETE FROM things WHERE type <> ${Thing.HEADER}")
                    it.writableDatabase.execSQL("DELETE FROM thing_folders")
                }
                App.getApp()!!.setStatus(Def.ThingStatus.UNDERWAY, true)
                val manager = ThingManager.getInstance(targetContext)!!
                manager.loadThings()
                fixture.things.forEach { it.location = manager.getHeaderId(); manager.create(it, false, true) }
            }
            val id = prepareDetail()
            Glide.get(targetContext).clearDiskCache()
            home = startActivitySync(Intent(targetContext, ThingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Def.Communication.KEY_STATUS, Def.ThingStatus.UNDERWAY)) as ThingsActivity
            await("首页") { home!!.hasWindowFocus() && (field(home, "mRecyclerView") as? RecyclerView)?.childCount?.let { it > 1 } == true }
            val list = main { field(home, "mRecyclerView") as RecyclerView }
            main { (list.adapter as ThingsAdapter).setShouldThingsAnimWhenAppearing(false) }
            fun homeRun(name: String) {
                progress(name)
                val dir = File(output, name).apply { mkdirs() }
                val options = Bundle().apply { putString("perf_policy", "continuous"); putString("perf_suite", "owner") }
                result.put(name, ScrollPerformanceAudit.run(home!!, list, main { field(home, "mModeManager") as ModeManager },
                    fixture, options, dir, uiAutomation) { block -> main(block) })
                if (args.getString("require_mapped") == "true") {
                    val records = JSONArray(File(dir, "perf-samples.json").readText())
                    var mapped = 0; var heap = 0
                    for (i in 0 until records.length()) {
                        val cards = records.getJSONObject(i).getJSONArray("cards")
                        for (j in 0 until cards.length()) {
                            val card = cards.getJSONObject(j)
                            if (card.has("gif")) { if (card.optBoolean("direct")) mapped++ else heap++ }
                        }
                    }
                    result.put("$name-mapped", JSONObject().put("mappedSamples", mapped).put("heapSamples", heap))
                    check(mapped > 0 && heap == 0) { "首页 GIF 未全部通过文件映射：mapped=$mapped, heap=$heap" }
                }
            }
            if (args.getString("after_only") != "true") homeRun("home-before")
            for ((mode, name) in listOf(DetailAutoplayMode.ONE_BY_ONE to "sequential",
                DetailAutoplayMode.ALL_ONCE to "all-once", DetailAutoplayMode.ALL_LOOP to "all-loop")) {
                openDetail(id, mode)
                measureDetail(name)
                closeDetail()
                snapshot("closed-$name")
            }
            repeat(3) { pass ->
                progress("reopen-$pass")
                openDetail(id, DetailAutoplayMode.ONE_BY_ONE)
                SystemClock.sleep(1800)
                snapshot("reopen-$pass")
                closeDetail()
            }
            await("返回首页") { home!!.hasWindowFocus() }
            SystemClock.sleep(2000)
            snapshot("returned-home")
            homeRun("home-after")
            main { home!!.finish() }; home = null
            SystemClock.sleep(2500)
            result.put("runningAfterFinish", snapshot("finished"))
            result.put("duplicateSource", duplicateSource())
            result.put("ok", true)
        } catch (error: Throwable) {
            result.put("ok", false).put("stage", stage).put("error", error.stackTraceToString())
        } finally {
            main { detail?.finish(); home?.finish() }; detail = null; home = null
            File(output, "result.json").writeText(result.put("events", events).toString(2))
            finish(if (result.optBoolean("ok")) 0 else 1, Bundle().apply { putString("result", result.toString()) })
        }
    }
    private fun prepareDetail(): Long {
        val files = File(targetContext.filesDir, "owner-media").apply { mkdirs() }
        val media = (0 until 12).map { index ->
            val file = File(files, "$index.gif")
            if (!file.exists()) targetContext.assets.open("gif-owner/${if (index < 6) "large" else "medium"}.gif")
                .use { input -> file.outputStream().use(input::copyTo) }
            "0${file.path}"
        }
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        db.insertOrThrow(Def.Database.TABLE_THINGS, null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.FINISHED)
            put("color", 0xff3388aa.toInt()); put("title", "独立解码性能检查")
            put("content", (1..80).joinToString("\n") { "第 $it 段：实际详情的大 GIF 播放和滚动测试。" })
            put("attachment", AttachmentHelper.toAttachmentStr(media, emptyList()))
            put("location", id); put("create_time", System.currentTimeMillis()); put("update_time", System.currentTimeMillis()); put("finish_time", 0L)
        })
        events.put(JSONObject().put("fixtureBytes", JSONArray(media.map { File(it.substring(1)).length() })).put("homeCards", 25))
        return id
    }
    private fun content() = field(detail, "mDetailContent") as DetailContentLayout
    private fun adapter() = field(detail, "mImageAttachmentAdapter") as ImageAttachmentAdapter
    private fun openDetail(id: Long, mode: Int) {
        targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0).edit().putInt(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, mode).commit()
        FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, mode)
        detail = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext, "gif-owner", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        main { DetailActivity::class.java.getDeclaredField("dontSaveAfterOnPause").apply { isAccessible = true }.setBoolean(detail, true) }
        await("详情") { detail!!.hasWindowFocus() && field(detail, "mImageAttachmentAdapter") != null }
        main { (content().recyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(content().globalPosition(adapter(), 0), 0) }
        await("GIF 就绪") { (content().holder(adapter(), 0) as? ImageAttachmentAdapter.ImageViewHolder)?.ivImage?.drawable is GifDrawable }
        SystemClock.sleep(1000)
    }
    private fun closeDetail() { main { detail!!.finish() }; detail = null; SystemClock.sleep(700) }
    private fun snapshot(label: String): Int = main {
        val running = MediaMemoryAudit.snapshot(targetContext, output, label)
        val cache = field(Glide.get(targetContext), "memoryCache")
        events.put(JSONObject().put("phase", label).put("cacheBytes", field(cache, "currentSize"))
            .put("cacheMaxBytes", field(cache, "maxSize")).put("running", running))
        running
    }
    private fun measureDetail(name: String) {
        progress(name)
        val dir = File(output, name).apply { mkdirs() }
        snapshot("$name-start")
        val handlerThread = HandlerThread("OwnerFrameMetrics").apply { start() }
        val handler = Handler(handlerThread.looper)
        val frames = ArrayList<LongArray>()
        val metrics = intArrayOf(FrameMetrics.INTENDED_VSYNC_TIMESTAMP, FrameMetrics.TOTAL_DURATION, FrameMetrics.DEADLINE,
            FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION, FrameMetrics.GPU_DURATION)
        val listener = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
            frames.add(LongArray(metrics.size + 1) { if (it == metrics.size) dropped.toLong() else m.getMetric(metrics[it]) })
        }
        val memory = ArrayList<JSONObject>()
        val collecting = AtomicBoolean(true)
        val sampler = Thread({ while (collecting.get()) {
            val m = Debug.MemoryInfo(); Debug.getMemoryInfo(m); val r = Runtime.getRuntime()
            memory.add(JSONObject().put("ns", System.nanoTime()).put("pssKb", m.totalPss)
                .put("javaBytes", r.totalMemory() - r.freeMemory()).put("nativeBytes", Debug.getNativeHeapAllocatedSize()))
            SystemClock.sleep(1500)
        } }, "OwnerMemory")
        val samples = JSONArray(); val spans = JSONArray()
        var distance = 0L
        val rv = main { content().recyclerView }
        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { distance += kotlin.math.abs(dy.toLong()) }
        }
        fun inspect(phase: String) { main {
            val cards = JSONArray()
            for (i in 0 until 12) {
                val view = (content().holder(adapter(), i) as? ImageAttachmentAdapter.ImageViewHolder)?.ivImage ?: continue
                val bounds = Rect()
                if (!view.isShown || !view.getGlobalVisibleRect(bounds) || bounds.height() < 32) continue
                val gif = view.drawable as? GifDrawable
                cards.put(JSONObject().put("position", i).put("gif", gif?.let { System.identityHashCode(it) })
                    .put("frame", gif?.frameIndex).put("running", gif?.isRunning).put("direct", gif?.buffer?.isDirect))
            }
            samples.put(JSONObject().put("ns", System.nanoTime()).put("phase", phase).put("cards", cards))
        } }
        main { detail!!.window.addOnFrameMetricsAvailableListener(listener, handler); rv.addOnScrollListener(scrollListener) }
        sampler.start()
        try {
            val start = System.nanoTime()
            repeat(60) { inspect("play"); SystemClock.sleep(100) }
            spans.put(JSONObject().put("phase", "play").put("start", start).put("end", System.nanoTime()))
            for (direction in listOf(1, -1)) {
                val begin = System.nanoTime(); val before = distance
                repeat(8) { swipe(rv, direction); inspect("scroll-$direction") }
                spans.put(JSONObject().put("phase", "scroll-$direction").put("start", begin).put("end", System.nanoTime()).put("distancePx", distance - before))
            }
            repeat(20) { inspect("return"); SystemClock.sleep(100) }
        } finally {
            main { detail!!.window.removeOnFrameMetricsAvailableListener(listener); rv.removeOnScrollListener(scrollListener) }
            val done = java.util.concurrent.CountDownLatch(1); handler.post { done.countDown() }; done.await(5, TimeUnit.SECONDS)
            collecting.set(false); sampler.join(3000); handlerThread.quitSafely()
            File(dir, "frames.csv").bufferedWriter().use { w ->
                w.appendLine("vsync,total,deadline,animation,layout,draw,gpu,dropped")
                frames.forEach { w.appendLine(it.joinToString(",")) }
            }
            File(dir, "memory.json").writeText(JSONArray(memory).toString())
            File(dir, "samples.json").writeText(samples.toString())
            File(dir, "spans.json").writeText(spans.toString())
        }
        uiAutomation.takeScreenshot()?.let { b -> File(dir, "screen.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
        snapshot("$name-end")
    }
    private fun swipe(rv: RecyclerView, direction: Int) {
        val rect = main { Rect().also { rv.getGlobalVisibleRect(it) } }
        val down = SystemClock.uptimeMillis()
        val start = rect.top + rect.height() * if (direction > 0) .82f else .24f
        val end = rect.top + rect.height() * if (direction > 0) .24f else .82f
        for (i in 0..25) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), if (i == 0) MotionEvent.ACTION_DOWN else if (i == 25) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_MOVE,
                rect.centerX().toFloat(), start + (end - start) * minOf(i, 24) / 24f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(uiAutomation.injectInputEvent(event, i == 0 || i == 25)); event.recycle(); SystemClock.sleep(16)
        }
        SystemClock.sleep(100)
    }
    /** 相同大文件和尺寸、六个消费者的边界测试；与页面性能区间分开。 */
    private fun duplicateSource(): JSONObject {
        progress("duplicate-source")
        main { Glide.get(targetContext).clearMemory() }
        val targets = (0 until 6).map {
            val request = Glide.with(targetContext).asGif().load(File(targetContext.filesDir, "owner-media/0.gif"))
                .signature(ObjectKey("duplicate-source")).override(720, 540)
            if (isolationEnabled) request.apply(DetailGifPlaybackOwner().requestOptions)
            request.submit()
        }
        try {
            val gifs = targets.map { it.get(30, TimeUnit.SECONDS) }
            val loaders = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            val rows = JSONArray()
            gifs.forEach { gif ->
                val loader = field(field(gif, "state"), "frameLoader")!!
                if (loaders.add(loader)) {
                    val decoder = field(loader, "gifDecoder")!!
                    rows.put(JSONObject().put("reportedBytes", gif.size).put("mappedInputBytes", gif.buffer.limit())
                        .put("direct", gif.buffer.isDirect).put("mainPixelsBytes", (field(decoder, "mainPixels") as? ByteArray)?.size)
                        .put("mainScratchBytes", (field(decoder, "mainScratch") as? IntArray)?.size?.times(4))
                        .put("firstFrameBytes", gif.firstFrame?.allocationByteCount))
                }
            }
            return JSONObject().put("consumers", gifs.size).put("decoders", loaders.size).put("resources", rows)
        } finally { main { targets.forEach { Glide.with(targetContext).clear(it) } } }
    }
}
