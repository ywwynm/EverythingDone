package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.widget.Toolbar
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance as Appearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.views.KnockoutTextBadge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 仅 debug：真实详情列表、静态/播放配色及耗时；已有记事只读并校验整行内容。 */
class DetailMediaOverlayProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private var activity: DetailActivity? = null
    private val created = mutableListOf<Long>()
    private val samples = JSONArray()
    private var failures = 0
    private val existingBefore = linkedMapOf<Long, String>()
    private val ids = intArrayOf(R.id.tv_badge_hdr, R.id.tv_badge_gif, R.id.iv_badge_live,
        R.id.iv_badge_spatial, R.id.iv_edit_image_attachment_appearance,
        R.id.iv_delete_image_attachment, R.id.iv_video_signal)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val run = args.getString("run_id") ?: "overlay"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "detail-overlay/$run").apply { mkdirs() }
        val result = JSONObject()
        try {
            // 探针创建临时数据前选取已有记事；不输出标题、正文或附件路径。
            val existing = existingIds()
            existing.forEach { existingBefore[it] = fingerprint(it) }
            fixtures()
            if (args.getString("existing", "true") == "true") {
                for ((index, id) in existing.withIndex()) {
                    open(id)
                    val adapter = adapter()
                    for (position in 0 until minOf(adapter.itemCount, 4)) {
                        val holder = show(position)
                        record("existing-$id-$position", snapshot(holder), true)
                    }
                    close()
                    // 立即重开，验证正常缓存复用；跨许多大图后的 LRU 淘汰不属于失败。
                    if (index < 2) {
                        open(id)
                        val holder = show(0)
                        record("existing-$id-warm", snapshot(holder), holder.overlayColors.cacheHitCount > 0)
                        close()
                    }
                }
            }
        } catch (error: Throwable) {
            failures++
            result.put("error", error.stackTraceToString())
        } finally {
            close()
            for ((id, before) in existingBefore) {
                record("existing-$id-unchanged", JSONObject(), fingerprint(id) == before)
            }
            val db = DBHelper(targetContext).writableDatabase
            var removed = 0
            created.forEach { id -> removed += db.delete(Def.Database.TABLE_THINGS,
                "id=? AND create_time=?", arrayOf(id.toString(), FIXTURE_TIME.toString())) }
            result.put("failures", failures).put("samples", samples)
                .put("existing_notes", existingBefore.size).put("fixture_rows_removed", removed)
                .put("orientation", targetContext.resources.configuration.orientation)
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures == 0) 0 else 1, Bundle().apply {
                putString("result", "failures=$failures samples=${samples.length()} path=$output/result.json")
            })
        }
    }

    private fun fixtures() {
        val white = image("white") { _, _ -> Color.WHITE }
        val black = image("black") { _, _ -> Color.BLACK }
        val transparent = image("transparent") { _, _ -> Color.TRANSPARENT }
        val split = image("split") { x, _ -> if (x < 700) Color.WHITE else Color.BLACK }
        val checker = image("checker") { x, y -> if ((x / 20 + y / 20) % 2 == 0) Color.BLACK else Color.WHITE }
        for ((name, path, dark) in listOf(Triple("white", white, true), Triple("black", black, false),
            Triple("transparent", transparent, false))) {
            val id = fixture(listOf(path))
            open(id)
            val holder = show(0)
            forceBadges(holder)
            record("$name-colors", snapshot(holder), colors(holder).values.all { it == dark })
            screenshot("$name")
            val applies = holder.overlayColors.applyCount
            main { repeat(20) { adapter().refreshPlayback(0) } }
            record("$name-rebind-reuses", snapshot(holder), holder.overlayColors.applyCount == applies)
            close()
            open(id)
            val warm = show(0)
            record("$name-cache", snapshot(warm), warm.overlayColors.cacheHitCount > 0)
            close()
        }

        val splitId = fixture(listOf(split))
        open(splitId)
        var holder = show(0)
        forceBadges(holder)
        val splitColors = colors(holder)
        record("split-independent", snapshot(holder), splitColors[R.id.tv_badge_hdr] == true &&
            splitColors[R.id.iv_delete_image_attachment] == false)
        screenshot("split")
        for ((center, expected) in listOf(.25 to true, .75 to false, .25 to true)) {
            val previous = holder.overlayColors.appliedKey
            val source = Appearance.SourceAppearance(fullSpanEnabled = true)
                .withPresentation("fullSpan", Appearance.MediaPresentationAppearance(
                    targetAspectRatio = if (center == .25) 1.0 else 2.0,
                    crop = Appearance.DetailMediaCrop(centerX = center, scale = 3.0)))
            val appearance = Appearance.default().withSource(split, source)
            main {
                DetailActivity::class.java.getDeclaredMethod("applyDetailAttachmentMediaAppearance", Appearance::class.java)
                    .apply { isAccessible = true }.invoke(activity, appearance)
            }
            holder = show(0, previous)
            forceBadges(holder)
            record("crop-$center-${samples.length()}", snapshot(holder), colors(holder).values.all { it == expected })
        }
        screenshot("crop-left")
        for ((action, expected) in listOf(R.id.act_undo to false, R.id.act_redo to true)) {
            val previous = holder.overlayColors.appliedKey
            // Activity 已执行动作后仍返回 super 的结果；以实际外观更新判断，不断言菜单返回值。
            main { field<Toolbar>("mActionbar").menu.performIdentifierAction(action, 0) }
            holder = show(0, previous)
            forceBadges(holder)
            record(if (expected) "crop-redo" else "crop-undo", snapshot(holder),
                colors(holder).values.all { it == expected })
        }
        close()

        // 多媒体滚动及复用：不同内容必须恢复自己的颜色。
        val multi = fixture(listOf(white, black, checker, white, black, split, white, black))
        open(multi)
        for (position in listOf(0, 1, 4, 7, 0)) {
            holder = show(position)
            val expected = position == 0
            record("multi-$position-${samples.length()}", snapshot(holder),
                colors(holder)[R.id.iv_delete_image_attachment] == expected)
        }
        main {
            val reversed = adapter().getItems()!!.reversed()
            adapter().setItems(reversed)
            adapter().notifyDataSetChanged()
        }
        holder = show(0)
        record("reorder-colors", snapshot(holder), colors(holder)[R.id.iv_delete_image_attachment] == false)
        close()

        for ((name, type) in listOf("animated.gif" to "0", "video.mp4" to "1")) {
            val media = File(output, name)
            targetContext.assets.open("detail-regression/$name").use { input -> media.outputStream().use(input::copyTo) }
            val id = fixture(listOf(type + media.absolutePath))
            open(id)
            holder = show(0)
            // 让异步标记和派生动画完成初次布局，随后检查播放期没有持续配色。
            SystemClock.sleep(1200)
            val before = main { colors(holder) to holder.overlayColors.applyCount }
            SystemClock.sleep(1200)
            record("$name-playback-stable", snapshot(holder), main {
                colors(holder) == before.first && holder.overlayColors.applyCount == before.second })
            if (type == "1") {
                val retriever = MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(media.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                } finally { retriever.release() }
                val previous = holder.overlayColors.appliedKey
                val frameMs = (duration * .75).toLong().coerceAtLeast(1L)
                val appearance = Appearance.default().withSource(type + media.absolutePath,
                    Appearance.SourceAppearance(videoFrameMs = frameMs))
                main {
                    DetailActivity::class.java.getDeclaredMethod("applyDetailAttachmentMediaAppearance", Appearance::class.java)
                        .apply { isAccessible = true }.invoke(activity, appearance)
                }
                holder = show(0, previous)
                record("video-cover-frame", snapshot(holder), holder.overlayColors.appliedKey!!.contains("|$frameMs|"))
            }
            close()
            open(id)
            holder = show(0)
            record("$name-playback-cache", snapshot(holder), holder.overlayColors.cacheHitCount > 0)
            close()
        }
    }

    private fun existingIds(): List<Long> {
        val candidates = mutableListOf<Pair<Long, Int>>()
        DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT id,title,attachment FROM things WHERE attachment IS NOT NULL AND attachment<>'' AND type<>-1 ORDER BY id DESC",
            null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id == App.getDoingThingId() || cursor.getString(1).orEmpty().startsWith(Thing.PRIVATE_THING_PREFIX)) continue
                val attachments = cursor.getString(2).orEmpty()
                if (!attachments.contains(AttachmentHelper.SIGNAL)) continue
                val media = AttachmentHelper.toAttachmentItems(attachments).first
                if (media.isNotEmpty()) candidates += id to media.size
            }
        }
        return (candidates.take(4) + candidates.sortedByDescending { it.second }.take(4))
            .map { it.first }.distinct().take(8)
    }

    private fun fingerprint(id: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(id.toString())).use { c ->
            check(c.moveToFirst())
            for (i in 0 until c.columnCount) {
                val value = if (c.isNull(i)) "<null>" else c.getString(i)
                digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun image(name: String, pixel: (Int, Int) -> Int): String {
        val bitmap = Bitmap.createBitmap(1400, 700, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1400 * 700) { pixel(it % 1400, it / 1400) }
        bitmap.setPixels(pixels, 0, 1400, 0, 0, 1400, 700)
        val file = File(File(output, "media").apply { mkdirs() }, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return "0${file.absolutePath}"
    }

    private fun fixture(media: List<String>): Long {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        db.insertOrThrow(Def.Database.TABLE_THINGS, null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", Color.rgb(45, 112, 136)); put("title", "附件配色验证 · 临时记事")
            put("content", "本条用于配色与速度验证，测试结束自动清理。")
            put("attachment", AttachmentHelper.toAttachmentStr(media, emptyList()))
            put("location", id); put("create_time", FIXTURE_TIME); put("update_time", FIXTURE_TIME); put("finish_time", 0L)
        })
        created += id
        return id
    }

    private fun open(id: Long) {
        activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
            "detail-overlay-probe", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        waitForIdleSync()
    }

    private fun close() {
        if (activity != null) { main { activity?.finish() }; waitForIdleSync(); activity = null }
    }

    private fun adapter() = field<ImageAttachmentAdapter>("mImageAttachmentAdapter")
    private fun content() = field<DetailContentLayout>("mDetailContent")

    private fun show(position: Int, previousKey: String? = null): ImageAttachmentAdapter.ImageViewHolder {
        main { (content().recyclerView.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(content().globalPosition(adapter(), position), 12) }
        var holder: ImageAttachmentAdapter.ImageViewHolder? = null
        await {
            holder = content().holder(adapter(), position) as? ImageAttachmentAdapter.ImageViewHolder
            holder?.let { it.itemView.width > 0 && !it.itemView.isLayoutRequested &&
                it.pbLoading?.visibility != View.VISIBLE && !it.overlayColors.pending && it.overlayColors.appliedKey != null &&
                (previousKey == null || it.overlayColors.appliedKey != previousKey) } == true
        }
        return holder!!
    }

    private fun forceBadges(holder: ImageAttachmentAdapter.ImageViewHolder) {
        // 在合成白/黑测试图上覆盖所有现有图形；不改变生产检测或已有记事。
        main {
            holder.llMediaBadges!!.visibility = View.VISIBLE
            ids.take(4).forEach { holder.itemView.findViewById<View>(it).visibility = View.VISIBLE }
            holder.overlayColors.schedule()
        }
        waitForIdleSync()
        await { holder.tvBadgeHdr!!.width > 0 && !holder.itemView.isLayoutRequested }
        main { holder.overlayColors.schedule() }
        await { !holder.overlayColors.pending }
    }

    private fun colors(holder: ImageAttachmentAdapter.ImageViewHolder): Map<Int, Boolean> = main { ids.asSequence().mapNotNull { id ->
        val view = holder.itemView.findViewById<View>(id)
        if (view.visibility != View.VISIBLE) null else {
            val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            val ink = pixels.filter { Color.alpha(it) >= 64 }
            check(ink.isNotEmpty()) { "图形没有可见像素：$id" }
            id to (ink.sumOf { Color.red(it).toLong() }.toDouble() / ink.size < 128)
        }
    }.toMap() }

    private fun snapshot(holder: ImageAttachmentAdapter.ImageViewHolder): JSONObject = main {
        val state = holder.overlayColors
        JSONObject().put("colors", JSONObject().also { json -> colors(holder).forEach { (id, dark) ->
            json.put(targetContext.resources.getResourceEntryName(id), if (dark) "black" else "white") } })
            .put("apply_us", state.lastApplyMicros).put("capture_us", state.lastCaptureMicros)
            .put("worker_us", state.lastWorkerMicros)
            .put("ready_ms", state.readyLatencyMs).put("after_media_ms", state.afterMediaReadyMs)
            .put("applies", state.applyCount).put("static_loads", state.staticLoadCount)
            .put("cache_hits", state.cacheHitCount)
    }

    private fun record(name: String, data: JSONObject, passed: Boolean) {
        samples.put(data.put("name", name).put("passed", passed))
        if (!passed) failures++
    }

    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("无法取得截图")
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 16000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "等待配色超时" }
            SystemClock.sleep(16)
        }
        waitForIdleSync()
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

    companion object { private const val FIXTURE_TIME = 1791676800129L }
}
