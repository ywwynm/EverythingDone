package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.helpers.MotionPhotoCoverHelper
import com.ywwynm.everythingdone.helpers.MotionPhotoDetector
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.model.Thing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅在独立包中执行：真实详情、实际 GIF 解码、逐次绘制采样，不修改用户记事。 */
class DetailGifFlickerProbe : Instrumentation() {
    private lateinit var output: File
    private lateinit var activity: DetailActivity
    private var args = Bundle()
    private var stage = "initial"
    private val samples = JSONArray()
    private val events = JSONArray()
    private val gestures = JSONArray()
    private var sampledMediaCount = 3
    private var drawSequence = 0
    private val lastFrames = mutableMapOf<Pair<String, Int>, Pair<Int, Int>>()
    private val loopWraps = mutableMapOf<Pair<String, Int>, Int>()
    private var observer: ViewTreeObserver? = null
    private var windowCapture: DetailGifWindowCapture? = null
    private val sampleBitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(24 * 24)
    private val listener = ViewTreeObserver.OnPreDrawListener { sample(); true }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        check(targetContext.packageName == "com.ywwynm.everythingdone.gifflickervalidation")
        val run = args.getString("run_id", "baseline")!!
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        output = File(targetContext.getExternalFilesDir(null), "gif-flicker/$run").apply { mkdirs() }
        val result = JSONObject()
        try {
            if (args.getString("boundary") == "true") {
                // 每轮重新建立 Glide 磁盘缓存，排除前一轮静态解码已缓存导致的假通过。
                // 仅清隔离包 Glide 缓存；真实派生 GIF 与正式应用数据均不在其中。
                Glide.get(targetContext).clearDiskCache()
                events.put(JSONObject().put("name", "boundary-glide-cold").put("passed", true))
            }
            // 仅隔离包跳过首次使用提示，避免截图被引导窗口遮挡。
            targetContext.getSharedPreferences(Def.Meta.META_DATA_NAME, 0).edit()
                .putBoolean(Def.Meta.KEY_1_1_4_TO_1_1_5, true).commit()
            val initialMode = if (args.getString("sequential") == "true") DetailAutoplayMode.ONE_BY_ONE else DetailAutoplayMode.ALL_LOOP
            targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0).edit()
                .putInt(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, initialMode).commit()
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, initialMode)
            if (args.getString("queue") == "true") {
                queuePolicyChecks()
                result.put("ok", true)
                return
            }
            val complex = args.getString("complex") == "true"
            val video = File(targetContext.filesDir, if (complex) "complex-video.mp4" else "flicker-video.mp4")
            if (!video.exists()) targetContext.assets.open("gif-flicker/${if (complex) "complex-source" else "source"}.mp4")
                .use { input -> video.outputStream().use(input::copyTo) }
            val gif = checkNotNull(VideoCoverPreviewManager.generateBlocking(targetContext, video.path, null))
            gif.copyTo(File(output, "video.gif"), overwrite = true)
            val motion = File(targetContext.filesDir, if (complex) "complex-motion.jpg" else "flicker-motion.jpg")
            val still = Bitmap.createBitmap(if (complex) 960 else 480, if (complex) 720 else 480,
                Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
            if (!motion.exists()) motion.outputStream().use { stream ->
                still.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                stream.write(video.readBytes())
            }
            val staticFile = File(targetContext.filesDir, "flicker-still.jpg")
            staticFile.outputStream().use { still.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            still.recycle()
            check(MotionPhotoDetector.detect(motion.path).isMotionPhoto)
            MotionPhotoCoverHelper.requestGif(targetContext, motion.path) {}
            await { MotionPhotoCoverHelper.getReadyGif(targetContext, motion.path) != null }
            val dynamic = listOf("1${video.path}", "0${motion.path}", "0${gif.path}") + if (complex) {
                (0..8).map { index ->
                    val file = File(targetContext.filesDir, "complex-real-$index.gif")
                    if (!file.exists()) targetContext.assets.open("gif-flicker/real-${index % 3}.gif")
                        .use { input -> file.outputStream().use(input::copyTo) }
                    "0${file.path}"
                }
            } else emptyList()
            sampledMediaCount = dynamic.size
            events.put(JSONObject().put("name", "fixture-media").put("complex", complex)
                .put("derivedVideoBytes", gif.length())
                .put("derivedMotionBytes", MotionPhotoCoverHelper.getReadyGif(targetContext, motion.path)?.length())
                .put("dynamicFileBytes", JSONArray(dynamic.map { File(it.substring(1)).length() })))
            val media = dynamic + List(17) { "0${staticFile.path}" }
            if (args.getString("boundary") == "true") {
                loopBoundaries(dynamic, "0${staticFile.path}")
                result.put("ok", true)
                return
            }
            if (args.getString("sequential") == "true") {
                sequentialWithKeyboard(media)
                result.put("ok", true)
                return
            }
            if (args.getString("large") == "true") {
                largeMedia(media)
                result.put("ok", true)
                return
            }
            val id = fixture(media)
            open(id)
            show(0)
            await { (0..2).all { (holder(it)?.ivImage?.drawable as? GifDrawable)?.isRunning == true } }
            await {
                (0 until sampledMediaCount).all { position ->
                    val view = holder(position)?.ivImage
                    view == null || !view.getGlobalVisibleRect(Rect()) ||
                        (view.drawable != null && view.getTag(R.id.tag_detail_attachment_image_load_key) != null)
                }
            }
            events.put(main { JSONObject().put("name", "mapped-gif-data").put("passed",
                (0..2).all { (holder(it)?.ivImage?.drawable as? GifDrawable)?.buffer?.isDirect == true }) })
            watch("loop")
            // 大文件并发解码时墙钟 7.5 秒未必足够两轮，以实际帧回绕为准。
            await { (0..2).all { (loopWraps["loop" to it] ?: 0) >= 2 } }
            screenshot("loop")
            val originals = main { (0..2).map { holder(it)!!.ivImage!!.drawable as GifDrawable } }
            repeat(3) { pass ->
                stage = "edge-leave-$pass"
                main {
                    val height = checkNotNull(holder(0)).itemView.height
                    (content().recyclerView.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(content().globalPosition(adapter(), 0), -(height * .8f).toInt())
                }
                SystemClock.sleep(350)
                events.put(main { JSONObject().put("name", "edge-paused-$pass")
                    .put("same", (0..1).all { holder(it)?.ivImage?.drawable === originals[it] })
                    .put("stopped", (0..1).all { !originals[it].isRunning }) })
                stage = "edge-return-$pass"
                show(0)
                SystemClock.sleep(650)
            }
            stage = "recycle-away"
            show(18)
            SystemClock.sleep(350)
            events.put(main { JSONObject().put("name", "recycled-stopped")
                .put("passed", originals.all { !it.isRunning }) })
            main { Glide.get(targetContext).clearMemory() }
            stage = "recycle-return"
            show(0)
            SystemClock.sleep(1600)
            screenshot("return")
            unwatch()
            close()
            main { Glide.get(targetContext).clearMemory() }
            open(id)
            watch("reopen")
            show(0)
            SystemClock.sleep(1500)
            unwatch()
            close()
            // 保留 Glide 缓存重新打开：GifFrameLoader 可能已经释放首帧，只剩当前帧。
            open(id)
            show(0)
            await { (holder(0)?.ivImage?.drawable as? GifDrawable)?.isRunning == true }
            events.put(main { JSONObject().put("name", "warm-first-frames")
                .put("available", (0..2).count { (holder(it)?.ivImage?.drawable as? GifDrawable)?.firstFrame != null }) })
            watch("warm-away")
            show(18)
            SystemClock.sleep(300)
            main { Glide.get(targetContext).clearMemory() }
            stage = "warm-return"
            show(0)
            SystemClock.sleep(1600)
            if (args.getString("extended") == "true") extended()
            if (targetContext.assets.list("gif-flicker")?.contains("real-0.gif") == true) {
                unwatch()
                close()
                val real = (0..2).map { index ->
                    val file = File(targetContext.filesDir, "real-$index.gif")
                    targetContext.assets.open("gif-flicker/real-$index.gif").use { input -> file.outputStream().use(input::copyTo) }
                    "0${file.path}"
                }
                open(fixture(real))
                show(0)
                await { (0..2).all { (holder(it)?.ivImage?.drawable as? GifDrawable)?.isRunning == true } }
                watch("real-loop")
                SystemClock.sleep(12000)
                events.put(JSONObject().put("name", "existing-media-loop").put("passed", true))
            }
            result.put("ok", true)
        } catch (error: Throwable) {
            result.put("ok", false).put("error", error.stackTraceToString())
        } finally {
            main { windowCapture?.stop() }
            await { windowCapture?.isPending() != true }
            main { windowCapture?.finish(); windowCapture = null }
            if (::activity.isInitialized) { unwatch(); close() }
            sampleBitmap.recycle()
            File(output, "samples.json").writeText(samples.toString())
            File(output, "gestures.json").writeText(gestures.toString())
            File(output, "result.json").writeText(result.put("samples", samples.length()).put("events", events).toString(2))
            finish(if (result.optBoolean("ok")) 0 else 1, Bundle().apply { putString("result", result.toString()) })
        }
    }

    private fun loopBoundaries(dynamic: List<String>, still: String) {
        val mixed = dynamic.flatMapIndexed { index, source ->
            if (index % 3 == 2) listOf(source, still) else listOf(source)
        }
        sampledMediaCount = mixed.size
        val id = fixture(mixed + List(17) { still })
        open(id)
        if (args.getString("fullspan") == "true") {
            val appearance = DetailAttachmentMediaAppearance().withSource(mixed.first(),
                DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true))
            main { DetailActivity::class.java.getDeclaredMethod("setDetailAttachmentMediaAppearanceFromJson",
                String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(activity, appearance.toJson(), true) }
        }
        fun ready() = await {
            (0 until sampledMediaCount).all { position ->
                val image = holder(position)?.ivImage
                image == null || !image.getGlobalVisibleRect(Rect()) ||
                    (image.drawable != null && image.getTag(R.id.tag_detail_attachment_image_load_key) != null)
            }
        }
        show(0)
        ready()
        main { windowCapture = DetailGifWindowCapture(activity.window, output) }
        watch("boundary-loop-0")
        for (position in listOf(0, 6, 10)) {
            stage = "boundary-positioning-$position"
            show(position)
            ready()
            SystemClock.sleep(500)
            stage = "boundary-loop-$position"
            val start = SystemClock.uptimeMillis()
            SystemClock.sleep(if (args.getString("fullspan") == "true") 25000 else 45000)
            events.put(main { JSONObject().put("name", stage).put("start", start)
                .put("end", SystemClock.uptimeMillis()).put("wraps", JSONObject().apply {
                    (0 until sampledMediaCount).forEach { put(it.toString(), loopWraps[stage to it] ?: 0) }
                }) })
        }
        stage = "boundary-mode-switch"
        main {
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, DetailAutoplayMode.ONE_BY_ONE)
            targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0).edit()
                .putInt(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, DetailAutoplayMode.ONE_BY_ONE).commit()
            field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController").onResume()
        }
        if (args.getString("fullspan") == "true") {
            stage = "boundary-open-keyboard"
            main { (content().recyclerView.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(content().positionOf(R.id.et_title), 0) }
            await { content().view<EditText>(R.id.et_title).isAttachedToWindow }
            main { content().view<EditText>(R.id.et_title).requestFocus() }
            await { (targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .isActive(content().view<EditText>(R.id.et_title)) }
            main { (targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(content().view<EditText>(R.id.et_title), InputMethodManager.SHOW_IMPLICIT) }
            await { ViewCompat.getRootWindowInsets(content().recyclerView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            SystemClock.sleep(400)
            stage = "boundary-ime-fast-return"
            repeat(18) { swipeMedia(down = true) }
            stage = "boundary-ime-fast-fling"
            repeat(8) {
                repeat(3) { swipeMedia(down = false) }
                repeat(4) { swipeMedia(down = true) }
            }
            events.put(main { JSONObject().put("name", "boundary-ime-fast-covered").put("passed",
                ViewCompat.getRootWindowInsets(content().recyclerView)?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    (0 until samples.length()).count { samples.getJSONObject(it).getString("stage") == stage } > 100) })
        }
        stage = "boundary-sequential-positioning"
        show(sampledMediaCount + 8)
        SystemClock.sleep(250)
        show(0)
        ready()
        if (args.getString("fullspan") == "true") events.put(main { JSONObject().put("name", "boundary-fullspan-ime-entry")
            .put("width", holder(0)!!.ivImage!!.width).put("height", holder(0)!!.ivImage!!.height)
            .put("passed", holder(0)!!.ivImage!!.width > content().recyclerView.width * .9f &&
                field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController").shouldPlay(0)) })
        stage = "boundary-sequential-completion"
        SystemClock.sleep(40000)
        events.put(main { JSONObject().put("name", "boundary-sequential-completed").put("passed",
            (0 until sampledMediaCount).none { field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController").shouldPlay(it) }) })
    }

    private fun extended() {
        stage = "lifecycle"
        val gifs = main { (0..2).map { holder(it)!!.ivImage!!.drawable as GifDrawable } }
        val cover = startActivitySync(Intent(targetContext, DetailRegressionCoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(300)
        events.put(main { JSONObject().put("name", "activity-stopped").put("passed", gifs.all { !it.isRunning }) })
        main { cover.finish() }
        await { holder(0)?.ivImage?.isShown == true }
        SystemClock.sleep(300)
        events.put(main { JSONObject().put("name", "activity-resumed").put("passed",
            (0..2).all { (holder(it)?.ivImage?.drawable as? GifDrawable)?.isRunning == true }) })
        val controller = field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController")
        for (mode in listOf(DetailAutoplayMode.OFF, DetailAutoplayMode.ALL_ONCE, DetailAutoplayMode.ONE_BY_ONE, DetailAutoplayMode.ALL_LOOP)) {
            stage = "mode-$mode"
            main {
                DetailAttachmentPlaybackController::class.java.getDeclaredField("mode").apply { isAccessible = true }.setInt(controller, mode)
                controller.onItemsChanged()
            }
            SystemClock.sleep(300)
            if (mode == DetailAutoplayMode.ALL_ONCE || mode == DetailAutoplayMode.ONE_BY_ONE) {
                await { (0..2).none { controller.shouldPlay(it) } }
                SystemClock.sleep(120)
            }
            events.put(main { JSONObject().put("name", "mode-$mode").put("passed",
                if (mode == DetailAutoplayMode.ALL_LOOP) (0..2).all { (holder(it)?.ivImage?.drawable as? GifDrawable)?.isRunning == true }
                else (0..2).all { holder(it)?.ivImage?.drawable != null && holder(it)?.ivImage?.drawable !is GifDrawable }) })
        }
        stage = "reorder"
        main {
            val moved = adapter().getItems()!!.toMutableList()
            moved.add(1, moved.removeAt(0))
            adapter().setItems(moved)
            adapter().notifyItemMoved(0, 1)
            controller.onItemsChanged()
        }
        SystemClock.sleep(500)
        events.put(main { JSONObject().put("name", "reorder-plays").put("passed",
            (0..2).all { (holder(it)?.ivImage?.drawable as? GifDrawable)?.isRunning == true }) })
    }

    private fun largeMedia(media: List<String>) {
        val id = fixture(media)
        val appearance = DetailAttachmentMediaAppearance().withSource(media.first(),
            DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true))
        fun openLarge() {
            open(id)
            main { DetailActivity::class.java.getDeclaredMethod("setDetailAttachmentMediaAppearanceFromJson",
                String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(activity, appearance.toJson(), true) }
            show(0)
            await { (holder(0)?.ivImage?.drawable as? GifDrawable)?.isRunning == true }
            SystemClock.sleep(500)
        }
        openLarge()
        events.put(main { JSONObject().put("name", "large-size")
            .put("width", holder(0)!!.ivImage!!.width).put("height", holder(0)!!.ivImage!!.height) })
        watch("large-away")
        show(18)
        SystemClock.sleep(300)
        main { Glide.get(targetContext).clearMemory() }
        stage = "large-return"
        show(0)
        SystemClock.sleep(1500)
        unwatch()
        close()
        openLarge()
        events.put(main { JSONObject().put("name", "large-warm-first-frame")
            .put("available", (holder(0)?.ivImage?.drawable as? GifDrawable)?.firstFrame != null) })
        watch("large-warm-away")
        show(18)
        SystemClock.sleep(300)
        main { Glide.get(targetContext).clearMemory() }
        stage = "large-warm-return"
        show(0)
        SystemClock.sleep(1500)
        screenshot("large-return")
    }

    private fun sequentialWithKeyboard(media: List<String>) {
        main {
            targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0).edit()
                .putInt(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, DetailAutoplayMode.ONE_BY_ONE).commit()
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, DetailAutoplayMode.ONE_BY_ONE)
        }
        open(fixture(media))
        if (args.getString("complex") == "true") {
            val fullSpan = args.getString("fullspan") == "true"
            val appearance = DetailAttachmentMediaAppearance().withSource(media[if (fullSpan) 0 else 3],
                DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true))
            main { DetailActivity::class.java.getDeclaredMethod("setDetailAttachmentMediaAppearanceFromJson",
                String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(activity, appearance.toJson(), true) }
            // 先走完页面，区分首次解码的等待与已显示附件回收后露底。
            for (position in 0 until sampledMediaCount) {
                show(position)
                await { holder(position)?.ivImage?.drawable != null }
                if (position == 0 && fullSpan) events.put(main {
                    JSONObject().put("name", "fullspan-measured")
                        .put("width", holder(0)!!.ivImage!!.width).put("height", holder(0)!!.ivImage!!.height)
                        .put("passed", holder(0)!!.ivImage!!.width > content().recyclerView.width * .9f)
                })
                SystemClock.sleep(80)
            }
        }
        show(0)
        await { holder(0)?.ivImage?.drawable != null }
        watch("sequential-idle")
        SystemClock.sleep(10500)
        for (keyboard in listOf(false, true)) {
            if (keyboard) {
                stage = "sequential-open-keyboard"
                main { (content().recyclerView.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(content().positionOf(R.id.et_title), 0) }
                await { content().view<EditText>(R.id.et_title).isAttachedToWindow }
                main { content().view<EditText>(R.id.et_title).requestFocus() }
                await { (targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .isActive(content().view<EditText>(R.id.et_title)) }
                main {
                    val editor = content().view<EditText>(R.id.et_title)
                    (targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                await { ViewCompat.getRootWindowInsets(content().recyclerView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                var height = -1
                var stableSince = SystemClock.uptimeMillis()
                await {
                    val currentHeight = content().recyclerView.height
                    if (height != currentHeight) { height = currentHeight; stableSince = SystemClock.uptimeMillis() }
                    SystemClock.uptimeMillis() - stableSince > 300 &&
                        (ViewCompat.getRootWindowInsets(content().recyclerView)
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0
                }
                events.put(JSONObject().put("name", "keyboard-shown").put("passed", true))
                screenshot("sequential-keyboard")
                // 用真实触摸从标题滚回附件，不让编辑器定位助手代替用户滚动。
                stage = "sequential-keyboard-return"
                repeat(if (args.getString("complex") == "true") 18 else 9) { swipeMedia(down = true) }
            }
            stage = if (keyboard) "sequential-ime-fling" else "sequential-fling"
            repeat(8) {
                repeat(3) { swipeMedia(down = false) }
                repeat(4) { swipeMedia(down = true) }
            }
            if (keyboard) events.put(main { JSONObject().put("name", "keyboard-still-shown")
                .put("passed", ViewCompat.getRootWindowInsets(content().recyclerView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true) })
            stage = if (keyboard) "sequential-ime-settle" else "sequential-settle"
            SystemClock.sleep(10500)
            screenshot(stage)
        }
        events.put(JSONObject().put("name", "ime-media-observed").put("passed",
            (0 until samples.length()).count { samples.getJSONObject(it).getString("stage") == "sequential-ime-fling" } > 100))
        if (args.getString("fullspan") == "true") {
            stage = "sequential-fullspan-ime-entry"
            show(sampledMediaCount + 8)
            SystemClock.sleep(250)
            show(0)
            SystemClock.sleep(500)
            events.put(main {
                val rect = Rect()
                val child = holder(0)!!.itemView
                child.getGlobalVisibleRect(rect)
                JSONObject().put("name", "fullspan-ime-entry")
                    .put("visibleHeight", rect.height()).put("height", child.height)
                    .put("passed", field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController").shouldPlay(0))
            })
            SystemClock.sleep(3500)
        }
    }

    /** 用真实布局/回收驱动生产调度器；只替换解码边界，记录每次起播而非最终状态。 */
    private fun queuePolicyChecks() {
        val cover = startActivitySync(Intent(targetContext, DetailRegressionCoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var list: RecyclerView
        lateinit var controller: DetailAttachmentPlaybackController
        val starts = mutableListOf<Int>()
        try {
            main {
                list = RecyclerView(cover).apply {
                    layoutManager = GridLayoutManager(cover, 2)
                    itemAnimator = null
                }
                val fake = object : ImageAttachmentAdapter(cover, false, List(20) { "0queue-$it.gif" }, null, null) {
                    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
                        holder.itemView.layoutParams = RecyclerView.LayoutParams(200, 200)
                    }
                    override fun onViewAttachedToWindow(holder: ImageViewHolder) {}
                    override fun onViewDetachedFromWindow(holder: ImageViewHolder) {}
                    override fun onViewRecycled(holder: ImageViewHolder) {}
                    override fun isPlayableNow(position: Int) = true
                    override fun refreshPlayback(position: Int) {
                        if (controller.shouldPlay(position)) starts.add(position)
                    }
                }
                list.adapter = fake
                cover.setContentView(android.widget.FrameLayout(cover).apply {
                    addView(list, android.widget.FrameLayout.LayoutParams(400, 400))
                })
                controller = DetailAttachmentPlaybackController(list)
                controller.setAdapter(fake)
            }
            await { list.childCount >= 4 && controller.shouldPlay(0) }
            main { starts.clear(); list.scrollBy(0, 400) }
            await { controller.shouldPlay(4) }
            events.put(main { JSONObject().put("name", "batch-leave-starts")
                .put("positions", JSONArray(starts.toList())).put("passed", starts == listOf(4)) })
            main { starts.clear(); controller.onPlaybackFinished(4) }
            events.put(main { JSONObject().put("name", "queue-next-visible")
                .put("positions", JSONArray(starts.toList())).put("passed", starts == listOf(5)) })
            main { starts.clear(); list.scrollBy(0, -400) }
            await { controller.shouldPlay(0) }
            events.put(main { JSONObject().put("name", "batch-return-starts")
                .put("positions", JSONArray(starts.toList())).put("passed", starts == listOf(0)) })
            main {
                starts.clear()
                (0..3).forEach(controller::onPlaybackFinished)
                controller.onResume()
            }
            events.put(main { JSONObject().put("name", "completed-visible-not-requeued")
                .put("positions", JSONArray(starts.toList()))
                .put("passed", starts == listOf(1, 2, 3) && (0..3).none(controller::shouldPlay)) })
        } finally {
            main { controller.dispose(); cover.finish() }
        }
    }

    private fun swipeMedia(down: Boolean) {
        val rect = main { Rect().also {
            check(content().recyclerView.getGlobalVisibleRect(it))
            val toolbar = Rect()
            field<View>("mActionbar").getGlobalVisibleRect(toolbar)
            it.top = maxOf(it.top, toolbar.bottom)
        } }
        val offsetBefore = main { content().recyclerView.computeVerticalScrollOffset() }
        val start = rect.top + rect.height() * if (down) .18f else .9f
        val end = rect.top + rect.height() * if (down) .9f else .18f
        val x = rect.left + rect.width() * .35f
        val time = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, action != MotionEvent.ACTION_MOVE))
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, start)
        repeat(6) { index ->
            SystemClock.sleep(8)
            touch(MotionEvent.ACTION_MOVE, start + (end - start) * (index + 1) / 6)
        }
        touch(MotionEvent.ACTION_UP, end)
        gestures.put(main { JSONObject().put("stage", stage).put("start", time)
            .put("durationMs", SystemClock.uptimeMillis() - time).put("distance", end - start)
            .put("scrollState", content().recyclerView.scrollState)
            .put("scrollOffsetDelta", content().recyclerView.computeVerticalScrollOffset() - offsetBefore)
            .put("ime", ViewCompat.getRootWindowInsets(content().recyclerView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true) })
        SystemClock.sleep(40)
    }

    private fun watch(name: String) { stage = name; main {
        observer = content().recyclerView.viewTreeObserver
        observer!!.addOnPreDrawListener(listener)
    } }
    private fun unwatch() = main { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener); observer = null }
    private fun sample() {
        val draw = ++drawSequence
        val regions = mutableListOf<DetailGifWindowCapture.Region>()
        for (position in 0 until sampledMediaCount) {
            val holder = holder(position) ?: continue
            val image = holder.ivImage ?: continue
            val rect = Rect()
            if (!image.getGlobalVisibleRect(rect) || image.width <= 0 || image.height <= 0) continue
            sampleBitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(sampleBitmap)
            canvas.scale(24f / image.width, 24f / image.height)
            image.draw(canvas)
            sampleBitmap.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            val gif = image.drawable as? GifDrawable
            if (windowCapture != null) {
                val location = IntArray(2).also(image::getLocationOnScreen)
                regions.add(DetailGifWindowCapture.Region(position,
                    Rect(location[0], location[1], location[0] + image.width, location[1] + image.height),
                    gif?.frameIndex ?: -2, System.identityHashCode(image.drawable), gif?.isRunning == true,
                    image.drawable != null && image.getTag(R.id.tag_detail_attachment_image_load_key) != null))
            }
            if (gif != null && gif.frameIndex >= 0) {
                val key = stage to position
                val frame = System.identityHashCode(gif) to gif.frameIndex
                val previous = lastFrames.put(key, frame)
                if (previous != null && previous.first == frame.first && previous.second > frame.second) {
                    loopWraps[key] = (loopWraps[key] ?: 0) + 1
                }
            }
            val controller = field<DetailAttachmentPlaybackController>("mAttachmentPlaybackController")
            samples.put(JSONObject().put("t", SystemClock.uptimeMillis()).put("stage", stage)
                .put("draw", draw)
                .put("pos", position).put("type", image.drawable?.javaClass?.simpleName ?: "null")
                .put("drawable", System.identityHashCode(image.drawable)).put("frame", gif?.frameIndex ?: -2)
                .put("running", gif?.isRunning ?: false).put("visibleH", rect.height())
                .put("mapped", gif?.buffer?.isDirect ?: false)
                .put("alpha", image.alpha * holder.itemView.alpha)
                .put("shouldPlay", controller.shouldPlay(position))
                .put("rect", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                .put("height", image.height).put("request", image.getTag(R.id.tag_detail_attachment_image_request_key))
                .put("load", image.getTag(R.id.tag_detail_attachment_image_load_key))
                .put("opaque", pixels.count { Color.alpha(it) > 240 })
                .put("black", pixels.count { Color.red(it) + Color.green(it) + Color.blue(it) < 30 })
                .put("yellow", pixels.count { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) < 70 })
                .put("hash", pixels.contentHashCode()))
        }
        windowCapture?.let { capture ->
            val clip = Rect()
            content().recyclerView.getGlobalVisibleRect(clip)
            val toolbar = Rect()
            field<View>("mActionbar").getGlobalVisibleRect(toolbar)
            clip.top = maxOf(clip.top, toolbar.bottom)
            capture.request(stage, regions, clip, content().recyclerView.computeVerticalScrollOffset())
        }
    }
    private fun fixture(media: List<String>): Long {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        db.insertOrThrow(Def.Database.TABLE_THINGS, null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", Color.rgb(45, 112, 136)); put("title", "GIF 闪烁隔离回归")
            put("content", if (args.getString("complex") == "true")
                (1..80).joinToString("\n") { "第 $it 段：大 GIF、通栏媒体与长正文的快速滚动回归。" }
                else "默认循环、视口边界及回收后重载。")
            put("attachment", AttachmentHelper.toAttachmentStr(media, emptyList()))
            put("location", id); put("create_time", System.currentTimeMillis()); put("update_time", System.currentTimeMillis()); put("finish_time", 0L)
        })
        return id
    }
    private fun open(id: Long) {
        activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext, "gif-flicker", id, -1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        main { DetailActivity::class.java.getDeclaredField("dontSaveAfterOnPause").apply { isAccessible = true }.setBoolean(activity, true) }
        waitForIdleSync()
        if (args.getString("sequential") == "true" || args.getString("boundary") == "true") {
            val deadline = SystemClock.uptimeMillis() + 45000
            while (!main { activity.hasWindowFocus() && field<ImageAttachmentAdapter?>("mImageAttachmentAdapter") != null }) {
                check(SystemClock.uptimeMillis() < deadline) { "详情窗口未就绪：$stage" }
                val root = uiAutomation.rootInActiveWindow
                if (root?.packageName == "android" &&
                    root.findAccessibilityNodeInfosByText("Android 应用兼容性").isNotEmpty()) {
                    root.findAccessibilityNodeInfosByViewId("android:id/button2")
                        .firstOrNull { it.text?.toString() == "确定" }
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                } else if (root?.packageName?.contains("permissioncontroller") == true) {
                    listOf("permission_allow_all_button", "permission_allow_button").firstNotNullOfOrNull { id ->
                        root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id").firstOrNull()
                    }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                SystemClock.sleep(100)
            }
        }
        await { field<ImageAttachmentAdapter?>("mImageAttachmentAdapter") != null }
    }
    private fun close() { main { activity.finish() }; waitForIdleSync() }
    private fun show(position: Int) {
        main { (content().recyclerView.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(content().globalPosition(adapter(), position), 0) }
        await { holder(position)?.itemView?.width?.let { it > 0 } == true }
    }
    private fun adapter() = field<ImageAttachmentAdapter>("mImageAttachmentAdapter")
    private fun content() = field<DetailContentLayout>("mDetailContent")
    private fun holder(position: Int) = content().holder(adapter(), position) as? ImageAttachmentAdapter.ImageViewHolder
    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: return
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 45000
        while (!main(condition)) { check(SystemClock.uptimeMillis() < deadline) { "等待超时：$stage" }; SystemClock.sleep(20) }
    }
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = DetailActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
}
