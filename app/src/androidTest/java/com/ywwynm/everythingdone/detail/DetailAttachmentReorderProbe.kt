package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** 独立测试 APK 驱动已发布安装包；不替换动画器，不直接调用附件换位方法。 */
class DetailAttachmentReorderProbe : Instrumentation() {
    private var args = Bundle()
    private val stamp = System.currentTimeMillis()
    private val title = "附件换位动画实机验证 · 临时记事"
    private lateinit var output: File
    private lateinit var mediaDirectory: File
    private val ids = mutableListOf<Long>()
    private val checks = JSONArray()
    private val runs = JSONArray()
    private var activity: DetailActivity? = null
    private lateinit var content: DetailContentLayout
    private lateinit var images: ImageAttachmentAdapter
    private lateinit var checklist: CheckListAdapter
    private val list get() = content.recyclerView

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val runId = args.getString("run_id", "device").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        output = File(targetContext.getExternalFilesDir(null), "attachment-reorder/$runId").apply { mkdirs() }
        File(output, "clock.json").writeText(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("uptime_ms", SystemClock.uptimeMillis()).toString())
        mediaDirectory = File(targetContext.cacheDir, "attachment-reorder-$stamp").apply { mkdirs() }
        val baseline = rows()
        var error: String? = null
        try {
            val media = createMedia()
            for (fullSpan in listOf(false, true)) {
                val layout = if (fullSpan) "full-span" else "grid"
                val id = fixture(media, fullSpan)
                val screen = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "attachment-reorder-regression", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                activity = screen
                main { set(screen, "dontSaveAfterOnPause", true) }
                content = field(screen, "mDetailContent")
                images = field(screen, "mImageAttachmentAdapter")
                checklist = field(screen, "mCheckListAdapter")
                await("详情可交互") { screen.hasWindowFocus() && list.height > 0 && !list.isLayoutRequested }
                mediaReady()
                val originalContent = main { checklist.getItems()!!.toList() }
                for (phase in listOf("normal", "move-1", "exit-1", "move-2", "exit-2")) {
                    if (phase != "normal") toggleMode(phase.startsWith("move"), "$layout/$phase")
                    if (phase.startsWith("move")) checkChecklistDrag("$layout/$phase")
                    mediaReady()
                    val pairs = if (phase == "exit-2") args.getString("exit_pairs", "1").toInt().coerceIn(1, 4) else 1
                    repeat(pairs * 2) { index -> reorder("$layout/$phase/${if (index % 2 == 0) "image" else "video"}-${index / 2}") }
                    record("$layout/$phase/附件顺序恢复", main { images.getItems()!!.toList() == media })
                    record("$layout/$phase/清单内容保持", main { checklist.getItems()!!.toList() == originalContent })
                }
                main { screen.finish() }
                waitForIdleSync()
                activity = null
            }
        } catch (failure: Throwable) {
            error = failure.stackTraceToString()
        } finally {
            main { activity?.let { set(it, "dontSaveAfterOnPause", true); it.finish() } }
            waitForIdleSync()
            val db = DBHelper(targetContext).writableDatabase
            ids.forEach { db.delete("things", "id=? AND create_time=? AND title=?",
                arrayOf(it.toString(), stamp.toString(), title)) }
            mediaDirectory.listFiles()?.filter { it.extension == "mp4" }?.forEach {
                VideoCoverPreviewManager.deletePreviewsForVideo(targetContext, it.absolutePath)
            }
            // 只删除本次创建的专用目录，原附件目录不参与清理。
            check(mediaDirectory.parentFile == targetContext.cacheDir && mediaDirectory.name == "attachment-reorder-$stamp")
            mediaDirectory.deleteRecursively()
            record("原记事逐行散列一致", rows() == baseline)
            record("临时媒体已清理", !mediaDirectory.exists())
            record("临时记事已清理", ids.none { id -> rows().containsKey(id) })
            val failed = (0 until checks.length()).count { !checks.getJSONObject(it).getBoolean("passed") }
            val report = JSONObject().put("model", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("target_apk", targetContext.applicationInfo.sourceDir)
                .put("original_rows", baseline.size).put("checks", checks).put("runs", runs)
                .put("failures", failed).put("error", error ?: JSONObject.NULL)
            File(output, "checks.json").writeText(report.toString(2))
            finish(if (error == null && failed == 0) 0 else 1, Bundle().apply {
                putString("output", output.absolutePath); putInt("checks", checks.length())
                putInt("failures", failed); putString("error", error)
            })
        }
    }

    private fun reorder(name: String) {
        mediaReady()
        val before = main { images.getItems()!!.toList() }
        val selected = main { content.holder(images, 0)!!.itemView }
        val displaced = main { content.holder(images, 1)!!.itemView }
        val a = mediaTouchBounds(selected)
        val sourceBounds = bounds(selected)
        val targetBounds = bounds(displaced)
        // 手指在图片内的偏移不随目标改变，否则通栏图从较低触点起拖时会少移动一段。
        val dx = targetBounds.exactCenterX() - sourceBounds.exactCenterX()
        val dy = targetBounds.exactCenterY() - sourceBounds.exactCenterY()
        val endX = a.exactCenterX() + dx + sign(dx) * dp(12)
        val endY = a.exactCenterY() + dy + sign(dy) * dp(12)
        val frames = JSONArray()
        var phase = "press"
        val listener = ViewTreeObserver.OnPreDrawListener {
            frames.put(JSONObject().put("time", SystemClock.uptimeMillis()).put("phase", phase)
                .put("selected", pose(selected)).put("displaced", pose(displaced))
                .put("selected_position", list.getChildViewHolder(selected).bindingAdapterPosition)
                .put("displaced_position", list.getChildViewHolder(displaced).bindingAdapterPosition))
            true
        }
        val origin = main { pose(displaced) }
        main { list.viewTreeObserver.addOnPreDrawListener(listener) }
        val down = SystemClock.uptimeMillis()
        try {
            pointer(down, MotionEvent.ACTION_DOWN, a.exactCenterX(), a.exactCenterY())
            await("$name 长按选中附件") { field<RecyclerView.ViewHolder?>(field<ItemTouchHelper>(activity!!, "mChecklistTouchHelper"), "mSelected")?.itemView === selected }
            main { phase = "drag" }
            drag(down, a.exactCenterX(), a.exactCenterY(), endX, endY)
            pointer(down, MotionEvent.ACTION_UP, endX, endY)
            main { phase = "release" }
            await("$name 实际换位与动画结束") {
                images.getItems()!![1] == before[0] && list.itemAnimator?.isRunning != true &&
                    abs(selected.translationX) < 0.1 && abs(selected.translationY) < 0.1 &&
                    abs(displaced.translationX) < 0.1 && abs(displaced.translationY) < 0.1
            }
        } catch (failure: Throwable) {
            val state = main {
                list.viewTreeObserver.removeOnPreDrawListener(listener)
                val helper = field<ItemTouchHelper>(activity!!, "mChecklistTouchHelper")
                JSONObject().put("name", name).put("error", failure.stackTraceToString()).put("frames", frames)
                    .put("before", JSONArray(before)).put("after", JSONArray(images.getItems()!!))
                    .put("selected", pose(selected)).put("displaced", pose(displaced))
                    .put("active_drag", field<RecyclerView.ViewHolder?>(helper, "mSelected")?.bindingAdapterPosition)
                    .put("animator_running", list.itemAnimator?.isRunning)
                    .put("has_window_focus", activity!!.hasWindowFocus())
                    .put("toolbar_bounds", bounds(activity!!.findViewById(R.id.actionbar)).toShortString())
                    .put("start_bounds", a.toShortString()).put("end_x", endX).put("end_y", endY)
            }
            File(output, "failed-gesture.json").writeText(state.toString(2))
            throw failure
        } finally {
            pointer(down, MotionEvent.ACTION_CANCEL, endX, endY)
            main { list.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val end = main { pose(displaced) }
        fun distance(a: JSONObject, b: JSONObject) = hypot(a.getDouble("x") - b.getDouble("x"), a.getDouble("y") - b.getDouble("y"))
        val intermediate = (0 until frames.length()).map { frames.getJSONObject(it).getJSONObject("displaced") }
            .filter { distance(it, origin) > 1.0 && distance(it, end) > 1.0 }
        val distinct = intermediate.map { "${it.getDouble("x").toInt()},${it.getDouble("y").toInt()}" }.distinct().size
        record("$name/让位附件有连续中间帧", distance(origin, end) > 20 && distinct >= 3)
        record("$name/视图复用", main { content.holder(images, 1)?.itemView === selected && content.holder(images, 0)?.itemView === displaced })
        record("$name/透明度与媒体图像保持", (0 until frames.length()).all {
            val f = frames.getJSONObject(it)
            listOf("selected", "displaced").all { role -> f.getJSONObject(role).let { p ->
                p.getDouble("alpha") > .99 && p.getBoolean("drawable") && p.getBoolean("attached")
            } }
        })
        record("$name/其它附件未变化", main { images.getItems()!!.drop(2) == before.drop(2) })
        runs.put(JSONObject().put("name", name).put("start", origin).put("end", end)
            .put("distinct_intermediate_positions", distinct).put("frames", frames)
            .put("animator", main { list.itemAnimator?.javaClass?.simpleName }))
        File(output, "progress.json").writeText(JSONObject().put("checks", checks).put("runs", runs).toString())
    }

    private fun toggleMode(enabled: Boolean, name: String) {
        val control = content.view<View>(R.id.tv_move_checklist_as_bt)
        main { content.layoutManager.scrollToPositionWithOffset(content.globalPosition(checklist, checklist.itemCount - 1), dp(180)) }
        await("移动模式按钮可见") { control.isShown && Rect().let { control.getGlobalVisibleRect(it) && it.height() == control.height } }
        tap(control)
        await("清单移动模式切换") { checklist.isDragging() == enabled && !list.isLayoutRequested }
        record("$name/模式及动画器正确", main {
            checklist.isDragging() == enabled && list.itemAnimator?.javaClass?.simpleName ==
                if (enabled) "ChecklistDragItemAnimator" else "DetailAttachmentItemAnimator"
        })
        record("$name/清单裁剪恢复", main { list.clipChildren == !enabled && !list.clipToPadding })
    }

    private fun checkChecklistDrag(name: String) {
        val before = main { checklist.getItems()!!.toList() }
        main { content.layoutManager.scrollToPositionWithOffset(content.globalPosition(checklist, 0), dp(190)) }
        await("清单拖动行可见") { content.holder(checklist, 0) != null && !list.isLayoutRequested }
        val handle = main { (content.holder(checklist, 0) as CheckListAdapter.EditTextHolder).ivState!! }
        val a = bounds(handle)
        val down = SystemClock.uptimeMillis()
        pointer(down, MotionEvent.ACTION_DOWN, a.exactCenterX(), a.exactCenterY())
        try {
            await("子项收束") { checklist.getItems()!!.size == before.size - 1 && list.itemAnimator?.isRunning != true }
            val b = bounds(main { content.holder(checklist, 1)!!.itemView })
            val y = b.bottom + a.height() / 2f
            drag(down, a.exactCenterX(), a.exactCenterY(), a.exactCenterX(), y)
            pointer(down, MotionEvent.ACTION_UP, a.exactCenterX(), y)
            await("子树展开与拖动结束") { field<Any?>(activity!!, "mDraggedChecklistVH") == null && checklist.getItems()!!.size == before.size }
            record("$name/清单真实拖动及子树完整", main {
                val after = checklist.getItems()!!
                val index = after.indexOf(before[0])
                index > 0 && after.subList(index, index + 2) == before.take(2) && after.toSet() == before.toSet()
            })
            main {
                val toolbar = activity!!.findViewById<androidx.appcompat.widget.Toolbar>(R.id.actionbar)
                activity!!.onOptionsItemSelected(toolbar.menu.findItem(R.id.act_undo))
            }
            await("撤销清单拖动") { checklist.getItems()!!.toList() == before && list.itemAnimator?.isRunning != true }
            record("$name/清单撤销与拖动状态清理", main { checklist.getItems()!!.toList() == before && field<Any?>(activity!!, "mDraggedChecklistVH") == null })
        } finally { pointer(down, MotionEvent.ACTION_CANCEL, a.exactCenterX(), a.exactCenterY()) }
    }

    private fun mediaReady() {
        await("上一轮附件拖拽完全结束") {
            val helper = field<ItemTouchHelper>(activity!!, "mChecklistTouchHelper")
            field<RecyclerView.ViewHolder?>(helper, "mSelected") == null &&
                field<List<Any>>(helper, "mRecoverAnimations").all { field<Boolean>(it, "mEnded") } &&
                list.itemAnimator?.isRunning != true
        }
        main { content.layoutManager.scrollToPositionWithOffset(0, 0) }
        await("图片视频完成加载") {
            !list.isLayoutRequested && (0..1).all { position ->
                content.holder(images, position)?.itemView?.let { view ->
                    view.findViewById<ImageView>(R.id.iv_image_attachment)?.drawable != null &&
                        view.findViewById<View>(R.id.pb_image_attachment)?.isShown != true
                } == true
            }
        }
        waitForIdleSync()
    }

    private fun pose(view: View): JSONObject = JSONObject().put("x", view.x).put("y", view.y)
        .put("width", view.width).put("height", view.height).put("alpha", view.alpha)
        .put("tx", view.translationX).put("ty", view.translationY).put("attached", view.isAttachedToWindow)
        .put("drawable", view.findViewById<ImageView>(R.id.iv_image_attachment)?.drawable != null)

    private fun createMedia(): List<String> = (0..3).map { index ->
        val file = File(mediaDirectory, if (index % 2 == 0) "image-$index.png" else "video-$index.mp4")
        if (index % 2 == 0) {
            val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (index == 0) Color.rgb(38, 150, 120) else Color.rgb(205, 95, 40))
            canvas.drawText("IMAGE $index", 100f, 340f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 110f })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } else targetContext.assets.open("detail-regression/video.mp4").use { input -> file.outputStream().use(input::copyTo) }
        "${if (index % 2 == 0) '0' else '1'}${file.absolutePath}"
    }

    private fun fixture(media: List<String>, fullSpan: Boolean): Long {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM things", null).use { it.moveToFirst(); it.getLong(0) }
        var appearance = DetailAttachmentMediaAppearance.default()
        if (fullSpan) for (key in media.take(2)) appearance = appearance.withSource(key,
            DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true, presentations = mapOf(
                "fullSpan" to DetailAttachmentMediaAppearance.MediaPresentationAppearance(targetAspectRatio = 2.0))))
        val rows = listOf(CheckListHelper.makeItem('0', 1, "父项 A"), CheckListHelper.makeItem('0', 2, "子项 A1"),
            CheckListHelper.makeItem('0', 1, "父项 B"), CheckListHelper.makeItem('0', 1, "父项 C"))
        val values = ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY); put("color", Color.rgb(40, 65, 90))
            put("title", title); put("content", CheckListHelper.toCheckListStr(rows))
            put("attachment", media.joinToString("") { AttachmentHelper.SIGNAL + it }); put("location", id)
            put("create_time", stamp); put("update_time", stamp); put("finish_time", 0L)
            put(Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS, appearance.toJson())
        }
        db.insertOrThrow("things", null, values)
        ids += id
        File(output, "fixtures.json").writeText(JSONObject().put("ids", JSONArray(ids)).put("stamp", stamp).put("title", title)
            .put("media", mediaDirectory.absolutePath).toString())
        return id
    }

    private fun rows(): Map<Long, String> = DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE type<>-1 ORDER BY id", null).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val digest = MessageDigest.getInstance("SHA-256")
                for (i in 0 until cursor.columnCount) { digest.update((cursor.getString(i) ?: "<null>").toByteArray()); digest.update(0.toByte()) }
                put(cursor.getLong(cursor.getColumnIndexOrThrow("id")), digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
    }

    private fun record(name: String, passed: Boolean) { checks.put(JSONObject().put("name", name).put("passed", passed)) }
    private fun dp(value: Int) = (value * targetContext.resources.displayMetrics.density).toInt()
    private fun bounds(view: View): Rect = main { Rect().also { check(view.getGlobalVisibleRect(it)) { "操作目标不可见" } } }
    private fun mediaTouchBounds(view: View): Rect = main {
        // 图片延伸到透明工具栏下方；几何中心可能被工具栏截获，必须选择未遮挡区域。
        bounds(view).apply {
            top = maxOf(top, bounds(activity!!.findViewById(R.id.actionbar)).bottom + dp(8))
            check(height() > dp(24)) { "附件没有足够的未遮挡触摸区域" }
        }
    }
    private fun tap(view: View) {
        val rect = bounds(view); val time = SystemClock.uptimeMillis()
        pointer(time, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
        pointer(time, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
    }
    private fun drag(time: Long, x1: Float, y1: Float, x2: Float, y2: Float) {
        repeat(24) { index ->
            val fraction = (index + 1) / 24f
            pointer(time, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction)
            SystemClock.sleep(16)
        }
    }
    private fun pointer(time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { sendPointerSync(event) } finally { event.recycle() }
    }
    private fun await(name: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition)) return; SystemClock.sleep(24) }
        throw AssertionError("等待失败：$name")
    }
    private fun <T> main(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private fun <T> field(instance: Any, name: String): T {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val member = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
            if (member != null) {
                @Suppress("UNCHECKED_CAST") return member.apply { isAccessible = true }.get(instance) as T
            }
            type = type.superclass
        }
        throw NoSuchFieldException("${instance.javaClass.name}.$name")
    }
    private fun set(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value) }
}
