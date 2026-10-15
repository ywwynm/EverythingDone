package com.ywwynm.everythingdone.listvalidation

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.Request
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingListOverlayDragController
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 独立包专用：长按源卡片后跨全列表拖动，采样时不松手、不暂停自动滚动。 */
internal object ComplexMediaDragAudit {
    data class Fixture(val things: List<Thing>, val kinds: Map<Thing, String>)

    fun prepare(context: Context, args: Bundle, output: File): Fixture {
        check(context.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val count = (args.getString("items")?.toInt() ?: 96).coerceIn(24, 192)
        val directory = File(context.filesDir, "complex-drag").apply { mkdirs() }
        val template = File(directory, "source.mp4")
        context.assets.open("complex-drag/source.mp4").use { input -> template.outputStream().use(input::copyTo) }
        val things = ArrayList<Thing>()
        val kinds = java.util.IdentityHashMap<Thing, String>()
        var generatedGif: File? = null
        val density = context.resources.displayMetrics.density
        val metrics = context.resources.displayMetrics
        for (i in 0 until count) {
            val kind = arrayOf("checklist", "image", "video", "gif", "checklist-video", "text-image")[i % 6]
            val thing = Thing(0L, Thing.NOTE, 0xff3388aa.toInt(), 0L).apply {
                title = if (kind == "video") "" else "复杂列表 $i · $kind"
                content = when {
                    kind.contains("checklist") -> CheckListHelper.toCheckListStr((1..96).map { item ->
                        CheckListHelper.makeItem(if (item % 9 == 0) '1' else '0', item % 3 + 1,
                            "测试清单 $i 第 $item 项：包含较长说明文字，验证滚动时完整绑定、换行与层级显示")
                    })
                    kind == "text-image" -> (1..24).joinToString("\n") { "复杂内容 $i 第 $it 行，图片和文字同时加载。" }
                    else -> ""
                }
            }
            var file: File? = null
            if (kind.contains("video")) {
                file = File(directory, "video-$i.mp4")
                if (!file.exists()) template.copyTo(file)
                // 视频内容完全相同，只生成一份正式预览再复制到各自的正式缓存路径。
                // 不共享文件路径／Glide 缓存 key；避免把准备阶段的重复编码计入加载压力。
                val gif = if (generatedGif == null) {
                    checkNotNull(VideoCoverPreviewManager.generateBlocking(context, file.path, null))
                } else VideoCoverPreviewManager.getReadyPreview(context, file.path, null) ?: run {
                    val method = VideoCoverPreviewManager::class.java.getDeclaredMethod("previewFileFor",
                        Context::class.java, String::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                    val destination = method.invoke(VideoCoverPreviewManager, context, file.path, null) as File
                    generatedGif!!.copyTo(destination, overwrite = true)
                }
                if (generatedGif == null) generatedGif = gif
            } else if (kind == "gif") {
                file = File(directory, "animation-$i.gif")
                checkNotNull(generatedGif).copyTo(file, overwrite = true)
            } else if (kind.contains("image")) {
                file = File(directory, "image-$i.png")
                val bitmap = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); val paint = Paint()
                for (y in 0 until 12) for (x in 0 until 9) {
                    paint.color = if ((x + y) % 2 == 0) 0xffe8ad19.toInt() else 0xffc42d57.toInt()
                    canvas.drawRect(x * 120f, y * 120f, (x + 1) * 120f, (y + 1) * 120f, paint)
                }
                paint.color = Color.BLACK; paint.textSize = 72f
                canvas.drawText("IMAGE $i", 120f, 240f, paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            val full = i % 4 == 2
            val background = kind == "video" && i % 12 == 2
            var appearance = thing.thingCardAppearance.copy(
                spanMode = if (full) Thing.THING_CARD_SPAN_FULL else Thing.THING_CARD_SPAN_NORMAL,
                mediaBackgroundEnabled = background,
                checklistMaxItemsNormal = 12, checklistMaxItemsFull = 16,
                contentMaxLinesNormal = 12, contentMaxLinesFull = 18,
                imagePlacement = if (i % 3 == 0) Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM else Thing.THING_CARD_IMAGE_PLACEMENT_TOP)
            if (file != null) {
                val key = "${if (kind.contains("video")) AttachmentHelper.VIDEO else AttachmentHelper.IMAGE}${file.path}"
                thing.attachment = AttachmentHelper.SIGNAL + key
                val ratio = if (background) (metrics.widthPixels - 32 * density) / (metrics.heightPixels * .83f)
                    else if (kind == "video") .6f else 1.2f
                val source = ThingCardAppearance.SourceAppearance(fileSize = file.length(), lastModified = file.lastModified())
                    .withPresentation(ThingCardAppearance.PRESENTATION_THUMBNAIL,
                        ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = ratio.toDouble()))
                    .withPresentation(ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND,
                        ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = ratio.toDouble()))
                appearance = appearance.copy(sources = mapOf(key to source))
            }
            thing.thingCardAppearance = appearance
            things.add(thing); kinds[thing] = kind
            if (i % 6 == 0) File(output, "preparation.json").writeText(JSONObject().put("prepared", i + 1).put("total", count).toString())
        }
        // 最后创建，在列表顶部；悬浮卡片保持短小，避免遮住待检查媒体。
        val source = Thing(0L, Thing.NOTE, 0xff3388aa.toInt(), 0L).apply { title = "持续拖动源"; content = "全程不松手" }
        things.add(source); kinds[source] = "source"
        if (args.getString("cold_load") == "true") Glide.get(context).clearDiskCache()
        return Fixture(things, kinds)
    }

    private fun field(instance: Any?, name: String): Any? {
        var type: Class<*>? = instance?.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(instance) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        return null
    }

    private fun requestStates(request: Request?): JSONArray {
        val states = JSONArray()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Request, Boolean>())
        fun visit(current: Request?) {
            if (current == null || !visited.add(current)) return
            states.put(JSONObject().put("type", current.javaClass.simpleName)
                .put("status", field(current, "status")?.toString())
                .put("complete", current.isComplete).put("running", current.isRunning))
            // 只读实际请求树，不覆盖产品加载 listener，也不发起额外请求。
            var type: Class<*>? = current.javaClass
            while (type != null) {
                type.declaredFields.filter { Request::class.java.isAssignableFrom(it.type) }.forEach {
                    it.isAccessible = true; visit(it.get(current) as? Request)
                }
                type = type.superclass
            }
        }
        visit(request)
        return states
    }

    private data class Seen(val id: Long, val card: View, val target: View, val rect: Rect, val json: JSONObject)

    fun run(activity: ThingsActivity, list: RecyclerView, mode: ModeManager, fixture: Fixture,
            args: Bundle, output: File, automation: UiAutomation, main: (() -> Unit) -> Unit): JSONObject {
        val sourceId = fixture.things.last().id
        val kinds = fixture.things.associate { it.id to fixture.kinds.getValue(it) }
        val expectedMedia = kinds.filterValues { it !in listOf("source", "checklist") }.keys
        val controller = checkNotNull(field(activity, "mOverlayDragController") as? ThingListOverlayDragController)
        val density = activity.resources.displayMetrics.density
        val samples = File(output, "drag-samples.jsonl")
        val firstSeen = HashMap<Long, Long>()
        val seenIds = HashSet<Long>(); val mediaShown = HashSet<Long>()
        val allViews = HashSet<Int>()
        var totalDy = 0L
        var sampleIndex = 0
        var suspects = 0
        var transparentNonSource = 0
        var recycledSourceSamples = 0
        var failedRequestSamples = 0
        var sourcePlaceholderSamples = 0
        var visibleSourceSamples = 0
        var inspectedMoving = 0
        val screenshotEvery = (args.getString("screenshot_every")?.toInt() ?: 1).coerceIn(1, 100)
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { totalDy += dy }
        }
        main { list.addOnScrollListener(listener) }
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val metadata = JSONObject().put("items", fixture.things.size).put("mediaItems", expectedMedia.size)
            .put("memoryClassMb", am.memoryClass).put("largeMemoryClassMb", am.largeMemoryClass)
            .put("lowRamDevice", am.isLowRamDevice).put("maxJavaHeapBytes", Runtime.getRuntime().maxMemory())
            .put("screenshotEvery", screenshotEvery)
        File(output, "device-memory.json").writeText(metadata.toString(2))

        fun inspect(pass: Int, moving: Boolean) {
            val before = ArrayList<Seen>(); var beforeDy = 0L
            val overlayRect = Rect()
            val now = SystemClock.uptimeMillis()
            val runtime = Runtime.getRuntime()
            val record = JSONObject().put("sample", sampleIndex).put("time", now).put("pass", pass).put("duringDrag", moving)
            main {
                beforeDy = totalDy
                record.put("mode", mode.getCurrentMode()).put("dragActive", controller.isActive).put("scrollState", list.scrollState)
                    .put("totalDy", totalDy).put("autoScrollVelocity", field(controller, "autoScrollVelocity"))
                    .put("glidePaused", Glide.with(activity.applicationContext).isPaused)
                val session = field(controller, "session")
                val originalSourceView = field(session, "sourceView") as? View
                (field(session, "overlay") as? View)?.getGlobalVisibleRect(overlayRect)
                val visible = HashSet<Long>()
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    val id = card.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                    if (id == sourceId || id !in kinds) continue
                    val cardRect = Rect(); if (!card.getGlobalVisibleRect(cardRect)) continue
                    cardRect.top = max(cardRect.top, (150 * density).toInt())
                    cardRect.bottom = min(cardRect.bottom, activity.resources.displayMetrics.heightPixels - (150 * density).toInt())
                    if (cardRect.height() < 60 * density) continue
                    visible.add(id); seenIds.add(id); allViews.add(System.identityHashCode(card))
                    firstSeen.putIfAbsent(id, now)
                    val media = listOf(R.id.iv_thing_image, R.id.iv_thing_media_background)
                        .mapNotNull { card.findViewById<ImageView>(it) }.firstOrNull { it.isShown }
                    val target = if (id in expectedMedia && media != null) media else card
                    val rect = Rect(); if (!target.getGlobalVisibleRect(rect) || !rect.intersect(cardRect)) continue
                    val drawable = media?.drawable
                    val request = media?.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) as? Request
                    fun textCount(view: View): Int = when (view) {
                        is TextView -> if (view.isShown && view.text.isNotEmpty()) 1 else 0
                        is ViewGroup -> (0 until view.childCount).sumOf { textCount(view.getChildAt(it)) }
                        else -> 0
                    }
                    val json = JSONObject().put("id", id).put("kind", kinds[id]).put("holder", System.identityHashCode(card))
                        .put("position", list.getChildAdapterPosition(card)).put("ageMs", now - firstSeen.getValue(id))
                        .put("alpha", card.alpha).put("visibility", card.visibility).put("textViews", textCount(card))
                        .put("isOriginalDragView", card === originalSourceView)
                        .put("dragTag", card.getTag(R.id.tag_thing_card_drag_active))
                        .put("mediaExpected", id in expectedMedia).put("mediaShown", media?.isShown == true)
                        .put("drawable", drawable?.javaClass?.simpleName).put("gifBytes", (drawable as? GifDrawable)?.size)
                        .put("gifRunning", (drawable as? GifDrawable)?.isRunning).put("frame", (drawable as? GifDrawable)?.frameIndex)
                        .put("requestComplete", request?.isComplete).put("requestRunning", request?.isRunning)
                        .put("requestCleared", request?.isCleared).put("requestStates", requestStates(request))
                    before.add(Seen(id, card, target, rect, json))
                }
                firstSeen.keys.retainAll(visible)
            }
            val bitmap = if (sampleIndex % screenshotEvery == 0) checkNotNull(automation.takeScreenshot()) else null
            val rows = JSONArray(); var suspect = false
            main {
                record.put("dyDuringScreenshot", totalDy - beforeDy)
                val afterOverlay = Rect()
                (field(field(controller, "session"), "overlay") as? View)?.getGlobalVisibleRect(afterOverlay)
                overlayRect.union(afterOverlay)
                overlayRect.inset(-(24 * density).toInt(), -(24 * density).toInt())
                if (moving && controller.isActive && totalDy != beforeDy) inspectedMoving++
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    if (card.getTag(R.id.tag_thing_card_bound_stable_id) == sourceId) {
                        sourcePlaceholderSamples++
                        if (card.alpha > .01f) visibleSourceSamples++
                    }
                }
                for (seen in before) {
                    if (seen.card.getTag(R.id.tag_thing_card_bound_stable_id) != seen.id || !seen.card.isAttachedToWindow) continue
                    val after = Rect()
                    if (!seen.target.getGlobalVisibleRect(after) || !after.intersect(seen.rect) || after.height() < 50 * density) continue
                    // 使用截图前后视图区域的交集，避免把运动中的旧坐标当作当前卡片。
                    var colored = 0; var pattern = 0; var pixels = 0
                    if (bitmap != null) for (y in 2..8) for (x in 2..8) {
                        val px = (after.left + after.width() * x / 10).coerceIn(0, bitmap.width - 1)
                        val py = (after.top + after.height() * y / 10).coerceIn(0, bitmap.height - 1)
                        if (overlayRect.contains(px, py)) continue
                        val pixel = bitmap.getPixel(px, py); pixels++
                        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                        // MOVING 模式会淡化非源卡片；阈值仍须能区分淡化后的蓝底、视频色块和灰色页面。
                        if (max(r, max(g, b)) - min(r, min(g, b)) > 12) colored++
                        if (r > b + 14 || g > b + 14 || b > g + 18) pattern++
                    }
                    val json = seen.json.put("rect", JSONArray(listOf(after.left, after.top, after.right, after.bottom)))
                        .put("coloredPixels", colored).put("mediaPatternPixels", pattern).put("pixelCount", pixels)
                    if (seen.card.alpha < .01f) transparentNonSource++
                    if (json.getJSONArray("requestStates").toString().contains("\"FAILED\"")) failedRequestSamples++
                    if (json.getBoolean("isOriginalDragView")) recycledSourceSamples++
                    if (seen.id in expectedMedia && pattern >= 3) mediaShown.add(seen.id)
                    val missing = pixels >= 12 && (colored < 3 ||
                        (seen.id in expectedMedia && pattern < 3 && json.getLong("ageMs") >= 1000))
                    json.put("suspect", missing)
                    if (missing) { suspect = true; suspects++ }
                    rows.put(json)
                }
            }
            val mem = Debug.MemoryInfo(); Debug.getMemoryInfo(mem)
            record.put("pssKb", mem.totalPss).put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("javaCommittedBytes", runtime.totalMemory()).put("rows", rows)
                .put("memoryStats", JSONObject(mem.memoryStats))
            val glide = Glide.get(activity)
            record.put("activeResources", (field(field(field(glide, "engine"), "activeResources"), "activeEngineResources") as? Map<*, *>)?.size)
                .put("glideCacheBytes", (field(glide, "memoryCache") as? com.bumptech.glide.load.engine.cache.MemoryCache)?.currentSize)
                .put("bitmapPoolBytes", field(glide.bitmapPool, "currentSize"))
            samples.appendText(record.toString() + "\n")
            if (args.getString("memory_audit") == "true" && sampleIndex % 8 == 0) {
                main { MediaMemoryAudit.snapshot(activity, output, "drag-$pass-$sampleIndex") }
            }
            if (bitmap != null && (suspect || sampleIndex % 12 == 0)) File(output, "drag-${if (suspect) "suspect" else "sample"}-$sampleIndex.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap?.recycle(); sampleIndex++
        }

        var x = 0f; var y = 0f
        val visibleUntil = SystemClock.uptimeMillis() + 10000
        var sourceVisible = false
        while (!sourceVisible && SystemClock.uptimeMillis() < visibleUntil) {
            main {
                val card = list.findViewHolderForAdapterPosition(1)?.itemView
                sourceVisible = card?.isShown == true && card.alpha > .99f &&
                    card.getTag(R.id.tag_thing_card_bound_stable_id) == sourceId
            }
            SystemClock.sleep(32)
        }
        check(sourceVisible) { "拖动源尚未显示" }
        main {
            val source = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
            metadata.put("sourceId", sourceId).put("sourceView", System.identityHashCode(source))
            File(output, "device-memory.json").writeText(metadata.toString(2))
            val rect = Rect(); check(source.getGlobalVisibleRect(rect))
            x = rect.exactCenterX(); y = rect.exactCenterY()
        }
        // 原生堆分析的启动插桩会延长首屏布局；在按下前等待，避免系统长按识屏抢走手势。
        if (args.getString("memory_audit") == "true") SystemClock.sleep(1500)
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val accepted = automation.injectInputEvent(event, true); event.recycle()
            check(accepted) { "拖动输入被拒绝 action=$action" }
        }
        touch(MotionEvent.ACTION_DOWN, x, y)
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 180)
            val activeUntil = SystemClock.uptimeMillis() + 4000
            var active = false
            while (!active && SystemClock.uptimeMillis() < activeUntil) {
                main { active = controller.isActive }; SystemClock.sleep(32)
            }
            main { check(controller.isActive && mode.getCurrentMode() == ModeManager.MOVING) { "未进入真实卡片拖动" } }
            // 立即开始移动；在原地截图／编码会延长长按，让部分系统启动内容识别拖放。
            main {
                val source = list.findViewHolderForAdapterPosition(1)?.itemView
                check(source?.getTag(R.id.tag_thing_card_bound_stable_id) == sourceId && source.alpha < .01f)
                sourcePlaceholderSamples++
            }
            val passes = (args.getString("passes")?.toInt() ?: 2).coerceIn(1, 6)
            for (pass in 0 until passes) {
                val direction = if (pass % 2 == 0) 1 else -1
                var edgeX = 0f; var edgeY = 0f
                main {
                    val at = IntArray(2); list.getLocationOnScreen(at)
                    edgeX = at[0] + list.width * .5f
                    val inset = (args.getString("edge_inset_dp")?.toFloat() ?: 64f) * density
                    edgeY = at[1] + if (direction > 0) list.height - inset else inset
                }
                for (step in 1..24) {
                    touch(MotionEvent.ACTION_MOVE, x + (edgeX - x) * step / 24, y + (edgeY - y) * step / 24)
                    SystemClock.sleep(12)
                }
                x = edgeX; y = edgeY
                val deadline = SystemClock.uptimeMillis() + 240000
                var atEnd = false
                do {
                    touch(MotionEvent.ACTION_MOVE, x, y)
                    inspect(pass, true)
                    main { check(controller.isActive) { "遍历中拖动提前结束" }; atEnd = !list.canScrollVertically(direction) }
                    if (!atEnd) SystemClock.sleep(180)
                } while (!atEnd && SystemClock.uptimeMillis() < deadline)
                check(atEnd) { "未在限时内跨越全列表 pass=$pass" }
                File(output, "traversal-progress.json").writeText(JSONObject().put("completedPasses", pass + 1)
                    .put("seenIds", seenIds.size).put("mediaShownIds", mediaShown.size).toString())
            }
        } finally {
            touch(MotionEvent.ACTION_CANCEL, x, y)
            main { list.removeOnScrollListener(listener) }
        }
        var restored = false
        val restoreDeadline = SystemClock.uptimeMillis() + 5000
        while (!restored && SystemClock.uptimeMillis() < restoreDeadline) {
            main {
                if (!controller.isActive) {
                    list.scrollToPosition(1)
                    val card = list.findViewHolderForAdapterPosition(1)?.itemView
                    restored = card?.getTag(R.id.tag_thing_card_bound_stable_id) == sourceId && card.alpha > .99f
                }
            }
            SystemClock.sleep(32)
        }
        val result = metadata.put("samples", sampleIndex).put("samplesWithMovement", inspectedMoving)
            .put("seenCardCount", seenIds.size).put("loadedMediaCount", mediaShown.size)
            .put("unseenIds", JSONArray((kinds.keys - sourceId - seenIds).toList()))
            .put("mediaNeverShownIds", JSONArray((expectedMedia - mediaShown).toList()))
            .put("suspectSamples", suspects).put("holderCount", allViews.size)
            .put("transparentNonSourceSamples", transparentNonSource).put("recycledSourceSamples", recycledSourceSamples)
            .put("failedRequestSamples", failedRequestSamples).put("sourcePlaceholderSamples", sourcePlaceholderSamples)
            .put("visibleSourceSamples", visibleSourceSamples).put("sourceRestoredAfterCancel", restored)
        File(output, "complex-summary.json").writeText(result.toString(2))
        // 可疑样本只归档；是否为真实缺陷须结合画面、位移和加载记录判断。
        check(inspectedMoving >= 12 && seenIds.size >= fixture.things.size - 3) { "实际移动或全列表覆盖不足：$result" }
        check(transparentNonSource == 0) { "拖动期间非源卡片透明：$transparentNonSource 个样本" }
        check(visibleSourceSamples == 0 && restored) { "拖动源占位或结束恢复异常：$result" }
        check(failedRequestSamples == 0) { "媒体请求失败：$failedRequestSamples 个样本" }
        if (screenshotEvery == 1) check(mediaShown.containsAll(expectedMedia)) { "有媒体从未在拖动中显示：$result" }
        return result
    }
}
