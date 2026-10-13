package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** 在隔离包的真实 RecyclerView 上核对完整绑定、局部绑定及滚动缓存的子控件外观。 */
internal object SelectionCardAppearanceAudit {
    const val DOING_ID = 8_000_017L

    fun decorate(context: Context, thing: Thing, index: Int) {
        check(context.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val dir = File(context.filesDir, "selection-appearance").apply { mkdirs() }
        val kind = index % 8
        val extension = when (kind) { 2, 5 -> "mp4"; 3, 6 -> "gif"; else -> "png" }
        val media = File(dir, "${thing.id}.$extension")
        if (extension == "png") {
            val bitmap = Bitmap.createBitmap(192, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(28, 95, 185))
            canvas.drawRect(0f, 0f, 96f, 128f, Paint().apply { color = Color.rgb(245, 145, 30) })
            media.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } else context.assets.open("selection-appearance/media.$extension").use { input ->
            media.outputStream().use { input.copyTo(it) }
        }
        val audio = File(dir, "${thing.id}.wav")
        context.assets.open("selection-appearance/audio.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
        thing.attachment = AttachmentHelper.SIGNAL + (if (extension == "mp4") AttachmentHelper.VIDEO else AttachmentHelper.IMAGE) +
            media.absolutePath + AttachmentHelper.SIGNAL + AttachmentHelper.AUDIO + audio.absolutePath
        thing.thingCardImagePlacement = when (kind) {
            2 -> Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM
            3 -> Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
            4 -> Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
            else -> Thing.THING_CARD_IMAGE_PLACEMENT_TOP
        }
        if (kind in 5..6) thing.thingCardAppearance = thing.thingCardAppearance.copy(mediaBackgroundEnabled = true)
        if (kind == 7) {
            thing.thingCardAppearance = thing.thingCardAppearance.copy(mediaSourceKey = ThingCardAppearance.MEDIA_SOURCE_NONE)
            thing.content = CheckListHelper.SIGNAL + "01待办一" + CheckListHelper.SIGNAL + "02子任务" +
                CheckListHelper.SIGNAL + "11已完成项"
        }
        if (kind == 0) thing.title = Thing.PRIVATE_THING_PREFIX + thing.title
        if (kind == 4) thing.location = -thing.location
        // 两个保留项覆盖真实提醒／习惯卡片的子控件；只写隔离数据库，不注册系统闹钟。
        if (index == 30 || index == 31) {
            val now = System.currentTimeMillis()
            com.ywwynm.everythingdone.database.DBHelper(context).use { helper ->
                val db = helper.writableDatabase
                if (index == 30) {
                    thing.type = Thing.REMINDER
                    db.execSQL("INSERT OR REPLACE INTO reminders (id,notify_time,state,notify_millis,create_time,update_time) VALUES (?,?,?,?,?,?)",
                        arrayOf(thing.id, now + 86_400_000L, 0, 86_400_000L, now, now))
                } else {
                    thing.type = Thing.HABIT
                    db.execSQL("INSERT OR REPLACE INTO habits (id,type,reminded_times,detail,record,interval_info,create_time,first_time) VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf(thing.id, java.util.Calendar.DATE, 3, "8:0", "101", "", now - 3 * 86_400_000L, now - 3 * 86_400_000L))
                    db.execSQL("INSERT OR REPLACE INTO habit_reminders (id,habit_id,notify_time) VALUES (?,?,?)",
                        arrayOf(thing.id, thing.id, now + 86_400_000L))
                }
            }
        }
    }

    private val ids = intArrayOf(
        R.id.ll_thing_content, R.id.tv_thing_title, R.id.tv_thing_content,
        R.id.fl_thing_image, R.id.iv_thing_image, R.id.view_thing_image_cover,
        R.id.tv_thing_image_attachment_count, R.id.iv_thing_media_background,
        R.id.view_thing_media_background_mask, R.id.fl_thing_doing_cover,
        R.id.tv_thing_doing, R.id.iv_private_thing,
        R.id.ll_thing_audio_attachment, R.id.ll_thing_media_attachment_count,
        R.id.ll_thing_inline_media_attachment_count
    )

    fun snapshot(list: RecyclerView): JSONArray {
        val rows = JSONArray()
        for (index in 0 until list.childCount) {
            val view = list.getChildAt(index)
            val id = view.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
            if (id == Long.MIN_VALUE) continue
            val nodes = JSONObject()
            // 对标题、媒体、Doing 等记录可见性；其余静态子树一并记录，包含置顶、提醒、习惯和清单。
            fun collect(node: View) {
                if (node.id != View.NO_ID) {
                    val name = node.resources.getResourceEntryName(node.id)
                    val row = JSONObject().put("alpha", node.alpha).put("visibility", node.visibility)
                        .put("shown", node.isShown).put("clickable", node.isClickable)
                    if (node is ImageView && node.isShown &&
                        (node.id == R.id.iv_thing_image || node.id == R.id.iv_thing_media_background)) {
                        check(node.drawable != null) { "测试媒体没有解码：$id/$name" }
                        row.put("mediaDrawable", node.drawable!!.javaClass.simpleName)
                    }
                    if (node is ImageView && name == "iv_thing_sticky_ongoing") {
                        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                        val drawable = node.drawable
                        val saved = drawable?.bounds?.let { android.graphics.Rect(it) }
                        drawable?.setBounds(0, 0, 40, 40); drawable?.draw(Canvas(bmp))
                        if (saved != null) drawable.bounds = saved
                        val pixels = IntArray(1600); bmp.getPixels(pixels, 0, 40, 0, 0, 40, 40)
                        row.put("drawableHash", pixels.contentHashCode()); bmp.recycle()
                    }
                    nodes.put(name, row)
                }
                if (node is android.view.ViewGroup) for (i in 0 until node.childCount) collect(node.getChildAt(i))
            }
            // 仅检查当前卡片，不让文件夹内部缩略图同名 id 覆盖文件夹本身。
            if (id > 0) collect(view) else ids.forEach { key -> view.findViewById<View>(key)?.let { node ->
                nodes.put(node.resources.getResourceEntryName(key), JSONObject().put("alpha", node.alpha)
                    .put("visibility", node.visibility).put("shown", node.isShown))
            } }
            rows.put(JSONObject().put("id", id).put("view", System.identityHashCode(view)).put("nodes", nodes))
        }
        return rows
    }

    fun scan(list: RecyclerView, main: (() -> Unit) -> Unit, output: File, label: String,
             baseline: Map<Long, JSONObject>? = null): Map<Long, JSONObject> {
        val latest = linkedMapOf<Long, JSONObject>()
        val visits = JSONArray()
        val mismatches = JSONArray()
        var count = 0
        main { count = list.adapter!!.itemCount }
        // 从当前画面开始逐屏往返；直接跳到头尾会触发完整重绑，反而掩盖局部绑定遗漏。
        val directions = listOf(0) + List(count * 2) { 1 } + List(count * 2) { -1 }
        for ((position, direction) in directions.withIndex()) {
            var canScroll = direction == 0
            main {
                if (direction != 0) {
                    canScroll = list.canScrollVertically(direction)
                    if (canScroll) list.scrollBy(0, (list.height * .55f).toInt() * direction)
                }
            }
            if (!canScroll) continue
            val until = SystemClock.uptimeMillis() + 3500
            do {
                SystemClock.sleep(32)
                var ready = false
                main {
                    ready = !list.isLayoutRequested && !list.isComputingLayout &&
                        (0 until list.childCount).none { index ->
                            val card = list.getChildAt(index)
                            card.findViewById<View>(R.id.pb_thing_image_attachment)?.isShown == true ||
                                intArrayOf(R.id.iv_thing_image, R.id.iv_thing_media_background).any { key ->
                                    card.findViewById<ImageView>(key)?.let { it.isShown && it.drawable == null } == true
                                }
                        }
                }
                if (ready) break
            } while (SystemClock.uptimeMillis() < until)
            main {
                val rows = snapshot(list)
                visits.put(JSONObject().put("position", position).put("items", rows))
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val id = row.getLong("id")
                    latest[id] = row
                    val expected = baseline?.get(id)?.getJSONObject("nodes") ?: continue
                    val actual = row.getJSONObject("nodes")
                    for (key in expected.keys()) {
                        val a = actual.optJSONObject(key) ?: continue
                        val e = expected.getJSONObject(key)
                        if (key == "pb_thing_image_attachment" || key == "cv_thing") continue
                        val visibleDiff = (e.getBoolean("shown") || a.getBoolean("shown")) &&
                            a.getInt("visibility") != e.getInt("visibility")
                        val alphaDiff = e.getBoolean("shown") && a.getBoolean("shown") &&
                            abs(a.getDouble("alpha") - e.getDouble("alpha")) > .001
                        val drawableDiff = e.has("drawableHash") && e.getBoolean("shown") &&
                            a.optInt("drawableHash") != e.getInt("drawableHash")
                        val clickDiff = key == "ll_check_list_tv" && e.getBoolean("shown") && a.getBoolean("shown") &&
                            a.optBoolean("clickable") != e.optBoolean("clickable")
                        if (visibleDiff || alphaDiff || drawableDiff || clickDiff) mismatches.put(JSONObject()
                            .put("id", id).put("node", key).put("position", position)
                            .put("expected", e).put("actual", a))
                    }
                }
            }
        }
        File(output, "$label.json").writeText(JSONObject().put("visits", visits).put("mismatches", mismatches)
            .put("doingId", App.getDoingThingId()).toString())
        if (baseline != null) check(mismatches.length() == 0) {
            "滚动后子控件未恢复：${mismatches.length()} 处，首项 ${mismatches.opt(0)}"
        }
        return latest
    }
}
