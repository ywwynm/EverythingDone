package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.DetailToolbarContrast
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance as Appearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅 debug：使用真实详情页验证顶部统一配色；已有记事只读，修改只发生在专用临时记事。 */
class DetailToolbarContrastProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private var activity: DetailActivity? = null
    private val created = mutableListOf<Long>()
    private val fixtureTime = System.currentTimeMillis()
    private val samples = JSONArray()
    private val timings = JSONArray()
    private var failures = 0
    private val existing = linkedMapOf<Long, String>()
    private var visualGateHandled = false

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        val run = args.getString("run_id") ?: "toolbar"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "detail-toolbar/$run").apply { mkdirs() }
        val result = JSONObject()
        try {
            if (args.getString("existing", "true") == "true") existingIds().forEach { existing[it] = fingerprint(it) }
            if (args.getString("phase", "full") != "full") supplement() else fixtures()
            for (id in existing.keys) {
                open(id)
                show(0)
                record("existing-$id", controls().isNotEmpty() && controls().all { it.foreground is GradientRippleDrawable })
                close()
            }
        } catch (error: Throwable) {
            failures++
            result.put("error", error.stackTraceToString())
        } finally {
            close()
            for ((id, before) in existing) record("existing-$id-unchanged", fingerprint(id) == before, includeColors = false)
            val db = DBHelper(targetContext).writableDatabase
            var removed = 0
            created.forEach { id -> removed += db.delete(Def.Database.TABLE_THINGS,
                "id=? AND create_time=?", arrayOf(id.toString(), fixtureTime.toString())) }
            result.put("failures", failures).put("samples", samples).put("timings", timings)
                .put("fixture_rows_removed", removed).put("existing_notes", existing.size)
                .put("orientation", targetContext.resources.configuration.orientation)
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures == 0) 0 else 1, Bundle().apply {
                putString("result", "failures=$failures samples=${samples.length()} path=$output/result.json")
            })
        }
    }

    private fun supplement() {
        for (name in listOf("white", "black", "split")) {
            val path = image("visual-$name") { x, _ -> if (name == "white" || name == "split" && x < 700) Color.WHITE else Color.BLACK }
            open(fixture(listOf(path)))
            show(0)
            if (name != "split") main { controls().first().apply { isPressed = true; foreground.jumpToCurrentState() } }
            screenshot("visual-$name")
            record("visual-$name", rippleColorsMatch() && allDark(name == "white"))
            main { controls().forEach { it.isPressed = false } }
            close()
        }
        if (args.getString("phase") == "visual") return
        playbackFixtures()
    }

    private fun playbackFixtures() {
        for ((name, type) in listOf("animated.gif" to "0", "video.mp4" to "1")) {
            val media = File(output, name)
            targetContext.assets.open("detail-regression/$name").use { input -> media.outputStream().use(input::copyTo) }
            val path = type + media.absolutePath
            open(fixture(listOf(path)))
            main {
                val source = Appearance.SourceAppearance(fullSpanEnabled = true).withPresentation("fullSpan",
                    Appearance.MediaPresentationAppearance(targetAspectRatio = 3.0))
                call("applyDetailAttachmentMediaAppearance", Appearance::class.java, Appearance.default().withSource(path, source))
                val playback = field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController")
                // 仅此临时页面切为循环，不改用户的持久设置。
                DetailAttachmentPlaybackController::class.java.getDeclaredField("mode").apply { isAccessible = true }
                    .setInt(playback, DetailAutoplayMode.ALL_LOOP)
                playback.onItemsChanged()
            }
            val holder = show(0)
            await { (holder.ivImage!!.drawable as? GifDrawable)?.isRunning == true }
            settle()
            val before = main { Triple(controller().computeCount, holder.overlayColors.staticLoadCount, colors()) }
            val indices = mutableSetOf<Int>()
            repeat(36) {
                SystemClock.sleep(24)
                main { (holder.ivImage!!.drawable as? GifDrawable)?.let { indices += it.frameIndex } }
            }
            record("$name-frames-advance", indices.size > 1)
            record("$name-playing-keeps-static-colors", unifiedColors() && main { controller().computeCount == before.first &&
                holder.overlayColors.staticLoadCount == before.second && colors() == before.third })
            close()
        }
    }

    private fun fixtures() {
        val white = image("white") { _, _ -> Color.WHITE }
        val black = image("black") { _, _ -> Color.BLACK }
        val transparent = image("transparent") { _, _ -> Color.TRANSPARENT }
        val split = image("split") { x, _ -> if (x < 700) Color.WHITE else Color.BLACK }
        val inverse = image("split-inverse") { x, _ -> if (x < 700) Color.BLACK else Color.WHITE }
        val bands = image("bands") { _, y -> if (y < 700) Color.WHITE else Color.BLACK }
        for ((name, path, dark) in listOf(Triple("white", white, true), Triple("black", black, false),
            Triple("transparent", transparent, false))) {
            open(fixture(listOf(path)))
            show(0)
            record("$name-icons-and-ripples", allDark(dark) && rippleColorsMatch())
            if (name == "white") {
                main { controls().first().apply { isPressed = true; foreground.jumpToCurrentState() } }
                screenshot("white")
                main { controls().forEach { it.isPressed = false } }
            }
            val previous = main { controller().computeCount }
            SystemClock.sleep(600)
            record("$name-idle-no-resample", main { controller().computeCount == previous })
            if (name == "transparent") {
                changeColor(Color.WHITE)
                record("transparent-white-note", allDark(true) && rippleColorsMatch())
                changeColor(Color.BLACK)
                record("transparent-black-note", allDark(false) && rippleColorsMatch())
            }
            close()
        }

        open(fixture(listOf(split)))
        show(0)
        record("split-unified-white", allDark(false) && rippleColorsMatch())
        screenshot("split")
        unifiedDuring("menu-rebuild-frames") { main { activity!!.invalidateOptionsMenu() } }
        record("menu-rebuild", allDark(false) && rippleColorsMatch())
        for ((center, dark) in listOf(.25 to true, .75 to false)) {
            val source = Appearance.SourceAppearance(fullSpanEnabled = true).withPresentation("fullSpan",
                Appearance.MediaPresentationAppearance(targetAspectRatio = 1.0,
                    crop = Appearance.DetailMediaCrop(centerX = center, scale = 3.0)))
            main { call("applyDetailAttachmentMediaAppearance", Appearance::class.java,
                Appearance.default().withSource(split, source)) }
            show(0)
            record("crop-$center", allDark(dark) && rippleColorsMatch())
            if (dark) unifiedDuring("menu-rebuild-dark-frames") { main { activity!!.invalidateOptionsMenu() } }
        }
        for ((action, dark) in listOf(R.id.act_undo to true, R.id.act_redo to false)) {
            main { toolbar().menu.performIdentifierAction(action, 0) }
            show(0)
            record("history-$action", allDark(dark))
        }
        close()

        open(fixture(listOf(inverse)))
        show(0)
        record("split-inverse-unified-black", allDark(true) && rippleColorsMatch())
        screenshot("split-inverse")
        close()

        open(fixture(listOf(bands)))
        val holder = show(0)
        record("bands-top", allDark(true))
        val height = main { holder.itemView.height }
        val before = main { controller().computeCount }
        main { content().recyclerView.smoothScrollBy(0, (height * .64f).toInt(), LinearInterpolator(), 1200) }
        val observed = mutableSetOf<Boolean>()
        var scrollUniform = true
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            SystemClock.sleep(16)
            main {
                observed += isDark(controls().first())
                scrollUniform = scrollUniform && unifiedColors() && rippleColorsMatch()
                timings.put(JSONObject().put("main_us", controller().lastMainMicros)
                    .put("worker_us", controller().lastWorkerMicros).put("applies", controller().applyCount))
            }
        } while (SystemClock.uptimeMillis() < deadline && main { content().recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE })
        settle()
        record("scroll-updates-during-motion", observed.size == 2 && main { controller().computeCount > before + 6 })
        record("scroll-remains-unified", scrollUniform)
        record("bands-bottom", allDark(false) && rippleColorsMatch())
        screenshot("bands-bottom")
        show(0)
        record("scroll-back-top", allDark(true))
        main { content().layoutManager.scrollToPositionWithOffset(content().positionOf(R.id.et_title), -toolbar().height) }
        settle()
        record("outside-media-note-color", allDark(false))
        val pressed = main { controls().first().also { it.isPressed = true } }
        val ripple = main { pressed.foreground as GradientRippleDrawable }
        changeColor(Color.WHITE)
        record("note-color-and-ripple-instance", allDark(true) && main { pressed.foreground === ripple && ripple.hasVisibleFeedback })
        main { pressed.isPressed = false }
        changeColor(Color.BLACK)
        record("note-color-back", allDark(false) && rippleColorsMatch())
        close()

        open(fixture(emptyList()))
        record("no-media-initial", allDark(false))
        changeColor(Color.WHITE)
        record("no-media-color-change", allDark(true) && rippleColorsMatch())
        close()

        open(fixture(listOf(white, black, black, white, white, black, black, white)))
        val columns = main { adapter().gridSpanCount() }
        main {
            adapter().setItems(List(columns * 4) { if (it % columns == 0) white else black })
            adapter().notifyDataSetChanged()
        }
        for (position in listOf(0, columns * 2, 0)) {
            show(position)
            record("grid-$position-${samples.length()}", allDark(false) && rippleColorsMatch())
        }
        main {
            adapter().setItems(List(columns * 4) { if (it % columns == 0) white else transparent })
            adapter().notifyDataSetChanged()
        }
        show(0)
        record("media-and-dark-note-unified", allDark(false) && rippleColorsMatch())
        changeColor(Color.WHITE)
        show(0)
        record("media-and-light-note-unified", allDark(true) && rippleColorsMatch())
        close()

        playbackFixtures()
    }

    private fun image(name: String, color: (Int, Int) -> Int): String {
        val size = 1400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(size * size) { color(it % size, it / size) }, 0, size, 0, 0, size, size)
        val file = File(File(output, "media").apply { mkdirs() }, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return "0" + file.absolutePath
    }

    private fun fixture(media: List<String>): Long {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        db.insertOrThrow(Def.Database.TABLE_THINGS, null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", Color.rgb(36, 64, 81)); put("title", "顶部按钮配色验证 · 临时记事")
            put("content", List(96) { "滚动验证 ${it + 1}" }.joinToString("\n"))
            put("attachment", AttachmentHelper.toAttachmentStr(media, emptyList()))
            put("location", id); put("create_time", fixtureTime); put("update_time", fixtureTime); put("finish_time", 0L)
        })
        created += id
        return id
    }

    private fun open(id: Long) {
        activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
            "detail-toolbar-probe", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        main { DetailActivity::class.java.getDeclaredField("dontSaveAfterOnPause").apply { isAccessible = true }.setBoolean(activity, true) }
        settle()
    }

    private fun close() {
        if (activity != null) { main { activity?.finish() }; waitForIdleSync(); activity = null }
    }

    private fun show(position: Int): ImageAttachmentAdapter.ImageViewHolder {
        main { content().layoutManager.scrollToPositionWithOffset(content().globalPosition(adapter(), position), 0) }
        waitForIdleSync()
        var holder: ImageAttachmentAdapter.ImageViewHolder? = null
        await {
            holder = content().holder(adapter(), position) as? ImageAttachmentAdapter.ImageViewHolder
            holder?.let { !it.itemView.isLayoutRequested && it.overlayColors.staticRaster != null &&
                it.pbLoading?.visibility != View.VISIBLE && !it.overlayColors.pending } == true
        }
        settle()
        return holder!!
    }

    private fun changeColor(color: Int) {
        main { call("changeBackground", ThingBackground::class.java, ThingBackground.pure(color)) }
        SystemClock.sleep(700)
        settle()
    }

    private fun settle() {
        waitForIdleSync()
        val count = main { controller().refreshCount.also { controller().refresh() } }
        await { controller().refreshCount > count && !controller().pending && controls().isNotEmpty() }
    }

    private fun controls(): List<View> = main { buildList {
        val toolbar = toolbar()
        for (i in 0 until toolbar.childCount) when (val view = toolbar.getChildAt(i)) {
            is ImageView -> if (view.isShown) add(view)
            is ActionMenuView -> for (j in 0 until view.childCount) if (view.getChildAt(j).isShown) add(view.getChildAt(j))
        }
    } }

    private fun isDark(view: View): Boolean {
        val drawable = when (view) {
            is ImageView -> view.drawable
            is TextView -> view.compoundDrawables[0] ?: view.compoundDrawables[2]
            else -> null
        } ?: error("按钮没有图形")
        val bounds = drawable.bounds
        val bitmap = Bitmap.createBitmap(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        drawable.draw(canvas)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        val ink = pixels.filter { Color.alpha(it) >= 64 }
        check(ink.isNotEmpty()) { "按钮没有有效像素" }
        return ink.sumOf { Color.red(it).toLong() }.toDouble() / ink.size < 128
    }

    private fun colors(): Map<String, Boolean> = main { controls().associate { view ->
        (if (view.id == View.NO_ID) "overflow" else resources.getResourceEntryName(view.id)) to isDark(view)
    } }
    private val resources get() = targetContext.resources
    private fun allDark(dark: Boolean) = main { controls().isNotEmpty() && controls().all { isDark(it) == dark } }
    private fun unifiedColors() = colors().values.toSet().size == 1

    private fun unifiedDuring(name: String, action: () -> Unit) {
        var frames = 0
        var uniform = true
        val observer = main { toolbar().viewTreeObserver }
        val listener = ViewTreeObserver.OnPreDrawListener {
            frames++
            uniform = uniform && unifiedColors() && rippleColorsMatch()
            true
        }
        main { observer.addOnPreDrawListener(listener) }
        try { action(); settle() } finally { main { if (observer.isAlive) observer.removeOnPreDrawListener(listener) } }
        record(name, frames > 0 && uniform)
    }
    private fun rippleColorsMatch(): Boolean = main { controls().all { view ->
        val ripple = view.foreground as? GradientRippleDrawable ?: return@all false
        val background = GradientRippleDrawable::class.java.getDeclaredField("background").apply { isAccessible = true }
            .get(ripple) as ThingBackground
        val peak = GradientRippleDrawable::class.java.getDeclaredField("peakAlphaOverride").apply { isAccessible = true }.getFloat(ripple)
        val dark = isDark(view)
        background.color == (if (dark) Color.BLACK else Color.WHITE) &&
            kotlin.math.abs(peak - (if (dark) 41 else 92) / 255f) < .001f
    } }

    private fun record(name: String, passed: Boolean, includeColors: Boolean = true) {
        val data = JSONObject().put("name", name).put("passed", passed)
        if (includeColors && activity != null) data.put("colors", JSONObject(colors()))
        samples.put(data)
        if (!passed) failures++
    }
    private fun screenshot(name: String) {
        if (!visualGateHandled && args.getString("visual_gate") == "true") {
            // 系统兼容性窗口不向本测试进程的无障碍连接暴露，由 runner 先用系统层级关闭。
            File(output, "capture.ready").writeText(name)
            val deadline = SystemClock.uptimeMillis() + 60000
            while (!File(output, "capture.continue").exists()) {
                check(SystemClock.uptimeMillis() < deadline) { "等待系统窗口核验超时" }
                SystemClock.sleep(100)
            }
            visualGateHandled = true
        }
        // 等待按下状态对应的帧实际提交，不能把旧前缓冲截图当成波纹绘制结果。
        val drawn = CountDownLatch(1)
        main { toolbar().postOnAnimation { toolbar().postOnAnimation { drawn.countDown() } } }
        check(drawn.await(3, TimeUnit.SECONDS)) { "等待截图帧超时" }
        val bitmap = uiAutomation.takeScreenshot() ?: error("无法取得截图")
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun existingIds(): List<Long> {
        val result = mutableListOf<Long>()
        DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT id,title,attachment FROM things WHERE attachment IS NOT NULL AND attachment<>'' AND type<>-1 ORDER BY id DESC", null).use { c ->
            while (c.moveToNext() && result.size < 2) {
                if (c.getLong(0) == App.getDoingThingId() || c.getString(1).orEmpty().startsWith(Thing.PRIVATE_THING_PREFIX)) continue
                if (AttachmentHelper.toAttachmentItems(c.getString(2)).first.isNotEmpty()) result += c.getLong(0)
            }
        }
        return result
    }
    private fun fingerprint(id: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(id.toString())).use { c ->
            check(c.moveToFirst())
            for (i in 0 until c.columnCount) {
                digest.update((if (c.isNull(i)) "<null>" else c.getString(i)).toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun location(view: View) = IntArray(2).also(view::getLocationOnScreen)
    private fun controller() = field<DetailToolbarContrast>("mToolbarContrast")
    private fun toolbar() = field<Toolbar>("mActionbar")
    private fun content() = field<DetailContentLayout>("mDetailContent")
    private fun adapter() = field<ImageAttachmentAdapter>("mImageAttachmentAdapter")
    private fun <T> field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return DetailActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    }
    private fun call(name: String, type: Class<*>, value: Any) = DetailActivity::class.java.getDeclaredMethod(name, type)
        .apply { isAccessible = true }.invoke(activity, value)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 16000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "等待顶部配色超时" }
            SystemClock.sleep(16)
        }
    }
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
