package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.ThingCardCropEditorController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** 仅 debug：默认展示与真实外观确认前后的几何回归，测试行独立创建并清理。 */
class DetailMediaGeometryProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private var current: DetailActivity? = null
    private val fixtureIds = mutableListOf<Long>()
    private val samples = JSONArray()
    private var failures = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val run = args.getString("run_id") ?: "candidate"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "detail-media-geometry/$run").apply { mkdirs() }
        val result = JSONObject()
        try {
            if (args.getString("suite") == "slider") {
                DetailMediaSliderChecks(this, output, ::createFixture, ::record) { current = it }.run()
            } else {
                configurationMatrix()
                if (args.getString("suite") != "matrix") screenMatrix()
            }
        } catch (failure: Throwable) {
            failures++
            result.put("error", failure.stackTraceToString())
        } finally {
            main { current?.finish() }
            waitForIdleSync()
            val db = DBHelper(targetContext).writableDatabase
            fixtureIds.forEach { id ->
                db.delete(Def.Database.TABLE_THINGS, "id=? AND create_time=?",
                    arrayOf(id.toString(), FIXTURE_TIME.toString()))
            }
            result.put("failures", failures).put("samples", samples).put("fixture_rows_removed", fixtureIds.size)
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures == 0) 0 else 1, Bundle().apply {
                putString("result", "failures=$failures samples=${samples.length()} path=$output/result.json")
            })
        }
    }

    private fun configurationMatrix() {
        for (tablet in listOf(false, true)) for (landscape in listOf(false, true)) {
            val config = Configuration(targetContext.resources.configuration).apply {
                smallestScreenWidthDp = if (tablet) 800 else 400
                screenWidthDp = if (landscape) 1000 else smallestScreenWidthDp
                screenHeightDp = if (landscape) smallestScreenWidthDp else 1000
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            val context = targetContext.createConfigurationContext(config)
            check(DisplayUtil.isTablet(context) == tablet)
            for (type in listOf("0", "1")) main {
                val key = "${type}/geometry/first"
                val second = "0/geometry/second"
                val appearance = DetailAttachmentMediaAppearance.default()
                val adapter = ImageAttachmentAdapter(context, true, listOf(key), null, null,
                    maxSpan = if (tablet) { if (landscape) 5 else 3 } else { if (landscape) 4 else 2 },
                    detailAttachmentMediaAppearance = appearance)
                val before = adapter.getItemTargetSize(0)
                val source = DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true)
                    .ensurePresentation("grid").ensurePresentation("fullSpan", "grid")
                adapter.setDetailAttachmentMediaAppearance(appearance.withSource(key, source))
                val confirmed = adapter.getItemTargetSize(0)
                record("matrix-$tablet-$landscape-$type-default", JSONObject()
                    .put("before", JSONArray(before.toList())).put("confirmed", JSONArray(confirmed.toList())),
                    before.contentEquals(confirmed) && abs(before[1] - before[0] * 0.75) <= 1)
                check(appearance.isDefault()) { "运行时默认修改了外观数据" }
                adapter.setDetailAttachmentMediaAppearance(appearance)
                adapter.setItems(listOf(key, second))
                check(!adapter.isCustomizedMode() && !adapter.isFullSpanPosition(0))
                check(adapter.getItemTargetSize(0).contentEquals(AttachmentHelper.calculateImageSize(context, 2)))
                adapter.setItems(listOf(key))
                record("matrix-$tablet-$landscape-$type-remove", JSONObject(),
                    abs(adapter.getItemTargetSize(0)[1] - before[0] * 0.75) <= 1)
                for (ratio in listOf(0.5, 4.0 / 3.0, 65.0 / 24.0)) {
                    val saved = source.withPresentation("fullSpan",
                        DetailAttachmentMediaAppearance.MediaPresentationAppearance(targetAspectRatio = ratio))
                    adapter.setDetailAttachmentMediaAppearance(appearance.withSource(key, saved))
                    val size = adapter.getItemTargetSize(0)
                    check(size[1] == (size[0] / ratio).roundToInt())
                    adapter.setItems(listOf(key, second))
                    check(adapter.isFullSpanPosition(0) && adapter.getItemTargetSize(0).contentEquals(size))
                    check(adapter.getItemTargetSize(1).let { it[0] == it[1] })
                    adapter.setItems(listOf(second, key))
                    check(!adapter.isFullSpanPosition(0)) { "重排后外观错误地跟随位置" }
                    adapter.setItems(listOf(key))
                }
                record("matrix-$tablet-$landscape-$type-saved", JSONObject(), true)
            }
        }
    }

    private fun screenMatrix() {
        val directory = File(output, "media").apply { mkdirs() }
        val media = listOf("landscape" to (960 to 540), "portrait" to (540 to 960),
            "square" to (720 to 720), "ultrawide" to (1440 to 360)).map { (name, size) ->
            val file = File(directory, "$name.png")
            val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(45, 112, 136))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 60f }
            canvas.drawText(name, 40f, 120f, paint)
            canvas.drawRect(20f, 20f, size.first - 20f, 32f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            name to "0${file.absolutePath}"
        }.toMutableList()
        val video = File(directory, "video.mp4")
        targetContext.assets.open("detail-regression/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
        media += "video" to "1${video.absolutePath}"
        // 平板可能忽略应用的方向请求；runner 先设置并在结束后恢复系统旋转状态。
        val orientations = when (args.getString("orientation")) {
            "portrait" -> listOf(false)
            "landscape" -> listOf(true)
            else -> listOf(false, true)
        }
        for (landscape in orientations) for ((name, key) in media) {
            val sampleName = "screen-${if (landscape) "landscape" else "portrait"}-$name"
            val id = createFixture(key)
            val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                "detail-media-geometry", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
            current = activity
            main { activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await {
                activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            val adapter = field<ImageAttachmentAdapter>(activity, "mImageAttachmentAdapter")
            val content = field<DetailContentLayout>(activity, "mDetailContent")
            await {
                val tile = content.holder(adapter, 0)?.itemView
                tile != null && tile.height > 0 && !tile.isLayoutRequested &&
                    tile.findViewById<View>(R.id.pb_image_attachment).visibility != View.VISIBLE
            }
            val before = main { tileSize(content, adapter) }
            check(field<DetailAttachmentMediaAppearance>(activity, "mDetailAttachmentMediaAppearance").isDefault())
            if (name == "landscape") screenshot("$sampleName-before")
            main { content.holder(adapter, 0)!!.itemView.findViewById<View>(R.id.iv_edit_image_attachment_appearance).performClick() }
            var dialog: MediaCropAppearanceDialogFragment? = null
            await {
                dialog = activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) as? MediaCropAppearanceDialogFragment
                dialog?.view?.let { button(it, activity.getString(R.string.confirm))?.isEnabled } == true
            }
            val editorRatio = main {
                val editor = descendants(dialog!!.requireView()).filterIsInstance<ThingCardCropEditorController>().first()
                val ratio = editor.getTargetAspectRatio()
                button(dialog!!.requireView(), activity.getString(R.string.confirm))!!.performClick()
                ratio
            }
            await { activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) == null }
            await {
                activity.window.decorView.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS &&
                    content.holder(adapter, 0)?.itemView?.let {
                        !it.isLayoutRequested && it.height == adapter.getItemTargetSize(0)[1] &&
                            it.findViewById<View>(R.id.pb_image_attachment).visibility != View.VISIBLE
                    } == true
            }
            val after = main { tileSize(content, adapter) }
            val saved = field<DetailAttachmentMediaAppearance>(activity, "mDetailAttachmentMediaAppearance")
            check(saved.source(key)?.fullSpanEnabled == true)
            record(sampleName, JSONObject().put("before", JSONArray(before.toList()))
                .put("confirmed", JSONArray(after.toList())).put("editor_ratio", editorRatio)
                .put("tablet", DisplayUtil.isTablet(activity)),
                before.contentEquals(after) && abs(before[1] - before[0] * 0.75) <= 1 && abs(editorRatio - 4.0 / 3.0) < 0.00001)
            if (name == "landscape") screenshot("$sampleName-confirmed")
            main { activity.finish() }
            waitForIdleSync()
            current = null
        }
    }

    private fun createFixture(key: String): Long {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { check(it.moveToFirst()); it.getLong(0) + 1 }
        db.insertOrThrow(Def.Database.TABLE_THINGS, null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", Color.rgb(45, 112, 136)); put("title", "附件比例回归 · 临时记事")
            put("content", "用于核对默认比例和确认后比例。"); put("attachment", AttachmentHelper.SIGNAL + key)
            put("location", id); put("create_time", FIXTURE_TIME); put("update_time", FIXTURE_TIME)
            put("finish_time", 0L)
            put(Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS, DetailAttachmentMediaAppearance.default().toJson())
        })
        fixtureIds += id
        return id
    }

    private fun tileSize(content: DetailContentLayout, adapter: ImageAttachmentAdapter): IntArray =
        content.holder(adapter, 0)!!.itemView.let { intArrayOf(it.width, it.height) }

    private fun record(name: String, data: JSONObject, passed: Boolean) {
        samples.put(data.put("name", name).put("passed", passed))
        if (!passed) failures++
    }

    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("无法取得测试截图")
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun button(root: View, text: String): TextView? =
        descendants(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text && it.isClickable }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "等待界面超时" }
            SystemClock.sleep(50)
        }
        waitForIdleSync()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: DetailActivity, name: String): T =
        DetailActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    companion object { private const val FIXTURE_TIME = 1791580800129L }
}
