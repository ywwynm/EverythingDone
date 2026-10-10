package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingBackground
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * 详情迁移前后的真机对照。只创建有明确标识的临时记事，不读取或修改用户记事。
 * 基线和候选版本运行同一入口；截图及可见控件几何独立保存，不能以截图生成成功代替对照通过。
 */
class DetailLayoutRegressionProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private val result = JSONObject()
    private val captures = JSONArray()
    private var current: DetailActivity? = null
    private val fixtureIds = mutableListOf<Long>()
    private val fixtureTimes = mutableMapOf<Long, Long>()
    private val fixtureTitle = "详情布局回归专用 · 临时数据"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val run = args.getString("run_id") ?: "baseline"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "detail-layout-regression/$run")
        output.mkdirs()
        val oldMode = AppCompatDelegate.getDefaultNightMode()
        val oldAutoLink = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_LINK)
        try {
            if (args.getString("test_case") == "preview_loader") {
                DetailCropPreviewChecks(this, output).run()
                result.put("completed", true)
                return
            }
            // 回收此前进程崩溃遗留的专用测试行；同时限定测试标题和固定创建时间。
            DBHelper(targetContext).writableDatabase.delete(Def.Database.TABLE_THINGS,
                "title=? AND create_time=?", arrayOf(fixtureTitle, "1780272000000"))
            DBHelper(targetContext).writableDatabase.delete("thing_folders", "title=? AND create_time=?",
                arrayOf("详情新建验证临时文件夹", "1780272000000"))
            val media = createMedia()
            val behavior = args.getString("suite") == "behavior"
            if (behavior && args.getString("test_case") in listOf("business", "business_external")) {
                DetailBusinessChecks(this, output).run(args.getString("test_case") == "business_external")
                result.put("completed", true)
                return
            }
            if (behavior && (args.getString("test_case").isNullOrEmpty() || args.getString("test_case") == "creation")) verifyCreation()
            for (night in if (behavior) listOf(false) else listOf(false, true)) {
                onMain {
                    AppCompatDelegate.setDefaultNightMode(
                        if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                }
                val kinds = if (behavior) listOf("plain", "checklist", "mixed", "readonly", "dynamic", "editor", "workflow", "navigation", "links_plain", "edge_checklist", "drag_constraints", "picker_success", "media_failure", "export_failure", "recording", "camera_cancel", "camera_capture")
                    else listOf("plain", "checklist", "mixed", "single", "wide", "tall", "readonly")
                require(args.getString("test_case").orEmpty().let { it.isEmpty() || it in kinds || behavior && it == "creation" }) {
                    "未知测试组：${args.getString("test_case")}"
                }
                for (kind in kinds) {
                    if (args.getString("test_case").orEmpty().isNotEmpty() && kind != args.getString("test_case")) continue
                    FrequentSettings.put(Def.Meta.KEY_AUTO_LINK,
                        if (kind == "navigation" || kind == "links_plain") true else oldAutoLink)
                    val name = "${if (night) "dark" else "light"}-$kind"
                    val id = createThing(kind, media)
                    val activity = startActivitySync(
                        DetailActivity.getOpenIntentForUpdate(targetContext, "detail-layout-regression", id, -1)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ) as DetailActivity
                    current = activity
                    onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitForLayout(activity)
                    capture("$name-top", activity, kind != "dynamic" && kind != "workflow")
                    if (kind == "checklist" || kind == "mixed") {
                        revealChecklist(activity, 9)
                        capture("$name-checklist", activity)
                        scrollToEnd(activity)
                        capture("$name-bottom", activity)
                    }
                    if (kind == "mixed") {
                        scrollToTop(activity)
                        onMain {
                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                        waitForLayout(activity)
                        capture("$name-landscape", activity)
                        onMain {
                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        waitForLayout(activity)
                    }
                    if (behavior) {
                        if (kind == "editor") DetailEnvironmentChecks(this, activity, output, name).run()
                        else DetailBehaviorChecks(this, activity, output, name).run(kind)
                        if (kind != "readonly") verifyPersistence(activity, id, name)
                        if (kind == "workflow") {
                            DetailBehaviorChecks(this, current!!, output, "$name-cancel-export").cancelExport()
                            current = null
                        }
                    }
                    onMain { current?.finish() }
                    waitForIdleSync()
                    current = null
                }
            }
            result.put("completed", true)
        } catch (failure: Throwable) {
            result.put("error", failure.stackTraceToString())
        } finally {
            onMain {
                current?.finish()
                AppCompatDelegate.setDefaultNightMode(oldMode)
                FrequentSettings.put(Def.Meta.KEY_AUTO_LINK, oldAutoLink)
            }
            waitForIdleSync()
            // 同时限定本次分配的 id 与固定测试创建时间；测试中编辑标题也必须能清理。
            val db = DBHelper(targetContext).writableDatabase
            fixtureIds.forEach { id ->
                db.delete(Def.Database.TABLE_THINGS, "id=? AND create_time=?",
                    arrayOf(id.toString(), (fixtureTimes[id] ?: 1780272000000L).toString()))
            }
            result.put("captures", captures).put("fixture_ids", JSONArray(fixtureIds))
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("model", android.os.Build.MODEL)
            File(output, "result.json").writeText(result.toString(2))
            finish(if (result.has("error")) 1 else 0, Bundle().apply {
                putString("result_file", File(output, "result.json").absolutePath)
                putString("error", result.optString("error"))
                putInt("captures", captures.length())
            })
        }
    }

    private fun verifyCreation() {
        val report = JSONObject().put("check", "空白新建返回、新建清单保存与重新打开")
        val db = DBHelper(targetContext).writableDatabase
        val folderId = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM thing_folders", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        ThingFolderDAO.getInstance(targetContext)!!.create(ThingFolder(folderId, null,
            "详情新建验证临时文件夹", Thing.UNDERWAY, Color.BLUE, folderId, createTime = 1780272000000L))
        fun field(activity: DetailActivity, name: String): Any? = DetailActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity)
        fun snapshot(activity: DetailActivity) = DetailActivity::class.java.getDeclaredMethod("buildShareSnapshot")
            .apply { isAccessible = true }.invoke(activity) as Thing
        try {
            for (empty in listOf(true, false)) {
                val activity = startActivitySync(DetailActivity.getOpenIntentForCreate(targetContext,
                    com.ywwynm.everythingdone.App::class.java.name, ThingBackground.pure(Color.rgb(40, 90, 130)), folderId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                current = activity
                waitForLayout(activity)
                lateinit var thing: Thing
                lateinit var expected: Thing
                onMain {
                    val content = field(activity, "mDetailContent") as DetailContentLayout
                    thing = field(activity, "mThing") as Thing
                    check(content.view<EditText>(R.id.et_title).text.isEmpty())
                    check(content.view<EditText>(R.id.et_content).text.isEmpty())
                    check(content.view<TextView>(R.id.tv_update_time).text.isEmpty())
                    if (!empty) {
                        content.view<EditText>(R.id.et_title).setText("$fixtureTitle【新建验证】")
                        content.view<EditText>(R.id.et_content).setText("第一项\n第二项")
                        activity.onOptionsItemSelected(activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.actionbar)
                            .menu.findItem(R.id.act_check_list))
                        expected = snapshot(activity)
                        check(CheckListHelper.isCheckListStr(expected.content))
                    }
                    (field(activity, "mIbBack") as View).performClick()
                    check(activity.isFinishing)
                    if (!empty) {
                        fixtureIds += thing.id
                        fixtureTimes[thing.id] = thing.createTime
                    }
                }
                waitForIdleSync()
                if (empty) {
                    db.rawQuery("SELECT id FROM things WHERE id=? AND type<>?",
                        arrayOf(thing.id.toString(), Thing.HEADER.toString())).use {
                        check(!it.moveToFirst()) { "空白新建意外保存了记事" }
                    }
                } else {
                    val reopened = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                        "detail-layout-regression", thing.id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                    current = reopened
                    waitForLayout(reopened)
                    onMain {
                        val saved = snapshot(reopened)
                        check(saved.title == expected.title && saved.content == expected.content)
                        check(saved.folderId == folderId)
                        check(saved.getBackground()?.toJson() == expected.getBackground()?.toJson())
                        reopened.finish()
                    }
                    waitForIdleSync()
                }
                current = null
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("error", failure.stackTraceToString())
            throw failure
        } finally {
            db.delete("thing_folders", "id=? AND create_time=?", arrayOf(folderId.toString(), "1780272000000"))
            File(output, "creation.json").writeText(report.toString(2))
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun createThing(kind: String, media: List<String>): Long {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use {
            check(it.moveToFirst()); it.getLong(0) + 1
        }
        val items = mutableListOf<String?>()
        for (i in 0 until 54) {
            val level = when (i % 6) { 1, 3 -> 2; 2 -> 3; else -> 1 }
            val label = if (i % 7 == 0) "清单 $i：多行文字用于核对换行、字号和行间距，中文 English 123，以及缩进后的编辑区域宽度。" else "清单 $i · 第 $level 级"
            items += CheckListHelper.makeItem('0', level, label)
        }
        for (i in 54 until 90) items += CheckListHelper.makeItem('1', 1, "已完成清单 $i")
        val isPlain = kind == "plain" || kind == "editor" || kind == "links_plain"
        val content = if (isPlain) "正文第一段：中文 English 123。\n\n" +
            "这是一段用于测试详情页面布局、颜色、链接和编辑行为的多行正文。https://example.com\n".repeat(24)
        else CheckListHelper.toCheckListStr(items)
        val selected = when (kind) {
            "media_failure" -> {
                val directory = File(media[0].substring(1)).parentFile!!
                val image = File(directory, "broken.png").apply { writeText("invalid image") }
                val audio = File(directory, "broken.wav").apply { writeText("invalid audio") }
                listOf("0${File(directory, "missing.png")}", "0$image", media[0],
                    "2${File(directory, "missing.wav")}", "2$audio", media[3])
            }
            "dynamic" -> createDynamicMedia()
            "single", "wide", "tall" -> media.take(1)
            "mixed", "navigation", "drag_constraints", "picker_success" -> media
            "workflow" -> media + createDynamicMedia().first { it.startsWith("1") }
            else -> emptyList()
        }
        val background = when (kind) {
            "plain", "wide" -> ThingBackground.pure(Color.rgb(250, 225, 120))
            "mixed", "tall" -> ThingBackground.gradient(Color.rgb(36, 65, 100), Color.rgb(132, 45, 82), ThingBackground.Orientation.LT_RB)
            else -> ThingBackground.pure(Color.rgb(46, 114, 104))
        }
        var appearance = DetailAttachmentMediaAppearance.default()
        if (kind == "wide" || kind == "tall") {
            appearance = appearance.withSource(media[0], DetailAttachmentMediaAppearance.SourceAppearance(
                fullSpanEnabled = true,
                presentations = mapOf("fullSpan" to DetailAttachmentMediaAppearance.MediaPresentationAppearance(
                    targetAspectRatio = if (kind == "wide") 65.0 / 24.0 else 0.5
                ))
            ))
        }
        val values = ContentValues().apply {
            put("id", id); put("type", Thing.NOTE)
            put("state", if (kind == "readonly") Thing.FINISHED else Thing.UNDERWAY)
            put("color", background.representativeColor()); put("background", background.toJson())
            put("title", fixtureTitle); put("content", content)
            put("attachment", selected.joinToString("") { AttachmentHelper.SIGNAL + it })
            put("location", id); put("create_time", 1780272000000L); put("update_time", 1780358400000L)
            put("finish_time", if (kind == "readonly") 1780444800000L else 0L)
            put(Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS, appearance.toJson())
        }
        db.insertOrThrow(Def.Database.TABLE_THINGS, null, values)
        fixtureIds += id
        return id
    }

    private fun verifyPersistence(activity: DetailActivity, id: Long, name: String) {
        val report = JSONObject().put("check", "未保存分享快照、前后台自动保存、返回重开的内容/外观一致")
        val previous = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)
        fun invoke(target: DetailActivity, method: String, vararg args: Any?): Any? =
            DetailActivity::class.java.declaredMethods.first { it.name == method && it.parameterCount == args.size }
                .apply { isAccessible = true }.invoke(target, *args)
        fun content(target: DetailActivity) = DetailActivity::class.java.getDeclaredField("mDetailContent")
            .apply { isAccessible = true }.get(target) as DetailContentLayout
        fun state(thing: Thing) = listOf(thing.title, thing.content, thing.attachment,
            thing.getBackground()?.toJson(), thing.detailAttachmentMediaAppearance.toJson())
        var cover: android.app.Activity? = null
        try {
            lateinit var expected: Thing
            onMain {
                content(activity).view<EditText>(R.id.et_title).append("【保存重开】")
                invoke(activity, "changeBackground", ThingBackground.pure(Color.rgb(52, 62, 82)))
                expected = invoke(activity, "buildShareSnapshot") as Thing
            }
            check(expected.title?.endsWith("【保存重开】") == true)
            val titleInDatabase = DBHelper(targetContext).readableDatabase.rawQuery(
                "SELECT title FROM things WHERE id=?", arrayOf(id.toString())).use { check(it.moveToFirst()); it.getString(0) }
            check(!titleInDatabase.endsWith("【保存重开】")) { "分享快照意外写回数据库" }
            onMain { FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, true) }
            cover = startActivitySync(Intent(targetContext, DetailRegressionCoverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val savedTitle = DBHelper(targetContext).readableDatabase.rawQuery(
                "SELECT title FROM things WHERE id=?", arrayOf(id.toString())).use { check(it.moveToFirst()); it.getString(0) }
            check(savedTitle == expected.title) { "真实 onPause 没有自动保存当前草稿" }
            onMain { cover?.finish() }
            waitForIdleSync()
            cover = null
            // finish() 表示已保存或明确放弃；正常返回按钮才执行保存事务。
            onMain {
                val back = DetailActivity::class.java.getDeclaredField("mIbBack")
                    .apply { isAccessible = true }.get(activity) as View
                back.performClick()
                check(activity.isFinishing) { "正常返回没有完成保存退出" }
            }
            waitForIdleSync()
            val reopened = startActivitySync(DetailActivity.getOpenIntentForUpdate(
                targetContext, "detail-layout-regression", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
            current = reopened
            waitForLayout(reopened)
            lateinit var actual: Thing
            onMain { actual = invoke(reopened, "buildShareSnapshot") as Thing }
            if (name == "light-media_failure") {
                // 本组主动删除了两个专用源文件；旧有加载语义会在重开时过滤不存在的路径。
                val surviving = AttachmentHelper.toAttachmentItems(expected.attachment)
                expected.attachment = AttachmentHelper.toAttachmentStr(surviving.first, surviving.second)
            }
            val fields = listOf("title", "content", "attachment", "background", "media_appearance")
            val differences = JSONArray()
            state(expected).zip(state(actual)).forEachIndexed { index, (before, after) ->
                if (before != after) differences.put(JSONObject().put("field", fields[index])
                    .put("expected", before).put("actual", after))
            }
            report.put("differences", differences)
            check(state(actual) == state(expected)) { "保存并重开后的标题、内容、附件、背景或媒体外观不一致" }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("error", failure.stackTraceToString())
            throw failure
        } finally {
            onMain { cover?.finish(); FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, previous) }
            File(output, "$name-persistence.json").writeText(report.toString(2))
        }
    }

    private fun createMedia(): List<String> {
        val dir = File(targetContext.getExternalFilesDir(null), "detail-layout-regression/media").apply { mkdirs() }
        val paths = mutableListOf<String>()
        repeat(3) { index ->
            val file = File(dir, "layout-image-$index.png")
            if (!file.exists()) {
                val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(listOf(0xffef8354.toInt(), 0xff4f5d75.toInt(), 0xff42a599.toInt())[index])
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 96f }
                canvas.drawRect(80f, 80f, 880f, 110f, paint)
                canvas.drawText("MEDIA $index", 110f, 400f, paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            paths += "0${file.absolutePath}"
        }
        repeat(3) { index ->
            val file = File(dir, "layout-audio-$index.wav")
            if (!file.exists()) {
                val samples = 16000 * 3
                val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                bytes.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                bytes.put("data".toByteArray()).putInt(samples * 2)
                repeat(samples) { sample -> bytes.putShort((sin(sample * 2 * Math.PI * (220 + index * 110) / 16000) * 900).toInt().toShort()) }
                file.writeBytes(bytes.array())
            }
            paths += "2${file.absolutePath}"
        }
        return paths
    }

    private fun createDynamicMedia(): List<String> {
        val dir = File(targetContext.getExternalFilesDir(null), "detail-layout-regression/media").apply { mkdirs() }
        return listOf("animated.gif", "video.mp4", "second.gif").map { name ->
            val file = File(dir, name)
            if (!file.exists()) targetContext.assets.open("detail-regression/${if (name == "second.gif") "animated.gif" else name}")
                .use { input -> file.outputStream().use(input::copyTo) }
            "${if (name.endsWith("mp4")) '1' else '0'}${file.absolutePath}"
        }
    }

    private fun waitForLayout(activity: DetailActivity) {
        waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            onMain { ready = activity.window.decorView.width > 0 && !activity.window.decorView.isLayoutRequested }
            if (!ready) SystemClock.sleep(50)
        }
        check(ready) { "详情布局没有完成" }
        // 媒体首次解码不属于布局完成信号；等候其加载指示器消失。
        val imageDeadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < imageDeadline) {
            var loading = false
            onMain {
                walk(activity.window.decorView) { view ->
                    if (view.id == com.ywwynm.everythingdone.R.id.pb_image_attachment && view.isShown) loading = true
                }
            }
            if (!loading) break
            SystemClock.sleep(100)
        }
        waitForIdleSync()
    }

    private fun root(activity: DetailActivity): View = activity.findViewById(com.ywwynm.everythingdone.R.id.sv_detail)

    private fun scrollToTop(activity: DetailActivity) {
        onMain {
            when (val view = root(activity)) {
                is NestedScrollView -> view.scrollTo(0, 0)
                is RecyclerView -> (view.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            }
        }
        waitForLayout(activity)
    }

    private fun scrollToEnd(activity: DetailActivity) {
        onMain {
            when (val view = root(activity)) {
                is NestedScrollView -> view.scrollTo(0, view.getChildAt(0).height)
                is RecyclerView -> view.scrollToPosition(view.adapter!!.itemCount - 1)
            }
        }
        waitForLayout(activity)
    }

    private fun revealChecklist(activity: DetailActivity, position: Int) {
        onMain {
            when (val view = root(activity)) {
                is NestedScrollView -> {
                    val rv = activity.findViewById<RecyclerView>(com.ywwynm.everythingdone.R.id.rv_check_list)
                    val row = rv.findViewHolderForAdapterPosition(position)!!.itemView
                    view.scrollTo(0, rv.top + row.top)
                }
                is RecyclerView -> {
                    val concat = view.adapter as ConcatAdapter
                    var offset = 0
                    for (adapter in concat.adapters) {
                        if (adapter is CheckListAdapter) break
                        offset += adapter.itemCount
                    }
                    (view.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(offset + position, 0)
                }
            }
        }
        waitForLayout(activity)
    }

    private fun capture(name: String, activity: DetailActivity, stable: Boolean = true) {
        if (stable) waitForStableFrame()
        val nodes = JSONArray()
        onMain {
            walk(activity.window.decorView) { view ->
                if (view.id == View.NO_ID || !view.isShown) return@walk
                val bounds = Rect()
                if (!view.getGlobalVisibleRect(bounds)) return@walk
                val node = JSONObject().put("id", runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault(view.id.toString()))
                    .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    .put("size", JSONArray(listOf(view.width, view.height)))
                    .put("padding", JSONArray(listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)))
                    .put("alpha", view.alpha.toDouble()).put("enabled", view.isEnabled)
                if (view is TextView) {
                    node.put("text", view.text.toString()).put("text_color", view.currentTextColor)
                        .put("hint_color", view.currentHintTextColor).put("text_size", view.textSize.toDouble())
                        .put("line_height", view.lineHeight).put("paint_flags", view.paintFlags)
                }
                nodes.put(node)
            }
        }
        val bitmap = uiAutomation.takeScreenshot() ?: error("无法获取设备截图")
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$name.json").writeText(nodes.toString(2))
        captures.put(name)
        sendStatus(0, Bundle().apply { putString("capture", name) })
    }

    private fun walk(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), action)
    }

    /** 等待 Surface 上的窗口/旋转动画结束；View 布局完成并不代表截图已稳定。 */
    private fun waitForStableFrame() {
        val deadline = SystemClock.uptimeMillis() + 4000
        var previous: IntArray? = null
        var stable = 0
        while (SystemClock.uptimeMillis() < deadline && stable < 3) {
            val frame = uiAutomation.takeScreenshot() ?: error("无法获取稳定帧")
            val tiny = Bitmap.createScaledBitmap(frame, 24, 48, true)
            val pixels = IntArray(24 * 44)
            tiny.getPixels(pixels, 0, 24, 0, 3, 24, 44)
            tiny.recycle()
            frame.recycle()
            val old = previous
            val equal = old != null && pixels.indices.all {
                val a = pixels[it]; val b = old[it]
                kotlin.math.abs(Color.red(a) - Color.red(b)) < 3 &&
                    kotlin.math.abs(Color.green(a) - Color.green(b)) < 3 &&
                    kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 3
            }
            stable = if (equal) stable + 1 else 0
            previous = pixels
            SystemClock.sleep(70)
        }
        check(stable >= 3) { "窗口画面未稳定，不能用于外观对照" }
    }
}
