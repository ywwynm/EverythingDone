package com.ywwynm.everythingdone.listvalidation

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingListOverlayDragController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** 仅隔离包：同一二进制切换旧暂停策略，计时期间无截图、无资源图反射。 */
internal object ScrollPerformanceAudit {
    fun run(activity: ThingsActivity, list: RecyclerView, mode: ModeManager,
            fixture: ComplexMediaDragAudit.Fixture, args: Bundle, output: File,
            automation: UiAutomation, main: (() -> Unit) -> Unit): JSONObject {
        check(activity.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val legacy = args.getString("perf_policy") == "legacy"
        val quick = args.getString("perf_suite") == "fling"
        require(args.getString("perf_policy") in listOf("legacy", "continuous"))
        val frameThread = HandlerThread("ListPerfFrames").apply { start() }
        val frameHandler = Handler(frameThread.looper)
        val frames = ArrayList<LongArray>(60000)
        val metrics = intArrayOf(FrameMetrics.INTENDED_VSYNC_TIMESTAMP, FrameMetrics.TOTAL_DURATION,
            FrameMetrics.DEADLINE, FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.GPU_DURATION, FrameMetrics.FIRST_DRAW_FRAME, FrameMetrics.VSYNC_TIMESTAMP)
        val frameListener = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
            frames.add(LongArray(metrics.size + 1) { if (it == metrics.size) dropped.toLong() else m.getMetric(metrics[it]) })
        }
        val spans = JSONArray()
        val phases = JSONArray()
        val samples = JSONArray()
        val memory = ArrayList<JSONObject>()
        val collecting = AtomicBoolean(true)
        val sampleThread = Thread({
            while (collecting.get()) {
                val start = System.nanoTime()
                val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
                val runtime = Runtime.getRuntime()
                memory.add(JSONObject().put("ns", start).put("costNs", System.nanoTime() - start)
                    .put("pssKb", info.totalPss).put("privateDirtyKb", info.totalPrivateDirty)
                    .put("javaBytes", runtime.totalMemory() - runtime.freeMemory())
                    .put("nativeBytes", Debug.getNativeHeapAllocatedSize()).put("stats", JSONObject(info.memoryStats)))
                SystemClock.sleep(1500)
            }
        }, "ListPerfMemory")
        val moves = ArrayList<LongArray>(40000)
        var absoluteDy = 0L
        var signedDy = 0L
        val legacyListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (!legacy) return
                val running = state == RecyclerView.SCROLL_STATE_IDLE
                if (running) Glide.with(activity.applicationContext).resumeRequests()
                else Glide.with(activity.applicationContext).pauseRequests()
                for (i in 0 until rv.childCount) for (id in intArrayOf(R.id.iv_thing_image, R.id.iv_thing_media_background)) {
                    val drawable = rv.getChildAt(i).findViewById<ImageView>(id)?.drawable as? Animatable ?: continue
                    if (running) drawable.start() else drawable.stop()
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                absoluteDy += kotlin.math.abs(dy.toLong()); signedDy += dy
                moves.add(longArrayOf(System.nanoTime(), dy.toLong(), rv.scrollState.toLong(), mode.getCurrentMode().toLong()))
            }
        }
        val kinds = fixture.things.associate { it.id to fixture.kinds.getValue(it) }
        var lastInspect = 0L
        val allSeen = HashSet<Long>()
        val allReady = HashSet<Long>()
        fun inspect(phase: String, force: Boolean = false) {
            if (!force && SystemClock.uptimeMillis() - lastInspect < 192) return
            lastInspect = SystemClock.uptimeMillis()
            val row = JSONObject().put("phase", phase).put("ns", System.nanoTime())
            main {
                row.put("state", list.scrollState).put("mode", mode.getCurrentMode()).put("dy", signedDy)
                    .put("paused", Glide.with(activity.applicationContext).isPaused)
                val cards = JSONArray()
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    val id = card.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                    val rect = Rect()
                    if (!card.getGlobalVisibleRect(rect) || rect.height() < 32) continue
                    allSeen.add(id)
                    val image = listOf(R.id.iv_thing_image, R.id.iv_thing_media_background)
                        .mapNotNull { card.findViewById<ImageView>(it) }.firstOrNull { it.isShown }
                    val gif = image?.drawable as? GifDrawable
                    if (image?.drawable != null) allReady.add(id)
                    cards.put(JSONObject().put("id", id).put("kind", kinds[id]).put("alpha", card.alpha)
                        .put("pos", list.getChildAdapterPosition(card)).put("drawable", image?.drawable?.javaClass?.simpleName)
                        .put("gif", gif?.let { System.identityHashCode(it) }).put("frame", gif?.frameIndex)
                        .put("playing", gif?.isRunning).put("spinner", card.findViewById<View>(R.id.pb_thing_image_attachment)?.isShown == true))
                }
                row.put("cards", cards)
            }
            samples.put(row)
        }
        fun capture(name: String) {
            automation.takeScreenshot()?.let { b ->
                File(output, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
            }
        }
        fun resetTop() {
            main { list.stopScroll(); (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(0, 0) }
            SystemClock.sleep(1000)
        }
        var x = 0f; var top = 0f; var bottom = 0f
        fun bounds() { main {
            val at = IntArray(2); list.getLocationOnScreen(at)
            x = at[0] + list.width * .55f
            top = at[1] + list.height * .18f; bottom = at[1] + list.height * .84f
        } }
        fun touch(down: Long, action: Int, px: Float, py: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(e, action != MotionEvent.ACTION_MOVE))
            e.recycle()
        }
        fun span(phase: String, part: String, start: Long, end: Long) {
            spans.put(JSONObject().put("phase", phase).put("part", part).put("start", start).put("end", end))
        }
        fun traversal(name: String, direction: Int, fling: Boolean) {
            bounds()
            var startDy = 0L
            main { startDy = absoluteDy }
            val begin = System.nanoTime()
            val initialSeen = HashSet<Long>()
            File(output, "perf-progress.json").writeText(JSONObject().put("phase", name).put("state", "start").toString())
            var canScroll = true; var gestures = 0
            while (canScroll && gestures < 240) {
                main { check(mode.getCurrentMode() == if (name.startsWith("selection")) ModeManager.SELECTING else ModeManager.NORMAL) }
                val down = SystemClock.uptimeMillis()
                val from = if (direction > 0) bottom else top
                val to = if (direction > 0) top else bottom
                val moveStart = System.nanoTime()
                touch(down, MotionEvent.ACTION_DOWN, x, from)
                try {
                    val steps = if (fling) 9 else 32
                    val interval = if (fling) 8L else 16L
                    val movingAt = SystemClock.uptimeMillis()
                    for (i in 1..steps) {
                        touch(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / steps)
                        if (i % 8 == 0) inspect(name)
                        val wait = movingAt + i * interval - SystemClock.uptimeMillis()
                        if (wait > 0) SystemClock.sleep(wait)
                    }
                } finally { touch(down, if (fling) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, x, to) }
                val up = System.nanoTime()
                span(name, "finger", moveStart, up)
                if (fling) {
                    var state = RecyclerView.SCROLL_STATE_SETTLING
                    val until = SystemClock.uptimeMillis() + 6000
                    do {
                        SystemClock.sleep(48); inspect(name)
                        main { state = list.scrollState }
                    } while (state != RecyclerView.SCROLL_STATE_IDLE && SystemClock.uptimeMillis() < until)
                    span(name, "fling", up, System.nanoTime())
                    check(state == RecyclerView.SCROLL_STATE_IDLE)
                } else SystemClock.sleep(96)
                inspect(name, true)
                main {
                    canScroll = list.canScrollVertically(direction)
                    for (i in 0 until list.childCount) (list.getChildAt(i).getTag(R.id.tag_thing_card_bound_stable_id) as? Long)?.let(initialSeen::add)
                }
                gestures++
            }
            check(!canScroll) { "未遍历全列表 $name" }
            var distance = 0L; main { distance = absoluteDy - startDy }
            phases.put(JSONObject().put("name", name).put("start", begin).put("end", System.nanoTime())
                .put("distance", distance).put("gestures", gestures).put("endReached", true).put("seen", initialSeen.size))
        }
        val runtimeBefore = JSONObject(Debug.getRuntimeStats())
        val result = JSONObject().put("policy", if (legacy) "legacy" else "continuous")
            .put("suite", if (quick) "fling" else "full")
            .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("count", fixture.things.size)
            .put("runtimeBefore", runtimeBefore)
        main {
            result.put("refreshRate", activity.display?.refreshRate).put("width", list.width).put("height", list.height)
            list.addOnScrollListener(legacyListener)
            activity.window.addOnFrameMetricsAvailableListener(frameListener, frameHandler)
        }
        sampleThread.start()
        try {
            if (!quick) {
                resetTop()
                traversal("normal-finger-down", 1, false)
                traversal("normal-finger-up", -1, false)
                SystemClock.sleep(1500)
                main { MediaMemoryAudit.snapshot(activity, output, "perf-after-finger") }
            }
            resetTop()
            traversal("normal-fling-down", 1, true)
            traversal("normal-fling-up", -1, true)
            SystemClock.sleep(1500)
            main { MediaMemoryAudit.snapshot(activity, output, "perf-after-fling") }
            if (!quick) {
                resetTop()
                // 使用实际长按并松手，覆盖完整的选择状态与工具栏计数更新顺序。
                var selectX = 0f; var selectY = 0f
                main {
                    val card = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
                    val rect = Rect(); check(card.getGlobalVisibleRect(rect))
                    selectX = rect.exactCenterX(); selectY = rect.exactCenterY()
                }
                val selectionDown = SystemClock.uptimeMillis()
                touch(selectionDown, MotionEvent.ACTION_DOWN, selectX, selectY)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
                touch(selectionDown, MotionEvent.ACTION_UP, selectX, selectY)
                SystemClock.sleep(800)
                main {
                    check(mode.getCurrentMode() == ModeManager.SELECTING)
                    check(com.ywwynm.everythingdone.managers.ThingManager.getInstance(activity)!!.getSelectedThings()!!.size == 1)
                }
                traversal("selection-finger-down", 1, false)
                traversal("selection-fling-up", -1, true)
                capture("perf-selection")
                main { mode.backNormalMode(0) }
                SystemClock.sleep(800)
                resetTop(); bounds()
                val controller = MediaMemoryAudit.field(activity, "mOverlayDragController") as ThingListOverlayDragController
                var y = 0f
                main {
                    val card = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
                    val rect = Rect(); check(card.getGlobalVisibleRect(rect)); x = rect.exactCenterX(); y = rect.exactCenterY()
                }
                val down = SystemClock.uptimeMillis()
                touch(down, MotionEvent.ACTION_DOWN, x, y)
                try {
                    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
                    main { check(controller.isActive && mode.getCurrentMode() == ModeManager.MOVING) }
                    for (direction in listOf(1, -1)) {
                        val name = if (direction > 0) "card-drag-down" else "card-drag-up"
                        var edgeX = 0f; var edgeY = 0f; var startDy = 0L
                        main {
                            val at = IntArray(2); list.getLocationOnScreen(at)
                            val inset = 64 * activity.resources.displayMetrics.density
                            edgeX = at[0] + list.width * .5f; edgeY = at[1] + if (direction > 0) list.height - inset else inset
                            startDy = absoluteDy
                        }
                        val start = System.nanoTime()
                        File(output, "perf-progress.json").writeText(JSONObject().put("phase", name).put("state", "start").toString())
                        for (i in 1..24) {
                            touch(down, MotionEvent.ACTION_MOVE, x + (edgeX - x) * i / 24, y + (edgeY - y) * i / 24)
                            SystemClock.sleep(12)
                        }
                        x = edgeX; y = edgeY
                        var can = true
                        val until = SystemClock.uptimeMillis() + 240000
                        while (can && SystemClock.uptimeMillis() < until) {
                            touch(down, MotionEvent.ACTION_MOVE, x, y)
                            inspect(name)
                            main { check(controller.isActive); can = list.canScrollVertically(direction) }
                            SystemClock.sleep(64)
                        }
                        check(!can)
                        val end = System.nanoTime()
                        span(name, "card-drag", start, end)
                        var distance = 0L; main { distance = absoluteDy - startDy }
                        phases.put(JSONObject().put("name", name).put("start", start).put("end", end)
                            .put("distance", distance).put("endReached", true))
                    }
                } finally { touch(down, MotionEvent.ACTION_CANCEL, x, y) }
            }
            SystemClock.sleep(2500)
            inspect("settled", true); capture("perf-final")
            main { MediaMemoryAudit.snapshot(activity, output, "perf-settled") }
            result.put("seen", allSeen.size).put("mediaReady", allReady.size).put("runtimeAfter", JSONObject(Debug.getRuntimeStats()))
        } finally {
            main {
                list.stopScroll(); list.removeOnScrollListener(legacyListener)
                Glide.with(activity.applicationContext).resumeRequests()
                activity.window.removeOnFrameMetricsAvailableListener(frameListener)
            }
            val drained = CountDownLatch(1); frameHandler.post { drained.countDown() }; drained.await()
            frameThread.quitSafely()
            collecting.set(false); sampleThread.join()
            File(output, "perf-frames.csv").bufferedWriter().use { w ->
                w.appendLine("vsync,total,deadline,unknown,input,animation,layout,draw,sync,command,swap,gpu,first,actualVsync,dropped")
                frames.forEach { w.appendLine(it.joinToString(",")) }
            }
            File(output, "perf-motion.csv").bufferedWriter().use { w ->
                w.appendLine("ns,dy,state,mode"); moves.forEach { w.appendLine(it.joinToString(",")) }
            }
            File(output, "perf-samples.json").writeText(samples.toString())
            File(output, "perf-memory.json").writeText(JSONArray(memory).toString())
            result.put("phases", phases).put("spans", spans).put("frames", frames.size)
            File(output, "perf-result.json").writeText(result.toString(2))
        }
        return result
    }
}
