package com.ywwynm.everythingdone.notification

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
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅 debug：使用真实通知入口和触摸事件；只创建、清理本次虚构数据，不改用户设置。 */
class NoticeableNotificationScrollProbe : Instrumentation() {
    private var arguments = Bundle()
    private lateinit var output: File
    private var current: NoticeableNotificationActivity? = null
    private val fixtures = mutableListOf<Long>()
    private val samples = JSONArray()
    private val fixtureTime = System.currentTimeMillis()
    private var failures = 0
    private val mediaFiles = mutableListOf<File>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val run = arguments.getString("run_id") ?: "candidate"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "notification-scroll/$run").apply { mkdirs() }
        val result = JSONObject()
        try {
            val defaults = listOf("text", "checklist", "short", "audio", "checklist-audio") +
                listOf("image", "video").flatMap { type ->
                    listOf("top", "bottom", "left", "right", "background").map { "$type-$it" }
                } + listOf("checklist-image-left", "checklist-video-background")
            val boundaries = listOf("wide-checklist", "image-left", "image-right", "video-left", "video-right",
                "tall-image-top", "tall-video-bottom", "flat-image-top", "flat-video-bottom",
                "tall-wide-image-background", "flat-wide-video-background",
                "audio-image-left", "checklist-video-right", "medium-image-left", "medium-video-right",
                "landscape-wide-text", "landscape-wide-checklist", "landscape-tall-wide-image-top",
                "landscape-tall-wide-video-bottom", "landscape-audio-image-left",
                "sparse-tall-image-top", "sparse-flat-video-bottom", "sparse-tall-wide-image-background",
                "sparse-flat-wide-video-background", "sparse-tall-image-left", "sparse-flat-video-right")
            val cases = arguments.getString("cases")?.split(',') ?: when (arguments.getString("suite")) {
                "boundaries" -> boundaries
                "all" -> (defaults + boundaries).distinct()
                else -> defaults
            }
            for (kind in cases) checkCase(kind)
        } catch (failure: Throwable) {
            failures++
            result.put("error", failure.stackTraceToString())
        } finally {
            main { current?.finish() }
            val db = DBHelper(targetContext).writableDatabase
            var removed = 0
            fixtures.forEach { id ->
                db.delete("reminders", "id=? AND create_time=?",
                    arrayOf(id.toString(), fixtureTime.toString()))
                removed += db.delete("things", "id=? AND title=? AND create_time=?",
                    arrayOf(id.toString(), TITLE, fixtureTime.toString()))
            }
            mediaFiles.forEach { it.delete() }
            result.put("failures", failures).put("samples", samples).put("fixtures_removed", removed)
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures == 0) 0 else 1, Bundle().apply {
                putString("result", "failures=$failures samples=${samples.length()} path=$output/result.json")
            })
        }
    }

    private fun checkCase(kind: String) {
        val open = NoticeableNotificationActivity.getOpenIntentForReminder(targetContext, fixture(kind), -1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (kind.startsWith("landscape")) open.setClass(targetContext, NoticeableNotificationLandscapeProbeActivity::class.java)
        val activity = startActivitySync(open) as NoticeableNotificationActivity
        current = activity
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (!main {
            val rv = activity.findViewById<RecyclerView>(R.id.rv_thing_noticeable_notification)
            val dialog = activity.supportFragmentManager.findFragmentByTag("activity-content-dialog")
                as? androidx.fragment.app.DialogFragment
            rv.isShown && rv.height > 0 && rv.childCount > 0 && !rv.isLayoutRequested &&
                activity.findViewById<View>(R.id.fl_1_noticeable_notification_as_bt).visibility == View.VISIBLE &&
                dialog?.dialog?.window?.attributes?.alpha == 1f
        }) {
            check(SystemClock.uptimeMillis() < deadline) { "通知窗口未就绪" }
            SystemClock.sleep(100)
        }
        waitForIdleSync()
        // 等附件解码与随后的重新布局结束，避免把异步重设行数遗漏在断言之外。
        var previous = ""
        var stable = 0
        val settleDeadline = SystemClock.uptimeMillis() + 5_000L
        while (stable < 5 && SystemClock.uptimeMillis() < settleDeadline) {
            SystemClock.sleep(100)
            val snapshot = state(activity).toString()
            stable = if (snapshot == previous) stable + 1 else 0
            previous = snapshot
        }
        awaitFrames(activity)
        val before = state(activity)
        screenshot("$kind-before")
        if (kind == "short" || (kind.startsWith("sparse") && !before.getBoolean("can_down"))) {
            record(kind, JSONObject().put("before", before),
                before.getBoolean("chrome_visible") && !before.getBoolean("can_down"))
        } else {
            swipe(activity, up = true)
            val afterUp = state(activity)
            main { recycler(activity).scrollBy(0, 1_000_000) }
            awaitFrames(activity)
            val tail = state(activity)
            screenshot("$kind-tail")
            swipe(activity, up = false)
            val afterDown = state(activity)
            val mediaGesture = if (kind.contains("image") || kind.contains("video")) {
                val upward = !kind.endsWith("bottom")
                main { recycler(activity).scrollBy(0, if (upward) -1_000_000 else 1_000_000) }
                awaitFrames(activity)
                val start = state(activity).getInt("offset")
                swipe(activity, up = upward, fromMedia = true)
                val end = state(activity).getInt("offset")
                JSONObject().put("start", start).put("end", end)
                    .put("passed", if (upward) end > start else end < start)
            } else null
            record(kind, JSONObject().put("before", before).put("after_up", afterUp)
                .put("tail", tail).put("after_down", afterDown).put("media_gesture", mediaGesture),
                before.getBoolean("can_down") && before.getBoolean("chrome_visible") &&
                    before.getBoolean("content_complete") && before.getBoolean("content_inside_card") &&
                    (!(kind.endsWith("left") || kind.endsWith("right")) || before.getBoolean("side_layout")) &&
                    afterUp.getInt("offset") > before.getInt("offset") &&
                    !tail.getBoolean("can_down") && tail.getBoolean("can_up") &&
                    tail.getBoolean("chrome_visible") && tail.getBoolean("tail_visible") &&
                    afterDown.getInt("offset") < tail.getInt("offset") &&
                    mediaGesture?.getBoolean("passed") != false)
        }
        main { activity.finish() }
        val closeDeadline = SystemClock.uptimeMillis() + 8_000L
        while (!main { activity.isDestroyed }) {
            check(SystemClock.uptimeMillis() < closeDeadline) { "通知窗口未关闭" }
            SystemClock.sleep(100)
        }
        current = null
    }

    private fun state(activity: NoticeableNotificationActivity): JSONObject = main {
        val rv = recycler(activity)
        val root = activity.findViewById<ViewGroup>(R.id.ll_root_noticeable_notification)
        val footer = activity.findViewById<View>(R.id.fl_1_noticeable_notification_as_bt).parent as View
        val visible = Rect().also { root.getLocalVisibleRect(it) }
        val child = rv.getChildAt(0)
        val text = child?.findViewById<TextView>(R.id.tv_thing_content)
        val checklist = child?.findViewById<ViewGroup>(R.id.rv_check_list)
        val media = child?.findViewById<ImageView>(R.id.iv_thing_image)
        val background = child?.findViewById<ImageView>(R.id.iv_thing_media_background)
        val textColumn = child?.findViewById<View>(R.id.ll_thing_text_content)
        val contentBox = if (checklist?.visibility == View.VISIBLE) checklist else text
        val contentPosition = IntArray(2).also { contentBox?.getLocationOnScreen(it) }
        val cardPosition = IntArray(2).also { child?.getLocationOnScreen(it) }
        val parent = child?.findViewById<LinearLayout>(R.id.ll_thing_content)
        JSONObject().put("root_height", root.height).put("root_visible_height", visible.height())
            .put("viewport_height", rv.height).put("child_height", child?.height ?: -1)
            .put("footer_top", footer.top).put("footer_bottom", footer.bottom)
            .put("offset", rv.computeVerticalScrollOffset()).put("range", rv.computeVerticalScrollRange())
            .put("can_down", rv.canScrollVertically(1)).put("can_up", rv.canScrollVertically(-1))
            .put("text_lines", text?.lineCount ?: -1)
            .put("text_max_lines", text?.maxLines ?: -1)
            .put("checklist_items", checklist?.childCount ?: -1)
            .put("media_size", "${media?.width}x${media?.height}")
            .put("media_loaded", media?.drawable != null || background?.drawable != null)
            .put("side_layout", parent?.orientation == LinearLayout.HORIZONTAL)
            .put("text_column_height", textColumn?.height ?: 0)
            .put("content_height", contentBox?.height ?: 0)
            .put("content_inside_card", child != null && contentBox != null &&
                contentPosition[1] + contentBox.height <= cardPosition[1] + child.height)
            .put("content_complete", if (checklist?.visibility == View.VISIBLE) checklist.childCount >= 60
                else text != null && text.maxLines >= 60 && text.lineCount >=
                text.text.toString().count { it == '\n' } + 1)
            .put("chrome_visible", visible.top == 0 && footer.bottom <= visible.bottom &&
                footer.bottom <= root.height && rv.bottom <= footer.top && footer.height > 0)
            .put("tail_visible", child != null && child.bottom <= rv.height - rv.paddingBottom)
    }

    private fun swipe(activity: NoticeableNotificationActivity, up: Boolean, fromMedia: Boolean = false) {
        val bounds = main {
            val rv = recycler(activity)
            val child = rv.getChildAt(0)
            val foreground = child?.findViewById<View>(R.id.fl_thing_image)
            val background = child?.findViewById<View>(R.id.iv_thing_media_background)
            val target = if (fromMedia) foreground?.takeIf { it.isShown } ?: background ?: rv else rv
            val location = IntArray(2).also { target.getLocationOnScreen(it) }
            Rect().also { target.getLocalVisibleRect(it); it.offset(location[0], location[1]) }
        }
        val startY = bounds.top + bounds.height() * if (up) 0.8f else 0.2f
        val endY = bounds.top + bounds.height() * if (up) 0.2f else 0.8f
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
                .also { it.source = InputDevice.SOURCE_TOUCHSCREEN; sendPointerSync(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, startY)
        for (step in 1..24) {
            SystemClock.sleep(20)
            event(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 24f)
        }
        SystemClock.sleep(120)
        event(MotionEvent.ACTION_UP, endY)
        waitForIdleSync()
        main { recycler(activity).stopScroll() }
        awaitFrames(activity)
    }

    private fun fixture(kind: String): Long {
        val content = when {
            kind == "short" || kind.startsWith("sparse") -> "短记事应完整显示，窗口随内容收缩。"
            kind.contains("checklist") -> CheckListHelper.toCheckListStr((1..60).map {
                CheckListHelper.makeItem('0', 1, "通知滚动验证 · 清单第 $it 项")
            })
            else -> (1..if (kind.startsWith("medium")) 16 else 60).joinToString("\n") { "通知滚动验证 · 内容第 $it 行" }
        }
        val media = when {
            kind.contains("image") -> "0" + imageFixture().absolutePath
            kind.contains("video") -> "1" + assetFixture("detail-regression/video.mp4").absolutePath
            else -> null
        }
        val audio = if (kind.contains("audio")) "2" + assetFixture("selection-appearance/audio.wav").absolutePath else null
        val placement = when {
            kind.endsWith("bottom") -> Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM
            kind.endsWith("left") -> Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
            kind.endsWith("right") -> Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
            else -> Thing.THING_CARD_IMAGE_PLACEMENT_TOP
        }
        val wide = kind.contains("wide") || kind.endsWith("left") || kind.endsWith("right")
        val ratio = when { kind.contains("tall") -> 0.129; kind.contains("flat") -> 6.9; else -> null }
        val presentations = listOf(ThingCardAppearance.PRESENTATION_THUMBNAIL,
            ThingCardAppearance.PRESENTATION_SIDE_PANEL, ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND)
            .associateWith { ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = ratio) }
        val appearance = ThingCardAppearance(imagePlacement = placement, mediaSourceKey = media,
            spanMode = if (wide) Thing.THING_CARD_SPAN_FULL else Thing.THING_CARD_SPAN_NORMAL,
            mediaBackgroundEnabled = kind.endsWith("background"), sources = if (media != null)
                mapOf(media to ThingCardAppearance.SourceAppearance(presentations = presentations)) else emptyMap())
        val db = DBHelper(targetContext).writableDatabase
        db.beginTransaction()
        try {
            val id = db.rawQuery("SELECT MAX(id)+1 FROM things", null).use { it.moveToFirst(); it.getLong(0) }
            db.insertOrThrow("things", null, ContentValues().apply {
                put("id", id); put("type", Thing.REMINDER); put("state", Thing.UNDERWAY)
                put("color", 0xff226655.toInt()); put("title", TITLE); put("content", content)
                put("attachment", AttachmentHelper.toAttachmentStr(listOfNotNull(media), listOfNotNull(audio)))
                put("thing_card_appearance", appearance.toJson())
                put("location", id); put("create_time", fixtureTime)
                put("update_time", fixtureTime); put("finish_time", 0)
            })
            // 仅写虚构提醒数据，不调用会安排系统闹钟的 DAO.create。
            db.insertOrThrow("reminders", null, ContentValues().apply {
                put(Def.Database.COLUMN_ID_REMINDERS, id)
                put(Def.Database.COLUMN_NOTIFY_TIME_REMINDERS, fixtureTime)
                put(Def.Database.COLUMN_STATE_REMINDERS, 2)
                put(Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS, 0)
                put(Def.Database.COLUMN_CREATE_TIME_REMINDERS, fixtureTime)
                put(Def.Database.COLUMN_UPDATE_TIME_REMINDERS, fixtureTime)
            })
            fixtures += id
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    private fun assetFixture(asset: String): File = File(output, asset.substringAfterLast('/')).also { file ->
        if (!file.exists()) {
            targetContext.assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
            mediaFiles += file
        }
    }

    private fun imageFixture(): File = File(output, "cover.png").also { file ->
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(170, 90, 45))
            canvas.drawCircle(320f, 240f, 160f, Paint().apply { color = Color.rgb(245, 215, 105) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            mediaFiles += file
        }
    }

    private fun recycler(activity: NoticeableNotificationActivity): RecyclerView =
        activity.findViewById(R.id.rv_thing_noticeable_notification)

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun awaitFrames(activity: NoticeableNotificationActivity) {
        val ready = CountDownLatch(1)
        main { recycler(activity).postOnAnimation { recycler(activity).postOnAnimation { ready.countDown() } } }
        check(ready.await(3, TimeUnit.SECONDS)) { "未收到窗口绘制帧" }
        waitForIdleSync()
    }

    private fun record(kind: String, data: JSONObject, passed: Boolean) {
        if (!passed) failures++
        samples.put(data.put("case", kind).put("passed", passed))
        sendStatus(0, Bundle().apply { putString("case", "$kind: ${if (passed) "PASS" else "FAIL"}") })
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object { private const val TITLE = "通知滚动回归 · 临时测试记事" }
}
