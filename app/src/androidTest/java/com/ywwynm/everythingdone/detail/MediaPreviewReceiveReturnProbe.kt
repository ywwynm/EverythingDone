package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.interaction.UiTransitionState
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.ywwynm.everythingdone.taptransfer.ui.receive.TapDetailReceiveController
import com.ywwynm.everythingdone.taptransfer.ui.receive.TapReceivePull
import com.ywwynm.everythingdone.views.MediaPreviewTransition
import com.ywwynm.everythingdone.views.ThingDetailTransition
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 用独立测试 APK 验证已安装包的真实窗口、接收控制器和完整触摸分发链。 */
class MediaPreviewReceiveReturnProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private lateinit var media: File
    private val stamp = System.currentTimeMillis()
    private val title = "接收入口媒体返回验证 · 临时记事"
    private val ids = mutableListOf<Long>()
    private val checks = JSONArray()
    private val runs = JSONArray()
    private var detail: DetailActivity? = null
    @Volatile private var viewer: ImageViewerActivity? = null
    private val chromeChecks get() = args.getString("chrome") == "true"
    private val captures = mutableListOf<CountDownLatch>()
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is ImageViewerActivity) viewer = activity
            if (activity is DetailActivity) set(activity, "dontSaveAfterOnPause", true)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        output = File(targetContext.getExternalFilesDir(null), "media-receive-return/${args.getString("run", "device")}").apply { mkdirs() }
        media = File(targetContext.cacheDir, "media-receive-return-$stamp").apply { mkdirs() }
        val baseline = rows()
        val autoplay = DetailAutoplayMode.current()
        val app = targetContext.applicationContext as Application
        app.registerActivityLifecycleCallbacks(callbacks)
        var error: String? = null
        try {
            // 固定详情视频代表帧以比较几何；只改进程内设置，预览播放规则不变。
            main { FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, DetailAutoplayMode.OFF) }
            val image = File(media, "image.png")
            Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this); val paint = Paint()
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
                    paint.color = if (chromeChecks) Color.WHITE else color
                    canvas.drawRect(0f, index * 150f, 800f, (index + 1) * 150f, paint)
                }
                image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
            }
            val video = File(media, "video.mp4")
            targetContext.assets.open("detail-regression/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
            val quick = args.getString("quick") == "true"
            val gif = File(media, "bright.gif")
            if (chromeChecks) context.assets.open("media-return/bright.gif").use { input -> gif.outputStream().use(input::copyTo) }
            val samples = if (quick) listOf("image" to "0${image.path}") else
                listOf("image" to "0${image.path}", "video" to "1${video.path}") +
                    if (chromeChecks) listOf("gif" to "0${gif.path}") else emptyList()
            for ((type, key) in samples) {
                for (state in if (quick) listOf("pulled") else listOf("normal", "pulled", "receiving")) {
                    for (direction in if (quick) listOf(1) else listOf(1, -1, 0)) runCase(key, "$type-$state-$direction", state, direction)
                }
            }
        } catch (failure: Throwable) { error = failure.stackTraceToString() }
        finally {
            main {
                FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, autoplay)
                MediaPreviewTransition.diagnosticGeometry = null
                MediaPreviewTransition.diagnosticObserver = null
                MediaPreviewTransition.diagnosticInteractiveFrame = null
                viewer?.finish(); detail?.finish()
            }
            waitForIdleSync()
            app.unregisterActivityLifecycleCallbacks(callbacks)
            val db = DBHelper(targetContext).writableDatabase
            ids.forEach { db.delete("things", "id=? AND create_time=? AND title=?", arrayOf(it.toString(), stamp.toString(), title)) }
            media.listFiles()?.filter { it.extension == "mp4" }?.forEach { VideoCoverPreviewManager.deletePreviewsForVideo(targetContext, it.path) }
            check(media.parentFile == targetContext.cacheDir && media.name == "media-receive-return-$stamp")
            media.deleteRecursively()
            record("原记事逐行摘要一致", rows() == baseline)
            record("临时记事与媒体已清理", ids.none { rows().containsKey(it) } && !media.exists())
            val failures = (0 until checks.length()).count { !checks.getJSONObject(it).getBoolean("passed") }
            File(output, "checks.json").writeText(JSONObject().put("model", android.os.Build.MODEL)
                .put("original_rows", baseline.size).put("checks", checks).put("runs", runs)
                .put("failures", failures).put("error", error ?: JSONObject.NULL).toString(2))
            finish(if (failures == 0 && error == null) 0 else 1, Bundle().apply {
                putInt("checks", checks.length()); putInt("failures", failures); putString("error", error)
                putString("output", output.path)
            })
        }
    }

    private fun runCase(key: String, name: String, state: String, direction: Int) {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        val noteBackground = if (state == "normal") ThingBackground.pure(Color.rgb(48, 79, 110)) else
            ThingBackground.gradient(Color.rgb(160, 50, 190), Color.rgb(36, 180, 96),
                if (state == "pulled") ThingBackground.Orientation.LT_RB else ThingBackground.Orientation.T_B)
        db.insertOrThrow("things", null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("title", title); put("color", Color.rgb(48, 79, 110)); put("content", "专项回归，结束自动清理。")
            put("background", noteBackground.toJson())
            put("attachment", AttachmentHelper.toAttachmentStr(listOf(key), emptyList()))
            put("location", id); put("create_time", stamp); put("update_time", stamp); put("finish_time", 0L)
        })
        ids += id
        File(output, "fixtures.json").writeText(JSONObject().put("ids", JSONArray(ids)).put("stamp", stamp).put("title", title).put("media", media.path).toString())
        val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext, "media-receive-return", id, -1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        detail = activity
        fun source() = activity.findMediaPreviewSource(key)!!
        await("详情就绪") { activity.hasWindowFocus() && ready(activity) && activity.isMediaPreviewLayoutReady(key) && source().width > 0 }
        if (chromeChecks) await("静态参照配色就绪") {
            val holder = activity.findViewById<RecyclerView>(R.id.sv_detail).findContainingViewHolder(source())
                as? ImageAttachmentAdapter.ImageViewHolder
            holder?.overlayColors?.let { it.staticRaster != null && it.appliedKey != null && !it.pending } == true &&
                activity.isMediaPreviewLayoutReady(key)
        }
        if (chromeChecks && name.startsWith("gif-")) await("GIF 标识就绪") {
            source().findViewById<View>(R.id.tv_badge_gif).isShown
        }
        val reference = snapshot(activity.window.decorView)
        // 独立参照直接绘制实际详情根背景，不使用被测的背景复制或挖空实现。
        val notePixels = main {
            Bitmap.createBitmap(reference.width, reference.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val root = activity.findViewById<View>(R.id.fl_root_detail)
                val at = ThingDetailTransition.bounds(root, activity.window.decorView)
                Canvas(bitmap).apply { translate(at.left, at.top); root.background.draw(this) }
            }
        }
        val glyphs = if (chromeChecks && !key.startsWith("1")) main { glyphSamples(activity, source()) } else emptyMap()
        val referenceDark = main { toolbarDark(activity) }
        val sample = main { ThingDetailTransition.bounds(source(), activity.window.decorView).apply {
            top = maxOf(top, ThingDetailTransition.bounds(activity.findViewById(R.id.actionbar), activity.window.decorView).bottom + 16)
            inset(width() * .12f, height() * .12f)
        } }
        val receiver = field<TapDetailReceiveController>(activity, "tapUpdateReceiver")
        val pull = field<TapReceivePull>(receiver, "pull")
        val list = activity.findViewById<RecyclerView>(R.id.sv_detail)
        if (state != "normal") {
            val at = main { ThingDetailTransition.bounds(list, activity.window.decorView) }
            val toolbar = main { ThingDetailTransition.bounds(activity.findViewById(R.id.actionbar), activity.window.decorView) }
            swipe(activity, at.centerX(), toolbar.bottom + 40, 420 * activity.resources.displayMetrics.density, slow = true)
            await("下拉展开") { pull.currentOffset > 1 && !pull.isMoving && pull.button.visibility == View.VISIBLE }
            if (state == "receiving") {
                // 只模拟接收会话，不发起真实来件；布局和生命周期仍走生产控制器。
                main {
                    receiver.javaClass.getDeclaredMethod("enter").apply { isAccessible = true }.invoke(receiver)
                    field<Any?>(receiver, "transport")?.let { transport ->
                        transport.javaClass.getMethod("stop").invoke(transport)
                    }
                }
                await("接收展开") { receiver.isReceiving && !pull.isMoving }
            }
        }
        val openOffset = main { list.translationY }
        var destination: RectF? = null
        var returnSource: RectF? = null
        var started = 0L
        var moved = 0L
        var ended = false
        val iconFrames = JSONArray()
        val palette = mutableListOf<Boolean?>()
        val nextCapture = arrayListOf(.60f, .76f, .90f)
        val backCheckpoints = arrayListOf(.45f, .70f)
        val backgroundFrames = JSONArray()
        val backPictures = mutableListOf<Pair<String, Bitmap>>()
        captures.clear()
        main {
            MediaPreviewTransition.diagnosticGeometry = { entering, from, to -> if (!entering) {
                returnSource = from?.let(::RectF); destination = to?.let(::RectF)
            } }
            MediaPreviewTransition.diagnosticObserver = { event ->
                if (event == "enter-finished") ended = true
                if (event == "return-interactive-start") started = SystemClock.elapsedRealtimeNanos()
                if (started != 0L && moved == 0L && event.startsWith("return-progress:") && event.substringAfter(':').toFloat() > 0) moved = SystemClock.elapsedRealtimeNanos()
                if (chromeChecks && direction == 0 && event.startsWith("return-progress:") && backCheckpoints.isNotEmpty()) {
                    val progress = event.substringAfter(':').toFloat()
                    if (progress >= backCheckpoints.first()) {
                        backCheckpoints.removeAt(0)
                        val host = activity.window.decorView as ViewGroup
                        val layer = (0 until host.childCount).map(host::getChildAt)
                            .first { it.javaClass.name.endsWith("MediaPreviewTransition\$Presentation\$Layer") }
                        val frame = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
                        layer.draw(Canvas(frame))
                        val from = checkNotNull(returnSource); val to = checkNotNull(destination)
                        val moving = RectF(from.left + (to.left-from.left)*progress, from.top + (to.top-from.top)*progress,
                            from.right + (to.right-from.right)*progress, from.bottom + (to.bottom-from.bottom)*progress)
                        backgroundFrames.put(backgroundSample(frame, moving, to, notePixels, null).put("progress", progress))
                        backPictures += "$name-back-$progress.png" to frame
                    }
                }
            }
            MediaPreviewTransition.diagnosticInteractiveFrame = { p, _, _, clip ->
                if (chromeChecks && nextCapture.isNotEmpty() && p >= nextCapture.first()) {
                    val checkpoint = nextCapture.removeAt(0)
                    palette += toolbarDark(activity)
                    captureIcons(viewer!!, reference, glyphs, RectF(clip), "$name-$checkpoint", checkpoint, iconFrames)
                }
            }
        }
        // 真正接收中的舞台按设计吞掉页面触摸。此边界组直接给附件发送按下／抬起，
        // 保留真实点击反馈并验证后台退出；普通／入口展开组仍走 Activity 完整分发。
        if (chromeChecks) tap(activity, source(), directlyToSource = state == "receiving") else main { source().performClick() }
        await("预览展开") { viewer?.hasWindowFocus() == true && ended && ready(viewer!!) }
        await("详情后台复位") { !receiver.isReceiving && pull.currentOffset == 0f }
        val current = viewer!!
        val transition = field<MediaPreviewTransition>(current, "mPreviewTransition")
        // 同一测试 APK 也能复测未提供此状态字段的旧发布包。
        await("手势背景准备") { !runCatching { field<Boolean>(transition, "isRefreshingDismissBackground") }.getOrDefault(false) }
        val background = main { transition.dismissBackground() }
        if (chromeChecks) {
            record("$name 后台准备配色与正常详情一致", main { toolbarDark(activity) == referenceDark })
            record("$name 后台没有残留触摸反馈", main { feedbackCount(activity.window.decorView) == 0 })
        }
        val difference = background?.let { difference(reference, it, sample) } ?: 1.0
        val stopped = snapshot(activity.window.decorView)
        val stoppedDifference = difference(reference, stopped, sample)
        stopped.recycle()
        record("$name 背景与正常详情附件区域一致", difference < .04)
        reference.outputStreamTo(File(output, "$name-reference.png"))
        background?.outputStreamTo(File(output, "$name-background.png"))
        if (direction == 0) main { current.onBackPressedDispatcher.onBackPressed() }
        else {
            val photo = main { field<List<View>>(current, "mTabs")[0].findViewById<PhotoView>(R.id.iv_image_attachment) }
            val area = main { current.motionPreviewBounds(photo) ?: MediaPreviewTransition.fullPhotoRect(photo, current.window.decorView)!! }
            // 先短拖取消，验证背景更新不会破坏回弹，再提交返回。
            swipe(current, area.centerX(), area.centerY(), direction * 64 * current.resources.displayMetrics.density, slow = true) {
                if (chromeChecks) {
                    val moving = main { MediaPreviewTransition.fullPhotoRect(photo, current.window.decorView)!! }
                    val target = main { MediaPreviewTransition.laidOutDetailRect(source(), activity.window.decorView)!! }
                    val frame = windowSnapshot(current)
                    val progress = main {
                        val gesture = field<Any>(current, "mPreviewDismiss")
                        field<Float>(field<Any>(field<Any>(gesture, "drag"), "backdrop"), "progress")
                    }
                    val opacity = 1f - kotlin.math.round((1f-progress)*255f) / 255f
                    val measured = backgroundSample(frame, moving, target, notePixels, opacity)
                    backgroundFrames.put(measured.put("progress", progress))
                    record("$name 原附件位置显示记事背景且没有静止媒体副本", measured.getBoolean("passed"))
                    frame.outputStreamTo(File(output, "$name-empty-target.png")); frame.recycle()
                }
            }
            await("短拖回弹") { !field<com.ywwynm.everythingdone.views.MediaPreviewDismissGesture>(current, "mPreviewDismiss").isActive }
            record("$name 取消保持预览", !current.isFinishing && photo.scale == 1f)
            swipe(current, area.centerX(), area.centerY(), direction * current.window.decorView.height * .32f, slow = false)
        }
        await("返回详情") { activity.hasWindowFocus() && ready(activity) && current.isFinishing }
        if (chromeChecks) {
            captures.toList().forEach { check(it.await(5, TimeUnit.SECONDS)) }
            if (direction == 0) {
                val sampled = (0 until backgroundFrames.length()).map(backgroundFrames::getJSONObject).filter { it.getInt("points") > 0 }
                record("$name 返回键原附件位置显示记事背景", sampled.isNotEmpty() && sampled.all { it.getBoolean("passed") })
                for ((file, bitmap) in backPictures) { bitmap.outputStreamTo(File(output, file)); bitmap.recycle() }
            }
            repeat(12) {
                main { palette += toolbarDark(activity) }
                record("$name 恢复后第 $it 次无旧波纹", main { feedbackCount(activity.window.decorView) == 0 })
                SystemClock.sleep(32)
            }
            record("$name 运动与交接颜色保持", palette.all { it == referenceDark })
            if (direction != 0) for (glyph in glyphs.keys) {
                val values = (0 until iconFrames.length()).map { iconFrames.getJSONObject(it) }
                    .filter { it.optString("glyph") == glyph }
                record("$name $glyph 在结束前渐显", values.any { it.getDouble("progress") < .85 && it.getDouble("visible") > .15 } &&
                    values.any { it.getDouble("progress") >= .89 && it.getDouble("visible") > .75 })
            }
        }
        val actual = main { ThingDetailTransition.visibleBounds(source(), activity.window.decorView)!! }
        record("$name 目标与实际附件位置一致", destination?.let { near(it, actual) } == true)
        record("$name 接收入口已收起", main { list.translationY == 0f && pull.button.visibility != View.VISIBLE })
        if (direction != 0) record("$name 松手 48ms 内运动", started != 0L && moved > started && (moved - started) / 1_000_000.0 < 48)
        runs.put(JSONObject().put("name", name).put("open_offset", openOffset).put("background_difference", difference)
            .put("stopped_background_difference", stoppedDifference)
            .put("target", destination.toString()).put("actual", actual.toString()).put("first_motion_ms", (moved - started) / 1_000_000.0))
        if (chromeChecks) runs.getJSONObject(runs.length() - 1).put("icon_frames", iconFrames).put("palette", JSONArray(palette))
            .put("touch_route", if (state == "receiving") "attachment-boundary" else "activity")
            .put("note_background", noteBackground.toJson()).put("background_frames", backgroundFrames)
        reference.recycle()
        notePixels.recycle()
        main { activity.finish() }; waitForIdleSync(); detail = null; viewer = null
    }

    private fun toolbarDark(activity: DetailActivity): Boolean? = field<Any?>(activity, "mToolbarContrast")?.let { field(it, "toolbarDark") }

    private fun backgroundSample(frame: Bitmap, moving: RectF, target: RectF, note: Bitmap, opacity: Float?): JSONObject {
        val margin = (80 * targetContext.resources.displayMetrics.density).toInt()
        var points = 0; var matching = 0; var minOpacity = 1.0
        for (y in (target.top.toInt() + margin) until (target.bottom.toInt() - margin) step 16)
            for (x in (target.left.toInt() + margin) until (target.right.toInt() - margin) step 16) {
                if (moving.contains(x.toFloat(), y.toFloat())) continue
                val expected = note.getPixel(x, y); val actual = frame.getPixel(x, y)
                val e = intArrayOf(Color.red(expected), Color.green(expected), Color.blue(expected))
                val a = intArrayOf(Color.red(actual), Color.green(actual), Color.blue(actual))
                // 返回键的两份页面各自渐显／渐隐，亮度允许变化；颜色必须与同位置背景一致。
                val gain = opacity?.toDouble() ?: a.indices.sumOf { a[it] * e[it] }.toDouble() /
                    e.sumOf { it * it }.coerceAtLeast(1)
                val error = a.indices.maxOf { kotlin.math.abs(a[it] - e[it] * gain) }
                if (gain > .02 && gain <= 1.03 && error <= 3) matching++
                minOpacity = minOf(minOpacity, gain); points++
            }
        return JSONObject().put("points", points).put("matching", matching).put("min_opacity", minOpacity)
            .put("passed", points > 0 && matching.toDouble() / points > .95)
    }
    private fun feedbackCount(view: View): Int {
        var count = listOf(view.background, view.foreground).count { it is GradientRippleDrawable && (it.hasVisibleFeedback || it.feedbackAnimating) }
        if (view is ViewGroup) for (i in 0 until view.childCount) count += feedbackCount(view.getChildAt(i))
        return count
    }
    private fun tap(activity: Activity, view: View, directlyToSource: Boolean) {
        val rect = main { if (directlyToSource) RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            else ThingDetailTransition.visibleBounds(view, activity.window.decorView)!! }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.centerX(), rect.centerY(), 0)
            main { if (directlyToSource) view.dispatchTouchEvent(event) else activity.dispatchTouchEvent(event) }; event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(64)
        }
    }
    private fun glyphSamples(activity: DetailActivity, source: View): Map<String, List<Pair<Int, Int>>> {
        val toolbar = activity.findViewById<ViewGroup>(R.id.actionbar)
        val views = linkedMapOf("actionbar" to (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.first { it is android.widget.ImageButton },
            "delete" to source.findViewById<View>(R.id.iv_delete_image_attachment))
        source.findViewById<View>(R.id.tv_badge_gif)?.takeIf { it.visibility == View.VISIBLE }?.let { views["gif"] = it }
        return views.mapValues { (name, view) ->
            val bounds = ThingDetailTransition.bounds(view, activity.window.decorView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            buildList {
                for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) > 180 && Color.red(color) < 64 && Color.green(color) < 64 && Color.blue(color) < 64)
                        add((bounds.left.toInt() + x) to (bounds.top.toInt() + y))
                }
            }.also { bitmap.recycle(); check(it.isNotEmpty()) { "$name 图标采样不能为空，顶栏配色=${toolbarDark(activity)}" } }
        }
    }
    private fun captureIcons(activity: Activity, reference: Bitmap, glyphs: Map<String, List<Pair<Int, Int>>>, clip: RectF,
                             name: String, progress: Float, outputFrames: JSONArray) {
        val latch = CountDownLatch(1); captures += latch
        val bitmap = Bitmap.createBitmap(reference.width, reference.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                for ((glyph, points) in glyphs) {
                    val covered = points.filter { clip.contains(it.first.toFloat(), it.second.toFloat()) }
                    val visible = covered.count { (x, y) -> Color.red(bitmap.getPixel(x,y)) < 140 &&
                        Color.green(bitmap.getPixel(x,y)) < 140 && Color.blue(bitmap.getPixel(x,y)) < 140 }
                    outputFrames.put(JSONObject().put("progress", progress).put("glyph", glyph).put("covered", covered.size)
                        .put("visible", visible.toDouble() / covered.size.coerceAtLeast(1)))
                }
                bitmap.outputStreamTo(File(output, "$name-icons.png"))
            }
            bitmap.recycle(); latch.countDown()
        }, Handler(Looper.getMainLooper()))
    }

    private fun swipe(activity: Activity, x: Float, y: Float, dy: Float, slow: Boolean, beforeRelease: () -> Unit = {}) {
        val down = SystemClock.uptimeMillis()
        for (index in 0..16) {
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            if (index == 16 && slow) SystemClock.sleep(180) else if (index > 0) SystemClock.sleep(16)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y + dy * minOf(index, 15) / 15, 0)
            main { activity.dispatchTouchEvent(event) }; event.recycle()
            if (index == 15) beforeRelease()
        }
    }
    private fun windowSnapshot(activity: Activity): Bitmap {
        val latch = CountDownLatch(1)
        val bitmap = main { Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888) }
        var result = -1
        main { activity.window.decorView.postOnAnimation {
            PixelCopy.request(activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        } }
        check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        return bitmap
    }
    private fun snapshot(view: View): Bitmap {
        val latch = CountDownLatch(1); var bitmap: Bitmap? = null
        main { ParticleDismissController.captureContent(view) { bitmap = it; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS)); return checkNotNull(bitmap)
    }
    private fun difference(a: Bitmap, b: Bitmap, rect: RectF): Double {
        var changed = 0; var count = 0
        for (y in rect.top.toInt() until rect.bottom.toInt() step 8) for (x in rect.left.toInt() until rect.right.toInt() step 8) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (kotlin.math.abs(Color.red(p) - Color.red(q)) + kotlin.math.abs(Color.green(p) - Color.green(q)) + kotlin.math.abs(Color.blue(p) - Color.blue(q)) > 48) changed++
            count++
        }
        return changed.toDouble() / count.coerceAtLeast(1)
    }
    private fun Bitmap.outputStreamTo(file: File) { file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun near(a: RectF, b: RectF) = listOf(a.left-b.left, a.top-b.top, a.right-b.right, a.bottom-b.bottom).all { kotlin.math.abs(it) < 2 }
    private fun ready(activity: Activity): Boolean {
        val sessions = field<Map<Activity, Any>>(UiInteractions, "sessions")
        val state = sessions[activity]?.let { field<UiTransitionState>(it, "state") } ?: return false
        return state.canInteract(state.root)
    }
    private fun rows(): Map<Long, String> = DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE type<>-1 ORDER BY id", null).use { cursor ->
        buildMap { while (cursor.moveToNext()) {
            val digest = MessageDigest.getInstance("SHA-256")
            for (i in 0 until cursor.columnCount) { digest.update((cursor.getString(i) ?: "<null>").toByteArray()); digest.update(0.toByte()) }
            put(cursor.getLong(cursor.getColumnIndexOrThrow("id")), digest.digest().joinToString("") { "%02x".format(it) })
        } }
    }
    private fun record(name: String, passed: Boolean) { checks.put(JSONObject().put("name", name).put("passed", passed)) }
    private fun await(name: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition)) return; SystemClock.sleep(24) }
        throw AssertionError("等待失败：$name")
    }
    private fun <T> main(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        var result: Result<T>? = null; runOnMainSync { result = runCatching(action) }; return result!!.getOrThrow()
    }
    private fun <T> field(instance: Any, name: String): T {
        @Suppress("UNCHECKED_CAST") return instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    }
    private fun set(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value) }
}
