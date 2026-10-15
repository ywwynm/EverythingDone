package com.ywwynm.everythingdone.listvalidation

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.Request
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 使用真实普通触摸／fling，而不是长按卡片的边缘自动滚动。只在隔离包运行。 */
internal object RegularMediaScrollAudit {
    fun run(activity: ThingsActivity, list: RecyclerView, fixture: ComplexMediaDragAudit.Fixture,
            output: File, automation: UiAutomation, main: (() -> Unit) -> Unit): JSONObject {
        check(activity.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val kinds = fixture.things.associate { it.id to fixture.kinds.getValue(it) }
        val rows = File(output, "regular-scroll-samples.jsonl")
        val seenReady = HashSet<Long>()
        val readyFrames = HashMap<Triple<Int, String, Long>, MutableSet<Int>>()
        val pausedCounts = IntArray(3)
        val stateCounts = IntArray(3)
        val spinnerCounts = IntArray(3)
        val shownCounts = IntArray(3)
        val stoppedWhileHeld = HashSet<Long>()
        var cachePeakBytes = 0
        var cacheMaxBytes = 0
        var index = 0
        var totalDy = 0L
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { totalDy += dy }
        }
        main { list.addOnScrollListener(listener) }
        fun inspect(phase: String): JSONObject {
            val row = JSONObject().put("phase", phase).put("time", SystemClock.uptimeMillis()).put("sample", index++)
            main {
                val state = list.scrollState
                @Suppress("UNCHECKED_CAST")
                val cache = MediaMemoryAudit.field(list.adapter, "mThingCardMediaBitmapCache") as android.util.LruCache<String, Bitmap>
                cachePeakBytes = maxOf(cachePeakBytes, cache.size())
                cacheMaxBytes = cache.maxSize()
                val paused = Glide.with(activity.applicationContext).isPaused
                row.put("state", state).put("paused", paused).put("dy", totalDy)
                stateCounts[state]++
                if (paused) pausedCounts[state]++
                val cards = JSONArray()
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    val id = card.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                    val rect = Rect()
                    if (!card.getGlobalVisibleRect(rect) || rect.height() < 32) continue
                    val kind = kinds[id] ?: continue
                    val image = listOf(R.id.iv_thing_image, R.id.iv_thing_media_background)
                        .mapNotNull { card.findViewById<ImageView>(it) }.firstOrNull { it.isShown }
                    val gif = image?.drawable as? GifDrawable
                    val spinner = card.findViewById<View>(R.id.pb_thing_image_attachment)?.isShown == true
                    val request = image?.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) as? Request
                    val animated = kind.contains("video") || kind == "gif"
                    val data = JSONObject().put("id", id).put("kind", kind).put("position", list.getChildAdapterPosition(card))
                        .put("holder", System.identityHashCode(card)).put("loadKey", image?.getTag(R.id.tag_thing_card_image_load_key))
                        .put("previouslyReady", id in seenReady).put("spinner", spinner)
                        .put("drawable", image?.drawable?.javaClass?.simpleName).put("frame", gif?.frameIndex)
                        .put("playing", gif?.isRunning).put("requestRunning", request?.isRunning)
                        .put("requestComplete", request?.isComplete).put("requestCleared", request?.isCleared)
                    if (gif != null) {
                        seenReady.add(id)
                        readyFrames.getOrPut(Triple(state, "$phase:${System.identityHashCode(gif)}", id)) { HashSet() }.add(gif.frameIndex)
                        shownCounts[state]++
                        if (phase.endsWith("-held") && !gif.isRunning) stoppedWhileHeld.add(id)
                    }
                    if (animated && spinner) spinnerCounts[state]++
                    cards.put(data)
                }
                row.put("cards", cards)
            }
            rows.appendText(row.toString() + "\n")
            return row
        }
        fun observe(phase: String, millis: Long) {
            val until = SystemClock.uptimeMillis() + millis
            do { inspect(phase); SystemClock.sleep(48) } while (SystemClock.uptimeMillis() < until)
        }
        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun gesture(direction: Int, fling: Boolean, phase: String) {
            var x = 0f; var top = 0f; var bottom = 0f
            main {
                val at = IntArray(2); list.getLocationOnScreen(at)
                x = at[0] + list.width * .55f
                top = at[1] + list.height * .23f
                bottom = at[1] + list.height * .83f
            }
            val start = if (direction > 0) bottom else top
            val end = if (direction > 0) top else bottom
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                val accepted = automation.injectInputEvent(event, true); event.recycle()
                check(accepted) { "普通滑动输入被拒绝：$phase/$action" }
            }
            touch(MotionEvent.ACTION_DOWN, start)
            try {
                val steps = if (fling) 8 else 24
                for (step in 1..steps) {
                    touch(MotionEvent.ACTION_MOVE, start + (end - start) * step / steps)
                    if (!fling && step % 4 == 0) inspect("$phase-moving")
                    SystemClock.sleep(if (fling) 8 else 16)
                }
                if (!fling) {
                    observe("$phase-held", 1800)
                    if (phase == "drag-0") screenshot("regular-held")
                }
            } finally {
                touch(if (fling) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, end)
            }
            observe(if (fling) "$phase-fling" else "$phase-idle", if (fling) 1600 else 800)
        }
        val returns = JSONArray()
        try {
            observe("initial", 800)
            repeat(3) { gesture(1, false, "drag-$it") }
            repeat(2) { gesture(1, true, "fling-down-$it") }
            repeat(2) { gesture(-1, true, "fling-up-$it") }
            // 独立量测已播放过的条目离屏／重绑后的可见等待，不用扩大缓存掩盖问题。
            val positions = listOf(3, 9, 15, 21, 15, 9, 3)
            for ((visit, position) in positions.withIndex()) {
                main {
                    list.stopScroll()
                    (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, 0)
                }
                val start = SystemClock.uptimeMillis()
                val trace = JSONArray()
                do {
                    trace.put(inspect("return-$visit"))
                    SystemClock.sleep(40)
                } while (SystemClock.uptimeMillis() - start < 1400)
                returns.put(JSONObject().put("position", position).put("samples", trace))
            }
            screenshot("regular-return")
            main { MediaMemoryAudit.snapshot(activity, output, "regular-finished") }
        } finally {
            main { list.stopScroll(); list.removeOnScrollListener(listener) }
        }
        val result = JSONObject().put("samples", index).put("states", JSONArray(stateCounts.toList()))
            .put("paused", JSONArray(pausedCounts.toList())).put("gifShown", JSONArray(shownCounts.toList()))
            .put("spinner", JSONArray(spinnerCounts.toList())).put("distinctReady", seenReady.size)
            .put("playingDuringDrag", readyFrames.count { it.key.first == 1 && it.value.size > 1 })
            .put("playingDuringFling", readyFrames.count { it.key.first == 2 && it.value.size > 1 })
            .put("stoppedWhileHeld", JSONArray(stoppedWhileHeld.toList()))
            .put("bitmapCachePeakBytes", cachePeakBytes).put("bitmapCacheMaxBytes", cacheMaxBytes)
        File(output, "regular-summary.json").writeText(result.toString(2))
        File(output, "regular-returns.json").writeText(returns.toString(2))
        check(stateCounts[1] > 30 && stateCounts[2] > 10) { "普通滑动／fling 覆盖不足：$result" }
        check(pausedCounts[1] == 0 && pausedCounts[2] == 0) { "滑动期间请求被暂停：$result" }
        check(stoppedWhileHeld.isEmpty()) { "手指保持接触时已有 GIF 被停止：$result" }
        check(cachePeakBytes <= cacheMaxBytes) { "封面位图缓存超过原预算：$result" }
        check(result.getInt("playingDuringDrag") > 0 && result.getInt("playingDuringFling") > 0) { "普通滑动期间 GIF 没有播放：$result" }
        return result
    }
}
