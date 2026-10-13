package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.model.Thing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 回车后不调用定位行的辅助方法，直接检查真实键盘上方的光标可见性。 */
internal class DetailChecklistFocusChecks(private val probe: Instrumentation, private val output: File) {
    private val context get() = probe.targetContext
    private val fixtureTime = 1791849600000L
    private val records = JSONArray()
    private val ids = mutableListOf<Long>()
    private val mediaFiles = mutableListOf<File>()
    private var activity: DetailActivity? = null

    fun run(complex: Boolean = false, arrows: Boolean = false) {
        val failures = mutableListOf<String>()
        try {
            val cases = if (arrows) listOf(
                Case("arrows-middle-end", 3, nested = true, completeFirst = true),
                Case("arrows-middle-split", 3, split = true, nested = true, completeFirst = true),
                Case("arrows-checked-split", 4, split = true, nested = true, completeFirst = true),
                Case("arrows-repeat", 3, enters = 4, nested = true, completeFirst = true),
                Case("arrows-first-checked-end", 1, nested = true, completeFirst = true),
                Case("arrows-third-checked-end", 2, nested = true, completeFirst = true),
                Case("arrows-mixed", 3, images = 3, audios = 3, nested = true, completeFirst = true)
            ) else if (complex) listOf(
                Case("image-first", 0, images = 1), Case("image-grid-first", 0, images = 3),
                Case("audio-last", 47, audios = 3), Case("audio-short-last", 2, count = 3, audios = 3),
                Case("mixed-short-first", 0, count = 3, images = 3, audios = 3),
                Case("mixed-short-last-split", 2, split = true, count = 3, images = 1, audios = 3),
                Case("mixed-wrapped-split", 13, split = true, low = true, images = 3, audios = 3),
                Case("mixed-repeat-last", 47, enters = 4, images = 3, audios = 3)
            ) else listOf(
                Case("top-end", 0), Case("middle-end", 12), Case("lower-end", 12, low = true),
                Case("middle-split", 12, split = true), Case("wrapped-split", 13, split = true),
                Case("wrapped-end", 13), Case("last-end", 47), Case("repeated-enter", 12, enters = 4)
            )
            for (case in cases) {
                val (name, position, split, low, enters) = case
                val id = createFixture(case)
                val screen = probe.startActivitySync(DetailActivity.getOpenIntentForUpdate(
                    context, "checklist-focus-regression", id, -1
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                activity = screen
                probe.waitForIdleSync()
                val content = field<DetailContentLayout>(screen, "mDetailContent")
                val adapter = field<CheckListAdapter>(screen, "mCheckListAdapter")
                await { content.recyclerView.height > 0 }
                if (case.completeFirst) {
                    main { content.recyclerView.scrollToPosition(content.globalPosition(adapter, 1)) }
                    await { content.holder(adapter, 1)?.itemView?.isLayoutRequested == false }
                    val checkbox = main { (content.holder(adapter, 1) as CheckListAdapter.EditTextHolder).ivState!! }
                    tap(checkbox)
                    await { CheckListHelper.isFinished(adapter.getItems()!![1]) }
                    probe.waitForIdleSync()
                }
                val sections = main {
                    val children = content.adapter.adapters
                    val images = children.filterIsInstance<ImageAttachmentAdapter>().sumOf { it.itemCount }
                    val audios = children.filterIsInstance<AudioAttachmentAdapter>().sumOf { it.itemCount }
                    check(images == case.images && audios == case.audios) { "附件区域数量不符：$images/$audios" }
                    check(children.indexOf(adapter) > children.indexOfFirst { it is ImageAttachmentAdapter })
                    if (audios > 0) check(children.indexOf(adapter) < children.indexOfFirst { it is AudioAttachmentAdapter })
                    JSONObject().put("images", images).put("audios", audios).put("items", case.count)
                }
                main {
                    if (position == 0 && case.images > 0) {
                        // 首项只在必要时滚入可见区域，保留上方图片参与键盘避让时的布局。
                        val row = content.holder(adapter, position)?.itemView
                        val visible = Rect()
                        if (row == null || !row.getGlobalVisibleRect(visible) || visible.height() < row.height) {
                            content.recyclerView.scrollToPosition(content.globalPosition(adapter, position))
                        }
                    } else {
                        content.layoutManager.scrollToPositionWithOffset(content.globalPosition(adapter, position),
                            (content.recyclerView.height * if (low) .72f else .15f).toInt())
                    }
                }
                await { content.holder(adapter, position)?.itemView?.isLayoutRequested == false }
                screenshot("before-$name")
                val tapped = main { (content.holder(adapter, position) as CheckListAdapter.EditTextHolder).et!! }
                tap(tapped)
                await { tapped.hasFocus() && ViewCompat.getRootWindowInsets(content.recyclerView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                main { tapped.setSelection(if (split) tapped.length() / 2 else tapped.length()) }
                SystemClock.sleep(500)
                val before = state(screen, content, adapter)
                val sizeBefore = main { adapter.getItems()!!.size }
                val arrowFrames = JSONArray()
                val arrowObserver = ViewTreeObserver.OnPreDrawListener {
                    val list = content.recyclerView
                    for (child in 0 until list.childCount) {
                        val holder = list.getChildViewHolder(list.getChildAt(child)) as? CheckListAdapter.EditTextHolder ?: continue
                        val index = holder.bindingAdapterPosition
                        val focus = holder.et!!.hasFocus()
                        val indentVisible = holder.ivIndent!!.visibility == View.VISIBLE
                        val outdentVisible = holder.ivOutdent!!.visibility == View.VISIBLE
                        arrowFrames.put(JSONObject().put("time", SystemClock.uptimeMillis()).put("position", index)
                            .put("focused", focus).put("indent_visible", indentVisible).put("outdent_visible", outdentVisible)
                            .put("incorrect", !focus && (indentVisible || outdentVisible ||
                                holder.ivIndent.isClickable || holder.ivOutdent.isClickable)))
                    }
                    true
                }
                if (case.nested) main {
                    content.recyclerView.viewTreeObserver.addOnPreDrawListener(arrowObserver)
                }
                repeat(enters) { index ->
                    if (index > 0) SystemClock.sleep(100)
                    probe.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                }
                val samples = JSONArray()
                val start = SystemClock.uptimeMillis()
                for (time in listOf(0, 64, 160, 320, 640, 1200)) {
                    val delay = start + time - SystemClock.uptimeMillis()
                    if (delay > 0) SystemClock.sleep(delay)
                    samples.put(state(screen, content, adapter).put("elapsed_ms", SystemClock.uptimeMillis() - start))
                }
                val after = samples.getJSONObject(samples.length() - 1)
                val insertion = main { (screen.currentFocus as? EditText)?.let { it.text.toString() to it.selectionEnd } }
                val continued = "continued"
                probe.sendStringSync(continued)
                SystemClock.sleep(400)
                val typed = state(screen, content, adapter)
                if (case.nested) main { content.recyclerView.viewTreeObserver.removeOnPreDrawListener(arrowObserver) }
                val arrowsCorrect = (!case.nested || arrowFrames.length() > 0) &&
                    (0 until arrowFrames.length()).none { arrowFrames.getJSONObject(it).getBoolean("incorrect") }
                val textCorrect = main {
                    val editor = screen.currentFocus as? EditText
                    insertion != null && editor != null && insertion.second >= 0 &&
                        editor.text.toString() == StringBuilder(insertion.first).insert(insertion.second, continued).toString()
                }
                val pass = before.optBoolean("cursor_visible") && after.optBoolean("cursor_visible") &&
                    after.optBoolean("ime_visible") && after.optInt("local_position", -1) == position + enters &&
                    typed.optBoolean("cursor_visible") && typed.optBoolean("ime_visible") && textCorrect &&
                    typed.optInt("local_position", -1) == position + enters &&
                    arrowsCorrect && typed.optBoolean("controls_match") &&
                    main { adapter.getItems()!!.size == sizeBefore + enters }
                records.put(JSONObject().put("case", name).put("sections", sections).put("before", before)
                    .put("samples", samples).put("after_typing", typed).put("typing_correct", textCorrect)
                    .put("arrow_frames", arrowFrames).put("passed", pass))
                if (!pass) failures += name
                screenshot("enter-$name")
                main { screen.finish() }; probe.waitForIdleSync(); activity = null
            }
        } finally {
            main { activity?.finish() }; probe.waitForIdleSync()
            val db = DBHelper(context).writableDatabase
            ids.forEach { db.delete("things", "id=? AND create_time=?", arrayOf(it.toString(), fixtureTime.toString())) }
            mediaFiles.forEach { it.delete() }
            File(output, "enter-visibility.json").writeText(JSONObject().put("records", records)
                .put("fixture_ids", JSONArray(ids)).toString(2))
        }
        check(failures.isEmpty()) { "回车后光标、焦点或层级按钮状态错误：${failures.joinToString()}" }
    }

    private fun state(screen: DetailActivity, content: DetailContentLayout, adapter: CheckListAdapter): JSONObject = main {
        val list = content.recyclerView
        val viewport = Rect().also { list.getGlobalVisibleRect(it) }
        val insets = ViewCompat.getRootWindowInsets(list)
        val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val decor = screen.window.decorView
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        if (imeVisible) viewport.bottom = minOf(viewport.bottom, origin[1] + decor.height -
            (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0))
        val editor = screen.currentFocus as? EditText
        val holder = editor?.let(list::findContainingViewHolder)
        val record = JSONObject().put("ime_visible", imeVisible).put("viewport", viewport.flattenToString())
            .put("first_position", content.layoutManager.findFirstVisibleItemPosition())
            .put("scroll_state", list.scrollState).put("scroll_offset", list.computeVerticalScrollOffset())
            .put("local_position", if (holder?.bindingAdapter === adapter) holder.bindingAdapterPosition else -1)
            .put("cursor_visible", false)
        if (editor != null && editor.layout != null) {
            val layout = editor.layout
            val line = layout.getLineForOffset(editor.selectionEnd.coerceIn(0, editor.length()))
            val location = IntArray(2).also(editor::getLocationOnScreen)
            val top = location[1] + editor.totalPaddingTop - editor.scrollY + layout.getLineTop(line)
            val bottom = location[1] + editor.totalPaddingTop - editor.scrollY + layout.getLineBottom(line)
            val visible = Rect()
            record.put("cursor_top", top).put("cursor_bottom", bottom).put("selection", editor.selectionEnd)
                .put("editor_height", editor.height).put("editor_y", location[1])
                .put("cursor_visible", editor.getGlobalVisibleRect(visible) && top >= viewport.top && bottom <= viewport.bottom)
        }
        if (holder is CheckListAdapter.EditTextHolder && holder.bindingAdapter === adapter && holder.bindingAdapterPosition >= 0) {
            val position = holder.bindingAdapterPosition
            val indent = CheckListHelper.canIndent(adapter.getItems(), position)
            val outdent = CheckListHelper.canOutdent(adapter.getItems(), position)
            record.put("controls_match", (holder.ivIndent!!.visibility == View.VISIBLE) == indent &&
                holder.ivIndent.isClickable == indent && (holder.ivOutdent!!.visibility == View.VISIBLE) == outdent &&
                holder.ivOutdent.isClickable == outdent)
        }
        record
    }

    private fun createFixture(case: Case): Long {
        val images = (0 until case.images).map { index ->
            val file = File(output, "${case.name}-image-$index.jpg")
            mediaFiles += file
            val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(listOf(0xffef8354.toInt(), 0xff4f5d75.toInt(), 0xff42a599.toInt())[index])
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 96f }
            canvas.drawText("IMAGE $index", 110f, 400f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            "0${file.absolutePath}"
        }
        val audios = (0 until case.audios).map { index ->
            val file = File(output, "${case.name}-audio-$index.wav")
            mediaFiles += file
            val samples = 16000
            val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            bytes.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(samples * 2)
            file.writeBytes(bytes.array())
            "2${file.absolutePath}"
        }
        val db = DBHelper(context).writableDatabase
        db.beginTransaction()
        try {
            val id = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM things", null).use { it.moveToFirst(); it.getLong(0) }
            val items = (0 until case.count).map { index -> CheckListHelper.makeItem(
                if (case.nested && (index in listOf(4, 5) || !case.completeFirst && index in listOf(1, 2))) '1' else '0',
                if (case.nested && index in listOf(2, 5)) 3 else if (case.nested && index in 1..6) 2 else 1,
                "光标回归第 $index 项" + if (index == 13) "，较长文字用于检查自动折行后的光标位置".repeat(5) else "，按回车添加下一项") }
            db.insertOrThrow("things", null, ContentValues().apply {
                put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
                put("title", "回车光标回归 · 临时数据"); put("content", CheckListHelper.toCheckListStr(items))
                put("color", Color.rgb(35, 105, 95)); put("attachment", AttachmentHelper.toAttachmentStr(images, audios)); put("location", id)
                put("create_time", fixtureTime); put("update_time", fixtureTime); put("finish_time", 0)
            })
            ids += id; db.setTransactionSuccessful(); return id
        } finally { db.endTransaction() }
    }

    private fun screenshot(name: String) {
        probe.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun tap(view: View) {
        val box = main { Rect().also { check(view.getGlobalVisibleRect(it)) } }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                box.exactCenterX(), box.exactCenterY(), 0)
            probe.sendPointerSync(event); event.recycle()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 6000
        while (!main(condition)) { check(SystemClock.uptimeMillis() < until) { "界面未进入预期状态" }; SystemClock.sleep(30) }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        probe.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(screen: DetailActivity, name: String): T = DetailActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(screen) as T

    private data class Case(val name: String, val position: Int, val split: Boolean = false,
        val low: Boolean = false, val enters: Int = 1, val count: Int = 48, val images: Int = 0, val audios: Int = 0,
        val nested: Boolean = false, val completeFirst: Boolean = false)
}
